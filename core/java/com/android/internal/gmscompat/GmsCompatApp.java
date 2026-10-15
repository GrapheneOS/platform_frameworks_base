package com.android.internal.gmscompat;

import android.Manifest;
import android.accounts.AccountManager;
import android.annotation.Nullable;
import android.app.compat.gms.GmsCompat;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.android.internal.gmscompat.util.SynchronousServiceBinding;

import static com.android.internal.gmscompat.GmsHooks.inMainGmsCoreProcess;

public final class GmsCompatApp {
    private static final String TAG = "GmsCompat/GCA";
    public static final String PKG_NAME = "app.grapheneos.gmscompat";
    // permission that is held only by GmsCompatApp
    public static final String SELF_PERMISSION = "app.grapheneos.gmscompat.permission.SELF";
    // permission that is held only by GMS components that use GmsCompat
    public static final String CLIENT_PERMISSION = "app.grapheneos.gmscompat.permission.CLIENT";
    public static final String CONFIG_UPDATED_BROADCAST = PKG_NAME + ".CONFIG_UPDATED";

    @SuppressWarnings("FieldCanBeLocal") // written to field to prevent GC from collecting it
    private static IBinder processBinder;

    private static SynchronousServiceBinding<IGms2Gca> gms2GcaServiceBinding;

    static class Gms2GcaBinding extends SynchronousServiceBinding<IGms2Gca> {
        Gms2GcaBinding(Context ctx) {
            super(ctx, componentIntent(ComponentName.createRelative(PKG_NAME,
                    ".GmsCompatService")), "Gms2GcaBinding");
            setPermanent();
        }

        @Override
        protected IGms2Gca wrapBinder(IBinder binder) {
            return IGms2Gca.Stub.asInterface(binder);
        }
    }

    static class ClientOfGmsCore2GcaBinding extends SynchronousServiceBinding<IClientOfGmsCore2Gca> {
        ClientOfGmsCore2GcaBinding(Context ctx) {
            super(ctx, componentIntent(ComponentName.createRelative(PKG_NAME,
                            ".GmsClientCompatService")),
                    "GmsClient2GcaBinding");
        }

        @Override
        protected IClientOfGmsCore2Gca wrapBinder(IBinder binder) {
            return IClientOfGmsCore2Gca.Stub.asInterface(binder);
        }
    }

    static GmsCompatConfig connect(Context ctx, String processName) {
        gms2GcaServiceBinding = new Gms2GcaBinding(ctx);

        GmcPrivSettingsHooks.init(ctx);

        if (inMainGmsCoreProcess) {
            Runnable r = () -> {
                Context context = GmsCompat.appContext();
                maybeShowContactsSyncNotification(context);
                maybeShowGmsCorePowerExemptionNotif(context);
                maybeShowGmsCoreRestrictedBackgroundDataNotif();
            };
            GmsHooks.getBackgroundHandler().postDelayed(r, 3000L);
        }

        GmcConfigChangeListener.register(ctx);
        processBinder = new Binder();
        return call(i -> i.connect(ctx.getPackageName(), processName, processBinder));
    }

    public static <R> R call(SynchronousServiceBinding.BinderCall<IGms2Gca, R> callFn) {
        return gms2GcaServiceBinding.callOrExit(callFn);
    }

    public static void voidCall(SynchronousServiceBinding.VoidBinderCall<IGms2Gca> callFn) {
        gms2GcaServiceBinding.voidCallOrExit(callFn);
    }

    public static SynchronousServiceBinding<IGms2Gca> gms2GcaBinding() {
        return gms2GcaServiceBinding;
    }

    private static SynchronousServiceBinding<IClientOfGmsCore2Gca> clientOfGmsCore2GcaBinding;

    public static SynchronousServiceBinding<IClientOfGmsCore2Gca> iClientOfGmsCore2Gca() {
        synchronized (GmsCompatApp.class) {
            var cache = clientOfGmsCore2GcaBinding;
            if (cache == null) {
                return clientOfGmsCore2GcaBinding = new ClientOfGmsCore2GcaBinding(GmsCompat.appContext());
            }
            return cache;
        }
    }

    // Bypass some background activity restrictions (e.g. background service starts) for target
    // package by binding to it from foreground GmsCompat app
    public static void raisePackageToForeground(String targetPkg, long durationMs,
                                                @Nullable String reason, int reasonCode) {
        raisePackageToForeground(targetPkg, durationMs, reason, reasonCode, null);
    }

    public static void raisePackageToForeground(String targetPkg, long durationMs,
                                                @Nullable String reason, int reasonCode,
                                                @Nullable String serviceClassName) {
        if (durationMs < 0) {
            Log.e(TAG, "invalid duration: " + durationMs, new Throwable());
            return;
        }
        voidCall(i -> i.raisePackageToForeground(targetPkg, durationMs, reason, reasonCode, serviceClassName));
    }

    static void maybeShowContactsSyncNotification(Context ctx) {
        if (GmsCompat.hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            return;
        }

        var am = ctx.getSystemService(AccountManager.class);

        am.addOnAccountsUpdatedListener(accounts -> {
            // invoked only for Google accounts, "updateImmediately" arg ensures that it'll be called
            // even if account is already added
            if (accounts.length != 0) {
                voidCall(i -> i.maybeShowContactsSyncNotification());
            }
        }, GmsHooks.getBackgroundHandler(), true);
    }

    static final int RESTRICTED_BACKGROUND_DATA_CHECK_INTERVAL = 3 * 60_000; // 3 minutes

    static void maybeShowGmsCoreRestrictedBackgroundDataNotif() {
        Context ctx = GmsCompat.appContext();
        var cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
            voidCall(i -> i.maybeShowGmsCoreRestrictedBackgroundDataNotif());
        }

        // there's no listener API for getRestrictBackgroundStatus(), use polling with a large interval
        GmsHooks.getBackgroundHandler().postDelayed(
                GmsCompatApp::maybeShowGmsCoreRestrictedBackgroundDataNotif,
                RESTRICTED_BACKGROUND_DATA_CHECK_INTERVAL);
    }

    private static void maybeShowGmsCorePowerExemptionNotif(Context ctx) {
        var powerM = ctx.getSystemService(PowerManager.class);
        if (powerM.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
            return;
        }
        voidCall(i -> i.maybeShowGmsCorePowerExemptionNotification());
    }

    private GmsCompatApp() {}
}
