package app.grapheneos.goscompat.securespawn;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** One proc status snapshot, with independent assertions for each requested field. */
final class ProcessStatus {
    private final String path;
    private final FailureCollector errors;
    private final Map<String, String> fields = new HashMap<>();

    ProcessStatus(String path, String contents, FailureCollector errors) {
        this.path = path;
        this.errors = errors;
        for (String line : contents.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                fields.put(line.substring(0, colon), line.substring(colon + 1).trim());
            }
        }
    }

    String value(String field) {
        String value = fields.get(field);
        if (value == null) {
            throw new AssertionError(path + "\n" + field + ":\n  expected: <present>\n  actual:   <missing>");
        }
        return value;
    }

    void check(String field, Consumer<String> assertion) {
        errors.checkSucceeds(() -> {
            try {
                assertion.accept(value(field));
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError(path + " field " + field + ": " + e.getMessage(), e);
            }
            return null;
        });
    }

    void expect(String field, String expected) {
        check(field, actual -> errors.expectEquals(path + "\n" + field + ":", expected, actual));
    }

    void expectIntegers(String field, boolean unordered, int... expected) {
        check(field, value -> {
            int[] actual = integers(value);
            int[] wanted = expected.clone();
            if (unordered) {
                Arrays.sort(actual);
                Arrays.sort(wanted);
            }
            errors.expectEquals(path + "\n" + field + ":", Arrays.toString(wanted),
                    Arrays.toString(actual));
        });
    }

    void expectBits(String field, String description, long mask, long expected) {
        check(field, value -> {
            long actual = Long.parseUnsignedLong(value, 16) & mask;
            errors.expectEquals(path + " (" + description + ")\n" + field + ":",
                    Long.toHexString(expected), Long.toHexString(actual));
        });
    }

    static int[] integers(String value) {
        return value.isEmpty() ? new int[0]
                : Arrays.stream(value.split("\\s+")).mapToInt(Integer::parseInt).toArray();
    }
}
