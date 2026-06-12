package app.grapheneos.goscompat.securespawn.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.pm.GosPackageStateFlag;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;
import com.android.tradefed.util.CommandResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

abstract class SecureSpawnHostTestBase extends BaseHostJUnit4Test {
    private static final String PACKAGE_NAME = "app.grapheneos.goscompat.securespawn";
    private static final String PROFILEABLE_PACKAGE_NAME =
            "app.grapheneos.goscompat.securespawn.profileable";
    private static final String TEST_CLASS =
            "app.grapheneos.goscompat.securespawn.SecureSpawnDeviceTest";
    private static final String LOG_TAG = "GosCompatSecureSpawn";
    private static final String ROOT_CAUSE_LOG_TAG = "GosCompatSecureSpawnRc";
    private static final String ROOT_CAUSE_LOG_TAG_PROPERTY = "log.tag." + ROOT_CAUSE_LOG_TAG;
    protected static final String MEMORY_ACCOUNTING_METHOD =
            "runtimeMemoryAccountingCheck";
    private static final String HIDDEN_API_METHOD =
            "hiddenApiEnforcementCheck";
    private static final String TEST_API_COMPAT_DEFAULT_METHOD =
            "testApiCompatDefaultCheck";
    private static final String TEST_API_COMPAT_DISABLED_METHOD =
            "testApiCompatDisabledCheck";
    private static final String TEST_API_COMPAT_ENABLED_METHOD =
            "testApiCompatEnabledCheck";
    private static final String PROFILEABLE_DUMPABLE_METHOD =
            "profileableFromShellDumpableCheck";
    private static final String ACYCLIC_REFLECTIVE_DUMP_METHOD =
            "acyclicReflectiveDumpCheck";
    // From libcore/libart/src/main/java/dalvik/system/VMRuntime.java:
    // private static final long ALLOW_TEST_API_ACCESS = 166236554;
    private static final long ALLOW_TEST_API_ACCESS_CHANGE_ID = 166236554L;

    @Rule
    public final TestLogData mLogs = new TestLogData();

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

    @Test
    public void hiddenApiEnforcementCheck() throws Exception {
        runCheckCase(HIDDEN_API_METHOD);
    }

    @Test
    public void testApiCompatDefaultCheck() throws Exception {
        runTestApiCompatCheckCase(TEST_API_COMPAT_DEFAULT_METHOD, CompatOverride.DEFAULT);
    }

    @Test
    public void testApiCompatDisabledCheck() throws Exception {
        runTestApiCompatCheckCase(TEST_API_COMPAT_DISABLED_METHOD, CompatOverride.DISABLED);
    }

    @Test
    public void testApiCompatEnabledCheck() throws Exception {
        runTestApiCompatCheckCase(TEST_API_COMPAT_ENABLED_METHOD, CompatOverride.ENABLED);
    }

    @Test
    public void profileableFromShellDumpableCheck() throws Exception {
        runProfileableCheckCase(PROFILEABLE_DUMPABLE_METHOD);
    }

    @Test
    public void acyclicReflectiveDumpCheck() throws Exception {
        runCheckCase(ACYCLIC_REFLECTIVE_DUMP_METHOD);
    }

    protected void runDeviceTest(String methodName) throws Exception {
        runDeviceTest(PACKAGE_NAME, methodName);
    }

    private void runDeviceTest(String packageName, String methodName) throws Exception {
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
            if (isTestApiCompatMethod(methodName)) {
                // Adds --no-test-api-access so ALLOW_TEST_API_ACCESS is the deciding signal.
                options.setDisableTestApiCheck(false);
            }
            boolean passed = false;
            AssertionError deviceAssertion = null;
            try {
                passed = runDeviceTests(options);
            } catch (AssertionError e) {
                deviceAssertion = e;
            } catch (DeviceNotAvailableException e) {
                throw new IllegalStateException(e);
            }
            if (collectResultLog) {
                try {
                    logCheckResult(packageName, methodName);
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

    protected abstract void resetPackageState() throws Exception;

    // TODO: Refactor this when HardeningTests get refactored
    protected void editPackageState(int[] addFlags, int[] clearFlags) throws Exception {
        StringBuilder command = new StringBuilder("pm edit-gos-package-state ")
                .append(PACKAGE_NAME)
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

    private void runCheckCase(String methodName) throws Exception {
        try {
            resetPackageState();
            runDeviceTest(methodName);
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError(methodName, e);
        }
    }

    private void runTestApiCompatCheckCase(String methodName, CompatOverride compatOverride)
            throws Exception {
        assumeCompatOverrideSupported(compatOverride);
        try {
            resetPackageState();
            setTestApiCompatOverride(compatOverride);
            runDeviceTest(methodName);
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError(methodName, e);
        } finally {
            resetCompatState();
        }
    }

    private void runProfileableCheckCase(String methodName) throws Exception {
        try {
            runDeviceTest(PROFILEABLE_PACKAGE_NAME, methodName);
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError(methodName, e);
        }
    }

    private String shell(String command) throws Exception {
        return shell(getDevice(), command);
    }

    private void logCheckResult(String packageName, String methodName) throws Exception {
        String logcatTags = LOG_TAG + ":I";
        if (ACYCLIC_REFLECTIVE_DUMP_METHOD.equals(methodName)) {
            logcatTags += " " + ROOT_CAUSE_LOG_TAG + ":D";
        }
        String logcat = shell("logcat -d -v threadtime -s " + logcatTags + " '*:S'");
        String mode = this instanceof SecureSpawnEnabledHostTest ? "enabled" : "disabled";
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

    private void setTestApiCompatOverride(CompatOverride compatOverride) throws Exception {
        setCompatChange(compatOverride);
    }

    private void assumeCompatOverrideSupported(CompatOverride compatOverride) throws Exception {
        if (compatOverride == CompatOverride.DEFAULT) {
            return;
        }

        String buildType = shell("getprop ro.build.type").trim();
        assumeTrue("compat overrides for non-debuggable apps require a non-user build"
                        + " (ro.build.type=" + buildType + ")",
                !"user".equals(buildType));
    }

    private void resetCompatState() throws Exception {
        setCompatChange(CompatOverride.DEFAULT);
    }

    private void setCompatChange(CompatOverride compatOverride) throws Exception {
        shell("am compat " + compatOverride.command() + " "
                + ALLOW_TEST_API_ACCESS_CHANGE_ID + " " + PACKAGE_NAME);
    }

    private enum CompatOverride {
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

    private static boolean parseExecSpawning(String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (value.equals("true") || value.equals("1")) {
            return true;
        }
        if (value.equals("false") || value.equals("0")) {
            return false;
        }
        return true;
    }
}
