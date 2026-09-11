package grapheneos.securepaste.ime;

import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;

// Test-only IME used to exercise current and deliberately retained InputConnection paste calls.
// The harness verifies both the normal default-IME paste path and binding to the current target.
public class SecurePasteImeService extends InputMethodService {
    private static volatile SecurePasteImeService sInstance;
    private static volatile InputConnection sRetainedInputConnection;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
            sRetainedInputConnection = null;
        }
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        final LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER);
        final Button button = new Button(this);
        button.setText("IME Paste");
        button.setOnClickListener(v -> requestPaste());
        root.addView(button);
        return root;
    }

    public static boolean isReady() {
        return sInstance != null;
    }

    public static boolean requestPaste() {
        final SecurePasteImeService service = sInstance;
        if (service == null) {
            return false;
        }
        final InputConnection inputConnection = service.getCurrentInputConnection();
        if (inputConnection == null) {
            return false;
        }
        inputConnection.performContextMenuAction(android.R.id.paste);
        // Compose handles this action while returning false. Report whether dispatch was possible,
        // then let the test verify whether the focused editor consumed it.
        return true;
    }

    public static boolean retainCurrentInputConnection() {
        final SecurePasteImeService service = sInstance;
        if (service == null) {
            return false;
        }
        sRetainedInputConnection = service.getCurrentInputConnection();
        return sRetainedInputConnection != null;
    }

    public static boolean requestPasteFromRetainedInputConnection() {
        final SecurePasteImeService service = sInstance;
        final InputConnection retained = sRetainedInputConnection;
        if (service == null || retained == null) {
            return false;
        }
        final InputConnection current = service.getCurrentInputConnection();
        if (current == null || current == retained) {
            return false;
        }
        retained.performContextMenuAction(android.R.id.paste);
        return true;
    }
}
