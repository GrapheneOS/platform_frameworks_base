package android.ext.settings.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.settings.ExtSettings;
import android.provider.Settings;
import android.text.TextUtils;

/** @hide */
public class AswAllowClipboardRead extends AppSwitch {
    public static final AswAllowClipboardRead I = new AswAllowClipboardRead();

    private AswAllowClipboardRead() {
        gosPsFlag = GosPackageStateFlag.ALLOW_CLIPBOARD_READ;
        gosPsFlagNonDefault = GosPackageStateFlag.ALLOW_CLIPBOARD_READ_NON_DEFAULT;
        gosPsFlagSuppressNotif = GosPackageStateFlag.SUPPRESS_CLIPBOARD_ACCESS_DENIAL_NOTIF;
    }

    @Override
    public Boolean getImmutableValue(Context ctx, int userId, ApplicationInfo appInfo,
            GosPackageState ps, StateInfo si) {
        final int reason = getImmutabilityReason(appInfo.isSystemApp(),
                isDefaultIme(ctx, userId, appInfo.packageName));
        if (reason != IR_UNKNOWN) {
            si.immutabilityReason = reason;
            return true;
        }

        return null;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
            GosPackageState ps, StateInfo si) {
        si.defaultValueReason = DVR_DEFAULT_SETTING;
        return getDefaultValue(ctx, userId);
    }

    public boolean get(Context ctx, int userId, boolean isSystemApp, boolean isDefaultIme,
            GosPackageState ps) {
        if (getImmutabilityReason(isSystemApp, isDefaultIme) != IR_UNKNOWN) {
            return true;
        }
        return isUsingDefaultValue(ps)
                ? getDefaultValue(ctx, userId)
                : getNonDefaultValue(ps);
    }

    private static int getImmutabilityReason(boolean isSystemApp, boolean isDefaultIme) {
        if (isSystemApp) {
            return IR_IS_SYSTEM_APP;
        }
        return isDefaultIme ? IR_IS_DEFAULT_IME : IR_UNKNOWN;
    }

    private static boolean getDefaultValue(Context ctx, int userId) {
        return ExtSettings.ALLOW_CLIPBOARD_READ_BY_DEFAULT.get(ctx, userId);
    }

    // based on ClipboardService#isDefaultIme
    private boolean isDefaultIme(Context ctx, int userId, String packageName) {
        String defaultIme = Settings.Secure.getStringForUser(ctx.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, userId);
        if (!TextUtils.isEmpty(defaultIme)) {
            final ComponentName imeComponent = ComponentName.unflattenFromString(defaultIme);
            if (imeComponent == null) {
                return false;
            }
            final String imPkg = imeComponent.getPackageName();
            return imPkg.equals(packageName);
        }
        return false;
    }
}
