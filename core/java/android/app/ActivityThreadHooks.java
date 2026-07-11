package android.app;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.GosPackageState;
import android.content.pm.SrtPermissions;
import android.content.res.AssetManager;
import android.ext.dcl.DynCodeLoading;
import android.location.HookedLocationManager;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import com.android.internal.app.ContactScopes;
import com.android.internal.app.GservicesFlags;
import com.android.internal.app.StorageScopesAppHooks;
import com.android.internal.gmscompat.GmsHooks;
import com.android.internal.util.Preconditions;

import java.util.Objects;

class ActivityThreadHooks {

    private static final String GSERVICES_FLAGS_TAG = "GservicesFlags";

    @Nullable // null during the early part of app process init
    private static volatile Context appContext;
    private static volatile boolean onBindCalled;

    // called after the initial app context is constructed
    // ActivityThread.handleBindApplication
    static Bundle onBind(ActivityThread.AppBindData appBindData) {
        Bundle args = appBindData.extraArgs;
        Objects.requireNonNull(args, "args bundle is null");

        Preconditions.checkState(!onBindCalled);
        onBindCalled = true;

        AssetManager.systemIdmapPaths_ = args.getStringArray(AppBindArgs.KEY_SYSTEM_IDMAP_PATHS);

        AppGlobals.setInitialPackageId(appBindData.appInfo.ext().getPackageId());

        int[] flags = Objects.requireNonNull(args.getIntArray(AppBindArgs.KEY_FLAGS_ARRAY));

        SrtPermissions.setFlags(flags[AppBindArgs.FLAGS_IDX_SPECIAL_RUNTIME_PERMISSIONS]);

        HookedLocationManager.setFlags(flags[AppBindArgs.FLAGS_IDX_HOOKED_LOCATION_MANAGER]);

        DynCodeLoading.handleAppBindFlags(flags[AppBindArgs.FLAGS_IDX_DYN_CODE_LOADING]);

        if (flags[AppBindArgs.FLAGS_IDX_GSERVICES_FLAGS_REDIRECT] != 0) {
            if (Log.isLoggable(GSERVICES_FLAGS_TAG, Log.VERBOSE)) {
                Log.v(GSERVICES_FLAGS_TAG, "enabling Gservices flags redirect for "
                        + appBindData.appInfo.packageName);
            }
            GservicesFlags.enable();
        }

        return args;
    }

    // called after ActivityThread instrumentation is inited, which happens before execution of any
    // of app's code
    // ActivityThread.handleBindApplication
    static void onBind2(Context appContext, Bundle appBindArgs) {
        ActivityThreadHooks.appContext = appContext;
        if (!Process.isIsolated()) { // isolated processes don't have access to GosPackageState
            onGosPackageStateChanged(appBindArgs);
        }
    }

    private static final Object gosPackageStateChangeLock = new Object();
    private static boolean hadInitialGosPsChangeCallback;
    private static boolean hasPendingGosPsChangeCallback;

    // called from both main and binder threads
    static void onGosPackageStateChanged(@Nullable Bundle appBindArgs) {
        Context ctx = appContext;
        GosPackageState state;
        synchronized (gosPackageStateChangeLock) {
            if (appBindArgs != null) {
                Preconditions.checkState(!hadInitialGosPsChangeCallback);
                hadInitialGosPsChangeCallback = true;
                // app context is always initialized by this point
                Objects.requireNonNull(ctx);
                if (hasPendingGosPsChangeCallback) {
                    Log.i("GosPackageState", "ignoring AppBindArgs since hasPendingGosPsChangeCallback is set");
                }
                state = hasPendingGosPsChangeCallback ?
                        // GosPackageState from AppBindArgs is outdated, obtain a fresh one
                        GosPackageState.getForSelf(ctx) :
                        // this is the initial onGosPackageStateChanged() call during app binding,
                        // use GosPackageState from AppBindArgs to avoid IPC
                        appBindArgs.getParcelable(AppBindArgs.KEY_GOS_PACKAGE_STATE, GosPackageState.class);

                Objects.requireNonNull(state);
                hasPendingGosPsChangeCallback = false;
            } else {
                if (ctx == null) {
                    Preconditions.checkState(!hadInitialGosPsChangeCallback);
                    hasPendingGosPsChangeCallback = true;
                    Log.i("GosPackageState", "set hasPendingGosPsChangeCallback");
                    return;
                }
                state = GosPackageState.getForSelf(ctx);
            }
        }
        StorageScopesAppHooks.maybeEnable(state);
        ContactScopes.maybeEnable(ctx, state);
    }

    static Service instantiateService(String className) {
        Service res = null;
        if (res == null) {
            res = GmsHooks.maybeInstantiateService(className);
        }
        return res;
    }
}
