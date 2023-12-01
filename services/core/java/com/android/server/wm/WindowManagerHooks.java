package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.ext.BrowserUtils;
import android.os.Binder;
import android.os.Build;
import android.os.SystemProperties;
import android.os.UserHandle;

import com.android.internal.util.ArrayUtils;

import com.android.server.LocalServices;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;

class WindowManagerHooks {

    static boolean canAccessLaunchedFromPackagePermission() {
        final int callingUid = Binder.getCallingUid();
        var pmi = LocalServices.getService(PackageManagerInternal.class);
        AndroidPackage callingPkg = pmi.getPackage(callingUid);
        if (callingPkg == null) {
            return false;
        }

        String callingPkgName = callingPkg.getPackageName();
        if (canAccessForDebuggingPurposes(callingPkgName)) {
            return true;
        }

        Context ctx = ActivityThread.currentActivityThread().getSystemContext();
        if (!BrowserUtils.isSystemBrowser(ctx, callingPkgName)) {
            return false;
        }

        PackageStateInternal callingPsi = pmi.getPackageStateInternal(callingPkg.getPackageName());
        if (callingPsi == null) {
            return false;
        }

        return callingPsi.isSystem();
    }

    static boolean canAccessForDebuggingPurposes(@NonNull String packageName) {
        if (!Build.isDebuggable()) {
            return false;
        }

        String testPkgs = SystemProperties.get("persist.launchedFromPackagePermission_test_pkgs");
        return ArrayUtils.contains(testPkgs.split(","), packageName);
    }

    static int checkLaunchedFromPackagePermission(@Nullable ActivityRecord r, @NonNull String permission) {
        if (r == null) {
            return PackageManager.PERMISSION_DENIED;
        }

        String packageName = r.launchedFromPackage;
        final int userId = UserHandle.getUserId(r.launchedFromUid);
        var permService = LocalServices.getService(PermissionManagerServiceInternal.class);

        // Do not take into account of calling app's package visibility towards launchedFromPackage.
        long token = Binder.clearCallingIdentity();
        try {
            return permService.checkPermission(packageName, permission, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

    }
}
