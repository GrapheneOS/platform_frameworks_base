package grapheneos.securepaste.helper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SecurePasteKeyEventActivity extends Activity {
    public static final String NON_EDITOR_DESCRIPTION = "Secure Paste Key Target";
    public static final String EDITOR_DESCRIPTION = "Secure Paste Key Editor";
    public static final String RESULT_DESCRIPTION = "Secure Paste Key Result";
    public static final String EXTRA_READ_CLIPBOARD_ON_SHORTCUT = "readClipboardOnShortcut";
    public static final String EXTRA_CLIPBOARD_READ_DELAY_MILLIS = "clipboardReadDelayMillis";
    public static final String EXTRA_CLIPBOARD_READ_DEVICE_IDS = "clipboardReadDeviceIds";
    public static final String EXTRA_CLIP_TEXT_TO_SET = "clipTextToSet";
    public static final String EXTRA_RESULT_RECEIVER = "resultReceiver";
    public static final String RESULT_CLIP_TEXTS = "clipTexts";
    public static final String RESULT_DEVICE_ID = "deviceId";
    public static final String RESULT_HAS_WINDOW_FOCUS = "hasWindowFocus";

    private TextView mResult;
    private String mLastKey = "none";
    private String mLastClipText;
    private boolean mReadClipboardOnShortcut;
    private long mClipboardReadDelayMillis;
    private int[] mClipboardReadDeviceIds;
    private RemoteCallback mResultReceiver;
    private String mClipTextToSet;
    private boolean mInputConnectionCreated;
    private int mContextMenuActionCount;
    private int mLastContextMenuAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        mReadClipboardOnShortcut = getIntent().getBooleanExtra(
                EXTRA_READ_CLIPBOARD_ON_SHORTCUT, true);
        mClipboardReadDelayMillis = getIntent().getLongExtra(
                EXTRA_CLIPBOARD_READ_DELAY_MILLIS, 0);
        mClipboardReadDeviceIds = getIntent().getIntArrayExtra(EXTRA_CLIPBOARD_READ_DEVICE_IDS);
        mResultReceiver = getIntent().getParcelableExtra(
                EXTRA_RESULT_RECEIVER, RemoteCallback.class);
        mClipTextToSet = getIntent().getStringExtra(EXTRA_CLIP_TEXT_TO_SET);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int contentPadding = 24;
        root.setPadding(contentPadding, contentPadding, contentPadding, contentPadding);
        // UiAutomator ignores nodes outside the interactive region, so inset the test controls
        // from system bars and display cutouts when edge-to-edge is enforced.
        root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            final Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(contentPadding + insets.left, contentPadding + insets.top,
                    contentPadding + insets.right, contentPadding + insets.bottom);
            return windowInsets;
        });

        mResult = new TextView(this);
        mResult.setContentDescription(RESULT_DESCRIPTION);
        root.addView(mResult);

        final Button nonEditor = new Button(this);
        nonEditor.setText(NON_EDITOR_DESCRIPTION);
        nonEditor.setContentDescription(NON_EDITOR_DESCRIPTION);
        nonEditor.setFocusableInTouchMode(true);
        nonEditor.setOnClickListener(v -> v.requestFocus());
        root.addView(nonEditor);

        final EditText editor = new RecordingEditText();
        editor.setContentDescription(EDITOR_DESCRIPTION);
        editor.setMinLines(4);
        root.addView(editor);

        setContentView(root);
        updateResult();
        nonEditor.requestFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Wait until this app is associated with the virtual device before selecting its clipboard.
        if (hasFocus && mClipTextToSet != null) {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(
                    SecurePasteCommandProvider.TEXT_CLIP_LABEL, mClipTextToSet));
            mClipTextToSet = null;
        }
        sendResult(null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final String key = getTestShortcut(event);
        if (key == null) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            mLastKey = key;
            if (!mReadClipboardOnShortcut) {
                updateResult();
            } else if (mClipboardReadDelayMillis > 0) {
                // Model a paste handler which probes availability before posting its payload work.
                probeClipboardMetadata();
                updateResult();
                mResult.postDelayed(this::readClipboardAndUpdateResult,
                        mClipboardReadDelayMillis);
            } else {
                readClipboardAndUpdateResult();
            }
        }
        return true;
    }

    private void probeClipboardMetadata() {
        final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.hasPrimaryClip();
        clipboard.hasText();
        clipboard.getPrimaryClipDescription();
    }

    private void readClipboardAndUpdateResult() {
        final int[] deviceIds = mClipboardReadDeviceIds == null
                ? new int[] {getDeviceId()} : mClipboardReadDeviceIds;
        final String[] clipTexts = new String[deviceIds.length];
        for (int i = 0; i < deviceIds.length; i++) {
            clipTexts[i] = SecurePasteCommandProvider.readClipboardText(
                    createDeviceContext(deviceIds[i]));
        }
        mLastClipText = clipTexts[0];
        updateResult();
        sendResult(clipTexts);
    }

    private String getTestShortcut(KeyEvent event) {
        return switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_V -> {
                if (event.hasModifiers(KeyEvent.META_CTRL_ON)) {
                    yield "CTRL_V";
                }
                if (event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON)) {
                    yield "CTRL_SHIFT_V";
                }
                if (event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON)) {
                    yield "CTRL_ALT_V";
                }
                yield null;
            }
            case KeyEvent.KEYCODE_INSERT -> event.hasModifiers(KeyEvent.META_SHIFT_ON)
                    ? "SHIFT_INSERT" : null;
            case KeyEvent.KEYCODE_PASTE -> event.hasNoModifiers() ? "PASTE" : null;
            default -> null;
        };
    }

    private void updateResult() {
        mResult.setText("key=" + mLastKey
                + ";clip=" + mLastClipText
                + ";inputConnectionCreated=" + mInputConnectionCreated
                + ";contextActions=" + mContextMenuActionCount
                + ";lastContextAction=" + mLastContextMenuAction);
    }

    private void sendResult(String[] clipTexts) {
        if (mResultReceiver != null) {
            final Bundle result = new Bundle();
            result.putInt(RESULT_DEVICE_ID, getDeviceId());
            result.putBoolean(RESULT_HAS_WINDOW_FOCUS, hasWindowFocus());
            if (clipTexts != null) {
                result.putStringArray(RESULT_CLIP_TEXTS, clipTexts);
            }
            mResultReceiver.sendResult(result);
        }
    }

    private final class RecordingEditText extends EditText {
        RecordingEditText() {
            super(SecurePasteKeyEventActivity.this);
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            final InputConnection target = super.onCreateInputConnection(outAttrs);
            if (target == null) {
                return null;
            }
            mInputConnectionCreated = true;
            updateResult();
            return new InputConnectionWrapper(target, false) {
                @Override
                public boolean performContextMenuAction(int id) {
                    if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                        mContextMenuActionCount++;
                        mLastContextMenuAction = id;
                        updateResult();
                    }
                    if (id == android.R.id.pasteAsPlainText) {
                        return false;
                    }
                    return super.performContextMenuAction(id);
                }
            };
        }
    }
}
