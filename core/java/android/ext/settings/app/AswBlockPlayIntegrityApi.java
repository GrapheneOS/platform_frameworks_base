package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/** @hide */
public class AswBlockPlayIntegrityApi extends AppSwitch {
    public static final AswBlockPlayIntegrityApi I = new AswBlockPlayIntegrityApi();

    private AswBlockPlayIntegrityApi() {
        gosPsFlag = GosPackageStateFlag.BLOCK_PLAY_INTEGRITY_API;
        gosPsFlagSuppressNotif = GosPackageStateFlag.SUPPRESS_PLAY_INTEGRITY_API_NOTIF;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo, GosPackageState ps, StateInfo si) {
        return false;
    }
}
