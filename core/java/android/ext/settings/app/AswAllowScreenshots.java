package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/**
 * Per-app override of whether user-initiated screenshots may capture the app's windows.
 * <p>
 * Default: the app decides, i.e. windows with FLAG_SECURE are redacted.<br>
 * On: windows with FLAG_SECURE are captured. Other FLAG_SECURE properties (hidden recents
 * thumbnail, no mirroring to non-secure displays, screen recording) are unaffected.<br>
 * Off: the app's windows are excluded from screenshots and its recents thumbnail is hidden.
 * <p>
 * Use {@link #getNonDefaultValueOrNull(GosPackageState)} to check whether there's an override.
 *
 * @hide
 */
public class AswAllowScreenshots extends AppSwitch {
    public static final AswAllowScreenshots I = new AswAllowScreenshots();

    private AswAllowScreenshots() {
        gosPsFlagNonDefault = GosPackageStateFlag.ALLOW_SCREENSHOTS_NON_DEFAULT;
        gosPsFlag = GosPackageStateFlag.ALLOW_SCREENSHOTS;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
            GosPackageState ps, StateInfo si) {
        // screenshots are allowed unless the app opts out via FLAG_SECURE
        return true;
    }
}
