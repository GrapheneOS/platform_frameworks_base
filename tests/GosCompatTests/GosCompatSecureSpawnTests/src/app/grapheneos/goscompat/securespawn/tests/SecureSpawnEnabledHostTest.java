package app.grapheneos.goscompat.securespawn.tests;

import android.content.pm.GosPackageStateFlag;

import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.AfterClassWithInfo;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class SecureSpawnEnabledHostTest extends SecureSpawnHostTestBase {

    @Override
    protected void resetPackageState() throws Exception {
        editPackageState(
                new int[] {
                        GosPackageStateFlag.USE_EXEC_SPAWNING_NON_DEFAULT,
                        GosPackageStateFlag.USE_EXEC_SPAWNING,
                },
                new int[] {
                        GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE,
                        GosPackageStateFlag.USE_HARDENED_MALLOC_NON_DEFAULT,
                        GosPackageStateFlag.USE_HARDENED_MALLOC,
                        GosPackageStateFlag.USE_EXTENDED_VA_SPACE_NON_DEFAULT,
                        GosPackageStateFlag.USE_EXTENDED_VA_SPACE,
                });
    }

    @Test
    public void secureAppSpawningUsesExecInit() throws Exception {
        resetPackageState();
        runDeviceTest("execSpawned");
    }
}
