package android.ext.settings.app;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateBase;
import android.ext.settings.ExtSettings;

/** @hide */
public class AswDenyClipboardRead extends AppSwitch {
    public static final AswDenyClipboardRead I = new AswDenyClipboardRead();

    private AswDenyClipboardRead() {
        gosPsFlag = GosPackageState.FLAG_DENY_CLIPBOARD_READ;
        gosPsFlagNonDefault = GosPackageState.FLAG_DENY_CLIPBOARD_READ_NON_DEFAULT;
        gosPsFlagSuppressNotif = GosPackageState.FLAG_DENY_CLIPBOARD_READ_SUPPRESS_NOTIF;
    }

    @Override
    public Boolean getImmutableValue(Context ctx, int userId, ApplicationInfo appInfo,
                                     @Nullable GosPackageStateBase ps, StateInfo si) {
        if (appInfo.isSystemApp()) {
            si.immutabilityReason = IR_IS_SYSTEM_APP;
            return false;
        }

        return null;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
                                           @Nullable GosPackageStateBase ps, StateInfo si) {
        si.defaultValueReason = DVR_DEFAULT_SETTING;
        return ExtSettings.DENY_CLIPBOARD_READ_BY_DEFAULT.get(ctx, userId);
    }
}
