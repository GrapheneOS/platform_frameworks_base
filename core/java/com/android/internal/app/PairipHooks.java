package com.android.internal.app;

import android.app.AppGlobals;
import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.ext.PackageId;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import static java.util.Objects.requireNonNull;

/**
 * Hooks for bypassing the Play Store automatic protection system (pairip) for apps that have their
 * integrity verified by the Play Store APK source stamp.
 * For more info, see https://support.google.com/googleplay/android-developer/answer/10183279
 *
 * @hide
 */
public class PairipHooks {
    private static final String TAG = "PairipHooks";

    public static boolean shouldSkipOnCreate(ContentProvider p) {
        // startsWith() is used since the class name might be suffixed with a number in some cases
        if (!p.getClass().getName().startsWith("com.pairip.licensecheck.LicenseContentProvider")) {
            return false;
        }
        if (shouldBypass(p.requireContext())) {
            Log.d(TAG, "skipping pairip LicenseContentProvider checks");
            return true;
        }
        return false;
    }

    public static String maybeReplaceApplicationClassName(Context context, ClassLoader classLoader,
                                                          String className) throws ClassNotFoundException {
        if (!"com.pairip.application.Application".equals(className)) {
            return className;
        }
        if (!shouldBypass(context)) {
            return className;
        }
        Class cls = classLoader.loadClass(className);
        String res = requireNonNull(cls.getSuperclass()).getName();
        Log.d(TAG, "replaced pairip Application class with its parent " + res);
        return res;
    }

    private static Boolean shouldBypassCached;

    private static boolean shouldBypass(Context context) {
        Boolean cache = shouldBypassCached;
        if (cache != null) {
            return cache.booleanValue();
        }
        PackageManager pm = context.getPackageManager();
        String selfPkgName = context.getPackageName();
        ApplicationInfo selfAppInfo;
        try {
            selfAppInfo = pm.getApplicationInfo(selfPkgName,
                    ApplicationInfoFlags.of(PackageManager.GET_PLAY_STORE_SOURCE_STAMP_STATE));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
        boolean res = selfAppInfo.hasPlayStoreSourceStamp();
        if (res) {
            boolean installedFromPlayStore = false;
            String installerPkg;
            try {
                // ApplicationPackageManager result might be spoofed, see InstallSourceSpoofingHooks
                installerPkg = AppGlobals.getPackageManager().getInstallerPackageName(selfPkgName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (PackageId.PLAY_STORE_NAME.equals(installerPkg)) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(PackageId.PLAY_STORE_NAME, 0);
                    installedFromPlayStore = ai.ext().getPackageId() == PackageId.PLAY_STORE;
                } catch (PackageManager.NameNotFoundException e) {}
            }
            if (installedFromPlayStore) {
                Log.d(TAG, "app is installed from Play Store, skipping bypass");
                res = false;
            }
        }

        if (res && android.os.Flags.isDevBuild()) {
            if (Settings.Global.getInt(context.getContentResolver(), "skip_pairip_bypass", 0) == 1) {
                Log.d(TAG, "skip_pairip_bypass is set, keeping pairip check", new Throwable());
                shouldBypassCached = Boolean.FALSE;
                return false;
            }
            Log.d(TAG, "bypassing pairip check", new Throwable());
        }

        shouldBypassCached = Boolean.valueOf(res);
        return res;
    }
}
