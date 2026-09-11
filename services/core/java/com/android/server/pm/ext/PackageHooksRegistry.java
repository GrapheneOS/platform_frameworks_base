package com.android.server.pm.ext;

import android.ext.PackageId;

import com.android.internal.pm.pkg.parsing.PackageParsingHooks;

public class PackageHooksRegistry {

    public static PackageParsingHooks getParsingHooks(String pkgName) {
        PackageParsingHooks gmsCompatHooks = GmsCompatPkgParsingHooks.maybeGet(pkgName);
        if (gmsCompatHooks != null) {
            return gmsCompatHooks;
        }

        return switch (pkgName) {
            case PackageId.GSF_NAME -> new GsfParsingHooks();
            case PackageId.G_EUICC_LPA_NAME -> new EuiccGoogleParsingHooks();
            case PackageId.PIXEL_HEALTH_NAME -> new PixelHealthHooks.ParsingHooks();
            default -> PackageParsingHooks.DEFAULT;
        };
    }

    public static PackageHooks getHooks(int packageId) {
        return switch (packageId) {
            case PackageId.G_CARRIER_SETTINGS -> new GCarrierSettingsHooks();
            case PackageId.ANDROID_AUTO -> new AndroidAutoHooks();
            case PackageId.PIXEL_HEALTH -> new PixelHealthHooks();
            case PackageId.GMS_CORE -> new GmsCoreHooks();
            default -> PackageHooks.DEFAULT;
        };
    }
}
