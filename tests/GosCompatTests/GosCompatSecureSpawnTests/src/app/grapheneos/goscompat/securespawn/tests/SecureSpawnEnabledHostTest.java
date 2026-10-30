package app.grapheneos.goscompat.securespawn.tests;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class SecureSpawnEnabledHostTest extends SecureSpawnModeHostTestBase {

    public SecureSpawnEnabledHostTest() {
        super(true);
    }

    @Test
    public void secureAppSpawningUsesExecInit() throws Exception {
        resetPackageState();
        runDeviceTest("execSpawned");
    }
}
