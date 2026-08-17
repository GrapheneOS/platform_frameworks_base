package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/** @hide */
public class AswStrictLeakBlocking extends AppSwitch {
    public static final AswStrictLeakBlocking I = new AswStrictLeakBlocking();

    private AswStrictLeakBlocking() {
        gosPsFlag = GosPackageStateFlag.STRICT_LEAK_BLOCKING;
        gosPsFlagNonDefault = GosPackageStateFlag.STRICT_LEAK_BLOCKING_NON_DEFAULT;
        gosPsFlagSuppressNotif = GosPackageStateFlag.STRICT_LEAK_BLOCKING_SUPPRESS_NOTIF;
    }

    @Override
    public Boolean getImmutableValue(Context ctx, int userId, ApplicationInfo appInfo,
                                     GosPackageState ps, StateInfo si) {
        if (appInfo.isSystemApp()) {
            si.immutabilityReason = IR_IS_SYSTEM_APP;
            // This is overly permissive because not all system apps have permissions that allow
            // them to bypass lockdown VPNs (netd PERMISSION_SYSTEM). With that said, it's plausible
            // that a system app without PERMISSION_SYSTEM could have a valid reason to use
            // functionality gated behind strict leak blocking. GrapheneOS ships proprietary core
            // components and system apps that could fall under that category.
            // TODO: Determine which core components and system apps actually need this to be false
            // (this will be difficult to maintain).
            return false;
        }

        return null;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
                                           GosPackageState ps, StateInfo si) {
        return true;
    }
}
