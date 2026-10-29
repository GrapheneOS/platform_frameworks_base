package com.android.server.ext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.ext.settings.ExtSettings;
import android.location.LocationManager;
import android.provider.Settings;
import android.util.Slog;
import android.os.UserHandle;

import static java.util.Objects.requireNonNull;

class LocationAutoOff extends DelayedConditionalAction {
    private static final String TAG = LocationAutoOff.class.getSimpleName();

    private final LocationManager locationManager;

    private final Context context;
    private LocationAutoOff(SystemServerExt sse) {
        super(sse, ExtSettings.LOCATION_AUTO_OFF, sse.bgHandler);
        context = sse.context.createAttributionContext("LocationAutoOff");
        locationManager = context.getSystemService(LocationManager.class);
    }

    static void maybeInit(SystemServerExt sse) {
       if (sse.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION, 0)) {
           new LocationAutoOff(sse).init();
       }
    }

    @Override
    protected boolean shouldScheduleAlarm() {
        return isEligibleForAutoOff();
    }

    @Override
    protected void alarmTriggered() {
        if (isEligibleForAutoOff()) {
            Slog.d(TAG, "applyTrigger()");
            applyTrigger();
        }
    }

    private boolean isEligibleForAutoOff() {
        return locationManager.isLocationEnabledForUser(UserHandle.of(UserHandle.myUserId()));
    }

    @Override
    protected void registerStateListener() {
        IntentFilter f = new IntentFilter();
        f.addAction(LocationManager.MODE_CHANGED_ACTION);
        f.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);

        sse.context.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context broadcastContext, Intent intent) {
                Slog.d(TAG, intent + ", extras " + intent.getExtras().deepCopy());
                update();
            }
        }, f, null, handler);
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    private void applyTrigger() {
        locationManager.setLocationEnabledForUser(false, UserHandle.of(UserHandle.myUserId()));
    }
}
