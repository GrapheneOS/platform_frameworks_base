package app.grapheneos.goscompat.securespawn.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.pm.GosPackageStateFlag;

import app.grapheneos.goscompat.securespawn.FailureCollector;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestMetrics;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;
import com.android.tradefed.util.CommandResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Shared device setup, instrumentation and reporting, without inherited test cases. */
abstract class SecureSpawnHostTestBase extends BaseHostJUnit4Test {
    protected static final String PACKAGE_NAME = "app.grapheneos.goscompat.securespawn";
    protected static final String PROFILEABLE_PACKAGE_NAME =
            "app.grapheneos.goscompat.securespawn.profileable";
    private static final String TEST_CLASS =
            "app.grapheneos.goscompat.securespawn.SecureSpawnDeviceTest";
    private static final String LOG_TAG = "GosCompatSecureSpawn";
    private static final String ROOT_CAUSE_LOG_TAG = "GosCompatSecureSpawnRc";
    private static final String ROOT_CAUSE_LOG_TAG_PROPERTY = "log.tag." + ROOT_CAUSE_LOG_TAG;
    protected static final String MEMORY_ACCOUNTING_METHOD =
            "runtimeMemoryAccountingCheck";
    protected static final String FD_STATE_METHOD = "fdStateCheck";
    protected static final String PREV_SELINUX_CONTEXT_METHOD = "prevSELinuxContextCheck";
    protected static final String HIDDEN_API_METHOD =
            "hiddenApiEnforcementCheck";
    protected static final String TEST_API_COMPAT_DEFAULT_METHOD =
            "testApiCompatDefaultCheck";
    protected static final String TEST_API_COMPAT_DISABLED_METHOD =
            "testApiCompatDisabledCheck";
    protected static final String TEST_API_COMPAT_ENABLED_METHOD =
            "testApiCompatEnabledCheck";
    protected static final String PROFILEABLE_DUMPABLE_METHOD =
            "profileableFromShellDumpableCheck";
    protected static final String ACYCLIC_REFLECTIVE_DUMP_METHOD =
            "acyclicReflectiveDumpCheck";
    private static final String WEBVIEW_METHOD_PREFIX = "webViewRenderer";
    private static final String WEBVIEW_ACTIVITY_LIFECYCLE_TIMEOUT_MILLIS = "5000";
    // From libcore/libart/src/main/java/dalvik/system/VMRuntime.java:
    // private static final long ALLOW_TEST_API_ACCESS = 166236554;
    private static final long ALLOW_TEST_API_ACCESS_CHANGE_ID = 166236554L;

    protected final boolean defaultExecSpawning;

    protected SecureSpawnHostTestBase(boolean defaultExecSpawning) {
        this.defaultExecSpawning = defaultExecSpawning;
    }

    // The collector publishes after test teardown, before TestMetrics.after() forwards metrics.
    @Rule(order = 0)
    public final TestMetrics mMetrics = new TestMetrics();

    @Rule(order = 1)
    public final TestLogData mLogs = new TestLogData();

    @Rule(order = 2)
    public final FailureCollector statusErrors = new FailureCollector(
            "secure_spawn_failures metric/report", this::reportFailures);

    private void reportFailures(String summary, String diagnostics) {
        mMetrics.addTestMetric("secure_spawn_failures", "\n" + summary);
        addFailureLog("secure_spawn_failures", summary);
        addFailureLog("secure_spawn_failure_diagnostics", diagnostics);
    }

    private void addFailureLog(String name, String text) {
        try (ByteArrayInputStreamSource source =
                new ByteArrayInputStreamSource(text.getBytes(StandardCharsets.UTF_8))) {
            mLogs.addTestLog(name, LogDataType.TEXT, source);
        }
    }

    @Before
    public void setUp() throws Exception {
        resetCompatState();
        resetPackageState();
    }

    @After
    public void tearDown() throws Exception {
        resetCompatState();
        resetPackageState();
        shell("am force-stop " + PACKAGE_NAME);
        shell("am force-stop " + PROFILEABLE_PACKAGE_NAME);
    }

    protected final void runDeviceTest(String methodName, boolean execSpawning) throws Exception {
        runDeviceTest(PACKAGE_NAME, methodName, execSpawning);
    }

    protected final void runDeviceTest(String methodName) throws Exception {
        runDeviceTest(PACKAGE_NAME, methodName);
    }

    protected final void runDeviceTest(String packageName, String methodName) throws Exception {
        runDeviceTest(packageName, methodName, defaultExecSpawning);
    }

