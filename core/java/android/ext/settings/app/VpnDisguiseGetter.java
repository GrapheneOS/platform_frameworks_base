package android.ext.settings.app;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.IPackageManager;
import android.ext.settings.ExtSettings;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;

/** @hide */
@SystemApi
public final class VpnDisguiseGetter {
    private static final String TAG = "VpnDisguiseGetter";
    private VpnDisguiseGetter() {}
    static public final boolean getVpnDisguiseSettingForPackage(@NonNull Context ctxt, int userId, @NonNull String pkg) {
        IPackageManager pmi = IPackageManager.Stub.asInterface(ServiceManager.checkService("package"));
        GosPackageState ps;
        if (pmi == null) {
            Log.e(TAG, "Couldn't get package service");
            return false;
        }
        long oldId = Binder.clearCallingIdentity();
        try {
            ps = pmi.getGosPackageState(pkg, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't get GOS package state", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }

        if (!ps.hasFlag(GosPackageStateFlag.VPN_DISGUISE_NON_DEFAULT)) {
            ApplicationInfo appInfo;
            try {
                appInfo = ctxt.getPackageManager().getApplicationInfo(pkg, 0);
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Couldn't get package info", e);
                return false;
            }
            if (appInfo.isSystemApp()) {
                return false;
            } else {
                return ExtSettings.VPN_DISGUISE_BY_DEFAULT.get(ctxt, userId);
            }
        } else {
            return ps.hasFlag(GosPackageStateFlag.VPN_DISGUISE);
        }
    }
}
