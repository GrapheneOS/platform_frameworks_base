package com.android.server.ext;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.ext.settings.LockAction;
import android.os.PowerManager;
import android.util.Slog;

import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.power.PowerManagerService;

import java.util.concurrent.atomic.AtomicBoolean;

/** @hide */
public final class ActionOnLock {
    private static final String TAG = ActionOnLock.class.getSimpleName();

    private static final AtomicBoolean actionStarted = new AtomicBoolean();

    private ActionOnLock() {}

    public static void onPowerButtonSleep(Context ctx) {
        try {
            if (!shouldTakeAction(ctx) || !actionStarted.compareAndSet(false, true)) {
                return;
            }

            final boolean shutdown =
                    LockAction.ACTION_SETTING.get(ctx) == LockAction.ACTION_SHUTDOWN;
            Slog.i(TAG, shutdown ? "shutting down" : "rebooting");
            new Thread(() -> performAction(ctx, shutdown), TAG).start();
        } catch (RuntimeException e) {
            Slog.e(TAG, "unable to process power button sleep", e);
        }
    }

    private static boolean shouldTakeAction(Context ctx) {
        if (!LockAction.ENABLED_SETTING.get(ctx)) {
            return false;
        }

        var activityManager = LocalServices.getService(ActivityManagerInternal.class);
        var userManager = LocalServices.getService(UserManagerInternal.class);
        if (activityManager == null || userManager == null) {
            return false;
        }

        final int userId = activityManager.getCurrentUserId();
        if (!userManager.isUserUnlocked(userId)) {
            return false;
        }

        return isScreenLockSecure(ctx, userId);
    }

    private static boolean isScreenLockSecure(Context ctx, int userId) {
        try {
            return new LockPatternUtils(ctx).isSecure(userId);
        } catch (RuntimeException e) {
            Slog.e(TAG, "unable to check whether the screen lock is secure", e);
            return true;
        }
    }

    private static void performAction(Context ctx, boolean shutdown) {
        try {
            var powerManager = ctx.getSystemService(PowerManager.class);
            if (shutdown) {
                powerManager.shutdown(false, PowerManager.SHUTDOWN_USER_REQUESTED, true);
            } else {
                powerManager.reboot(null);
            }
        } catch (RuntimeException e) {
            Slog.e(TAG, "clean " + (shutdown ? "shutdown" : "reboot") + " failed", e);
            if (shutdown) {
                PowerManagerService.lowLevelShutdown(PowerManager.SHUTDOWN_USER_REQUESTED);
            } else {
                PowerManagerService.lowLevelReboot(null);
            }
        } finally {
            actionStarted.set(false);
        }
    }
}