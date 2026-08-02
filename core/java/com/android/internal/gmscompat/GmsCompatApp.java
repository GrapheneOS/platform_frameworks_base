/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.gmscompat;

import android.Manifest;
import android.accounts.AccountManager;
import android.annotation.Nullable;
import android.app.compat.gms.GmsCompat;
import android.content.ContentProviderClient;
import android.content.Context;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.gmscompat.fileservice.FileProxyService;

import static com.android.internal.gmscompat.GmsHooks.inPersistentGmsCoreProcess;

public final class GmsCompatApp {
    private static final String TAG = "GmsCompat/GCA";
    public static final String PKG_NAME = "app.grapheneos.gmscompat";
    // permission that is held only by GmsCompatApp
    public static final String SIGNATURE_PROTECTED_PERMISSION = PKG_NAME + ".SIGNATURE_PROTECTED_PERMISSION";

    @SuppressWarnings("FieldCanBeLocal")
    // written to fields to prevent GC from collecting them
    private static BinderGca2Gms binderGca2Gms;
    @SuppressWarnings("FieldCanBeLocal")
    private static FileProxyService gmsCoreFileProxyService;

    private static IGms2Gca binderGms2Gca;

    static GmsCompatConfig connect(Context ctx, String processName) {
        registeredContentObservers = new ArraySet<>();

        BinderGca2Gms gca2Gms = new BinderGca2Gms();
        binderGca2Gms = gca2Gms;

        try {
            IGms2Gca iGms2Gca = IGms2Gca.Stub.asInterface(getBinder(RPC_GET_BINDER_IGms2Gca));
            binderGms2Gca = iGms2Gca;

            if (GmsCompat.isGmsCore()) {
                FileProxyService fileProxyService = null;
                if (inPersistentGmsCoreProcess) {
                    // FileProxyService binder needs to be always available to the GmsCore clients.
                    // "persistent" process launches at bootup and is kept alive by the ServiceConnection
                    // from the GmsCompatApp, which makes it fit for the purpose of hosting the FileProxyService
                    fileProxyService = new FileProxyService(ctx);
                    gmsCoreFileProxyService = fileProxyService;

                    Handler handler = ctx.getMainThreadHandler();
                    handler.postDelayed(GmsCompatApp::maybeShowContactsSyncNotification, 3000L);
                    handler.postDelayed(GmsCompatApp::maybeShowGmsCoreRestrictedBackgroundDataNotif, 3000L);
                }
                return iGms2Gca.connectGmsCore(processName, gca2Gms, fileProxyService);
            } else {
                return iGms2Gca.connect(ctx.getPackageName(), processName, gca2Gms);
            }
        } catch (RemoteException e) {
            throw callFailed(e);
        }
    }

    public static IGms2Gca iGms2Gca() {
        return binderGms2Gca;
    }

    private static volatile IClientOfGmsCore2Gca binderClientOfGmsCore2Gca;

    public static IClientOfGmsCore2Gca iClientOfGmsCore2Gca() {
        IClientOfGmsCore2Gca cache = binderClientOfGmsCore2Gca;
        if (cache != null) {
            return cache;
        }

        IBinder binder = getBinder(RPC_GET_BINDER_IClientOfGmsCore2Gca);
        IClientOfGmsCore2Gca iface = IClientOfGmsCore2Gca.Stub.asInterface(binder);
        // benign race, it's fine to obtain this interface more than once
        binderClientOfGmsCore2Gca = iface;
        return iface;
    }

    private static final String RPC_PROVIDER_AUTHORITY = PKG_NAME + ".RpcProvider";
    // Keep a stable provider connection for the process lifetime so that ActivityManager propagates
    // this process's OOM adjustment to GmsCompat. If GmsCompat dies, this stable provider 
    // connection means Android will kill clients with ApplicationExitInfo.REASON_DEPENDENCY_DIED
    private static ContentProviderClient rpcProviderClient;

    private static synchronized ContentProviderClient getRpcProviderClient(Context ctx,
            String authority) {
        ContentProviderClient client = rpcProviderClient;
        if (client == null) {
            client = ctx.getContentResolver().acquireContentProviderClient(authority);
            if (client == null) {
                throw new IllegalStateException("Unable to acquire " + authority);
            }
            rpcProviderClient = client;
        }
        return client;
    }

    public static final String KEY_BINDER = "binder";
    public static final String KEY_PKG_NAME = "pkg";

    public static final int RPC_GET_BINDER_IGms2Gca = 0;
    public static final int RPC_GET_BINDER_IClientOfGmsCore2Gca = 1;

    public static Bundle callRpcProvider(Context ctx, int method, String arg, Bundle extras) {
        String authority = RPC_PROVIDER_AUTHORITY;
        try {
            Bundle result = getRpcProviderClient(ctx, authority).call(Integer.toString(method),
                    arg, extras);
            // ContentResolver.call() marks provider results as defusable.
            Bundle.setDefusable(result, true);
            return result;
        } catch (Throwable t) {
            Log.e(TAG, "call to " + authority + " failed", t);
            if (GmsCompat.isEnabled()) {
                // content provider calls are infallible unless something goes very wrong, better fail fast in that case
                System.exit(1);
            }
            // don't crash processes that call GmsCompatApp, but don't use GmsCompat layer
            return null;
        }
    }

    private static IBinder getBinder(int which) {
        Bundle bundle = callRpcProvider(GmsCompat.appContext(), which, null, null);
        IBinder binder = bundle.getBinder(KEY_BINDER);
        DeathRecipient.register(binder);
        return binder;
    }

    static class DeathRecipient implements IBinder.DeathRecipient {
        private static final DeathRecipient INSTANCE = new DeathRecipient();
        private DeathRecipient() {}

