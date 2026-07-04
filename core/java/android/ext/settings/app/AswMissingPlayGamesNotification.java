package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/** @hide */
public class AswMissingPlayGamesNotification extends AppSwitch {
    public static final AswMissingPlayGamesNotification I = new AswMissingPlayGamesNotification();

    private AswMissingPlayGamesNotification() {
        gosPsFlagSuppressNotif = GosPackageStateFlag.SUPPRESS_MISSING_PLAY_GAMES_NOTIF;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo, GosPackageState ps, StateInfo si) {
        return false;
    }
}
