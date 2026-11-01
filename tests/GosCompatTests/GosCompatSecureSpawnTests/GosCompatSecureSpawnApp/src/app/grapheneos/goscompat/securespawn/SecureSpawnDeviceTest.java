package app.grapheneos.goscompat.securespawn;

import static com.google.common.truth.Truth.assertWithMessage;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class SecureSpawnDeviceTest {
    private static final String PACKAGE_NAME = "app.grapheneos.goscompat.securespawn";
    private static final String TAG = "GosCompatSecureSpawn";
    // Mirrors hidden framework constants used by UserHandle.getSharedAppGid().
    private static final int USER_HANDLE_PER_USER_RANGE = 100000;
    private static final int PROCESS_FIRST_SHARED_APPLICATION_GID = 50000;
    // Mirrors hidden framework constants used by UserHandle.getCacheAppGid() and
    // UserHandle.getUserGid().
    private static final int PROCESS_FIRST_APPLICATION_CACHE_GID = 20000;
    private static final int PROCESS_SHARED_USER_GID = 9997;
    // Mirrors the primary zygote groups in system/core/rootdir/init.zygote64.rc.
    private static final int AID_RESERVED_DISK = 1065;
    private static final int AID_READPROC = 3009;
    private static final long SERVICE_TIMEOUT_SECONDS = 10;
    private static final long WEBVIEW_TIMEOUT_SECONDS = 5;

    private interface WebViewRendererAction {
        void run(WebViewRendererActivity activity, PackageInfo provider, int rendererPid)
                throws Exception;
    }

    private interface NativeSignalProbeAction {
        void run(INativeSignalProbe probe) throws Exception;
    }

    private interface BoundServiceAction {
        void run(IBinder binder) throws Exception;
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
                    .that(terminated.await(SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
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

    @Test
    public void serviceUsingManagedAppZygoteHasExpectedSupplementaryGroups() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int appId = context.getApplicationInfo().uid % USER_HANDLE_PER_USER_RANGE;
        int expectedGroup = PROCESS_FIRST_SHARED_APPLICATION_GID
                + appId - Process.FIRST_APPLICATION_UID;
        withBoundService(".ManagedAppZygoteProbeService", binder -> {
            int pid = IManagedProcessProbe.Stub.asInterface(binder).getPid();
            assertWithMessage("expected only the defining package shared-app GID")
                    .that(processSupplementaryGroups(pid)).asList()
                    .containsExactly(expectedGroup);
        });
    }

    @Test
    public void webViewRendererHasExpectedSupplementaryGroups() throws Exception {
        withWebViewRenderer((activity, provider, rendererPid) -> {
            int appId = provider.applicationInfo.uid % USER_HANDLE_PER_USER_RANGE;
            int expectedGroup = PROCESS_FIRST_SHARED_APPLICATION_GID
                    + appId - Process.FIRST_APPLICATION_UID;
            assertWithMessage("expected only the WebView provider shared-app GID")
                    .that(processSupplementaryGroups(rendererPid)).asList()
                    .containsExactly(expectedGroup);
        });
    }

    @Test
    public void appProcessHasExpectedSupplementaryGroups() throws Exception {
        // Mirrors ProcessList.computeGidsForProcess() for a package without permission GIDs or
        // external storage access.
        int uid = Process.myUid();
        int userId = uid / USER_HANDLE_PER_USER_RANGE;
        int appId = uid % USER_HANDLE_PER_USER_RANGE;
        int sharedAppGid = PROCESS_FIRST_SHARED_APPLICATION_GID
                + appId - Process.FIRST_APPLICATION_UID;
        int cacheAppGid = userId * USER_HANDLE_PER_USER_RANGE
                + PROCESS_FIRST_APPLICATION_CACHE_GID + appId - Process.FIRST_APPLICATION_UID;
        int userGid = userId * USER_HANDLE_PER_USER_RANGE + PROCESS_SHARED_USER_GID;
        assertWithMessage("expected only the shared app, cache app and user GIDs")
                .that(processSupplementaryGroups(Process.myPid())).asList()
                .containsExactly(sharedAppGid, cacheAppGid, userGid);
    }

    @Test
    public void isolatedServiceWithoutAppZygoteHasPrimaryZygoteSupplementaryGroups()
            throws Exception {
        // ProcessList passes no group list for isolated processes, so an isolated service
        // without an app zygote keeps the primary zygote groups.
        withBoundService(".ManagedIsolatedProbeService", binder -> {
            int pid = IManagedProcessProbe.Stub.asInterface(binder).getPid();
            assertWithMessage("expected only the primary zygote readproc and reserved_disk GIDs")
                    .that(processSupplementaryGroups(pid)).asList()
                    .containsExactly(AID_RESERVED_DISK, AID_READPROC);
        });
    }

    private static void withWebViewRenderer(WebViewRendererAction action) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
        assertWithMessage("expected a current WebView provider")
                .that(webViewPackage).isNotNull();

        String providerPackageName = webViewPackage.packageName;
        Map<Integer, String> existingRenderers = webViewRendererProcesses(providerPackageName);
        Intent intent = new Intent(instrumentation.getTargetContext(),
                WebViewRendererActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        WebViewRendererActivity activity =
                (WebViewRendererActivity) instrumentation.startActivitySync(intent);
        try {
            CountDownLatch pageLoaded = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> {
                WebView webView = activity.getWebView();
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        pageLoaded.countDown();
                    }
                });
                webView.loadData("<html><body>ready</body></html>", "text/html", "UTF-8");
            });
            assertWithMessage("expected WebView page to finish loading")
                    .that(pageLoaded.await(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Map<Integer, String> renderers = webViewRendererProcesses(providerPackageName);
            Set<Integer> rendererPids = new HashSet<>();
            for (Map.Entry<Integer, String> renderer : renderers.entrySet()) {
                int parentPid = Integer.parseInt(renderer.getValue().split("\\s+")[1]);
                // A process forked by a renderer keeps the renderer's name until it execs.
                if (!existingRenderers.containsKey(renderer.getKey())
                        && !renderers.containsKey(parentPid)) {
                    rendererPids.add(renderer.getKey());
                }
            }
            assertWithMessage("expected exactly one new renderer for " + providerPackageName
                    + "; renderers before: " + existingRenderers.values()
                    + "; renderers after: " + renderers.values())
                    .that(rendererPids).hasSize(1);
            int rendererPid = rendererPids.iterator().next();
            action.run(activity, webViewPackage, rendererPid);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }

    /** Returns the ps line of each WebView renderer, keyed by PID. */
    private static Map<Integer, String> webViewRendererProcesses(String providerPackageName)
            throws IOException {
        Map<Integer, String> result = new HashMap<>();
        String processNamePrefix = providerPackageName + ":sandboxed_process";
        for (String line : shell("ps -A -o PID,PPID,UID,NAME:256").split("\\R")) {
            String[] fields = line.trim().split("\\s+", 4);
            if (fields.length == 4 && fields[3].startsWith(processNamePrefix)) {
                result.put(Integer.parseInt(fields[0]), line.trim());
            }
        }
        return result;
    }

    private static int[] processSupplementaryGroups(int pid) throws IOException {
        String value = processStatusField(pid, "Groups");
        if (value.isEmpty()) {
            return new int[0];
        }

        String[] fields = value.split("\\s+");
        int[] groups = new int[fields.length];
        for (int i = 0; i < fields.length; ++i) {
            groups[i] = Integer.parseInt(fields[i]);
        }
        return groups;
    }

    private static String processStatusField(int pid, String field) throws IOException {
        String status = shell("cat /proc/" + pid + "/status");
        String prefix = field + ":";
        for (String line : status.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        throw new AssertionError("missing " + field + " in /proc/" + pid + "/status:\n"
                + status);
    }

    // UiAutomation runs commands as shell, which can read cross-UID proc paths on user builds.
    private static String shell(String command) throws IOException {
        ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void withNativeSignalProbe(
            String serviceClassName, NativeSignalProbeAction action) throws Exception {
        withBoundService(serviceClassName,
                binder -> action.run(INativeSignalProbe.Stub.asInterface(binder)));
    }

    private static void withBoundService(
            String serviceClassName, BoundServiceAction action) throws Exception {
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
            assertWithMessage("expected " + serviceClassName + " bind to succeed")
                    .that(bound).isTrue();

            IBinder binder = connected.get(SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            action.run(binder);
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