        static void register(IBinder b) {
            try {
                b.linkToDeath(INSTANCE, 0);
            } catch (RemoteException e) {
                // binder already died
                INSTANCE.binderDied();
            }
        }

        public void binderDied() {
            // see comment in callFailed()
            Log.e(TAG, PKG_NAME + " died");
            System.exit(1);
        }
    }

    public static RuntimeException callFailed(RemoteException e) {
        // running GmsCompat app process is a hard dependency of sandboxed GMS
        Log.e(TAG, "call failed, calling System.exit(1)", e);
        System.exit(1);
        // unreachable, needed for control flow checks by the compiler
        // (Java doesn't have a concept of "noreturn")
        return e.rethrowAsRuntimeException();
    }

    public static final String NS_DeviceConfig = "config";

    public static String deviceConfigNamespace(String namespace) {
        // last path component of DeviceConfig.CONTENT_URI
        String topNs = "config";
        return NS_DeviceConfig + ':' + namespace;
    }

    public static String getString(String ns, String key) {
        try {
            return iGms2Gca().privSettingsGetString(ns, key);
        } catch (RemoteException e) {
            throw callFailed(e);
        }
    }

    public static boolean putString(String ns, String key, @Nullable String value) {
        try {
            return iGms2Gca().privSettingsPutString(ns, key, value);
        } catch (RemoteException e) {
            throw callFailed(e);
        }
    }

    public static boolean setProperties(DeviceConfig.Properties props) {
        String[] keys = props.getKeyset().toArray(new String[0]);
        String[] values = new String[keys.length];

        for (int i = 0; i < keys.length; ++i) {
            values[i] = props.getString(keys[i], null);
        }

        String ns = deviceConfigNamespace(props.getNamespace());

        try {
            return iGms2Gca().privSettingsPutStrings(ns, keys, values);
        } catch (RemoteException e) {
            throw callFailed(e);
        }
    }

    private static ArraySet<ContentObserver> registeredContentObservers;

    public static boolean registerObserver(Uri uri, ContentObserver observer) {
        String s = uri.toString();

        String prefix = "content://settings/";

        if (!s.startsWith(prefix)) {
            return false;
        }

        int nsStart = prefix.length();
        int nsEnd = s.indexOf('/', nsStart);

        if (nsEnd < 0 || nsStart == nsEnd) {
            return false;
        }

        String ns = s.substring(nsStart, nsEnd);
        String key = s.substring(nsEnd + 1);

        switch (ns) {
            // keep in sync with Settings.NameValueCache#maybeGetGmsCompatNamespace
            case "global":
                if (Settings.Global.isKnownKey(key)) {
                    return false;
                }
                break;
            case "secure":
                if (Settings.Secure.isKnownKey(key)) {
                    return false;
                }
                break;
            default:
                return false;
        }

        android.database.IContentObserver iObserver = observer.getContentObserver();

        try {
            iGms2Gca().privSettingsRegisterObserver(ns, key, iObserver);
        } catch (RemoteException e) {
            throw callFailed(e);
        }

        synchronized (registeredContentObservers) {
            registeredContentObservers.add(observer);
        }

        return true;
    }

    public static boolean unregisterObserver(ContentObserver observer) {
        synchronized (registeredContentObservers) {
            if (registeredContentObservers.contains(observer)) {
                registeredContentObservers.remove(observer);
            } else {
                return false;
            }
        }

        android.database.IContentObserver iObserver = observer.getContentObserver();

        try {
            iGms2Gca().privSettingsUnregisterObserver(iObserver);
        } catch (RemoteException e) {
            throw callFailed(e);
        }
        return true;
    }

    // Bypass some background activity restrictions (e.g. background service starts) for target
    // package by binding to it from foreground GmsCompat app
    public static void raisePackageToForeground(String targetPkg, long durationMs,
                                                @Nullable String reason, int reasonCode) {
        if (durationMs <= 0) {
            Log.e(TAG, "invalid duration: " + durationMs, new Throwable());
            return;
        }
        try {
            GmsCompatApp.iGms2Gca().raisePackageToForeground(targetPkg, durationMs, reason, reasonCode);
        } catch (RemoteException e) {
            throw callFailed(e);
        }
    }

    static void maybeShowContactsSyncNotification() {
        if (GmsCompat.hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            return;
        }

        Context ctx = GmsCompat.appContext();
        var am = ctx.getSystemService(AccountManager.class);

        am.addOnAccountsUpdatedListener(accounts -> {
            // invoked only for Google accounts, "updateImmediately" arg ensures that it'll be called
            // even if account is already added
            if (accounts.length != 0) {
                try {
                    iGms2Gca().maybeShowContactsSyncNotification();
                } catch (RemoteException e) {
                    callFailed(e);
                }
            }
        }, ctx.getMainThreadHandler(), true);
    }

    static final int RESTRICTED_BACKGROUND_DATA_CHECK_INTERVAL = 3 * 60_000; // 3 minutes

    static void maybeShowGmsCoreRestrictedBackgroundDataNotif() {
        Context ctx = GmsCompat.appContext();
        var cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
            try {
                iGms2Gca().maybeShowGmsCoreRestrictedBackgroundDataNotif();
            } catch (RemoteException e) {
                throw callFailed(e);
            }
        }

        // there's no listener API for getRestrictBackgroundStatus(), use polling with a large interval
        ctx.getMainThreadHandler().postDelayed(GmsCompatApp::maybeShowGmsCoreRestrictedBackgroundDataNotif, RESTRICTED_BACKGROUND_DATA_CHECK_INTERVAL);
    }

    private GmsCompatApp() {}
}
