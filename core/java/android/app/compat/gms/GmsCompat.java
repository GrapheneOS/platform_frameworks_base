package android.app.compat.gms;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.ext.AppInfoExt;
import android.ext.AppInfoExtFlag;
import android.ext.PackageId;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.gmscompat.GmsCompatLib;
import com.android.internal.gmscompat.GmsHooks;
import com.android.internal.gmscompat.GmsInfo;
import com.android.internal.gmscompat.fileservice.GmsCoreFileServerClientHooks;

/**
 * This class provides helpers for GMS ("Google Mobile Services") compatibility.
 * <p>
 * It allows the following apps to work as regular, unprivileged user apps:
 * <ul>
 *     <li>GSF ("Google Services Framework")</li>
 *     <li>GmsCore ("Google Play services")</li>
 *     <li>Google Play Store</li>
 *     <li>GSA ("Google Search app", com.google.android.googlequicksearchbox)</li>
 *     <li>Apps that depend on the above</li>
 * </ul>
 * <p>
 *
 * @hide
 */
@SystemApi
public final class GmsCompat {
    private static final String TAG = "GmsCompat/Core";

    private static boolean isGmsCompatEnabled;
    private static int curPackageId;
    private static boolean isGmsCore;

    private static boolean isEligibleForClientCompat;
    private static boolean isInGmsCompatProcess;

    // Static only
    private GmsCompat() { }

    public static boolean isEnabled() {
        return isGmsCompatEnabled;
    }

    /** @hide */
    public static boolean isDevBuild() {
        return !Build.IS_USER;
    }

    /** @hide */
    public static boolean isGmsCore() {
        return curPackageId == PackageId.GMS_CORE;
    }

    /** @hide */
    public static boolean isPlayStore() {
        return curPackageId == PackageId.PLAY_STORE;
    }

    /** @hide */
    public static boolean isGCarrierSettings() {
        return curPackageId == PackageId.G_CARRIER_SETTINGS;
    }

    public static boolean isAndroidAuto() {
        return curPackageId == PackageId.ANDROID_AUTO;
    }

    /** @hide */
    public static int getCurrentPackageId() {
        return curPackageId;
    }

    /** @hide */
    public static boolean isInGmsCompatProcess() {
        return isInGmsCompatProcess;
    }

    private static Context appContext;

    /** @hide */
    public static Context appContext() {
        return appContext;
    }

    /**
     * Call from Instrumentation.newApplication() before Application class in instantiated to
     * make sure init is completed in GMS processes before any of the app's code is executed.
     *
     * @hide
     */
    public static void maybeEnable(Context appCtx) {
        if (!Process.isApplicationUid(Process.myUid())) {
            // note that isApplicationUid() returns false for processes of services that have
            // 'android:isolatedProcess="true"' directive in AndroidManifest, which is fine,
            // because they have no need for GmsCompat
            return;
        }

        appContext = appCtx;
        ApplicationInfo appInfo = appCtx.getApplicationInfo();
        AppInfoExt appInfoExt = appInfo.ext();

        curPackageId = appInfoExt.getPackageId();

        if (isEnabledFor(appInfo)) {
            String processName = Application.getProcessName();
            if (isGmsCompatProcess(processName)) {
                isInGmsCompatProcess = true;
            } else {
                isGmsCompatEnabled = true;
                GmsHooks.init(appCtx, appInfo.packageName, processName);
            }
        }

        isEligibleForClientCompat = !isGmsCore() &&
                appInfoExt.hasFlag(AppInfoExtFlag.HAS_GMSCORE_CLIENT_LIBRARY);

        if (isEligibleForClientCompat && GmsCompatLib.get() == null) {
            GmsCompatLib.init(appCtx, Application.getProcessName());
        }

        if (GmsCompat.isClientOfGmsCore()) {
            GmsCoreFileServerClientHooks.init();
        }
    }

    public static boolean isEnabledFor(@NonNull ApplicationInfo app) {
        return isEnabledFor(app.ext().getPackageId(), app.isPrivilegedApp());
    }

    /** @hide */
    public static boolean isEnabledFor(int packageId, boolean isPrivileged) {
        if (isPrivileged) {
            // don't enable GmsCompat for privileged GMS
            return false;
        }

        switch (packageId) {
            case PackageId.GMS_CORE:
            case PackageId.PLAY_STORE:
            case PackageId.G_SEARCH_APP:
            case PackageId.ANDROID_AUTO:
            case PackageId.G_CARRIER_SETTINGS:
            case PackageId.GMSCOMPAT_TEST_PACKAGE_1:
            case PackageId.GMSCOMPAT_TEST_PACKAGE_2:
            case PackageId.GMSCOMPAT_TEST_PACKAGE_3:
                return true;
            default:
                return false;
        }
    }

    /** @hide */
    public static boolean canBeEnabledFor(String pkgName) {
        if (isDevBuild()) {
            if (maybeGetTestPackageId(pkgName) != PackageId.UNKNOWN) {
                return true;
            }
        }

        return switch (pkgName) {
            case
                PackageId.GMS_CORE_NAME,
                PackageId.PLAY_STORE_NAME,
                PackageId.G_SEARCH_APP_NAME,
                PackageId.ANDROID_AUTO_NAME,
                PackageId.G_CARRIER_SETTINGS_NAME ->
                    true;
            default ->
                    false;
        };

    }

