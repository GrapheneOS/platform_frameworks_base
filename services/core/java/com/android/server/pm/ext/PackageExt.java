package com.android.server.pm.ext;

import android.ext.AppInfoExt;
import android.ext.PackageId;

import com.android.internal.pm.parsing.pkg.PackageExtIface;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.server.ext.AppCompatConf;
import com.android.server.os.nano.AppCompatProtos;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

public class PackageExt implements PackageExtIface {
    public static final PackageExt DEFAULT = new PackageExt(PackageId.UNKNOWN, 0);

    private final int packageId;
    private final int flags;

    private final PackageHooks hooks;

    public static PackageExt get(AndroidPackage pkg) {
        PackageExtIface i = pkg.ext();
        if (i instanceof PackageExt) {
            return (PackageExt) i;
        }
        return DEFAULT;
    }

    public static PackageExt get(PackageState pkgState) {
        AndroidPackage pkg = pkgState.getAndroidPackage();
        if (pkg == null) {
            return DEFAULT;
        }
        return get(pkg);
    }

    public PackageExt(int packageId, int flags) {
        this.packageId = packageId;
        this.flags = flags;
        this.hooks = PackageHooksRegistry.getHooks(packageId);
    }

    public boolean hasFlag(@android.ext.AppInfoExtFlag.Enum int flag) {
        return (this.flags & (1 << flag)) != 0;
    }

    public int getPackageId() {
        return packageId;
    }

    public PackageHooks hooks() {
        return hooks;
    }

    public AppInfoExt toAppInfoExt(PackageImpl pkg) {
        AppCompatProtos.CompatConfig compatConfig = AppCompatConf.get(pkg);

        if (this == DEFAULT && compatConfig == null) {
            return AppInfoExt.DEFAULT;
        }

        long compatChanges = compatConfig != null ?
                compatConfig.compatChanges | AppInfoExt.HAS_COMPAT_CHANGES : 0L;

        return new AppInfoExt(packageId, flags, compatChanges);
    }
}
