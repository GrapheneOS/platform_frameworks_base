package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/**
 * When on, callbacks registered via WindowManager#addScreenRecordingCallback always report that
 * the app isn't being recorded.
 *
 * @hide
 */
public class AswBlockScreenRecordingDetection extends AppSwitch {
    public static final AswBlockScreenRecordingDetection I = new AswBlockScreenRecordingDetection();

    private AswBlockScreenRecordingDetection() {
        gosPsFlag = GosPackageStateFlag.BLOCK_SCREEN_RECORDING_DETECTION;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
            GosPackageState ps, StateInfo si) {
        return false;
    }

    public boolean get(GosPackageState ps) {
        return getNonDefaultValue(ps);
    }
}
