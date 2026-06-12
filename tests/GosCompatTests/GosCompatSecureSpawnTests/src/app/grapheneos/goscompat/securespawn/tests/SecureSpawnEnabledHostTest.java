package app.grapheneos.goscompat.securespawn.tests;

import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.AfterClassWithInfo;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public final class SecureSpawnEnabledHostTest extends SecureSpawnHostTestBase {
    private static ExecSpawningClassState sExecSpawningState;

    @BeforeClassWithInfo
    public static void beforeClass(TestInformation testInfo) throws Exception {
        sExecSpawningState = captureExecSpawningState(testInfo, true);
        sExecSpawningState = enterExecSpawningMode(testInfo, sExecSpawningState, true);
    }

    @AfterClassWithInfo
    public static void afterClass(TestInformation testInfo) throws Exception {
        restoreExecSpawningMode(testInfo, sExecSpawningState);
    }

    @Test
    public void secureAppSpawningUsesExecInit() throws Exception {
        resetPackageState();
        runDeviceTest("execSpawned");
    }
}
