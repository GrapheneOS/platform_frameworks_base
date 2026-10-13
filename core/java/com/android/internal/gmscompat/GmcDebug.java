package com.android.internal.gmscompat;

import android.app.BroadcastOptions;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;

public class GmcDebug {

    public static void maybeLogStartService(Intent service, boolean requireForeground) {
        String LOG_TAG = "GmcStartService";
        if (!Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            return;
        }
        var b = new StringBuilder();
        appendIntent(service, b);
        if (requireForeground) {
            b.append(", requireForeground");
        }
        Log.v(LOG_TAG, b.toString(), new Throwable());
    }

    public static void maybeLogStopService(Intent service) {
        String LOG_TAG = "GmcStopService";
        if (!Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            return;
        }
        var b = new StringBuilder();
        appendIntent(service, b);
        Log.v(LOG_TAG, b.toString(), new Throwable());
    }

    public static void maybeLogServiceOnStartCommand(Service service, Intent intent, int flags, int startId) {
        String LOG_TAG = "GmcServiceCmd";
        if (!Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            return;
        }
        var b = new StringBuilder();
        b.append(service.getClass().getName());
        b.append(", ");
        if (intent != null) {
            appendIntent(intent, b);
        } else {
            b.append("intent is null");
        }
        b.append(", flags: ");
        b.append(Integer.toHexString(flags));
        b.append(", startId: ");
        b.append(startId);
        Log.v(LOG_TAG, b.toString());
    }

    public static void maybeLogBindService(Intent service, ServiceConnection conn, long flags, String instanceName) {
        String LOG_TAG = "GmcBindService";
        if (!Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            return;
        }
        var b = new StringBuilder();
        appendIntent(service, b);
        b.append(", conn: ");
        b.append(System.identityHashCode(conn));
        b.append(", flags: ");
        b.append(Long.toHexString(flags));
        if (instanceName != null) {
            b.append(", instanceName: ");
            b.append(instanceName);
        }
        Log.v(LOG_TAG, b.toString(), new Throwable());
    }

    public static void maybeLogUnbindService(ServiceConnection conn) {
        String LOG_TAG = "GmcUnbindService";
        if (!Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            return;
        }
        Log.v(LOG_TAG, "conn: " + System.identityHashCode(conn), new Throwable());
    }

    public static void maybeLogServiceConnCallback(ServiceConnection conn, String name, ComponentName cn) {
        String LOG_TAG = "GmcServiceConnCb";
        if (!Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            return;
        }
        Log.v(LOG_TAG, "conn: " + System.identityHashCode(conn) + ", " + name + ", " + cn.toShortString());
    }

    public static void maybeLogSendBroadcast(Intent intent, String receiverPerm, Bundle options,
            int appOp) {
        maybeLogSendBroadcast(intent, receiverPerm, null, options, null, appOp, 0, null, null);
    }

    public static void maybeLogSendBroadcast(Intent intent, String receiverPerm, String[] receiverPerms, Bundle options, BroadcastOptions brOptions,
            int appOp) {
        maybeLogSendBroadcast(intent, receiverPerm, receiverPerms, options, brOptions, appOp, 0, null, null);
    }

    public static void maybeLogSendBroadcast(Intent intent, String receiverPerm, String[] receiverPerms, Bundle options, BroadcastOptions brOptions,
            int appOp, int initialCode, String initialData, Bundle initialExtras) {
        String LOG_TAG = "GmcSendBroadcast";
        if (!Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            return;
        }
        var b = new StringBuilder("sendBroadcast: ");
        appendIntent(intent, b);
        if (options != null) {
            b.append(", options: ");
            b.append(options.toStringDeep());
        }
        if (brOptions != null) {
            b.append(", brOptions: ");
            b.append(brOptions);
        }
        if (receiverPerm != null) {
            b.append(", receiverPerm: ");
            b.append(receiverPerm);
        }
        if (receiverPerms != null) {
            b.append(", receiverPerms: ");
            b.append(Arrays.toString(receiverPerms));
        }
        if (appOp != 0) {
            b.append(", appOp: ");
            b.append(appOp);
        }
        if (initialCode != 0) {
            b.append(", initialCode: ");
            b.append(initialCode);
        }
        if (initialData != null) {
            b.append(", initialData: ");
            b.append(initialData);
        }
        if (initialExtras != null) {
            b.append(", initialExtras: ");
            b.append(initialExtras.toStringDeep());
        }
        Log.v(LOG_TAG, b.toString(), new Throwable());
    }

    public static void maybeLogReceiveBroadcast(BroadcastReceiver receiver, Intent intent, boolean isManifest) {
        String LOG_TAG = "GmcRecvBroadcast";
        if (!Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            return;
        }
        var b = new StringBuilder();
        b.append(isManifest? "manifest; " : "dynamic; ");
        appendIntent(intent, b);
        b.append(", receiver: ");
        b.append(receiver.getClass().getName());
        Log.v(LOG_TAG, b.toString());
    }

    private static void appendIntent(Intent intent, StringBuilder b) {
        intent.toString(b);
        Bundle extras = intent.getExtras();
        if (extras != null) {
            b.append(", extras: ");
            b.append(extras.toStringDeep());
        }
    }
}
