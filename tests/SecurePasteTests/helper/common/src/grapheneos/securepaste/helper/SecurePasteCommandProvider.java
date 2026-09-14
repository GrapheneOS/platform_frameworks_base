package grapheneos.securepaste.helper;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.view.ContentInfo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class SecurePasteCommandProvider extends ContentProvider {
    private static final String JETPACK_COMPOSE_PACKAGE =
            "grapheneos.securepaste.jetpackcompose";
    private static final String JETPACK_COMPOSE_ACTIVITY =
            "grapheneos.securepaste.jetpackcompose.SecurePasteJetpackComposeActivity";
    private static final String WRITER_PACKAGE = "grapheneos.securepaste.writer";

    public static final String METHOD_SET_CLIP_TEXT = "set_clip_text";
    public static final String METHOD_SET_CLIP_HTML = "set_clip_html";
    public static final String METHOD_SET_CLIP_STYLED_TEXT = "set_clip_styled_text";
    public static final String METHOD_SET_CLIP_INTENT = "set_clip_intent";
    public static final String METHOD_SET_CLIP_URI = "set_clip_uri";
    public static final String METHOD_SET_CLIP_URI_ONLY = "set_clip_uri_only";
    public static final String METHOD_SET_CLIP_COMPLEX_ITEM = "set_clip_complex_item";
    public static final String METHOD_SET_CLIP_MULTIPLE_TEXT_ITEMS =
            "set_clip_multiple_text_items";
    public static final String METHOD_SET_CLIP_UNSUPPORTED_MIME_TYPE =
            "set_clip_unsupported_mime_type";
    public static final String METHOD_CLEAR_CLIP = "clear_clip";
    public static final String METHOD_READ_CLIP = "read_clip";
    public static final String METHOD_READ_OWN_CLIP = "read_own_clip";
    public static final String METHOD_REGISTER_LISTENER = "register_listener";
    public static final String METHOD_RESET_LISTENER = "reset_listener";
    public static final String METHOD_GET_LISTENER_COUNT = "get_listener_count";
    public static final String METHOD_CACHE_CLIP = "cache_clip";
    public static final String METHOD_ACTIVITY_READY = "activity_ready";
    public static final String METHOD_GET_EDITOR_TEXT = "get_editor_text";
    public static final String METHOD_ENABLE_RECORDING_CONTENT_RECEIVER =
            "enable_recording_content_receiver";
    public static final String METHOD_GET_RECEIVED_CONTENT = "get_received_content";
    public static final String METHOD_REQUEST_FOCUS = "request_focus";
    public static final String METHOD_CLEAR_FOCUS = "clear_focus";
    public static final String METHOD_CACHED_PASTE = "cached_paste";
    public static final String METHOD_READ_WRITER_URI = "read_writer_uri";
    public static final String METHOD_READ_WRITER_INTENT_URI = "read_writer_intent_uri";
    public static final String METHOD_RESET_DRAG_RESULT = "reset_drag_result";
    public static final String METHOD_GET_DRAG_RESULT = "get_drag_result";
    public static final String METHOD_ACCESSIBILITY_CONNECTED = "accessibility_connected";
    public static final String METHOD_ACCESSIBILITY_PASTE = "accessibility_paste";
    public static final String METHOD_IME_READY = "ime_ready";
    public static final String METHOD_IME_PASTE = "ime_paste";
    public static final String METHOD_IME_RETAIN_INPUT_CONNECTION =
            "ime_retain_input_connection";
    public static final String METHOD_IME_PASTE_RETAINED = "ime_paste_retained";
    public static final String METHOD_SET_JETPACK_COMPOSE_FIELD_MODE =
            "set_jetpack_compose_field_mode";

    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_HTML = "html";
    public static final String EXTRA_MODE = "mode";
    public static final String TEXT_CLIP_LABEL = "secure-paste-label";
    public static final String INTENT_CLIP_ACTION = "grapheneos.securepaste.intent.CLIP";
    public static final String RESULT_OK = "ok";
    public static final String RESULT_TEXT = "text";
    public static final String RESULT_EXCEPTION = "exception";
    public static final String RESULT_HAS_CLIP = "hasClip";
    public static final String RESULT_HAS_TEXT = "hasText";
    public static final String RESULT_DESCRIPTION = "description";
    public static final String RESULT_MIME_TYPES = "mimeTypes";
    public static final String RESULT_TIMESTAMP = "timestamp";
    public static final String RESULT_COUNT = "count";
    public static final String RESULT_HTML = "html";
    public static final String RESULT_URI = "uri";
    public static final String RESULT_SOURCE = "source";
    public static final String RESULT_IS_STYLED_TEXT = "isStyledText";
    public static final String RESULT_HAS_STRIKETHROUGH_SPAN = "hasStrikethroughSpan";
    public static final String RESULT_DRAG_START_RESULT = "dragStartResult";
    public static final String RESULT_DRAG_STARTED_HAS_CLIP_DATA = "dragStartedHasClipData";
    public static final String RESULT_DRAG_STARTED_TEXT = "dragStartedText";
    public static final String RESULT_DRAG_STARTED_EXTRA_VALUE = "dragStartedExtraValue";
    public static final String RESULT_DRAG_DROPPED = "dragDropped";
    public static final String RESULT_DRAG_DROP_HAS_CLIP_DATA = "dragDropHasClipData";
    public static final String RESULT_DRAG_DROP_TEXT = "dragDropText";
    public static final String RESULT_DRAG_ENDED = "dragEnded";
    public static final String RESULT_DRAG_DROP_RESULT = "dragDropResult";

    private static final AtomicInteger sListenerCount = new AtomicInteger();
    private static ClipboardManager.OnPrimaryClipChangedListener sListener;
    private static volatile String sCachedText;
    private static volatile boolean sAccessibilityConnected;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        final Bundle result = new Bundle();
        final Context context = requireContext();
        try {
            switch (method) {
                case METHOD_SET_CLIP_TEXT:
                    setTextClip(context, getString(extras, EXTRA_TEXT, "secure-paste-text"));
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_SET_CLIP_HTML:
                    setHtmlClip(context, getString(extras, EXTRA_TEXT, "secure-paste-text"),
                            getString(extras, EXTRA_HTML, "<b>secure-paste-text</b>"));
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_SET_CLIP_STYLED_TEXT:
                    setStyledTextClip(context,
                            getString(extras, EXTRA_TEXT, "secure-paste-styled-text"));
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_SET_CLIP_INTENT:
                    setIntentClip(context);
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_SET_CLIP_URI:
                    setUriClip(context, getString(extras, EXTRA_TEXT, "secure-paste-uri-text"));
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_SET_CLIP_URI_ONLY:
                    setUriOnlyClip(context);
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_SET_CLIP_COMPLEX_ITEM:
                    setComplexItemClip(context,
                            getString(extras, EXTRA_TEXT, "secure-paste-complex-text"));
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_SET_CLIP_MULTIPLE_TEXT_ITEMS:
                    setMultipleTextItemsClip(context);
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_SET_CLIP_UNSUPPORTED_MIME_TYPE:
                    setUnsupportedMimeTypeClip(context);
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_CLEAR_CLIP:
                    clipboard(context).clearPrimaryClip();
                    sCachedText = null;
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_READ_CLIP:
                    readClip(context, result);
                    break;
                case METHOD_READ_OWN_CLIP:
                    setTextClip(context, getString(extras, EXTRA_TEXT, "secure-paste-own-text"));
                    readClip(context, result);
                    break;
                case METHOD_REGISTER_LISTENER:
                    registerListener(context);
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_RESET_LISTENER:
                    sListenerCount.set(0);
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_GET_LISTENER_COUNT:
                    result.putInt(RESULT_COUNT, sListenerCount.get());
                    break;
                case METHOD_CACHE_CLIP:
                    sCachedText = readClipboardText(context);
                    result.putString(RESULT_TEXT, sCachedText);
                    result.putBoolean(RESULT_OK, sCachedText != null);
                    break;
                case METHOD_ACTIVITY_READY:
                    result.putBoolean(RESULT_OK, isActivityReady(context));
                    break;
                case METHOD_GET_EDITOR_TEXT:
                    result.putString(RESULT_TEXT, getEditorText(context));
                    result.putBoolean(RESULT_HAS_STRIKETHROUGH_SPAN,
                            SecurePasteActivity.editorHasStrikethroughSpan());
                    result.putBoolean(RESULT_OK, isActivityReady(context));
                    break;
                case METHOD_ENABLE_RECORDING_CONTENT_RECEIVER:
                    result.putBoolean(RESULT_OK,
                            SecurePasteActivity.enableRecordingContentReceiver());
                    break;
                case METHOD_GET_RECEIVED_CONTENT:
                    readReceivedContent(result);
                    break;
                case METHOD_REQUEST_FOCUS:
                    result.putBoolean(RESULT_OK, requestEditorFocus(context));
                    break;
                case METHOD_CLEAR_FOCUS:
                    result.putBoolean(RESULT_OK, clearEditorFocus(context));
                    break;
                case METHOD_CACHED_PASTE:
                    result.putBoolean(RESULT_OK, SecurePasteActivity.pasteText(sCachedText));
                    result.putString(RESULT_TEXT, SecurePasteActivity.getEditorText());
                    break;
                case METHOD_READ_WRITER_URI:
                    readUri(context, SecurePasteUriProvider.getUri(WRITER_PACKAGE), result);
                    break;
                case METHOD_READ_WRITER_INTENT_URI:
                    readUri(context, SecurePasteUriProvider.getIntentUri(WRITER_PACKAGE), result);
                    break;
                case METHOD_RESET_DRAG_RESULT:
                    SecurePasteActivity.resetDragResult();
                    result.putBoolean(RESULT_OK, true);
                    break;
                case METHOD_GET_DRAG_RESULT:
                    readDragResult(result);
                    break;
                case METHOD_SET_JETPACK_COMPOSE_FIELD_MODE:
                    result.putBoolean(RESULT_OK, setJetpackComposeFieldMode(context,
                            getString(extras, EXTRA_MODE, "value")));
                    break;
                case METHOD_ACCESSIBILITY_CONNECTED:
                    result.putBoolean(RESULT_OK, sAccessibilityConnected);
                    break;
                case METHOD_ACCESSIBILITY_PASTE:
                    result.putBoolean(RESULT_OK,
                            SecurePasteAccessibilityServiceBase.performPasteOnFocusedNode());
                    break;
                case METHOD_IME_READY:
                    result.putBoolean(RESULT_OK, invokeImeBoolean("isReady"));
                    break;
                case METHOD_IME_PASTE:
                    result.putBoolean(RESULT_OK, invokeImeBoolean("requestPaste"));
                    break;
                case METHOD_IME_RETAIN_INPUT_CONNECTION:
                    result.putBoolean(RESULT_OK,
                            invokeImeBoolean("retainCurrentInputConnection"));
                    break;
                case METHOD_IME_PASTE_RETAINED:
                    result.putBoolean(RESULT_OK,
                            invokeImeBoolean("requestPasteFromRetainedInputConnection"));
                    break;
                default:
                    result.putBoolean(RESULT_OK, false);
                    result.putString(RESULT_EXCEPTION, "Unknown method " + method);
            }
        } catch (Throwable t) {
            result.putBoolean(RESULT_OK, false);
            result.putString(RESULT_EXCEPTION, t.toString());
        }
        return result;
    }

    public static void setAccessibilityConnected(boolean connected) {
        sAccessibilityConnected = connected;
    }

    public static String readClipboardText(Context context) {
        final ClipData clip = clipboard(context).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        final CharSequence text = clip.getItemAt(0).coerceToText(context);
        return text == null ? null : text.toString();
    }

    private static boolean isActivityReady(Context context) throws Exception {
        if (isJetpackComposeApp(context)) {
            return (Boolean) invokeJetpackCompose("isReady");
        }
        return SecurePasteActivity.isReady();
    }

    private static String getEditorText(Context context) throws Exception {
        if (isJetpackComposeApp(context)) {
            return (String) invokeJetpackCompose("getEditorText");
        }
        return SecurePasteActivity.getEditorText();
    }

    private static boolean requestEditorFocus(Context context) throws Exception {
        if (isJetpackComposeApp(context)) {
            return (Boolean) invokeJetpackCompose("requestEditorFocus");
        }
        return SecurePasteActivity.requestEditorFocus();
    }

    private static boolean clearEditorFocus(Context context) throws Exception {
        if (isJetpackComposeApp(context)) {
            return (Boolean) invokeJetpackCompose("clearEditorFocus");
        }
        return SecurePasteActivity.clearEditorFocus();
    }

    private static boolean setJetpackComposeFieldMode(Context context, String mode)
            throws Exception {
        if (!isJetpackComposeApp(context)) {
            return false;
        }
        return (Boolean) invokeJetpackCompose("setFieldMode",
                new Class<?>[] {String.class}, mode);
    }

    private static boolean isJetpackComposeApp(Context context) {
        return JETPACK_COMPOSE_PACKAGE.equals(context.getPackageName());
    }

    private static Object invokeJetpackCompose(String methodName) throws Exception {
        return invokeJetpackCompose(methodName, new Class<?>[0]);
    }

    private static Object invokeJetpackCompose(String methodName, Class<?>[] parameterTypes,
            Object... args) throws Exception {
        final Class<?> cls = Class.forName(JETPACK_COMPOSE_ACTIVITY);
        final Method method = cls.getDeclaredMethod(methodName, parameterTypes);
        return method.invoke(null, args);
    }

    private static void setTextClip(Context context, String text) {
        clipboard(context).setPrimaryClip(ClipData.newPlainText(TEXT_CLIP_LABEL, text));
    }

    private static void setHtmlClip(Context context, String text, String html) {
        clipboard(context).setPrimaryClip(ClipData.newHtmlText(
                "secure-paste-html-label", text, html));
    }

    private static void setStyledTextClip(Context context, String text) {
        // Shape from cts/tests/tests/widget/src/android/widget/cts/TextViewReceiveContentTest.java:
        // CtsWidgetTestCases:TextViewReceiveContentTest#testDefaultReceiver_onReceive_styledText.
        // Use StrikethroughSpan instead of CTS's UnderlineSpan because an active input method can
        // add UnderlineSpan to composing text after paste.
        final SpannableString styledText = new SpannableString(text);
        styledText.setSpan(new StrikethroughSpan(), 0, styledText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        clipboard(context).setPrimaryClip(ClipData.newPlainText(
                "secure-paste-styled-label", styledText));
    }

    private static void setIntentClip(Context context) {
        // Shape from cts/tests/tests/content/src/android/content/cts/ClipboardManagerTest.java:
        // CtsContentTestCases:ClipboardManagerTest#testSetPrimaryClip_intent.
        clipboard(context).setPrimaryClip(ClipData.newIntent(
                "secure-paste-intent-label", new Intent(INTENT_CLIP_ACTION)));
    }

    private static void setUriClip(Context context, String text) {
        final Uri uri = SecurePasteUriProvider.getUri(context.getPackageName());
        final ClipData clip = new ClipData("secure-paste-uri-label",
                new String[] {ClipDescription.MIMETYPE_TEXT_PLAIN, "text/uri-list"},
                new ClipData.Item(text, null, uri));
        clipboard(context).setPrimaryClip(clip);
    }

    private static void setUriOnlyClip(Context context) {
        // Shape from cts/tests/tests/content/src/android/content/cts/ClipboardManagerTest.java:
        // CtsContentTestCases:ClipboardManagerTest#testSetPrimaryClip_rawUri. A content URI also
        // exercises the
        // temporary URI permission needed to coerce the item to text during Paste.
        final Uri uri = SecurePasteUriProvider.getUri(context.getPackageName());
        clipboard(context).setPrimaryClip(ClipData.newRawUri(
                "secure-paste-uri-only-label", uri));
    }

    private static void setComplexItemClip(Context context, String text) {
        // Shape from cts/tests/tests/content/src/android/content/cts/ClipboardManagerTest.java:
        // CtsContentTestCases:ClipboardManagerTest#testSetPrimaryClip_complexItem.
        // The Intent data uses a second content URI to cover both clipboard URI grant branches.
        final Uri uri = SecurePasteUriProvider.getUri(context.getPackageName());
        final Intent intent = new Intent(INTENT_CLIP_ACTION)
                .setData(SecurePasteUriProvider.getIntentUri(context.getPackageName()));
        final ClipDescription description = new ClipDescription("secure-paste-complex-label",
                new String[] {
                        ClipDescription.MIMETYPE_TEXT_PLAIN,
                        ClipDescription.MIMETYPE_TEXT_INTENT,
                        ClipDescription.MIMETYPE_TEXT_URILIST,
                });
        clipboard(context).setPrimaryClip(new ClipData(
                description, new ClipData.Item(text, intent, uri)));
    }

    private static void setMultipleTextItemsClip(Context context) {
        // Shape from cts/tests/tests/widget/src/android/widget/cts/TextViewReceiveContentTest.java:
        // CtsWidgetTestCases:TextViewReceiveContentTest#testDefaultReceiver_onReceive_multipleItemsInClipData.
        final ClipData clip = ClipData.newPlainText("secure-paste-multiple-label", "ONE");
        clip.addItem(new ClipData.Item("TWO"));
        clip.addItem(new ClipData.Item("THREE"));
        clipboard(context).setPrimaryClip(clip);
    }

    private static void setUnsupportedMimeTypeClip(Context context) {
        // Shape from cts/tests/tests/widget/src/android/widget/cts/TextViewReceiveContentTest.java:
        // CtsWidgetTestCases:TextViewReceiveContentTest#testPaste_customReceiver_unsupportedMimeType.
        final ClipData clip = new ClipData("secure-paste-unsupported-label",
                new String[] {"video/mp4"},
                new ClipData.Item("text", "html", null,
                        SecurePasteUriProvider.getUri(context.getPackageName())));
        clipboard(context).setPrimaryClip(clip);
    }

    private static void readClip(Context context, Bundle result) {
        try {
            final ClipboardManager clipboard = clipboard(context);
            final ClipData clip = clipboard.getPrimaryClip();
            result.putBoolean(RESULT_OK, clip != null);
            result.putBoolean(RESULT_HAS_CLIP, clipboard.hasPrimaryClip());
            result.putBoolean(RESULT_HAS_TEXT, clipboard.hasText());
            final ClipDescription description = clipboard.getPrimaryClipDescription();
            if (description != null) {
                final CharSequence label = description.getLabel();
                result.putString(RESULT_DESCRIPTION, label == null ? null : label.toString());
                result.putStringArray(RESULT_MIME_TYPES, mimeTypes(description));
                result.putLong(RESULT_TIMESTAMP, description.getTimestamp());
                result.putBoolean(RESULT_IS_STYLED_TEXT, description.isStyledText());
            }
            if (clip != null && clip.getItemCount() > 0) {
                final CharSequence text = clip.getItemAt(0).coerceToText(context);
                result.putString(RESULT_TEXT, text == null ? null : text.toString());
                result.putInt(RESULT_COUNT, clip.getItemCount());
            }
        } catch (Throwable t) {
            result.putBoolean(RESULT_OK, false);
            result.putString(RESULT_EXCEPTION, t.toString());
        }
    }

    private static void readUri(Context context, Uri uri, Bundle result) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                result.putBoolean(RESULT_OK, false);
                return;
            }
            result.putString(RESULT_TEXT,
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            result.putBoolean(RESULT_OK, true);
        }
    }

    private static void readReceivedContent(Bundle result) {
        final ContentInfo content = SecurePasteActivity.getReceivedContent();
        if (content == null) {
            result.putBoolean(RESULT_OK, false);
            return;
        }

        final ClipData clip = content.getClip();
        final ClipDescription description = clip.getDescription();
        result.putBoolean(RESULT_OK, true);
        result.putInt(RESULT_SOURCE, content.getSource());
        result.putInt(RESULT_COUNT, clip.getItemCount());
        result.putStringArray(RESULT_MIME_TYPES, mimeTypes(description));
        if (clip.getItemCount() > 0) {
            final ClipData.Item item = clip.getItemAt(0);
            final CharSequence text = item.getText();
            result.putString(RESULT_TEXT, text == null ? null : text.toString());
            result.putString(RESULT_HTML, item.getHtmlText());
            final Uri uri = item.getUri();
            result.putString(RESULT_URI, uri == null ? null : uri.toString());
        }
    }

    private static String[] mimeTypes(ClipDescription description) {
        final String[] mimeTypes = new String[description.getMimeTypeCount()];
        for (int i = 0; i < description.getMimeTypeCount(); i++) {
            mimeTypes[i] = description.getMimeType(i);
        }
        return mimeTypes;
    }

    private static void readDragResult(Bundle result) {
        final SecurePasteActivity.DragResult dragResult = SecurePasteActivity.getDragResult();
        result.putBoolean(RESULT_OK, dragResult.started);
        result.putBoolean(RESULT_DRAG_START_RESULT, dragResult.startDragResult);
        result.putBoolean(RESULT_DRAG_STARTED_HAS_CLIP_DATA,
                dragResult.startedHasClipData);
        result.putString(RESULT_DRAG_STARTED_TEXT, dragResult.startedText);
        result.putString(RESULT_DESCRIPTION, dragResult.startedLabel);
        result.putStringArray(RESULT_MIME_TYPES, dragResult.startedMimeTypes);
        result.putString(RESULT_DRAG_STARTED_EXTRA_VALUE, dragResult.startedExtraValue);
        result.putBoolean(RESULT_DRAG_DROPPED, dragResult.dropped);
        result.putBoolean(RESULT_DRAG_DROP_HAS_CLIP_DATA, dragResult.dropHasClipData);
        result.putString(RESULT_DRAG_DROP_TEXT, dragResult.dropText);
        result.putBoolean(RESULT_DRAG_ENDED, dragResult.ended);
        result.putBoolean(RESULT_DRAG_DROP_RESULT, dragResult.dropResult);
    }

    private static void registerListener(Context context) {
        if (sListener != null) {
            return;
        }
        sListener = () -> {
            sCachedText = null;
            sListenerCount.incrementAndGet();
        };
        clipboard(context).addPrimaryClipChangedListener(sListener);
    }

    private static ClipboardManager clipboard(Context context) {
        return context.getSystemService(ClipboardManager.class);
    }

    private static String getString(Bundle extras, String key, String fallback) {
        if (extras == null) {
            return fallback;
        }
        final String value = extras.getString(key);
        return value == null ? fallback : value;
    }

    private static boolean invokeImeBoolean(String methodName) throws Exception {
        final Class<?> cls = Class.forName("grapheneos.securepaste.ime.SecurePasteImeService");
        final Method method = cls.getDeclaredMethod(methodName);
        return (Boolean) method.invoke(null);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
