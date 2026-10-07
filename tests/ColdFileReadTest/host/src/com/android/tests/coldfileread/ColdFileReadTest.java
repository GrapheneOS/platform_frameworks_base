package com.android.tests.coldfileread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.Option;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestMetrics;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class ColdFileReadTest extends BaseHostJUnit4Test {
    private static final String RUNNER = "/data/local/tmp/cold_file_read_runner";

    @Option(name = "swap-cutoff-mib", description = "Free swap cutoff in MiB (strictly below).")
    private Integer mSwapCutoffMiB;

    @Option(name = "file-mib", description = "Size of each fresh file in MiB.")
    private Integer mFileMiB;

    @Option(name = "rounds", description = "Valid target pairs and baseline/recovery attempts.")
    private Integer mRounds;

    @Option(name = "max-allocation-mib", description = "Allocation budget in MiB; 0 selects auto.")
    private Integer mMaxAllocationMiB;

    @Option(name = "timeout-seconds", description = "Deadline for the complete native experiment.")
    private int mTimeoutSeconds = 300;

    @Rule public final TestMetrics mMetrics = new TestMetrics();
    @Rule public final TestLogData mLogs = new TestLogData();

    private String mWorkDir;

    @Test
    public void testColdFileReads() throws Exception {
        assertTrue(
                "timeout-seconds must be 30..600", mTimeoutSeconds >= 30 && mTimeoutSeconds <= 600);

        mWorkDir = "/data/local/tmp/cold-file-read-" + UUID.randomUUID();
        CommandResult setup =
                getDevice()
                        .executeShellV2Command("mkdir -m 700 " + mWorkDir, 10, TimeUnit.SECONDS, 0);
        assertEquals(
                "Cannot create benchmark directory: " + setup.getStderr(),
                Integer.valueOf(0),
                setup.getExitCode());

        String command =
                String.format(
                        Locale.ROOT,
                        "timeout -s KILL %d %s --work-dir %s",
                        mTimeoutSeconds,
                        RUNNER,
                        mWorkDir);
        command += nativeOption("swap-cutoff-mib", mSwapCutoffMiB);
        command += nativeOption("file-mib", mFileMiB);
        command += nativeOption("rounds", mRounds);
        command += nativeOption("max-allocation-mib", mMaxAllocationMiB);

        // Never retry a pressure experiment automatically after a command timeout.
        CommandResult result =
                getDevice()
                        .executeShellV2Command(command, mTimeoutSeconds + 30L, TimeUnit.SECONDS, 0);

        addLog(
                "coldfile_command",
                String.format(
                        Locale.ROOT,
                        "command=%s\nstatus=%s\nexit=%s\n%s\nstderr:\n%s",
                        command,
                        result.getStatus(),
                        result.getExitCode(),
                        result.getStdout(),
                        result.getStderr()));

        boolean valid = publishMetrics(result.getStderr());
        assertEquals(
                "Native command failed (exit=" + result.getExitCode() + "); see coldfile_command",
                CommandStatus.SUCCESS,
                result.getStatus());
        assertEquals(
                "Invalid workload; see coldfile_command", Integer.valueOf(0), result.getExitCode());
        assertTrue("Missing valid benchmark summary", valid);
    }

    private static String nativeOption(String name, Integer value) {
        // Omit unset options so the native runner owns the defaults.
        return value == null ? "" : " --" + name + " " + value;
    }

    // Forward the native summary to atest, including partial results when the workload fails.
    private boolean publishMetrics(String stderr) {
        if (stderr == null) {
            return false;
        }

        boolean valid = false;
        for (String line : stderr.split("\\R")) {
            int separator = line.indexOf('=');
            if (!line.startsWith("coldfile_") || separator < 0) {
                continue;
            }

            String name = line.substring(0, separator);
            String value = line.substring(separator + 1);
            mMetrics.addTestMetric(name, value);
            if (name.equals("coldfile_workload_valid")) {
                valid = value.equals("1");
            }
        }
        return valid;
    }

    @After
    public void cleanUp() throws Exception {
        if (mWorkDir == null) {
            return;
        }

        CommandResult cleanup =
                getDevice().executeShellV2Command("rm -rf " + mWorkDir, 10, TimeUnit.SECONDS, 0);
        assertEquals(
                "Cannot remove benchmark directory", Integer.valueOf(0), cleanup.getExitCode());
    }

    private void addLog(String name, String text) {
        try (ByteArrayInputStreamSource source =
                new ByteArrayInputStreamSource(text.getBytes(StandardCharsets.UTF_8))) {
            mLogs.addTestLog(name, LogDataType.TEXT, source);
        }
    }
}
