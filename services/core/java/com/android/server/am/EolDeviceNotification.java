package com.android.server.am;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.UserHandle;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;

class EolDeviceNotification {

    static void maybeShow(Context ctx) {
        String eolDate;
        switch (Build.DEVICE) {
            case "bramble":
            case "redfin":
                eolDate = "December 2023";
                break;
            case "barbet":
                eolDate = "September 2024";
                break;
            default:
                return;
        }

        var b = new Notification.Builder(ctx, SystemNotificationChannels.DEVICE_IS_EOL);
        b.setSmallIcon(R.drawable.stat_sys_warning);
        b.setContentTitle("This device is no longer supported");

        String text = "This device stopped receiving full security updates in " + eolDate
                + " and isn't safe to use anymore regardless of OS choice. It's strongly " +
                "recommended to replace it as soon as possible. Tap to see more info.";
        b.setStyle(new Notification.BigTextStyle().bigText(text));

        var link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://grapheneos.org/faq#supported-devices"));
        b.setContentIntent(PendingIntent.getActivity(ctx, 0, link, PendingIntent.FLAG_IMMUTABLE));

        b.setVisibility(Notification.VISIBILITY_PUBLIC);

        var notificationManager = ctx.getSystemService(NotificationManager.class);
        notificationManager.notifyAsUser(null, SystemMessageProto.SystemMessage.NOTE_LEGACY_DEVICE,
                b.build(), UserHandle.SYSTEM);
    }
}
