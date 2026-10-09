package com.android.server.pm.ext;

import android.annotation.Nullable;
import android.app.compat.gms.GmsCompat;
import android.ext.PackageId;

import com.android.internal.gmscompat.GmsCompatApp;

public class GmsCompatPackageHooks extends PackageHooks {

    @Nullable
    public static GmsCompatPackageHooks maybeGet(int packageId) {
        if (!GmsCompat.isEnabledFor(packageId, false)) {
            return null;
        }
        return switch (packageId) {
            case PackageId.GMS_CORE -> new GmsCoreHooks();
            case PackageId.G_CARRIER_SETTINGS -> new GCarrierSettingsHooks();
            case PackageId.ANDROID_AUTO -> new AndroidAutoHooks();
            default -> new GmsCompatPackageHooks();
        };
    }

    @Override
    public int overridePermissionState(String permission, int userId) {
        if (permission.equals(GmsCompatApp.CLIENT_PERMISSION)) {
            return PERMISSION_OVERRIDE_GRANT;
        }
        return NO_PERMISSION_OVERRIDE;
    }
}
