package com.android.server.wm;

import android.annotation.Nullable;
import android.content.pm.GosPackageState;
import android.ext.settings.app.AswAllowScreenRecording;
import android.ext.settings.app.AswAllowScreenshots;
import android.ext.settings.app.AswBlockScreenRecordingDetection;
import android.ext.settings.app.AswBlockScreenshotDetection;
import android.os.UserHandle;
import android.view.SurfaceControl;

import com.android.server.pm.pkg.PackageStateInternal;

import java.util.ArrayList;

/**
 * Per-app screen capture switches: {@link AswAllowScreenshots}, {@link AswAllowScreenRecording}
 * (applied via {@link SensitiveContentPackages}), {@link AswBlockScreenshotDetection} and
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

    // null means that the app decides via FLAG_SECURE
    @Nullable
    private static Boolean getScreenshotOverride(WindowState w) {
        return AswAllowScreenshots.I.getNonDefaultValueOrNull(
                getGosPackageState(w.mWmService, w.getOwningPackage(), w.getOwningUid()));
    }

    static boolean areScreenshotsBlocked(WindowState w) {
        return Boolean.FALSE.equals(getScreenshotOverride(w));
    }

    static boolean areScreenshotsBlocked(ActivityRecord ar) {
        return Boolean.FALSE.equals(AswAllowScreenshots.I.getNonDefaultValueOrNull(
                getGosPackageState(ar.mWmService, ar.packageName, ar.getUid())));
    }

    /**
     * Applies per-app screenshot overrides to a user-initiated screenshot of {@code dc}.
     *
     * @param outExcludeLayers receives copies of the surfaces of windows that must be excluded,
     *                         the caller is responsible for releasing them
     * @return whether secure layers should be captured
     */
    static boolean applyScreenshotOverrides(DisplayContent dc,
            ArrayList<SurfaceControl> outExcludeLayers) {
        // windows that must be excluded if secure layers are captured
        final ArrayList<WindowState> maybeSecureWindows = new ArrayList<>();
        final boolean[] captureSecureLayers = new boolean[1];
        dc.forAllWindows(w -> {
            final Boolean override = getScreenshotOverride(w);
            if (Boolean.FALSE.equals(override)) {
                addExcludeLayer(w, outExcludeLayers);
                return;
            }
            final boolean allowed = Boolean.TRUE.equals(override);
            if (!w.isSecureLocked()) {
                // untrusted windows might contain secure layers that WM doesn't know about,
                // e.g. a SurfaceView marked as secure
                if (!allowed && !w.mSession.mCanAddInternalSystemWindow) {
                    maybeSecureWindows.add(w);
                }
                return;
            }
            if (allowed && !w.isSecureIgnoringFlagSecureLocked()) {
                // only the app's own FLAG_SECURE can be overridden, not e.g. device policy
                captureSecureLayers[0] = true;
            } else if (!w.isSurfaceSecureLocked()) {
                // the surface isn't secure due to AswAllowScreenRecording, the window is still
                // secure as far as screenshots are concerned
                addExcludeLayer(w, outExcludeLayers);
            } else {
                maybeSecureWindows.add(w);
            }
        }, true /* traverseTopToBottom */);

        // transitions can add snapshot layers that contain secure content of other apps and
        // aren't windows, fall back to redacting secure content
        if (captureSecureLayers[0] && dc.mTransitionController.inTransition()) {
            return false;
        }
        if (captureSecureLayers[0]) {
            for (int i = 0, n = maybeSecureWindows.size(); i < n; ++i) {
                addExcludeLayer(maybeSecureWindows.get(i), outExcludeLayers);
            }
        }
        return captureSecureLayers[0];
    }

    private static void addExcludeLayer(WindowState w, ArrayList<SurfaceControl> out) {
        final SurfaceControl sc = w.getSurfaceControl();
        if (sc != null && sc.isValid()) {
            // a copy is needed, capture args are released after the capture
            out.add(new SurfaceControl(sc, "ScreenCaptureAppPolicy"));
        }
    }
}
