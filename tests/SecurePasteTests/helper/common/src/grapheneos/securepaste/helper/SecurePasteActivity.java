package grapheneos.securepaste.helper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.style.StrikethroughSpan;
import android.view.ActionMode;
import android.view.ContentInfo;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class SecurePasteActivity extends Activity {
    public static final String EDITOR_DESCRIPTION = "Secure Paste Editor";
    public static final String DRAG_SOURCE_DESCRIPTION = "Secure Paste Drag Source";
    public static final String DRAG_TARGET_DESCRIPTION = "Secure Paste Drag Target";
    public static final String DRAG_LABEL = "secure-paste-drag-label";
    public static final String DRAG_TEXT = "secure-paste-drag-text";
    public static final String DRAG_EXTRA_KEY = "secure-paste-drag-extra-key";
    public static final String DRAG_EXTRA_VALUE = "secure-paste-drag-extra-value";
    public static final String CUSTOM_PASTE_DESCRIPTION = "Custom Paste";
    private static final int MENU_FRAMEWORK_TITLE_PASTE = 0x53500001;
    private static final int MENU_CUSTOM_PASTE = 0x53500002;

    private static WeakReference<SecurePasteActivity> sActivity =
            new WeakReference<>(null);
    private static volatile DragResult sDragResult = new DragResult();
    private static volatile ContentInfo sReceivedContent;

    private EditText mEditor;
    private boolean mReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        if (isCustomToolbarApp()) {
            root.addView(newPasteButton());
        }
        mEditor = new EditText(this);
        mEditor.setContentDescription(EDITOR_DESCRIPTION);
        mEditor.setMinLines(4);
        mEditor.setSingleLine(false);
        mEditor.setTextIsSelectable(true);
        mEditor.setText("");
        mEditor.setSelectAllOnFocus(false);
        mEditor.setCustomSelectionActionModeCallback(newPasteActionModeCallback());
        mEditor.setCustomInsertionActionModeCallback(newPasteActionModeCallback());
        root.addView(mEditor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (isWriterApp()) {
            root.addView(newDragRow());
        }

        setContentView(root);
        sActivity = new WeakReference<>(this);
        mReady = true;
        mEditor.requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (sActivity.get() == this) {
            sActivity = new WeakReference<>(null);
        }
        super.onDestroy();
    }

    public static boolean isReady() {
        final SecurePasteActivity activity = sActivity.get();
        return activity != null && activity.mReady;
    }

    public static String getEditorText() {
        final SecurePasteActivity activity = sActivity.get();
        if (activity == null || activity.mEditor == null) {
            return null;
        }
        return activity.mEditor.getText().toString();
    }

    static boolean editorHasStrikethroughSpan() {
        final SecurePasteActivity activity = sActivity.get();
        return activity != null && activity.mEditor != null
                && activity.mEditor.getText().getSpans(
                        0, activity.mEditor.length(), StrikethroughSpan.class).length > 0;
    }

    static boolean enableRecordingContentReceiver() {
        final SecurePasteActivity activity = sActivity.get();
        if (activity == null || activity.mEditor == null) {
            return false;
        }
        sReceivedContent = null;
        activity.runOnUiThread(() -> activity.mEditor.setOnReceiveContentListener(
                new String[] {"text/plain", "video/avi"}, (view, content) -> {
                    sReceivedContent = content;
                    return null;
                }));
        return true;
    }

    static ContentInfo getReceivedContent() {
        return sReceivedContent;
    }

    public static void resetDragResult() {
        sDragResult = new DragResult();
    }

    public static DragResult getDragResult() {
        return sDragResult;
    }

    public static boolean requestEditorFocus() {
        final SecurePasteActivity activity = sActivity.get();
        if (activity == null || activity.mEditor == null) {
            return false;
        }
        activity.runOnUiThread(() -> {
            activity.mEditor.requestFocus();
            activity.mEditor.setSelection(activity.mEditor.getText().length());
        });
        return true;
    }

    public static boolean clearEditorFocus() {
        final SecurePasteActivity activity = sActivity.get();
        if (activity == null || activity.mEditor == null) {
            return false;
        }
        activity.runOnUiThread(() -> activity.mEditor.clearFocus());
        return true;
    }

    public static boolean directPasteFromClipboard() {
        final SecurePasteActivity activity = sActivity.get();
        if (activity == null) {
            return false;
        }
        final String text = SecurePasteCommandProvider.readClipboardText(activity);
        return pasteText(text);
    }

    public static boolean pasteText(String text) {
        final SecurePasteActivity activity = sActivity.get();
        if (activity == null || activity.mEditor == null || text == null) {
            return false;
        }
        activity.runOnUiThread(() -> {
            final int start = Math.max(0, activity.mEditor.getSelectionStart());
            final int end = Math.max(0, activity.mEditor.getSelectionEnd());
            activity.mEditor.getText().replace(Math.min(start, end), Math.max(start, end), text);
        });
        return true;
    }

    private static ClipData newDragClipData() {
        final ClipDescription description = new ClipDescription(DRAG_LABEL,
                new String[] {ClipDescription.MIMETYPE_TEXT_PLAIN});
        final PersistableBundle extras = new PersistableBundle();
        extras.putString(DRAG_EXTRA_KEY, DRAG_EXTRA_VALUE);
        description.setExtras(extras);
        return new ClipData(description, new ClipData.Item(DRAG_TEXT));
    }

    private Button newPasteButton() {
        final Button button = new Button(this);
        button.setText(CUSTOM_PASTE_DESCRIPTION);
        button.setContentDescription(CUSTOM_PASTE_DESCRIPTION);
        button.setOnClickListener(v -> directPasteFromClipboard());
        return button;
    }

    private LinearLayout newDragRow() {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Keep the UiAutomator drag low and horizontal so it does not pull down the SystemUI
        // notification shade from the status bar region.
        row.addView(newDragSourceView(), new LinearLayout.LayoutParams(
                0, 220, 1));
        row.addView(newDragTargetView(), new LinearLayout.LayoutParams(
                0, 220, 1));
        return row;
    }

    private TextView newDragSourceView() {
        final TextView view = new TextView(this);
        view.setText(DRAG_SOURCE_DESCRIPTION);
        view.setContentDescription(DRAG_SOURCE_DESCRIPTION);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(160);
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_DOWN) {
                return false;
            }
            final DragResult result = new DragResult();
            sDragResult = result;
            result.startDragResult = v.startDragAndDrop(
                    newDragClipData(), new View.DragShadowBuilder(v), null,
                    View.DRAG_FLAG_GLOBAL);
            return true;
        });
        return view;
    }

    private TextView newDragTargetView() {
        final TextView view = new TextView(this);
        view.setText(DRAG_TARGET_DESCRIPTION);
        view.setContentDescription(DRAG_TARGET_DESCRIPTION);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(160);
        view.setOnDragListener((v, event) -> {
            final DragResult result = sDragResult;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    recordDragStarted(result, event);
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION:
                case DragEvent.ACTION_DRAG_EXITED:
                    return true;
                case DragEvent.ACTION_DROP:
                    recordDrop(result, event);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    result.ended = true;
                    result.dropResult = event.getResult();
                    return true;
                default:
                    return false;
            }
        });
        return view;
    }

    private static void recordDragStarted(DragResult result, DragEvent event) {
        result.started = true;
        result.startedHasClipData = event.getClipData() != null;
        result.startedText = textFromClipData(event.getClipData());
        final ClipDescription description = event.getClipDescription();
        if (description != null) {
            result.startedLabel = String.valueOf(description.getLabel());
            result.startedMimeTypes = mimeTypes(description);
            final PersistableBundle extras = description.getExtras();
            result.startedExtraValue = extras == null ? null : extras.getString(DRAG_EXTRA_KEY);
        }
    }

    private static void recordDrop(DragResult result, DragEvent event) {
        result.dropped = true;
        result.dropHasClipData = event.getClipData() != null;
        result.dropText = textFromClipData(event.getClipData());
    }

    private static String textFromClipData(ClipData clipData) {
        if (clipData == null || clipData.getItemCount() == 0) {
            return null;
        }
        final CharSequence text = clipData.getItemAt(0).getText();
        return text == null ? null : text.toString();
    }

    private static String[] mimeTypes(ClipDescription description) {
        final String[] mimeTypes = new String[description.getMimeTypeCount()];
        for (int i = 0; i < description.getMimeTypeCount(); i++) {
            mimeTypes[i] = description.getMimeType(i);
        }
        return mimeTypes;
    }

    private ActionMode.Callback newPasteActionModeCallback() {
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                addCustomPasteItems(menu);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                addCustomPasteItems(menu);
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == MENU_FRAMEWORK_TITLE_PASTE
                        || item.getItemId() == MENU_CUSTOM_PASTE) {
                    directPasteFromClipboard();
                    mode.finish();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {}
        };
    }

    private void addCustomPasteItems(Menu menu) {
        if (!isCustomToolbarApp()) {
            return;
        }
        menu.removeItem(android.R.id.paste);
        menu.removeItem(MENU_FRAMEWORK_TITLE_PASTE);
        menu.removeItem(MENU_CUSTOM_PASTE);
        menu.add(0, MENU_FRAMEWORK_TITLE_PASTE, 0, getString(android.R.string.paste))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_CUSTOM_PASTE, 1, "Non-framework paste")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    private boolean isCustomToolbarApp() {
        return getPackageName().contains("customtoolbar");
    }

    private boolean isWriterApp() {
        return getPackageName().endsWith(".writer");
    }

    public static final class DragResult {
        public volatile boolean startDragResult;
        public volatile boolean started;
        public volatile boolean startedHasClipData;
        public volatile String startedText;
        public volatile String startedLabel;
        public volatile String[] startedMimeTypes;
        public volatile String startedExtraValue;
        public volatile boolean dropped;
        public volatile boolean dropHasClipData;
        public volatile String dropText;
        public volatile boolean ended;
        public volatile boolean dropResult;
    }
}
