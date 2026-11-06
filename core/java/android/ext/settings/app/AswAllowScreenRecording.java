package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/**
 * Per-app override of whether screen recording and screen sharing (MediaProjection) may capture
 * the app's windows.
 * <p>
 * Default: the app decides, i.e. windows with FLAG_SECURE are redacted.<br>
 * On: windows with FLAG_SECURE are captured while a MediaProjection session is active. Recents
 * thumbnails and long screenshots stay blocked.<br>
 * Off: the app's windows are redacted while a MediaProjection session is active.
 * <p>
 * Use {@link #getNonDefaultValueOrNull(GosPackageState)} to check whether there's an override.
 *
 * @hide
 */
public class AswAllowScreenRecording extends AppSwitch {
    public static final AswAllowScreenRecording I = new AswAllowScreenRecording();

    private AswAllowScreenRecording() {
        gosPsFlagNonDefault = GosPackageStateFlag.ALLOW_SCREEN_RECORDING_NON_DEFAULT;
        gosPsFlag = GosPackageStateFlag.ALLOW_SCREEN_RECORDING;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
            GosPackageState ps, StateInfo si) {
        // screen recording is allowed unless the app opts out via FLAG_SECURE
        return true;
    }
}
