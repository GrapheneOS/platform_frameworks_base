package com.android.server.input;

import static android.view.Display.INVALID_DISPLAY;

import android.annotation.Nullable;
import android.os.IBinder;
import android.view.KeyEvent;

import com.android.server.LocalServices;
import com.android.server.clipboard.ClipboardManagerInternal;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.InputTargetInfo;

final class SecurePasteKeyEventHandler {
    private SecurePasteKeyEventHandler() {}

    static void maybeGrantAccess(WindowManagerInternal wmi, @Nullable IBinder focusedToken,
            KeyEvent event) {
        if (focusedToken == null
                || !isPasteKeyEvent(event)
                || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0
                || event.isCanceled()) {
            return;
        }

        final InputTargetInfo target = wmi.getInputTargetInfo(focusedToken);
        if (target == null || target.ownerUid() < 0 || target.displayId() == INVALID_DISPLAY) {
            return;
        }

        final ClipboardManagerInternal cmi =
                LocalServices.getService(ClipboardManagerInternal.class);
        if (cmi == null) {
            return;
        }

        cmi.createPasteGrantForDisplay(target.ownerUid(), target.displayId());
    }

    static boolean isPasteKeyEvent(KeyEvent event) {
        // Keep in sync with the paste entries in InputGestureManager.mBlockListedTriggers.
        return switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_V -> event.hasModifiers(KeyEvent.META_CTRL_ON)
                    || event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
            case KeyEvent.KEYCODE_INSERT -> event.hasModifiers(KeyEvent.META_SHIFT_ON);
            case KeyEvent.KEYCODE_PASTE -> event.hasNoModifiers();
            default -> false;
        };
    }
}
