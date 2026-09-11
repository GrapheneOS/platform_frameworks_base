package com.android.server.ext;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.content.Intent;
import android.ext.LogViewerApp;
import android.os.Binder;
import android.util.Slog;

import java.util.function.Function;

public class SystemErrorNotification {
    static final String TAG = SystemErrorNotification.class.getSimpleName();

    public @CurrentTimeMillisLong long when = System.currentTimeMillis();
    public final String type;
    public final Function<Context, Text> textSupplier;
    public boolean showReportButton = true;

    public static class Text {
        public final CharSequence title;
        public final String message;

        public Text(CharSequence title, String message) {
            this.title = title;
            this.message = message;
        }
    }

    public SystemErrorNotification(String type, String message) {
        this(type, type, message);
    }

    public SystemErrorNotification(String type, String title, String message) {
        this(type, ctx -> new Text(title, message));
    }

    public SystemErrorNotification(String type, @StringRes int title, String message) {
        this(type, ctx -> new Text(ctx.getString(title), message));
    }

    public SystemErrorNotification(String type, Function<Context, Text> textSupplier) {
        this.type = type;
        this.textSupplier = textSupplier;
    }

    // kept for compatibility with the previous callers, context is intentionally ignored
    public void show(@Nullable Context context) {
        show();
    }

    public void show() {
        Function<Context, SystemJournalNotif.NotificationData> notifDataSupplier = ctx -> {
            Text text = this.textSupplier.apply(ctx);
            var intent = LogViewerApp.createBaseErrorReportIntent(text.message);
            intent.putExtra(Intent.EXTRA_TITLE, text.title);
            intent.putExtra(LogViewerApp.EXTRA_ERROR_TYPE, type);
            intent.putExtra(LogViewerApp.EXTRA_SHOW_REPORT_BUTTON, showReportButton);
            Slog.e(TAG, type + ", title: " + text.title + ", message: " + text.message);
            return new SystemJournalNotif.NotificationData(text.title, intent);
        };
        final long identity = Binder.clearCallingIdentity();
        try {
            SystemJournalNotif.show(when, notifDataSupplier);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
