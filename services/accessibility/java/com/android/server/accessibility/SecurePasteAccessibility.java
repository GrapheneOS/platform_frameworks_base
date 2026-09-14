package com.android.server.accessibility;

import static android.view.Display.INVALID_DISPLAY;

import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.clipboard.ClipboardManagerInternal;

/**
 * Validates that an accessibility paste action targets the exact focused input window.
 */
final class SecurePasteAccessibility {
    private SecurePasteAccessibility() {}

    @GuardedBy("AccessibilityManagerService.mLock")
    static void onPasteActionLocked(AbstractAccessibilityServiceConnection connection,
            int callingUid, int userId, int windowId, AccessibilityUserState userState,
            AccessibilityWindowManager windowManager) {
        if (!(connection instanceof AccessibilityServiceConnection realConnection)
                || connection instanceof ProxyAccessibilityServiceConnection
                || realConnection.mUserId != userId
                || realConnection.getClientUid() != callingUid
                || !userState.mBoundServices.contains(realConnection)) {
            return;
        }

        final AccessibilityWindowManager.RemoteAccessibilityConnection targetConnection =
                windowManager.getConnectionLocked(userId, windowId);
        if (targetConnection == null) {
            return;
        }

        final AccessibilityWindowInfo targetWindowInfo =
                windowManager.findA11yWindowInfoByIdLocked(windowId);
        final int targetUid = targetConnection.getUid();
        if (targetWindowInfo == null
                || TextUtils.isEmpty(targetConnection.getPackageName())
                || targetUid < 0) {
            return;
        }

        final int targetDisplayId = targetWindowInfo.getDisplayId();
        if (targetDisplayId == INVALID_DISPLAY
                || windowManager.getFocusedWindowId(
                        AccessibilityNodeInfo.FOCUS_INPUT, targetDisplayId) != windowId) {
            return;
        }

        final ClipboardManagerInternal cmi =
                LocalServices.getService(ClipboardManagerInternal.class);
        if (cmi == null) {
            return;
        }

        cmi.createPasteGrantForDisplay(targetUid, targetDisplayId);
    }
}
