package com.android.server.selectiontoolbar;

import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;

import com.android.server.LocalServices;
import com.android.server.clipboard.ClipboardManagerInternal;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.InputTargetInfo;

final class SecurePasteSelectionToolbar {
    private SecurePasteSelectionToolbar() {}

    static void onPasteAction(@NonNull ClipboardManagerInternal cmi, int uid,
            @Nullable IBinder hostInputToken) {
        if (hostInputToken == null) {
            return;
        }
        final WindowManagerInternal wmi = LocalServices.getService(WindowManagerInternal.class);
        if (wmi == null) {
            return;
        }

        final InputTargetInfo target = wmi.getInputTargetInfo(hostInputToken);
        if (target == null || target.ownerUid() != uid || target.displayId() == INVALID_DISPLAY) {
            return;
        }

        cmi.createPasteGrantForDisplay(uid, target.displayId());
    }
}
