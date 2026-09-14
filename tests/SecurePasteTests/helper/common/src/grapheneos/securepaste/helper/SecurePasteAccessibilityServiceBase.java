package grapheneos.securepaste.helper;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
public class SecurePasteAccessibilityServiceBase extends AccessibilityService {
    private static WeakReference<SecurePasteAccessibilityServiceBase> sInstance =
            new WeakReference<>(null);

    @Override
    protected void onServiceConnected() {
        sInstance = new WeakReference<>(this);
        SecurePasteCommandProvider.setAccessibilityConnected(true);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (sInstance.get() == this) {
            sInstance = new WeakReference<>(null);
            SecurePasteCommandProvider.setAccessibilityConnected(false);
        }
        super.onDestroy();
    }

    public static boolean performPasteOnFocusedNode() {
        final SecurePasteAccessibilityServiceBase service = sInstance.get();
        if (service == null) {
            return false;
        }
        AccessibilityNodeInfo target = null;
        final AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null) {
                target = findEditable(root);
            }
        }
        return target != null && target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isEditable() || node.isFocused()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            final AccessibilityNodeInfo child = node.getChild(i);
            final AccessibilityNodeInfo result = findEditable(child);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
