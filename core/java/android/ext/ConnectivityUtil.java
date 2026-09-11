package android.ext;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManager;
import android.ext.settings.app.AswStrictLeakBlocking;
import android.os.UserHandle;
import android.provider.Settings;

/** @hide */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class ConnectivityUtil {
    private ConnectivityUtil() {}
    /**
     * Return true if this uid is a core system component, or if the uid is known to PackageManager
     * and at least one of the packages that use it is a system app.
     */
    public static boolean isSystem(@NonNull Context context, int uid) {
        if (UserHandle.isCore(uid)) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        String[] packageNames = pm.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }
        int userId = UserHandle.getUserId(uid);
        for (String packageName : packageNames) {
            try {
                ApplicationInfo appInfo = pm.getApplicationInfoAsUser(packageName, 0, userId);
                if (appInfo.isSystemApp()) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return false;
    }

    public static boolean isRegularAppWithLockdownVpnEnabled(@NonNull Context context, int uid) {
        final int lockdownVpnEnabled = Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN, 0, UserHandle.getUserId(uid));
        return lockdownVpnEnabled == 1 && !isSystem(context, uid);
    }

    public static boolean isAppStrictLeakBlockingEnabled(@NonNull Context context, int uid,
            @NonNull ApplicationInfo ai, @NonNull GosPackageState ps) {
        return AswStrictLeakBlocking.I.get(context, uid, ai, ps);
    }
}
