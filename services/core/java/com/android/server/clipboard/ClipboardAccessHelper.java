package com.android.server.clipboard;

import android.content.ClipData;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.ext.SettingsIntents;
import android.ext.settings.app.AswDenyClipboardRead;
import android.os.Process;

import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.ext.AppSwitchNotification;
import com.android.server.pm.pkg.GosPackageStatePm;

public class ClipboardAccessHelper {
    static final ClipData dummyClip = ClipData.newPlainText(null, "");

    static boolean isReadBlockedForPackage(Context ctx, String pkgName, int userId) {
        var pmi = LocalServices.getService(PackageManagerInternal.class);
        ApplicationInfo appInfo = pmi.getApplicationInfo(pkgName, 0, Process.SYSTEM_UID, userId);
        if (appInfo == null) {
            return true;
        }
        GosPackageStatePm ps = pmi.getGosPackageState(pkgName, userId);
        return AswDenyClipboardRead.I.get(ctx, userId, appInfo, ps);
    }

    static void maybeNotifyAccessDenied(Context ctx, String pkgName, int userId) {
        var pmi = LocalServices.getService(PackageManagerInternal.class);
        ApplicationInfo appInfo = pmi.getApplicationInfo(pkgName, 0, Process.SYSTEM_UID, userId);
        if (appInfo == null) {
            return;
        }
        var n = AppSwitchNotification.create(ctx, appInfo, SettingsIntents.APP_CLIPBOARD);
        n.titleRes = R.string.notif_clipboard_read_deny_title;
        n.gosPsFlagSuppressNotif = GosPackageState.FLAG_DENY_CLIPBOARD_READ_SUPPRESS_NOTIF;
        n.maybeShow();
    }
}
