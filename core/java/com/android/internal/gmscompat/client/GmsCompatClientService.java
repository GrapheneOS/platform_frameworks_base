package com.android.internal.gmscompat.client;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * The sole purpose of this service is to bypass some background activity restrictions (e.g. background
 * service starts). GmsCompat app is always allowed to bypass those restrictions because it has a
 * power optimization exemption. GmsCompat app temporarily binds to apps that host this service when
 * that's required, which bring those apps to the foreground state.
 * <p>
 * Privileged GmsCore achieves this by sending a privileged broadcast with
 * BroadcastOptions#setTemporaryAppAllowlist() option set.
 * <p>
 * A declaration of this service is added to AndroidManifest of all GMS components that use GmsCompat
 * and to their clients during package parsing.
 */
public class GmsCompatClientService extends Service {
    protected String TAG;

    private final Binder dummyBinder = new Binder();

    @Override
    public void onCreate() {
        TAG = getClass().getSimpleName();
        Log.d(TAG, "onCreate");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return dummyBinder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
    }
}
