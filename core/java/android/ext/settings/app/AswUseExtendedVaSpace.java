package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.os.Build;

import com.android.server.os.nano.AppCompatProtos;

import java.util.Objects;

import dalvik.system.VMRuntime;

/** @hide */
public class AswUseExtendedVaSpace extends AppSwitch {
    public static final AswUseExtendedVaSpace I = new AswUseExtendedVaSpace();

    private AswUseExtendedVaSpace() {
        gosPsFlag = GosPackageStateFlag.USE_EXTENDED_VA_SPACE;
        gosPsFlagNonDefault = GosPackageStateFlag.USE_EXTENDED_VA_SPACE_NON_DEFAULT;
        compatChangeToDisableHardening = AppCompatProtos.DISABLE_EXTENDED_VA_SPACE;
    }

    @Override
    public Boolean getImmutableValue(Context ctx, int userId, ApplicationInfo appInfo,
                                     GosPackageState ps, StateInfo si) {
        if (AswUseHardenedMalloc.I.get(ctx, userId, appInfo, ps)) {
            si.immutabilityReason = IR_REQUIRED_BY_HARDENED_MALLOC;
            return Boolean.TRUE;
        }

        String primaryAbi = appInfo.primaryCpuAbi;
        if (primaryAbi != null) {
            String isa = Objects.requireNonNull(VMRuntime.getInstructionSet(primaryAbi));
            if (!VMRuntime.is64BitInstructionSet(isa)) {
                si.immutabilityReason = IR_NON_64_BIT_NATIVE_CODE;
                return Boolean.FALSE;
            }
            if (!isAvailable(isa)) {
                return Boolean.TRUE;
            }
        }

        if (!isAvailable()) {
            return Boolean.TRUE;
        }

        if (!AswUseExecSpawning.I.get(ctx, userId, appInfo, ps)) {
            // When zygote spawning is used, extended VA space can't be used without also using
            // hardened_malloc. This is a consequence of having 2 zygotes:
            // - primary zygote with hardened_malloc and extended VA space
            // - compat zygote with scudo and, on arm64, 39-bit VA space
            si.immutabilityReason = IR_REQUIRED_BY_ZYGOTE_SPAWNING;
            return Boolean.FALSE;
        }

        if (ps.hasFlag(GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE)) {
            si.immutabilityReason = IR_EXPLOIT_PROTECTION_COMPAT_MODE;
            return Boolean.FALSE;
        }

        return null;
    }

    public static boolean isAvailable() {
        return isAvailable(VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]));
    }

    public static boolean isAvailable(String isa) {
        // disabling extended VA space is supported only on arm64
        return isa.equals("arm64");
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
                                           GosPackageState ps, StateInfo si) {
        return true;
    }
}
