package app.grapheneos.goscompat.securespawn.tests;

import static org.junit.Assert.assertTrue;

import app.grapheneos.goscompat.securespawn.StatusComparison;

import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Each case checks one process type in both spawning modes and compares the same snapshots. */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class SecureSpawnProcessStatusHostTest extends SecureSpawnHostTestBase {
    public SecureSpawnProcessStatusHostTest() {
        super(false);
    }

    @Test
    public void appProcessStatus() {
        checkProcessStatus("appProcessStatus");
    }

    @Test
    public void isolatedServiceProcessStatus() {
        checkProcessStatus("isolatedServiceProcessStatus");
    }

    @Test
    public void appZygoteServiceProcessStatus() {
        checkProcessStatus("appZygoteServiceProcessStatus");
    }

    @Test
    public void nativeServiceProcessStatus() {
        checkProcessStatus("nativeServiceProcessStatus");
    }

    @Test
    public void nativeAppZygoteServiceProcessStatus() {
        checkProcessStatus("nativeAppZygoteServiceProcessStatus");
    }

    @Test
    public void webViewRendererProcessStatus() {
        checkProcessStatus("webViewRendererProcessStatus");
    }

    private void checkProcessStatus(String methodName) {
        StatusComparison.checkBothModes(statusErrors,
                () -> captureStatusForDiff(methodName, false),
                () -> captureStatusForDiff(methodName, true));
    }

    private String captureStatusForDiff(String methodName, boolean execSpawning)
            throws Exception {
        resetPackageState(execSpawning);
        // A failed device assertion still leaves run metrics containing the pre-probe snapshot.
        // Keep it for the diff, but never reuse a previous run's metrics if instrumentation fails.
        TestRunResult previous = getLastDeviceRunResults();
        String mode = execSpawning ? "Exec spawning enabled" : "Exec spawning disabled";
        statusErrors.checkSucceeds(mode + ": checks against expected values", () -> {
            runDeviceTest(methodName, execSpawning);
            return null;
        });
        TestRunResult result = getLastDeviceRunResults();
        assertTrue("missing fresh instrumentation result from " + methodName,
                result != null && result != previous);
        String snapshot = result.getRunMetrics().get(StatusComparison.RESULT_KEY);
        assertTrue("missing stable status result from " + methodName,
                snapshot != null && !snapshot.isBlank());
        return snapshot;
    }
}