    private void runDeviceTest(String packageName, String methodName, boolean execSpawning)
            throws Exception {
        boolean collectResultLog = resultLogName(methodName) != null;
        boolean collectRootCauseLog = ACYCLIC_REFLECTIVE_DUMP_METHOD.equals(methodName);
        String originalRootCauseLogTag = null;
        try {
            if (collectRootCauseLog) {
                originalRootCauseLogTag = shell("getprop " + ROOT_CAUSE_LOG_TAG_PROPERTY).trim();
                setRootCauseLogTag("DEBUG");
            }

            shell("am force-stop " + packageName);
            if (collectResultLog) {
                shell("logcat -c");
            }
            DeviceTestRunOptions options = new DeviceTestRunOptions(packageName);
            options.setTestClassName(TEST_CLASS);
            options.setTestMethodName(methodName);
            options.addInstrumentationArg("execSpawning", Boolean.toString(execSpawning));
            if (methodName.startsWith(WEBVIEW_METHOD_PREFIX)) {
                options.addInstrumentationArg(
                        "activityLifecycleChangeTimeoutMillis",
                        WEBVIEW_ACTIVITY_LIFECYCLE_TIMEOUT_MILLIS);
            }
            if (isTestApiCompatMethod(methodName)) {
                // Adds --no-test-api-access so ALLOW_TEST_API_ACCESS is the deciding signal.
                options.setDisableTestApiCheck(false);
            }
            boolean passed = false;
            AssertionError deviceAssertion = null;
            TestRunResult previous = getLastDeviceRunResults();
            try {
                passed = runDeviceTests(options);
            } catch (AssertionError e) {
                TestRunResult result = getLastDeviceRunResults();
                String summary = result == null || result == previous ? null
                        : result.getRunMetrics().get(FailureCollector.SUMMARY_KEY);
                if (summary != null && !summary.isBlank()) {
                    String diagnostics =
                            result.getRunMetrics().get(FailureCollector.DIAGNOSTICS_KEY);
                    if (result.isRunFailure()) {
                        summary += "\nInstrumentation run failure: "
                                + result.getRunFailureMessage();
                        diagnostics = (diagnostics == null ? "" : diagnostics + "\n")
                                + FailureCollector.diagnostics(e);
                    } else if (diagnostics == null) {
                        diagnostics = FailureCollector.diagnostics(e);
                    }
                    deviceAssertion = FailureCollector.reportedFailure(
                            methodName + "\n" + summary, diagnostics);
                } else {
                    // Crashes or failures outside the device rule may have no structured report.
                    // Keep the complete exception in the attached diagnostics, not in the summary.
                    deviceAssertion = FailureCollector.reportedFailure(
                            methodName + ": device test failed without a failure report; "
                                    + "see secure_spawn_failure_diagnostics",
                            FailureCollector.diagnostics(e));
                }
            } catch (DeviceNotAvailableException e) {
                throw new IllegalStateException(e);
            }
            if (collectResultLog) {
                try {
                    logCheckResult(packageName, methodName, execSpawning);
                } catch (AssertionError | Exception e) {
                    if (deviceAssertion == null) {
                        throw e;
                    }
                    deviceAssertion.addSuppressed(e);
                }
            }
            if (deviceAssertion != null) {
                throw deviceAssertion;
            }
            assertTrue(methodName, passed);
        } finally {
            if (collectRootCauseLog) {
                shell("am force-stop " + packageName);
                restoreRootCauseLogTag(originalRootCauseLogTag);
            }
        }
    }

    protected final void resetPackageState() throws Exception {
        resetPackageState(PACKAGE_NAME, defaultExecSpawning);
    }

    protected final void resetPackageState(String packageName) throws Exception {
        resetPackageState(packageName, defaultExecSpawning);
    }

    protected final void resetPackageState(boolean execSpawning) throws Exception {
        resetPackageState(PACKAGE_NAME, execSpawning);
    }

    private void resetPackageState(String packageName, boolean execSpawning) throws Exception {
        int[] clearFlags = {
                GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE,
                GosPackageStateFlag.USE_HARDENED_MALLOC_NON_DEFAULT,
                GosPackageStateFlag.USE_HARDENED_MALLOC,
                GosPackageStateFlag.USE_EXTENDED_VA_SPACE_NON_DEFAULT,
                GosPackageStateFlag.USE_EXTENDED_VA_SPACE,
        };
        int[] addFlags = execSpawning
                ? new int[] { GosPackageStateFlag.USE_EXEC_SPAWNING_NON_DEFAULT,
                        GosPackageStateFlag.USE_EXEC_SPAWNING }
                : new int[] { GosPackageStateFlag.USE_EXEC_SPAWNING_NON_DEFAULT };
        if (!execSpawning) {
            clearFlags = Arrays.copyOf(clearFlags, clearFlags.length + 1);
            clearFlags[clearFlags.length - 1] = GosPackageStateFlag.USE_EXEC_SPAWNING;
        }
        editPackageState(packageName, addFlags, clearFlags);
    }