    /** @hide */
    public static boolean isGmsAppAndUnprivilegedProcess(@NonNull String packageName) {
        if (!isEnabledFor(packageName, UserHandle.USER_CURRENT)) {
            return false;
        }

        Application a = AppGlobals.getInitialApplication();
        if (a == null) {
            return false;
        }

        ApplicationInfo ai = a.getApplicationInfo();
        if (ai == null) {
            return false;
        }

        return !ai.isPrivilegedApp();
    }

    public static boolean isEnabledFor(@NonNull String packageName, int userId) {
        return isEnabledFor(packageName, userId, false);
    }

    /** @hide */
    public static boolean isEnabledFor(@NonNull String packageName, int userId, boolean matchDisabledApp) {
        if (!canBeEnabledFor(packageName)) {
            return false;
        }

        if (userId == UserHandle.USER_CURRENT) {
            userId = UserHandle.myUserId();
        }

        Context ctx = appContext;
        if (ctx == null) {
            ctx = AppGlobals.getInitialApplication();
        }

        if (ctx == null) {
            throw new IllegalStateException("GmsCompat.isEnabledFor: Context is null");
        }

        PackageManager pm = ctx.getPackageManager();

        ApplicationInfo appInfo;
        long token = Binder.clearCallingIdentity();
        try {
            appInfo = pm.getApplicationInfoAsUser(packageName, 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return isEnabledFor(appInfo, matchDisabledApp);
    }

    /** @hide */
    public static boolean isEnabledFor(@Nullable ApplicationInfo app, boolean matchDisabledApp) {
        if (app == null) {
            return false;
        }
        if (!app.enabled && !matchDisabledApp) {
            return false;
        }
        return isEnabledFor(app);
    }

    private static volatile boolean cachedIsClientOfGmsCore;

    /** @hide */
    public static boolean isClientOfGmsCore() {
        return isClientOfGmsCore(null);
    }

    /** @hide */
    public static boolean isClientOfGmsCore(@Nullable ApplicationInfo gmsCoreAppInfo) {
        if (cachedIsClientOfGmsCore) {
            return true;
        }

        if (!isEligibleForClientCompat) {
            return false;
        }

        boolean res = (gmsCoreAppInfo != null) ?
                isEnabledFor(gmsCoreAppInfo) :
                isEnabledFor(GmsInfo.PACKAGE_GMS_CORE, appContext().getUserId());

        cachedIsClientOfGmsCore = res;
        return res;
    }

    public static boolean hasPermission(@NonNull String perm) {
        Context ctx = appContext();

        if (GmsHooks.config().shouldSpoofSelfPermissionCheck(perm)) {
            // result of checkSelfPermission() below would be spoofed, ask the PackageManager directly
            IPackageManager pm = ActivityThread.getPackageManager();
            try {
                return pm.checkPermission(perm, ctx.getPackageName(), ctx.getUserId())
                        == PackageManager.PERMISSION_GRANTED;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    public static void catchOrRethrow(@NonNull SecurityException se) {
        if (isEnabled()) {
            Log.d("GmsCompat", "caught SecurityException", se);
            return;
        }
        throw se;
    }

    private static final String GMSCOMPAT_PROCESS_SUFFIX = ":gmscompat";

    /** @hide */
    public static String gmsCompatProcessNameForPackage(String pkgName) {
        return pkgName + GMSCOMPAT_PROCESS_SUFFIX;
    }

    /** @hide */
    public static boolean isGmsCompatProcess(String processName) {
        return processName.endsWith(GMSCOMPAT_PROCESS_SUFFIX);
    }

    /** @hide */
    public static boolean isConstellationPhoneVerifyThread(Thread t) {
        var tName = t.getName();
        // Full thread name:
        // [com.google.android.gms.chimera.container.intentoperation.GmsIntentOperationChimeraService-Executor] processing com.google.android.gms.constellation.EventManager for action com.google.android.gms.constellation.eventmanager.SYNC_VERIFICATION
        return tName != null &&
                tName.endsWith("processing com.google.android.gms.constellation.EventManager for action com.google.android.gms.constellation.eventmanager.SYNC_VERIFICATION");
    }

    /** @hide */
    public static int maybeGetTestPackageId(String packageName) {
        if (!isDevBuild()) {
            return PackageId.UNKNOWN;
        }
        String testPkgsStr = SystemProperties.get("persist.gmscompat_test_pkgs");
        if (testPkgsStr.isEmpty()) {
            return PackageId.UNKNOWN;
        }

        String[] testPkgs = testPkgsStr.split(",");
        if (testPkgs.length > 3) {
            Log.e(TAG, "maybeGetTestPackageId: persist.gmscompat_test_pkgs can specify at most" +
                        " 3 packages, full value: " + testPkgsStr);
        }

        for (int i = 0, m = Math.min(3, testPkgs.length); i < m; ++i) {
            if (packageName.equals(testPkgs[i])) {
                int res = PackageId.GMSCOMPAT_TEST_PACKAGE_1 + i;
                if (res > PackageId.GMSCOMPAT_TEST_PACKAGE_3) {
                    throw new IllegalStateException();
                }
                return res;
            }
        }
        return PackageId.UNKNOWN;
    }
}
