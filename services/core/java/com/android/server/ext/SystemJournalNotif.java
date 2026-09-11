package com.android.server.ext;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.ext.LogViewerApp;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;

import java.util.ArrayList;
import java.util.function.Function;

public class SystemJournalNotif {

    static void showCrash(Context ctx, String progName, String errorReport,
                          @Nullable Pair<String, Long> textTombstoneFileSpec,
                          @CurrentTimeMillisLong long crashTimestamp, boolean showReportButton) {
        var i = LogViewerApp.createBaseErrorReportIntent(errorReport);
        i.putExtra(Intent.EXTRA_TITLE, progName + " crash");
        i.putExtra(LogViewerApp.EXTRA_SHOW_REPORT_BUTTON, showReportButton);
        if (textTombstoneFileSpec != null) {
            i.putExtra(LogViewerApp.EXTRA_TEXT_TOMBSTONE_FILE_PATH, textTombstoneFileSpec.first);
            i.putExtra(LogViewerApp.EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME, textTombstoneFileSpec.second.longValue());
        }

        show(crashTimestamp, ctx.getString(R.string.process_crash_notif_title, progName), i);
    }

    public static class NotificationData {
        public final CharSequence title;
        public final Intent mainIntent;

        public NotificationData(CharSequence title, Intent mainIntent) {
            this.title = title;
            this.mainIntent = mainIntent;
        }
    }

    static void show(@CurrentTimeMillisLong long when, Function<Context, NotificationData> notifDataSupplier) {
        synchronized (pendingActions) {
            if (!isSystemServerInited) {
                // NotificationManagerService isn't ready yet, delay this notification until after
                // system_server init completion
                pendingActions.add(() -> {
                    Context ctx = ActivityThread.currentActivityThread().getSystemContext();
                    showInner(ctx, when, notifDataSupplier);
                });
                return;
            }
        }
        Context ctx = ActivityThread.currentActivityThread().getSystemContext();
        showInner(ctx, when, notifDataSupplier);
    }

    static void show(@CurrentTimeMillisLong long when, String notifTitle, Intent mainIntent) {
        show(when, ctx -> new NotificationData(notifTitle, mainIntent));
    }

    private static void showInner(Context ctx, @CurrentTimeMillisLong long when,
                                  Function<Context, NotificationData> notifDataSupplier) {
        NotificationData notifData = notifDataSupplier.apply(ctx);
        var b = new Notification.Builder(ctx, SystemNotificationChannels.SYSTEM_JOURNAL);
        b.setSmallIcon(R.drawable.ic_error);
        b.setContentTitle(notifData.title);
        b.setContentText(ctx.getText(R.string.notif_text_tap_to_see_details));
        b.setAutoCancel(true);
        b.setWhen(when);
        b.setShowWhen(true);

        UserHandle user = UserHandle.of(ActivityManager.getCurrentUser());

        var pi = PendingIntent.getActivityAsUser(ctx, 0, notifData.mainIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null, user);
        b.setContentIntent(pi);

        var nm = ctx.getSystemService(NotificationManager.class);
        nm.notifyAsUser(null, createNotifId(), b.build(), user);
    }

    private static final ArrayList<Runnable> pendingActions = new ArrayList<>();
    // protected by lock of pendingActions object
    private static boolean isSystemServerInited;

    public static void onSystemServerInited() {
        final Runnable[] actions;
        synchronized (pendingActions) {
            isSystemServerInited = true;
            actions = pendingActions.toArray(new Runnable[0]);
            pendingActions.clear();
        }
        Handler bgHandler = BackgroundThread.getHandler();
        for (int i = 0; i < actions.length; ++i) {
            bgHandler.post(actions[i]);
        }
    }

    private static int notifIdSrc = SystemMessageProto.SystemMessage.NOTE_SYSTEM_JOURNAL_BASE;

    private static int createNotifId() {
        synchronized (SystemJournalNotif.class) {
            int res = notifIdSrc;
            notifIdSrc = (res == SystemMessageProto.SystemMessage.NOTE_SYSTEM_JOURNAL_MAX) ?
                    SystemMessageProto.SystemMessage.NOTE_SYSTEM_JOURNAL_BASE :
                    res + 1;
            return res;
        }
    }
}
