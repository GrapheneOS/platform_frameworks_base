package app.grapheneos.goscompat.securespawn.tests;

import android.content.pm.GosPackageStateFlag;

import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.AfterClassWithInfo;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class SecureSpawnDisabledHostTest extends SecureSpawnHostTestBase {
    private static ExecSpawningClassState sExecSpawningState;

    @BeforeClassWithInfo
    public static void beforeClass(TestInformation testInfo) throws Exception {
        sExecSpawningState = captureExecSpawningState(testInfo, false);
        sExecSpawningState = enterExecSpawningMode(testInfo, sExecSpawningState, false);
    }

    @AfterClassWithInfo
    public static void afterClass(TestInformation testInfo) throws Exception {
        restoreExecSpawningMode(testInfo, sExecSpawningState);
    }

    @Test
    public void secureAppSpawningDisabledUsesZygoteInit() throws Exception {
        resetPackageState();
        runDeviceTest("notExecSpawned");
    }

    @Test
    public void disabledHardenedMallocUsesExecInit() throws Exception {
        resetPackageState();
        editPackageState(
                new int[] { GosPackageStateFlag.USE_HARDENED_MALLOC_NON_DEFAULT },
                new int[] { GosPackageStateFlag.USE_HARDENED_MALLOC });
        runDeviceTest("hardenedMallocDisabled");
    }

    @Test
    public void exploitCompatibilityModeUsesExecInit() throws Exception {
        resetPackageState();
        editPackageState(
                new int[] { GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE },
                new int[0]);
        runDeviceTest("hardenedMallocDisabled");
    }
}
