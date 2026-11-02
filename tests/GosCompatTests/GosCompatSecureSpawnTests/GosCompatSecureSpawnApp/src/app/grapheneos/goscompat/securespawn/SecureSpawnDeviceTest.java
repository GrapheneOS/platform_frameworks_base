package app.grapheneos.goscompat.securespawn;

import static com.google.common.truth.Truth.assertWithMessage;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.OsConstants;
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    // Mirrors UserHandle shared app, cache app and user GID calculations.
    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_SHARED_APP_GID = 50000;
    private static final int FIRST_CACHE_APP_GID = 20000;
    private static final int SHARED_USER_GID = 9997;
    // Primary zygote groups from system/core/rootdir/init.zygote64.rc.
    private static final int AID_RESERVED_DISK = 1065;
    private static final int AID_READPROC = 3009;
    private static final Set<String> PRIMARY_ZYGOTE_NAMES = Set.of("zygote64", "zygote");
    // init starts the standalone native zygote as /system/bin/zygote_next with --name zygote_next.
    private static final Set<String> NATIVE_ZYGOTE_NAMES =
            Set.of("zygote_next", "/system/bin/zygote_next");
    private static final long SERVICE_TIMEOUT_SECONDS = 10;
    private static final long WEBVIEW_TIMEOUT_SECONDS = 5;

    @Rule
    public final FailureCollector statusErrors = new FailureCollector(
            "instrumentation failure results", (summary, diagnostics) -> {
                Bundle results = new Bundle();
                results.putString(FailureCollector.SUMMARY_KEY, summary);
                results.putString(FailureCollector.DIAGNOSTICS_KEY, diagnostics);
                InstrumentationRegistry.getInstrumentation().addResults(results);
            });

    private interface WebViewRendererAction {
        void run(WebViewRendererActivity activity, PackageInfo provider, int rendererPid)
                throws Exception;
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

    private enum ProcessKind {
        APP, ISOLATED, NATIVE_ISOLATED, APP_ZYGOTE, NATIVE_APP_ZYGOTE, WEBVIEW
    }

    @Test
    public void appProcessStatus() throws Exception {
        checkProcessStatus(ProcessKind.APP, Process.myPid(), Process.myUid());
    }

    @Test
    public void isolatedServiceProcessStatus() throws Exception {
        checkManagedServiceStatus(ProcessKind.ISOLATED, ".ManagedIsolatedProbeService");
    }

    @Test
    public void appZygoteServiceProcessStatus() throws Exception {
        checkManagedServiceStatus(ProcessKind.APP_ZYGOTE, ".ManagedAppZygoteProbeService");
    }

    @Test
    public void nativeServiceProcessStatus() throws Exception {
        checkNativeServiceStatus(ProcessKind.NATIVE_ISOLATED, ".NativeSignalProbeService");
    }

    @Test
    public void nativeAppZygoteServiceProcessStatus() throws Exception {
        checkNativeServiceStatus(ProcessKind.NATIVE_APP_ZYGOTE,
                ".NativeAppZygoteSignalProbeService");
    }

    @Test
    public void webViewRendererProcessStatus() throws Exception {
        withWebViewRenderer((activity, provider, rendererPid) ->
                checkProcessStatus(ProcessKind.WEBVIEW, rendererPid, provider.applicationInfo.uid));
    }

    private void checkManagedServiceStatus(ProcessKind kind, String service) throws Exception {
        withBoundService(service, binder -> checkProcessStatus(kind,
                IManagedProcessProbe.Stub.asInterface(binder).getPid(), Process.myUid()));
    }

    private void checkNativeServiceStatus(ProcessKind kind, String service) throws Exception {
        withBoundService(service, binder -> checkProcessStatus(kind,
                INativeSignalProbe.Stub.asInterface(binder).getPid(), Process.myUid()));
    }

    private void checkProcessStatus(ProcessKind kind, int pid, int definingUid) throws IOException {
        String mode = InstrumentationRegistry.getArguments().getString("execSpawning");
        assertWithMessage("host must specify execSpawning").that(mode).isIn(Set.of("true", "false"));
        boolean execSpawning = Boolean.parseBoolean(mode);
        String path = "/proc/" + pid + "/status";
        String contents = shell("cat " + path);
        // Publish the same snapshot before policy assertions, parent inspection or syscall probes.
        // A normalization failure must not prevent the explicit field checks below.
        statusErrors.checkSucceeds(() -> {
            Bundle results = new Bundle();
            results.putString(StatusComparison.RESULT_KEY,
                    StatusComparison.snapshot(contents, kind != ProcessKind.APP));
            InstrumentationRegistry.getInstrumentation().addResults(results);
            return null;
        });
        ProcessStatus status = new ProcessStatus(path, contents, statusErrors);
        if (kind == ProcessKind.APP) {
            status.expectIntegers("Uid", false, definingUid, definingUid, definingUid,
                    definingUid);
            status.expectIntegers("Gid", false, definingUid, definingUid, definingUid,
                    definingUid);
        }
        if (kind == ProcessKind.NATIVE_ISOLATED || kind == ProcessKind.NATIVE_APP_ZYGOTE) {
            // Check the main thread's inherited mask directly instead of killing the service.
            // Other threads may subsequently change their own signal masks.
            long spawnSignals = signalBit(OsConstants.SIGCHLD) | signalBit(OsConstants.SIGINT)
                    | signalBit(OsConstants.SIGTERM);
            status.expectBits("SigBlk", "SIGCHLD, SIGINT and SIGTERM bits", spawnSignals, 0);
            status.expectBits("SigIgn", "SIGTERM bit", signalBit(OsConstants.SIGTERM), 0);
            status.expectBits("SigCgt", "SIGTERM bit", signalBit(OsConstants.SIGTERM), 0);
        }
        String expectedNoNewPrivs = switch (kind) {
            case APP_ZYGOTE, NATIVE_APP_ZYGOTE, WEBVIEW -> "1";
            default -> "0";
        };
        // Chromium also sets this bit in its renderer sandbox, so the WebView assertion checks
        // the final policy rather than identifying which layer set it.
        status.expect("NoNewPrivs", expectedNoNewPrivs);
        int appId = definingUid % PER_USER_RANGE;
        int sharedAppGid = FIRST_SHARED_APP_GID + appId - Process.FIRST_APPLICATION_UID;
        switch (kind) {
            case APP -> {
                // ProcessList.computeGidsForProcess(), without permission or storage GIDs.
                int userId = definingUid / PER_USER_RANGE;
                int cacheAppGid = userId * PER_USER_RANGE + FIRST_CACHE_APP_GID
                        + appId - Process.FIRST_APPLICATION_UID;
                int userGid = userId * PER_USER_RANGE + SHARED_USER_GID;
                checkGroups(status, sharedAppGid, cacheAppGid, userGid);
            }
            // Isolated managed processes keep the primary zygote's groups when no list is passed.
            case ISOLATED -> checkGroups(status, AID_RESERVED_DISK, AID_READPROC);
            case APP_ZYGOTE, WEBVIEW -> checkGroups(status, sharedAppGid);
            // NativeZygoteProcess does not pass groups, even the shared app GID requested by
            // AppZygote. Both native service paths therefore have an empty group list.
            case NATIVE_ISOLATED, NATIVE_APP_ZYGOTE -> checkGroups(status);
        }
        ProcessStatus parent = checkParent(kind, status, execSpawning);
        if (parent != null) {
            if (execSpawning || kind == ProcessKind.APP || kind == ProcessKind.ISOLATED) {
                checkGroups(parent, AID_RESERVED_DISK, AID_READPROC);
            } else if (kind == ProcessKind.APP_ZYGOTE || kind == ProcessKind.WEBVIEW) {
                checkGroups(parent, sharedAppGid);
            } else {
                checkGroups(parent);
            }
        }
    }

    private static void checkGroups(ProcessStatus status, int... expected) {
        status.expectIntegers("Groups", true, expected);
    }

    private static long signalBit(int signal) {
        return 1L << (signal - 1);
    }

    private ProcessStatus readProcessStatus(int pid) throws IOException {
        String path = "/proc/" + pid + "/status";
        return new ProcessStatus(path, shell("cat " + path), statusErrors);
    }

    private ProcessStatus checkParent(ProcessKind kind, ProcessStatus status,
            boolean execSpawning) {
        Set<String> expectedNames;
        if (execSpawning || kind == ProcessKind.APP || kind == ProcessKind.ISOLATED) {
            expectedNames = PRIMARY_ZYGOTE_NAMES;
        } else {
            expectedNames = switch (kind) {
                case APP_ZYGOTE -> Set.of(PACKAGE_NAME + "_zygote");
                case NATIVE_APP_ZYGOTE -> Set.of(PACKAGE_NAME + "_zygote_native");
                case NATIVE_ISOLATED -> NATIVE_ZYGOTE_NAMES;
                case WEBVIEW -> Set.of("webview_zygote");
                default -> throw new AssertionError(kind);
            };
        }
        // A malformed PPid prevents parent inspection, but must not suppress other child checks.
        Integer parentPid = statusErrors.checkSucceeds(() ->
                Integer.parseInt(status.value("PPid")));
        if (parentPid == null) {
            return null;
        }
        statusErrors.checkSucceeds(() -> {
            String actual = processName(parentPid);
            if (!expectedNames.contains(actual)) {
                statusErrors.expectEquals(kind + " parent (PID " + parentPid + ")",
                        "name: one of " + new java.util.TreeSet<>(expectedNames), "name: " + actual);
            }
            return null;
        });
        return statusErrors.checkSucceeds(() -> readProcessStatus(parentPid));
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

    private static String processName(int pid) throws IOException {
        String pidString = Integer.toString(pid);
        for (String line : shell("ps -A -o PID,NAME:256").split("\\R")) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length == 2 && fields[0].equals(pidString)) {
                return fields[1];
            }
        }
        throw new AssertionError("missing process " + pid);
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
