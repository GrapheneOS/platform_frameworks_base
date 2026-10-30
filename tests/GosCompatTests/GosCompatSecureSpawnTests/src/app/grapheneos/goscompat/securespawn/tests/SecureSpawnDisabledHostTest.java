package app.grapheneos.goscompat.securespawn.tests;

import android.content.pm.GosPackageStateFlag;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class SecureSpawnDisabledHostTest extends SecureSpawnModeHostTestBase {

    public SecureSpawnDisabledHostTest() {
        super(false);
    }

    @Test
    public void secureAppSpawningDisabledUsesZygoteInit() throws Exception {
        resetPackageState();
        runDeviceTest("notExecSpawned");
    }

    @Test
    public void exploitCompatibilityModeUsesCompatZygoteInit() throws Exception {
        resetPackageState();
        editPackageState(
                new int[] { GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE },
                new int[0]);
        runDeviceTest("notExecSpawnedCompatZygote");
    }

    @Test
    public void exploitCompatibilityModePassesMemoryAccountingCheck() throws Exception {
        resetPackageState();
        editPackageState(
                new int[] { GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE },
                new int[0]);
        runDeviceTest(MEMORY_ACCOUNTING_METHOD);
    }

    @Test
    public void hardenedMallocOffFdStateCheck() throws Exception {
        resetPackageState();
        editPackageState(
                new int[] { GosPackageStateFlag.USE_HARDENED_MALLOC_NON_DEFAULT },
                new int[] { GosPackageStateFlag.USE_HARDENED_MALLOC });
        runDeviceTest("notExecSpawnedCompatZygote");
        runDeviceTest(FD_STATE_METHOD);
    }
}
