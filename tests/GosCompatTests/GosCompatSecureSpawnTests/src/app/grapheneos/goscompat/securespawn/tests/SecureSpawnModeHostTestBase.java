package app.grapheneos.goscompat.securespawn.tests;

import static org.junit.Assume.assumeTrue;

import org.junit.Test;

/** Cases run independently in the enabled and disabled control configurations. */
abstract class SecureSpawnModeHostTestBase extends SecureSpawnHostTestBase {

    protected SecureSpawnModeHostTestBase(boolean execSpawning) {
        super(execSpawning);
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

    @Test
    public void fdStateCheck() throws Exception {
        runCheckCase(FD_STATE_METHOD);
    }

    @Test
    public void prevSELinuxContextCheck() throws Exception {
        runCheckCase(PREV_SELINUX_CONTEXT_METHOD);
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
            setCompatChange(compatOverride);
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

    private void assumeCompatOverrideSupported(CompatOverride compatOverride) throws Exception {
        if (compatOverride == CompatOverride.DEFAULT) {
            return;
        }

        String buildType = shell("getprop ro.build.type").trim();
        assumeTrue("compat overrides for non-debuggable apps require a non-user build"
                        + " (ro.build.type=" + buildType + ")",
                !"user".equals(buildType));
    }

}
