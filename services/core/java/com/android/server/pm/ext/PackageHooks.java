package com.android.server.pm.ext;

import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.ext.PackageId;
import android.util.ArraySet;

import com.android.internal.pm.parsing.nano.ApcPackageConfig;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import static com.android.internal.pm.parsing.PackageParserConfig.hasApcFlag;

public class PackageHooks {
    static final PackageHooks DEFAULT = new PackageHooks();

    public static boolean isDefault(PackageHooks hooks) {
        return hooks == DEFAULT;
    }

    protected static final int NO_PERMISSION_OVERRIDE = -8;
    public static final int PERMISSION_OVERRIDE_GRANT = PackageManager.PERMISSION_GRANTED;
    public static final int PERMISSION_OVERRIDE_REVOKE = PackageManager.PERMISSION_DENIED;

    public int overridePermissionState(String permission, int userId) {
        return NO_PERMISSION_OVERRIDE;
    }

    /**
     * @param isSelfToOther direction of visibility: from self to other package or from other
     * package to self
     */
    public boolean shouldBlockPackageVisibility(int userId, PackageStateInternal otherPkg, boolean isSelfToOther) {
        return shouldBlockPackageVisibility(userId, otherPkg);
    }

    public boolean shouldBlockPackageVisibility(int userId, PackageStateInternal otherPkg) {
        return false;
    }

    public static boolean shouldBlockAppsFilterVisibility(
            @Nullable PackageStateInternal callingPkgSetting,
            ArraySet<PackageStateInternal> callingSharedPkgSettings,
            int callingUserId,
            PackageStateInternal targetPkgSetting, int targetUserId) {
        if (callingPkgSetting != null) {
            return shouldBlockPackageVisibilityTwoWay(
                    callingPkgSetting, callingUserId,
                    targetPkgSetting, targetUserId);
        }

        for (int i = callingSharedPkgSettings.size() - 1; i >= 0; --i) {
            boolean res = shouldBlockPackageVisibilityTwoWay(
                    callingSharedPkgSettings.valueAt(i), callingUserId,
                    targetPkgSetting, targetUserId);
            if (res) {
                return true;
            }
        }

        return false;
    }

    private static boolean shouldBlockPackageVisibilityTwoWay(
            PackageStateInternal pkgSetting, int pkgUserId,
            PackageStateInternal otherPkgSetting, int otherPkgUserId) {
        boolean res = shouldBlockPackageVisibilityInner(pkgSetting, pkgUserId, otherPkgSetting, true);
        if (!res) {
            res = shouldBlockPackageVisibilityInner(otherPkgSetting, otherPkgUserId, pkgSetting, false);
        }
        return res;
    }

    private static boolean shouldBlockPackageVisibilityInner(
            PackageStateInternal pkgSetting, int pkgUserId, PackageStateInternal otherPkgSetting,
            boolean isSelfToOther) {
        AndroidPackage pkg = pkgSetting.getPkg();
        if (pkg != null) {
            if (PackageExt.get(pkg).hooks()
                    .shouldBlockPackageVisibility(pkgUserId, otherPkgSetting, isSelfToOther)) {
                return true;
            }

            ApcPackageConfig config = pkg.getApcPackageConfig();
            if (config != null) {
                if (hasApcFlag(config, ApcPackageConfig.FLAG_ISOLATE_FROM_USER_APPS)) {
                    if (isUserInstalledPkg(otherPkgSetting)) {
                        return true;
                    }
                }
                if (hasApcFlag(config, ApcPackageConfig.FLAG_ISOLATE_FROM_GMSCORE_AND_FINSKY)) {
                    String otherPkgName = otherPkgSetting.getPackageName();
                    // Finsky is the internal name of the Play Store
                    switch (otherPkgName) {
                        case PackageId.GSF_NAME:
                        case PackageId.GMS_CORE_NAME:
                        case PackageId.PLAY_STORE_NAME:
                            return true;
                    }
                }
            }
        }

        return false;
    }

    protected static boolean isUserInstalledPkg(PackageState ps) {
        return !ps.isSystem();
    }

    public boolean shouldAllowFgsWhileInUsePermission(PackageManagerInternal pm, int userId) {
        return false;
    }
}
