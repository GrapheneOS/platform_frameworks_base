package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/**
 * When on, the app isn't notified about screenshots via Activity.ScreenCaptureCallback and isn't
 * listed in the "app detected this screenshot" notice. The callback API keeps working, it just
 * never fires.
 *
 * @hide
 */
public class AswBlockScreenshotDetection extends AppSwitch {
    public static final AswBlockScreenshotDetection I = new AswBlockScreenshotDetection();

    private AswBlockScreenshotDetection() {
        gosPsFlag = GosPackageStateFlag.BLOCK_SCREENSHOT_DETECTION;
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
