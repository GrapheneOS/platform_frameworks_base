package com.android.server.pm.ext;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.pm.parsing.PackageInfoCommonUtils;
import com.android.internal.pm.parsing.pkg.AndroidPackageInternal;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.server.ext.SystemErrorNotification;

public class PackageIdOwnershipChecks {
    private static final String TAG = "PackageIdOwnershipChecks";

    private static volatile boolean initialPackageScanCompleted;

    public static void setInitialPackageScanCompleted() {
        initialPackageScanCompleted = true;
    }

    @Nullable
    public static ParseResult<ParsingPackage> maybeOverridePackageParserResult(ParseInput input, ParsingPackage pkg) {
        String[] violations = pkg.getIdOwnershipViolations();
        if (violations.length == 0) {
            return null;
        }
        String pkgName = pkg.getPackageName();
        for (String text : violations) {
            Slog.w(TAG, "ID ownership violation for " + pkgName + ": " + text);
        }

        if (!initialPackageScanCompleted) {
            // Keep packages that were installed before introduction of PackageIdOwnershipChecks.
            //
            // TODO: notify the user about such packages
            Slog.w(TAG, "initial package scan is in progress, keeping " + pkgName);
            return null;
        }

        if (android.os.Flags.isDevBuild()) {
            if (SystemProperties.getBoolean("persist.disable_package_id_ownership_checks", false)) {
                return null;
            }
        }

        String msg;
        if (violations.length == 1) {
            msg = "Package " + pkgName + " violates an ID ownership requirement: " + violations[0];
        } else {
            msg = "Package " + pkgName
                    + " violates the following ID ownership requirements:\n• "
                    + String.join("\n• ", violations);
        }
        if (android.os.Flags.isDevBuild()) {
            msg += "\n\nTo disable this check, run 'setprop persist.disable_package_id_ownership_checks 1'";
        }

        final int maxSize = 20_000;
        final String finalMessage = msg.length() > maxSize ? msg.substring(0, maxSize) : msg;

        String appLabel = null;
        try {
            Context ctx = ActivityThread.currentActivityThread().getSystemContext();
            // the package will be discarded after this methods returns, modifying it is safe
            AndroidPackageInternal pkgFinal = pkg.hideAsParsed().hideAsFinal();
            PackageInfo pkgInfo = PackageInfoCommonUtils.generate(pkgFinal, 0L, UserHandle.USER_SYSTEM);
            appLabel = pkgInfo.applicationInfo.loadSafeLabel(ctx.getPackageManager(), PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                    TextUtils.SAFE_STRING_FLAG_TRIM | TextUtils.SAFE_STRING_FLAG_SINGLE_LINE).toString();
        } catch (Exception|OutOfMemoryError|StackOverflowError e) {
            Slog.e(TAG, "", e);
            // don't crash system_server, loading an app label isn't that important
        }
        if (appLabel == null) {
            appLabel = pkgName;
        }
        final String finalAppLabel = appLabel;

        BackgroundThread.getHandler().post(() -> {
            var notif = new SystemErrorNotification("attempt to claim a reserved name", ctx -> {
                String title = ctx.getString(com.android.internal.R.string.pkg_id_violation_blocked,
                        finalAppLabel);
                return new SystemErrorNotification.Text(title, finalMessage);
            });
            notif.showReportButton = false;
            notif.show();
        });

        return input.error(PackageManager.INSTALL_FAILED_INVALID_APK, finalMessage);
    }
}
