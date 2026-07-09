package com.android.server.pm.ext;

import android.Manifest;
import android.app.compat.gms.AndroidAutoPackageFlag;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.ext.PackageId;

import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl;
import com.android.server.LocalServices;
import com.android.server.pm.pkg.AndroidPackage;

import java.util.List;

public class AndroidAutoHooks extends PackageHooks {
    private static final String TAG = "AndroidAutoHooks";

    static class ParsingHooks extends GmsCompatPkgParsingHooks {

        @Override
        public List<ParsedUsesPermissionImpl> addUsesPermissions() {
            List<ParsedUsesPermissionImpl> res = super.addUsesPermissions();

            res.addAll(createUsesPerms(
                    Manifest.permission.ASSOCIATE_COMPANION_DEVICES_RESTRICTED,
                    Manifest.permission.BLUETOOTH_PRIVILEGED_ANDROID_AUTO,
                    Manifest.permission.MANAGE_USB_ANDROID_AUTO,
                    // MEDIA_CONTENT_CONTROL permission allows Android Auto to control media
                    // playback without holding the broad notification listener permission
                    Manifest.permission.MEDIA_CONTENT_CONTROL,
                    Manifest.permission.READ_DEVICE_SERIAL_NUMBER,
                    Manifest.permission.READ_PRIVILEGED_PHONE_STATE_ANDROID_AUTO,
                    Manifest.permission.WIFI_PRIVILEGED_ANDROID_AUTO
            ));

            return res;
        }
    }

    public static boolean isAndroidAutoWithGrantedBasePrivPerms(String packageName, int userId) {
        if (!PackageId.ANDROID_AUTO_NAME.equals(packageName)) {
            return false;
        }
        var pm = LocalServices.getService(PackageManagerInternal.class);
        return hasBaselinePrivilegedPermissions(pm, userId);
    }

    private static boolean hasBaselinePrivilegedPermissions(PackageManagerInternal pm, int userId) {
        String packageName = PackageId.ANDROID_AUTO_NAME;

        AndroidPackage pkg = pm.getPackage(packageName);
        if (pkg == null || PackageExt.get(pkg).getPackageId() != PackageId.ANDROID_AUTO) {
            return false;
        }

        GosPackageState ps = pm.getGosPackageState(packageName, userId);
        return (ps.hasPackageFlag(AndroidAutoPackageFlag.GRANT_PERMS_FOR_WIRED_ANDROID_AUTO)
                || ps.hasPackageFlag(AndroidAutoPackageFlag.GRANT_PERMS_FOR_WIRELESS_ANDROID_AUTO));
    }

    @Override
    public int overridePermissionState(String permission, int userId) {
        int flag;
        Integer flag2 = null;

        switch (permission) {
            /** @see android.companion.virtual.VirtualDeviceParams#LOCK_STATE_ALWAYS_UNLOCKED */
            case Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY:
            /** @see android.hardware.display.DisplayManager#VIRTUAL_DISPLAY_FLAG_TRUSTED */
            case Manifest.permission.ADD_TRUSTED_DISPLAY:
            case Manifest.permission.CREATE_VIRTUAL_DEVICE:
            /** @see android.app.UiModeManager#enableCarMode(int, int) */
            case Manifest.permission.ENTER_CAR_MODE_PRIORITIZED:
            case Manifest.permission.MANAGE_USB_ANDROID_AUTO:
            case Manifest.permission.MEDIA_CONTENT_CONTROL:
            // allows to enable/disable dark mode
            case Manifest.permission.MODIFY_DAY_NIGHT_MODE:
            // allows to asssociate only with DEVICE_PROFILE_AUTOMOTIVE_PROJECTION
            case Manifest.permission.REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION:
            // doesn't grant any data access, included here to improve UX
            case Manifest.permission.POST_NOTIFICATIONS:
            /** @see android.companion.AssociationInfo#isSelfManaged (check callers)*/
            case Manifest.permission.REQUEST_COMPANION_SELF_MANAGED:
            /** @see android.app.UiModeManager#requestProjection  */
            case Manifest.permission.TOGGLE_AUTOMOTIVE_PROJECTION:
                flag = AndroidAutoPackageFlag.GRANT_PERMS_FOR_WIRED_ANDROID_AUTO;
                flag2 = AndroidAutoPackageFlag.GRANT_PERMS_FOR_WIRELESS_ANDROID_AUTO;
                break;
            case Manifest.permission.MODIFY_AUDIO_ROUTING:
                flag = AndroidAutoPackageFlag.GRANT_AUDIO_ROUTING_PERM;
                break;
            // unprivileged permission
            case Manifest.permission.ACCESS_LOCAL_NETWORK:
            // Allows Android Auto to associate with any companion device that has a MAC address
            // Unrestricted version would allow Android Auto to associate any package in any user
            // with any such device. Not clear whether it's feasible to restrict this permission
            // further.
            case Manifest.permission.ASSOCIATE_COMPANION_DEVICES_RESTRICTED:
            case Manifest.permission.INTERNET:
            // allows to read MAC address of Bluetooth and WiFi adapters
            case Manifest.permission.LOCAL_MAC_ADDRESS:
            // unprivileged permission
            case Manifest.permission.NEARBY_WIFI_DEVICES:
            case Manifest.permission.READ_DEVICE_SERIAL_NUMBER:
            // grants access to a small subset of privileged WiFi APIs
            case Manifest.permission.WIFI_PRIVILEGED_ANDROID_AUTO:
                flag = AndroidAutoPackageFlag.GRANT_PERMS_FOR_WIRELESS_ANDROID_AUTO;
                break;
            // unprivileged permission
            case Manifest.permission.BLUETOOTH_CONNECT:
            // grants access to a small subset of BLUETOOTH_PRIVILEGED privileges
            case Manifest.permission.BLUETOOTH_PRIVILEGED_ANDROID_AUTO:
            // unprivileged permission
            case Manifest.permission.BLUETOOTH_SCAN:
                flag = AndroidAutoPackageFlag.GRANT_PERMS_FOR_WIRELESS_ANDROID_AUTO;
                flag2 = AndroidAutoPackageFlag.GRANT_PERMS_FOR_ANDROID_AUTO_PHONE_CALLS;
                break;
            // unprivileged permission
            case Manifest.permission.CALL_PHONE:
            case Manifest.permission.CALL_PRIVILEGED:
            case Manifest.permission.CONTROL_INCALL_EXPERIENCE:
            case Manifest.permission.MODIFY_PHONE_STATE:
            // unprivileged permission
            case Manifest.permission.READ_PHONE_STATE:
            case Manifest.permission.READ_PRIVILEGED_PHONE_STATE_ANDROID_AUTO:
                flag = AndroidAutoPackageFlag.GRANT_PERMS_FOR_ANDROID_AUTO_PHONE_CALLS;
                break;
            default:
                return NO_PERMISSION_OVERRIDE;
        }

        GosPackageState gosPs = LocalServices.getService(PackageManagerInternal.class)
                .getGosPackageState(PackageId.ANDROID_AUTO_NAME, userId);

        if (gosPs.hasPackageFlag(flag)) {
            return PERMISSION_OVERRIDE_GRANT;
        }
        if (flag2 != null && gosPs.hasPackageFlag(flag2.intValue())) {
            return PERMISSION_OVERRIDE_GRANT;
        }

        return PERMISSION_OVERRIDE_REVOKE;
    }

    @Override
    public boolean shouldAllowFgsWhileInUsePermission(PackageManagerInternal pm, int userId) {
        return hasBaselinePrivilegedPermissions(pm, userId);
    }
}
