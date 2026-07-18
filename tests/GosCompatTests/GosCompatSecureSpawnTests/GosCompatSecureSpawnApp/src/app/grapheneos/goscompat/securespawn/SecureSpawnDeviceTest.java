package app.grapheneos.goscompat.securespawn;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import app.grapheneos.goscompat.securespawn.shared.SecureSpawnDumpableCheck;
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnFileDescriptorCheck;
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnHiddenApiCheck;
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnReflectiveDumpCheck;
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnSmapsCheck;
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnTestApiCompatCheck;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class SecureSpawnDeviceTest {
    private static final String PACKAGE_NAME = "app.grapheneos.goscompat.securespawn";
    private static final String TAG = "GosCompatSecureSpawn";
    private static final long NATIVE_SERVICE_TIMEOUT_SECONDS = 10;

    private interface NativeSignalProbeAction {
        void run(INativeSignalProbe probe) throws Exception;
    }

    @Test
    public void execSpawned() {
        SecureSpawnCheck.ProcessState result = SecureSpawnCheck.processState();
        assertProcessState(result);
        assertWithMessage(failureMessage("expected execSpawned == true", result))
                .that(result.execSpawned()).isTrue();
        assertWithMessage(failureMessage("expected hardenedMallocDisabled == false", result))
                .that(result.hardenedMallocDisabled()).isFalse();
    }

    @Test
    public void notExecSpawned() {
        SecureSpawnCheck.ProcessState result = SecureSpawnCheck.processState();
        assertProcessState(result);
        assertWithMessage(failureMessage("expected execSpawned == false", result))
                .that(result.execSpawned()).isFalse();
        assertWithMessage(failureMessage("expected hardenedMallocDisabled == false", result))
                .that(result.hardenedMallocDisabled()).isFalse();
    }

    @Test
    public void notExecSpawnedCompatZygote() {
        SecureSpawnCheck.ProcessState result = SecureSpawnCheck.processState();
        assertProcessState(result);
        assertWithMessage(failureMessage("expected execSpawned == false", result))
                .that(result.execSpawned()).isFalse();
        assertWithMessage(failureMessage("expected hardenedMallocDisabled == true", result))
                .that(result.hardenedMallocDisabled()).isTrue();
    }

    @Test
    public void nativeServiceTerminatesOnSigterm() throws Exception {
        withNativeSignalProbe(".NativeSignalProbeService", probe -> {
            CountDownLatch terminated = new CountDownLatch(1);
            probe.asBinder().linkToDeath(terminated::countDown, 0);
            probe.terminateWithSigterm();

            assertWithMessage("expected SIGTERM to terminate native signal probe service")
                    .that(terminated.await(NATIVE_SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
        });
    }

    @Test
    public void nativeServiceHasNoSupplementaryGroups() throws Exception {
        withNativeSignalProbe(".NativeSignalProbeService", probe ->
                assertWithMessage(
                        "expected isolated native service to have no supplementary groups")
                        .that(probe.getSupplementaryGroups()).isEmpty());
    }

    @Test
    public void nativeServiceUsingAppZygoteHasNoSupplementaryGroups() throws Exception {
        // NativeZygoteProcess does not pass the shared app GID that AppZygote requests for a
        // native app zygote, so its children have no supplementary groups, unlike the children of
        // a Java app zygote.
        withNativeSignalProbe(".NativeAppZygoteSignalProbeService", probe ->
                assertWithMessage(
                        "expected native app zygote child to have no supplementary groups")
                        .that(probe.getSupplementaryGroups()).isEmpty());
    }

    private static void withNativeSignalProbe(
            String serviceClassName, NativeSignalProbeAction action) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CompletableFuture<IBinder> connected = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                connected.complete(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {}
        };
        boolean bound = context.bindService(
                new Intent().setComponent(
                        ComponentName.createRelative(PACKAGE_NAME, serviceClassName)),
                connection,
                Context.BIND_AUTO_CREATE);
        try {
            assertWithMessage("expected native signal probe service bind to succeed")
                    .that(bound).isTrue();

            IBinder binder = connected.get(NATIVE_SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            action.run(INativeSignalProbe.Stub.asInterface(binder));
        } finally {
            if (bound) {
                context.unbindService(connection);
            }
        }
    }

    @Test
    public void runtimeMemoryAccountingCheck() {
        SecureSpawnSmapsCheck.AndroidRuntimeSmaps result = SecureSpawnSmapsCheck.run();
        Log.i(TAG, "runtimeMemoryAccountingCheck\n" + result);
        assertWithMessage(failureMessage("expected sections > 0", result))
                .that(result.sections()).isGreaterThan(0);
        assertWithMessage(failureMessage("expected androidRuntimeSections > 0", result))
                .that(result.androidRuntimeSections()).isGreaterThan(0);
        assertWithMessage(failureMessage("expected isWithinMemoryBounds == true", result))
                .that(result.isWithinMemoryBounds()).isTrue();
    }

    @Test
    public void fdStateCheck() {
        SecureSpawnCheck.ProcessState processState = SecureSpawnCheck.processState();
        SecureSpawnFileDescriptorCheck.FileDescriptorState result =
                SecureSpawnFileDescriptorCheck.run();
        Log.i(TAG, "fdStateCheck\n" + processState + "\n" + result);
        assertProcessState(processState);
        assertDescriptorState("framework", result.framework(), result);
        assertDescriptorState("sharedMemory", result.sharedMemory(), result);
        assertWithMessage(failureMessage(
                "expected detached mount ID regression == false", result))
                .that(result.hasDetachedMountIdRegression()).isFalse();
    }

    @Test
    public void prevSELinuxContextCheck() throws IOException {
        String path = "/proc/self/attr/prev";
        String value = new String(Files.readAllBytes(Path.of(path)));
        assertWithMessage(path + " value")
                .that(value)
                .isEqualTo("u:r:init:s0\0");
    }

    @Test
    public void hiddenApiEnforcementCheck() {
        SecureSpawnCheck.ProcessState processState = SecureSpawnCheck.processState();
        SecureSpawnHiddenApiCheck.HiddenApiEnforcement result =
                SecureSpawnHiddenApiCheck.run(processState.execSpawned());
        Log.i(TAG, "hiddenApiEnforcementCheck\n" + result);
        assertProcessState(processState);
        assertWithMessage(failureMessage(
                "expected hidden API execSpawned to match process execSpawned", result))
                .that(result.execSpawned())
                .isEqualTo(processState.execSpawned());
        assertWithMessage(failureMessage("expected objectShadowFieldsHidden == true", result))
                .that(result.objectShadowFieldsHidden()).isTrue();
    }

    @Test
    public void testApiCompatDefaultCheck() {
        testApiCompatCheck("testApiCompatDefaultCheck", false);
    }

    @Test
    public void testApiCompatDisabledCheck() {
        testApiCompatCheck("testApiCompatDisabledCheck", false);
    }

    @Test
    public void testApiCompatEnabledCheck() {
        testApiCompatCheck("testApiCompatEnabledCheck", true);
    }

    @Test
    public void profileableFromShellDumpableCheck() {
        SecureSpawnCheck.ProcessState processState = SecureSpawnCheck.processState();
        SecureSpawnDumpableCheck.DumpableState result =
                SecureSpawnDumpableCheck.run(processState.execSpawned());
        Log.i(TAG, "profileableFromShellDumpableCheck\n" + result);
        assertProcessState(processState);
        assertWithMessage(failureMessage(
                "expected dumpable check execSpawned to match process execSpawned", result))
                .that(result.execSpawned())
                .isEqualTo(processState.execSpawned());
        assertWithMessage(failureMessage("expected isDumpable == true", result))
                .that(result.isDumpable()).isTrue();
    }

    @Test
    public void acyclicReflectiveDumpCheck() {
        SecureSpawnCheck.ProcessState processState = SecureSpawnCheck.processState();
        SecureSpawnReflectiveDumpCheck.AcyclicReflectiveDump result =
                SecureSpawnReflectiveDumpCheck.run(processState.execSpawned());
        Log.i(TAG, "acyclicReflectiveDumpCheck\n" + result);
        assertProcessState(processState);
        assertWithMessage(failureMessage(
                "expected reflective dump execSpawned to match process execSpawned", result))
                .that(result.execSpawned())
                .isEqualTo(processState.execSpawned());
        assertWithMessage(failureMessage("expected threadTid > 0", result))
                .that(result.threadTid()).isGreaterThan(0);
        assertWithMessage(failureMessage("expected threadTid != Process.myTid()", result))
                .that(result.threadTid()).isNotEqualTo(Process.myTid());
        assertWithMessage(failureMessage("expected fixtureDepth > 1", result))
                .that(result.fixtureDepth()).isGreaterThan(1);
        assertWithMessage(failureMessage("expected completed == true", result))
                .that(result.completed()).isTrue();
        assertWithMessage(failureMessage("expected resultLength > 0", result))
                .that(result.resultLength()).isGreaterThan(0);
    }

    private static void testApiCompatCheck(String methodName, boolean expectedAccessAllowed) {
        SecureSpawnCheck.ProcessState processState = SecureSpawnCheck.processState();
        SecureSpawnTestApiCompatCheck.TestApiCompat result =
                SecureSpawnTestApiCompatCheck.run(processState.execSpawned());
        Log.i(TAG, methodName + "\n" + result);
        assertProcessState(processState);
        assertWithMessage(failureMessage(
                "expected test API compat execSpawned to match process execSpawned", result))
                .that(result.execSpawned())
                .isEqualTo(processState.execSpawned());
        SecureSpawnTestApiCompatCheck.AccessOutcome expectedOutcome = expectedAccessAllowed
                ? SecureSpawnTestApiCompatCheck.AccessOutcome.ACCESS_ALLOWED
                : SecureSpawnTestApiCompatCheck.AccessOutcome.ACCESS_DENIED;
        assertWithMessage(failureMessage("expected test API access outcome == "
                + expectedOutcome, result)).that(result.accessResult().outcome())
                .isEqualTo(expectedOutcome);
    }

    private static void assertProcessState(SecureSpawnCheck.ProcessState result) {
        assertWithMessage(failureMessage("expected pid > 0", result))
                .that(result.pid()).isGreaterThan(0);
        assertWithMessage(failureMessage("expected tid > 0", result))
                .that(result.tid()).isGreaterThan(0);
    }

    private static void assertDescriptorState(
            String name,
            SecureSpawnFileDescriptorCheck.DescriptorState state,
            SecureSpawnFileDescriptorCheck.FileDescriptorState result) {
        if (!state.present()) {
            return;
        }
        assertWithMessage(failureMessage("expected " + name + " fd >= 0", result))
                .that(state.fd()).isAtLeast(0);
        assertWithMessage(failureMessage("expected " + name + " mount ID > 0", result))
                .that(state.mountId()).isGreaterThan(0);
    }

    private static String failureMessage(String expectation, Object result) {
        return expectation + "\n" + result;
    }
}