    protected final void editPackageState(int[] addFlags, int[] clearFlags) throws Exception {
        editPackageState(PACKAGE_NAME, addFlags, clearFlags);
    }

    private void editPackageState(String packageName, int[] addFlags, int[] clearFlags)
            throws Exception {
        StringBuilder command = new StringBuilder("pm edit-gos-package-state ")
                .append(packageName)
                .append(' ')
                .append(getDevice().getCurrentUser());
        for (int flag : addFlags) {
            command.append(" add-flag ").append(flag);
        }
        for (int flag : clearFlags) {
            command.append(" clear-flag ").append(flag);
        }
        command.append(" set-kill-uid-after-apply true");
        shell(command.toString());
    }

    protected final String shell(String command) throws Exception {
        return shell(getDevice(), command);
    }

    private void logCheckResult(String packageName, String methodName, boolean execSpawning)
            throws Exception {
        String logcatTags = LOG_TAG + ":I";
        if (ACYCLIC_REFLECTIVE_DUMP_METHOD.equals(methodName)) {
            logcatTags += " " + ROOT_CAUSE_LOG_TAG + ":D";
        }
        String logcat = shell("logcat -d -v threadtime -s " + logcatTags + " '*:S'");
        String mode = execSpawning ? "enabled" : "disabled";
        String logName = resultLogName(methodName);
        String report = "package=" + packageName
                + "\nmethod=" + methodName
                + "\n\n"
                + logcat;
        try (ByteArrayInputStreamSource source =
                new ByteArrayInputStreamSource(report.getBytes(StandardCharsets.UTF_8))) {
            mLogs.addTestLog("goscompat_secure_spawn_" + logName + "_" + mode,
                    LogDataType.TEXT, source);
        }
    }

    private static String resultLogName(String methodName) {
        if (MEMORY_ACCOUNTING_METHOD.equals(methodName)) {
            return "memory_accounting";
        }
        if (FD_STATE_METHOD.equals(methodName)) {
            return "fd_state";
        }
        if (PREV_SELINUX_CONTEXT_METHOD.equals(methodName)) {
            return "prev_selinux_context";
        }
        if (HIDDEN_API_METHOD.equals(methodName)) {
            return "hidden_api";
        }
        if (TEST_API_COMPAT_DEFAULT_METHOD.equals(methodName)) {
            return "test_api_compat_default";
        }
        if (TEST_API_COMPAT_DISABLED_METHOD.equals(methodName)) {
            return "test_api_compat_disabled";
        }
        if (TEST_API_COMPAT_ENABLED_METHOD.equals(methodName)) {
            return "test_api_compat_enabled";
        }
        if (PROFILEABLE_DUMPABLE_METHOD.equals(methodName)) {
            return "profileable_dumpable";
        }
        if (ACYCLIC_REFLECTIVE_DUMP_METHOD.equals(methodName)) {
            return "acyclic_reflective_dump";
        }
        return null;
    }

    private static boolean isTestApiCompatMethod(String methodName) {
        return TEST_API_COMPAT_DEFAULT_METHOD.equals(methodName)
                || TEST_API_COMPAT_DISABLED_METHOD.equals(methodName)
                || TEST_API_COMPAT_ENABLED_METHOD.equals(methodName);
    }

    protected final void resetCompatState() throws Exception {
        setCompatChange(CompatOverride.DEFAULT);
    }

    protected final void setCompatChange(CompatOverride compatOverride) throws Exception {
        shell("am compat " + compatOverride.command() + " "
                + ALLOW_TEST_API_ACCESS_CHANGE_ID + " " + PACKAGE_NAME);
    }

    protected enum CompatOverride {
        DEFAULT("reset"),
        DISABLED("disable"),
        ENABLED("enable");

        private final String mCommand;

        CompatOverride(String command) {
            mCommand = command;
        }

        String command() {
            return mCommand;
        }
    }

    private void restoreRootCauseLogTag(String value) throws Exception {
        setRootCauseLogTag(value == null ? "" : value);
    }

    private void setRootCauseLogTag(String value) throws Exception {
        assertTrue(ROOT_CAUSE_LOG_TAG_PROPERTY + "="
                + (value.isEmpty() ? "<unset>" : value),
                getDevice().setProperty(ROOT_CAUSE_LOG_TAG_PROPERTY, value));
    }

    private static String shell(ITestDevice device, String command) throws Exception {
        CommandResult result = device.executeShellV2Command(command);
        assertEquals(result.toString(), 0L, (long) result.getExitCode());
        return result.getStdout() == null ? "" : result.getStdout();
    }

}
