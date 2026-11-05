package android.ext.settings.app;

import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.settings.ExtSettings;
import android.util.ArraySet;

import com.android.internal.R;

/** @hide */
public class VpnDisguise extends AppSwitch {
    public static final VpnDisguise I = new VpnDisguise();

    private VpnDisguise() {
        gosPsFlagNonDefault = GosPackageStateFlag.VPN_DISGUISE_NON_DEFAULT;
        gosPsFlag = GosPackageStateFlag.VPN_DISGUISE;
    }

    @Override
    public Boolean getImmutableValue(Context ctx, int userId, ApplicationInfo appInfo,
                                     GosPackageState ps, StateInfo si) {
        return null;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
                                           GosPackageState ps, StateInfo si) {
        if (appInfo.isSystemApp()) {
            return false;
        } else {
            return ExtSettings.VPN_DISGUISE_BY_DEFAULT.get(ctx, userId);
        }
    }
}
