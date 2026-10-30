package app.grapheneos.goscompat.securespawn;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/** Compares stable proc status fields between conventional and exec spawning on the same device. */
public final class StatusComparison {
    public static final String RESULT_KEY = "stableProcessStatus";

    // Identity and ancestry change with every launch; parent routing has separate policy tests.
    private static final Set<String> IDENTITY_FIELDS = Set.of(
            "Name", "Tgid", "Ngid", "Pid", "PPid", "NStgid", "NSpid", "NSpgid", "NSsid");
    // Measurements and transient state depend on when the main thread is sampled.
    private static final Set<String> TRANSIENT_FIELDS = Set.of(
            "State", "FDSize", "HugetlbPages", "Threads", "SigQ", "SigPnd", "ShdPnd",
            "CoreDumping", "voluntary_ctxt_switches", "nonvoluntary_ctxt_switches");
    // Activity state and cpusets can change these independently of the spawning path.
    private static final Set<String> SCHEDULER_FIELDS = Set.of(
            "Cpus_allowed", "Cpus_allowed_list", "Mems_allowed", "Mems_allowed_list");

    private StatusComparison() {}

    /** Runs both captures and compares their snapshots even when policy checks have failed. */
    public static void checkBothModes(FailureCollector errors, Callable<String> disabled,
            Callable<String> enabled) {
        String before = errors.checkSucceeds("Exec spawning disabled: process capture", disabled);
        String after = errors.checkSucceeds("Exec spawning enabled: process capture", enabled);
        if (before != null && after != null) {
            errors.checkSucceeds("Differences between exec spawning disabled and enabled", () -> {
                String changes = diff(before, after);
                if (!changes.isEmpty()) {
                    throw new AssertionError(changes);
                }
                return null;
            });
        }
    }

    public static String snapshot(String contents, boolean isolated) {
        Map<String, String> fields = parse(contents);
        // Require credential fields so a failed proc read cannot become a passing empty snapshot.
        long[] uids = credentials(fields, "Uid");
        long[] gids = credentials(fields, "Gid");
        if (!fields.containsKey("Groups")) {
            throw new AssertionError("missing Groups in proc status");
        }
        fields.keySet().removeIf(field -> IDENTITY_FIELDS.contains(field)
                || TRANSIENT_FIELDS.contains(field) || SCHEDULER_FIELDS.contains(field)
                || field.startsWith("Vm") || field.startsWith("Rss"));
        // Android allocates isolated app IDs from 90000..99999, within each user's UID range.
        // Leave unexpected IDs literal so normalization cannot hide a non-isolated credential.
        boolean normalize = isolated && uids[0] % 100000 >= 90000
                && uids[0] % 100000 <= 99999;
        fields.put("Uid", identity(uids, uids[0], normalize));
        fields.put("Gid", identity(gids, uids[0], normalize));
        String groups = fields.get("Groups");
        if (!groups.isEmpty()) {
            fields.put("Groups", Arrays.stream(groups.split("\\s+"))
                    .mapToLong(Long::parseLong).sorted().mapToObj(Long::toString)
                    .collect(Collectors.joining(" ")));
        }
        StringBuilder result = new StringBuilder();
        fields.forEach((field, value) -> result.append(field).append(": ")
                .append(value).append('\n'));
        return result.toString();
    }

    /** Returns all differing fields, including fields present in only one snapshot. */
    public static String diff(String disabled, String enabled) {
        Map<String, String> before = parse(disabled);
        Map<String, String> after = parse(enabled);
        Set<String> fields = new TreeSet<>(before.keySet());
        fields.addAll(after.keySet());
        StringBuilder changes = new StringBuilder();
        for (String field : fields) {
            if (!Objects.equals(before.get(field), after.get(field))) {
                if (changes.length() != 0) {
                    changes.append('\n');
                }
                changes.append(field).append(":\n  exec spawning disabled: ")
                        .append(before.getOrDefault(field, "<missing>")).append('\n');
                changes.append("  exec spawning enabled:  ")
                        .append(after.getOrDefault(field, "<missing>")).append('\n');
            }
        }
        return changes.toString();
    }

    private static Map<String, String> parse(String contents) {
        Map<String, String> result = new TreeMap<>();
        for (String line : contents.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new AssertionError("malformed proc status field: " + line);
            }
            String field = line.substring(0, colon);
            String value = line.substring(colon + 1).trim().replaceAll("\\s+", " ");
            if (result.put(field, value) != null) {
                throw new AssertionError("duplicate proc status field: " + field);
            }
        }
        if (result.isEmpty()) {
            throw new AssertionError("empty proc status snapshot");
        }
        return result;
    }

    private static long[] credentials(Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null || value.split("\\s+").length != 4) {
            throw new AssertionError("missing or malformed " + field + " in proc status");
        }
        return Arrays.stream(value.split("\\s+")).mapToLong(Long::parseLong).toArray();
    }

    private static String identity(long[] values, long selfUid, boolean normalize) {
        return Arrays.stream(values).mapToObj(value -> normalize && value == selfUid
                ? "<isolated uid, user " + selfUid / 100000 + ">" : Long.toString(value))
                .collect(Collectors.joining(" "));
    }
}
