package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.settings.ExtSettings;

import com.android.server.os.nano.AppCompatProtos;

/** @hide */
public class AswUseExecSpawning extends AppSwitch {
    public static final AswUseExecSpawning I = new AswUseExecSpawning();

    private AswUseExecSpawning() {
        gosPsFlag = GosPackageStateFlag.USE_EXEC_SPAWNING;
        gosPsFlagNonDefault = GosPackageStateFlag.USE_EXEC_SPAWNING_NON_DEFAULT;
        compatChangeToDisableHardening = AppCompatProtos.USE_ZYGOTE_SPAWNING;
    }

    @Override
    public Boolean getImmutableValue(Context ctx, int userId, ApplicationInfo appInfo,
                                     GosPackageState ps, StateInfo si) {
        if (ps.hasFlag(GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE)) {
            si.immutabilityReason = IR_EXPLOIT_PROTECTION_COMPAT_MODE;
            return Boolean.FALSE;
        }

        return null;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
                                           GosPackageState ps, StateInfo si) {
        return ExtSettings.EXEC_SPAWNING.get();
    }
}
