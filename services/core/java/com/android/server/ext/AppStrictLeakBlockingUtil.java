package com.android.server.ext;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.pm.GosPackageStateFlag;
import android.content.pm.PackageManagerInternal;
import android.ext.SettingsIntents;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.pm.Computer;

public class AppStrictLeakBlockingUtil {
    private static final String TAG = AppStrictLeakBlockingUtil.class.getSimpleName();

    public static void showNotification(Context ctx, int uid, int pid) {
        String firstPackageName = null;

        var ami = LocalServices.getService(ActivityManagerInternal.class);
        ActivityManagerInternal.ProcessRecordSnapshot prs = ami.getProcessRecordByPid(pid);
        if (prs != null && prs.appInfo != null) {
            firstPackageName = prs.appInfo.packageName;
        } else {
            var pmi = LocalServices.getService(PackageManagerInternal.class);
            Computer snapshot = (Computer)pmi.snapshot();
            String[] packages = snapshot.getPackagesForUid(uid);
            if (packages != null && packages.length == 1) {
                firstPackageName = packages[0];
            }
        }

        if (firstPackageName == null) {
            Slog.d(TAG, "firstPackageName is null for uid " + uid);
            return;
        }

        var n = AppSwitchNotification.maybeCreate(ctx, firstPackageName, uid,
                SettingsIntents.APP_STRICT_LEAK_BLOCKING);
        if (n == null) {
            return;
        }

        n.titleRes = R.string.notif_app_strict_leak_blocking_title;
        n.gosPsFlagSuppressNotif = GosPackageStateFlag.STRICT_LEAK_BLOCKING_SUPPRESS_NOTIF;
        n.maybeShow();
    }
}
