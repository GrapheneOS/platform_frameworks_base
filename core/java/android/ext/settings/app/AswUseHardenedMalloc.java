package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

import com.android.server.os.nano.AppCompatProtos;

import dalvik.system.VMRuntime;

/** @hide */
public class AswUseHardenedMalloc extends AppSwitch {
    public static final AswUseHardenedMalloc I = new AswUseHardenedMalloc();

    private AswUseHardenedMalloc() {
        gosPsFlag = GosPackageStateFlag.USE_HARDENED_MALLOC;
        gosPsFlagNonDefault = GosPackageStateFlag.USE_HARDENED_MALLOC_NON_DEFAULT;
        compatChangeToDisableHardening = AppCompatProtos.DISABLE_HARDENED_MALLOC;
    }

    @Override
    public Boolean getImmutableValue(Context ctx, int userId, ApplicationInfo appInfo,
                                     GosPackageState ps, StateInfo si) {
        String primaryAbi = appInfo.primaryCpuAbi;
        if (primaryAbi == null) {
            si.immutabilityReason = IR_NO_NATIVE_CODE;
            return true;
        }

        if (!VMRuntime.is64BitAbi(primaryAbi)) {
            // hardened_malloc is 64-bit only
            si.immutabilityReason = IR_NON_64_BIT_NATIVE_CODE;
            return false;
        }

        if (appInfo.isSystemApp()) {
            si.immutabilityReason = IR_IS_SYSTEM_APP;
            return true;
        }

        if (ps.hasFlag(GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE)) {
            si.immutabilityReason = IR_EXPLOIT_PROTECTION_COMPAT_MODE;
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
