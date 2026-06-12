package app.grapheneos.goscompat.securespawn.shared;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public final class SecureSpawnSmapsCheck {
    private static final String ANDROID_RUNTIME_LIBRARY = "libandroid_runtime.so";
    private static final String SHARED_CLEAN_LABEL = "Shared_Clean:";
    private static final String SHARED_DIRTY_LABEL = "Shared_Dirty:";
    private static final long BYTES_PER_KIB = 1024L;
    private static final long ANDROID_RUNTIME_SHARED_CLEAN_LIMIT = 480L * BYTES_PER_KIB;

    private SecureSpawnSmapsCheck() {
    }

    public static AndroidRuntimeSmaps run() {
        int sections = 0;
        int androidRuntimeSections = 0;
        int measuredSections = 0;
        int outOfBoundsSections = 0;
        long maxAndroidRuntimeSharedClean = 0;
        long maxMeasuredSharedClean = 0;
        long maxOutOfBoundsSharedClean = 0;
        StringBuilder measuredDetails = new StringBuilder();
        String firstOutOfBoundsHeader = "";
        long firstOutOfBoundsSharedClean = 0;
        long firstOutOfBoundsSharedDirty = 0;
        String header = null;
        long sharedClean = 0;
        long sharedDirty = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/smaps"))) {
            while (true) {
                String line = reader.readLine();
                if (line == null || isSmapsHeader(line)) {
                    if (header != null) {
                        sections++;
                        boolean androidRuntime = header.contains(ANDROID_RUNTIME_LIBRARY);
                        boolean measured = androidRuntime && isMeasuredAndroidRuntimeHeader(header);
                        boolean outOfBounds = measured
                                && sharedDirty <= 0
                                && sharedClean >= ANDROID_RUNTIME_SHARED_CLEAN_LIMIT;

                        if (androidRuntime) {
                            androidRuntimeSections++;
                            maxAndroidRuntimeSharedClean = Math.max(
                                    maxAndroidRuntimeSharedClean, sharedClean);
                        }

                        if (measured) {
                            measuredSections++;
                            maxMeasuredSharedClean = Math.max(
                                    maxMeasuredSharedClean, sharedClean);
                            appendMeasuredDetail(measuredDetails, header, sharedClean,
                                    sharedDirty, outOfBounds);
                        }

                        if (outOfBounds) {
                            outOfBoundsSections++;
                            maxOutOfBoundsSharedClean = Math.max(
                                    maxOutOfBoundsSharedClean, sharedClean);
                            if (firstOutOfBoundsHeader.isEmpty()) {
                                firstOutOfBoundsHeader = header;
                                firstOutOfBoundsSharedClean = sharedClean;
                                firstOutOfBoundsSharedDirty = sharedDirty;
                            }
                        }
                    }

                    if (line == null) {
                        break;
                    }

                    header = line;
                    sharedClean = 0;
                    sharedDirty = 0;
                    continue;
                }

                if (header == null) {
                    continue;
                }

                long nextSharedClean = parseSmapsValue(line, SHARED_CLEAN_LABEL);
                if (nextSharedClean >= 0) {
                    sharedClean = nextSharedClean;
                    continue;
                }

                long nextSharedDirty = parseSmapsValue(line, SHARED_DIRTY_LABEL);
                if (nextSharedDirty >= 0) {
                    sharedDirty = nextSharedDirty;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read smaps", e);
        }

        return new AndroidRuntimeSmaps(
                sections,
                androidRuntimeSections,
                measuredSections,
                outOfBoundsSections,
                maxAndroidRuntimeSharedClean,
                maxMeasuredSharedClean,
                maxOutOfBoundsSharedClean,
                ANDROID_RUNTIME_SHARED_CLEAN_LIMIT,
                measuredDetails.toString(),
                firstOutOfBoundsHeader,
                firstOutOfBoundsSharedClean,
                firstOutOfBoundsSharedDirty);
    }

    private static boolean isSmapsHeader(String line) {
        int dash = line.indexOf('-');
        int space = line.indexOf(' ');
        return dash > 0 && space > dash + 1
                && isHex(line, 0, dash)
                && isHex(line, dash + 1, space);
    }

    private static boolean isHex(String value, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9')
                    && (c < 'a' || c > 'f')
                    && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return start < end;
    }

    private static long parseSmapsValue(String line, String label) {
        if (!line.startsWith(label)) {
            return -1;
        }

        String value = line.substring(label.length()).trim();
        int separator = value.indexOf(' ');
        if (separator >= 0) {
            value = value.substring(0, separator);
        }

        try {
            return Long.parseLong(value) * BYTES_PER_KIB;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isMeasuredAndroidRuntimeHeader(String header) {
        String[] fields = header.split("\\s+");
        if (fields.length <= 1) {
            return false;
        }

        String permissions = fields[1];
        return permissions.length() > 3
                && permissions.charAt(1) != 'w'
                && permissions.charAt(2) != 'x'
                && permissions.charAt(3) == 'p';
    }

    private static void appendMeasuredDetail(
            StringBuilder details, String header, long sharedClean, long sharedDirty,
            boolean outOfBounds) {
        if (details.length() > 0) {
            details.append('\n');
        }
        details.append("outOfBounds=").append(outOfBounds)
                .append(" Shared_Clean=").append(sharedClean)
                .append(" Shared_Dirty=").append(sharedDirty)
                .append(" header=").append(header);
    }

    public record AndroidRuntimeSmaps(
            int sections,
            int androidRuntimeSections,
            int measuredSections,
            int outOfBoundsSections,
            long maxAndroidRuntimeSharedClean,
            long maxMeasuredSharedClean,
            long maxOutOfBoundsSharedClean,
            long sharedCleanLimit,
            String measuredDetails,
            String firstOutOfBoundsHeader,
            long firstOutOfBoundsSharedClean,
            long firstOutOfBoundsSharedDirty) {
        public boolean isWithinMemoryBounds() {
            return outOfBoundsSections() == 0;
        }

        @Override
        public String toString() {
            return "sections=" + sections()
                    + "\nandroidRuntimeSections=" + androidRuntimeSections()
                    + "\nmeasuredSections=" + measuredSections()
                    + "\noutOfBoundsSections=" + outOfBoundsSections()
                    + "\nmaxAndroidRuntimeSharedClean=" + maxAndroidRuntimeSharedClean()
                    + "\nmaxMeasuredSharedClean=" + maxMeasuredSharedClean()
                    + "\nmaxOutOfBoundsSharedClean=" + maxOutOfBoundsSharedClean()
                    + "\nsharedCleanLimit=" + sharedCleanLimit()
                    + "\nmeasuredDetails=" + measuredDetails()
                    + "\nfirstOutOfBoundsHeader=" + firstOutOfBoundsHeader()
                    + "\nfirstOutOfBoundsSharedClean=" + firstOutOfBoundsSharedClean()
                    + "\nfirstOutOfBoundsSharedDirty=" + firstOutOfBoundsSharedDirty();
        }
    }
}
