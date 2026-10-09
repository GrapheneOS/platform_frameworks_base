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
            case PackageId.EUICC_SUPPORT_PIXEL_NAME -> new EuiccSupportPixelHooks.ParsingHooks();
            case PackageId.G_EUICC_LPA_NAME -> new EuiccGoogleHooks.ParsingHooks();
            case PackageId.PIXEL_CAMERA_SERVICES_NAME -> new PixelCameraServicesHooks.ParsingHooks();
            case PackageId.PIXEL_HEALTH_NAME -> new PixelHealthHooks.ParsingHooks();
            default -> PackageParsingHooks.DEFAULT;
        };
    }

    public static PackageHooks getHooks(int packageId) {
        GmsCompatPackageHooks gmsCompatHooks = GmsCompatPackageHooks.maybeGet(packageId);
        if (gmsCompatHooks != null) {
            return gmsCompatHooks;
        }

        return switch (packageId) {
            case PackageId.EUICC_SUPPORT_PIXEL -> new EuiccSupportPixelHooks();
            case PackageId.G_EUICC_LPA -> new EuiccGoogleHooks();
            case PackageId.PIXEL_CAMERA_SERVICES -> new PixelCameraServicesHooks();
            case PackageId.PIXEL_HEALTH -> new PixelHealthHooks();
            default -> PackageHooks.DEFAULT;
        };
    }
}
