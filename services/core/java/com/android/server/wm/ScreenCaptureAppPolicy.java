package com.android.server.wm;

import android.annotation.Nullable;
import android.content.pm.GosPackageState;
import android.ext.settings.app.AswBlockScreenRecordingDetection;
import android.ext.settings.app.AswBlockScreenshotDetection;
import android.os.UserHandle;

import com.android.server.pm.pkg.PackageStateInternal;

/**
 * Per-app screen capture switches: {@link AswBlockScreenshotDetection} and
 * {@link AswBlockScreenRecordingDetection}.
 */
final class ScreenCaptureAppPolicy {
    private ScreenCaptureAppPolicy() {}

    private static GosPackageState getGosPackageState(WindowManagerService wms,
            @Nullable String packageName, int uid) {
        if (packageName == null) {
            return GosPackageState.DEFAULT;
        }
        PackageStateInternal ps = wms.mPmInternal.getPackageStateInternal(packageName);
        if (ps == null || ps.getAppId() != UserHandle.getAppId(uid)) {
            return GosPackageState.DEFAULT;
        }
        return ps.getUserStateOrDefault(UserHandle.getUserId(uid)).getGosPackageState();
    }

    static boolean isScreenshotDetectionBlocked(ActivityRecord ar) {
        return AswBlockScreenshotDetection.I.get(
                getGosPackageState(ar.mWmService, ar.packageName, ar.getUid()));
    }

    static boolean isScreenRecordingDetectionBlocked(ActivityRecord ar) {
        return AswBlockScreenRecordingDetection.I.get(
                getGosPackageState(ar.mWmService, ar.packageName, ar.getUid()));
    }
}
