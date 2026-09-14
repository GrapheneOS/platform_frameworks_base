package grapheneos.securepaste;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.os.Bundle;
import android.view.ContentInfo;

import org.junit.Test;

import grapheneos.securepaste.helper.SecurePasteCommandProvider;
import grapheneos.securepaste.helper.SecurePasteUriProvider;

public class SecurePasteDeviceTest extends SecurePasteTestBase {
    private static final String HTML_FALLBACK_TEXT = "*secure paste html*";
    private static final String HTML_RENDERED_TEXT = "secure paste html";
    private static final String HTML_MARKUP = "<b>secure paste html</b>";
    private static final String STYLED_TEXT = "secure paste styled text";
    private static final String COMPLEX_ITEM_TEXT = "secure paste complex item";
    private static final String MULTIPLE_ITEMS_TEXT = "ONE\nTWO\nTHREE";

    @Test
    public void blockedPackage_cannotReadForeignTextClip() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);

        assertDirectReadDenied(READER);
    }

    @Test
    public void allowedPackage_canReadForeignTextClip() {
        writerSetsText(TEXT_A);
        allowPackageClipboardRead(READER);
        launchHelperActivity(READER);

        assertDirectReadAllowed(READER, TEXT_A);
    }

    @Test
    public void defaultBlockedByGlobalPolicy_cannotReadForeignClip() {
        writerSetsText(TEXT_A);
        setGlobalClipboardDefault(false);
        resetPackageClipboardPolicy(READER);
        launchHelperActivity(READER);

        assertDirectReadDenied(READER);
    }

    @Test
    public void blockedClipOwner_canReadOwnClip() {
        blockPackageClipboardRead(WRITER);
        launchHelperActivity(WRITER);
        final Bundle extras = new Bundle();
        extras.putString(SecurePasteCommandProvider.EXTRA_TEXT, TEXT_A);

        final Bundle result = call(WRITER,
                SecurePasteCommandProvider.METHOD_READ_OWN_CLIP, extras);
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_EXCEPTION)).isNull();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_TEXT)).contains(TEXT_A);
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_DESCRIPTION))
                .isEqualTo(SecurePasteCommandProvider.TEXT_CLIP_LABEL);
    }

    @Test
    public void blockedPackage_canReadClipFromSharedUid() {
        blockPackageClipboardRead(SHARED_A);
        blockPackageClipboardRead(SHARED_B);
        packageSetsText(SHARED_A, TEXT_A);
        launchHelperActivity(SHARED_B);

        assertDirectReadAllowed(SHARED_B, TEXT_A);
    }

    @Test
    public void blockedPackage_clipChangeInvalidatesCachedPayload() throws Exception {
        packageSetsText(WRITER, "");
        allowPackageClipboardRead(CACHE_CLIENT);
        launchHelperActivity(CACHE_CLIENT);
        final Bundle cached = call(CACHE_CLIENT, SecurePasteCommandProvider.METHOD_CACHE_CLIP);
        assertThat(cached.getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        assertThat(cached.getString(SecurePasteCommandProvider.RESULT_TEXT)).isEmpty();
        call(CACHE_CLIENT, SecurePasteCommandProvider.METHOD_REGISTER_LISTENER);
        call(CACHE_CLIENT, SecurePasteCommandProvider.METHOD_RESET_LISTENER);

        blockPackageClipboardRead(CACHE_CLIENT);
        writerSetsText(TEXT_B);
        final long deadline = System.currentTimeMillis() + 5_000;
        int listenerCount = 0;
        while (listenerCount == 0 && System.currentTimeMillis() < deadline) {
            listenerCount = call(CACHE_CLIENT,
                    SecurePasteCommandProvider.METHOD_GET_LISTENER_COUNT)
                    .getInt(SecurePasteCommandProvider.RESULT_COUNT);
            if (listenerCount == 0) {
                sleep(100);
            }
        }

        assertThat(listenerCount).isGreaterThan(0);
        assertDirectReadDenied(CACHE_CLIENT);
        assertThat(call(CACHE_CLIENT, SecurePasteCommandProvider.METHOD_CACHED_PASTE)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isFalse();

        systemToolbarPasteInto(CACHE_CLIENT);

        waitForEditorText(CACHE_CLIENT, TEXT_B);
        assertThat(getEditorText(CACHE_CLIENT)).isEqualTo(TEXT_B);
    }

    @Test
    public void blockedPackage_systemToolbarPasteIntoEditTextSucceeds() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);

        systemToolbarPasteInto(READER);

        waitForEditorText(READER, TEXT_A);
    }

    @Test
    public void blockedPackage_htmlPasteUsesStyledRepresentation() throws Exception {
        // Shape from cts/tests/tests/widget/src/android/widget/cts/TextViewReceiveContentTest.java:
        // CtsWidgetTestCases:TextViewReceiveContentTest#testDefaultReceiver_onReceive_html.
        writerSetsHtml(HTML_FALLBACK_TEXT, HTML_MARKUP);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        assertDirectReadDenied(READER);

        systemToolbarPasteInto(READER);

        waitForEditorText(READER, HTML_RENDERED_TEXT);
    }

    @Test
    public void blockedPackage_htmlPasteAsPlainTextUsesFallback() throws Exception {
        // Shape from cts/tests/tests/widget/src/android/widget/cts/TextViewReceiveContentTest.java:
        // CtsWidgetTestCases:TextViewReceiveContentTest#testDefaultReceiver_onReceive_html_convertToPlainText.
        // TextView.canPasteAsPlainText() needs its text/html MIME type to offer the action.
        writerSetsHtml(HTML_FALLBACK_TEXT, HTML_MARKUP);
        blockPackageClipboardRead(READER);

        systemToolbarPasteAsPlainTextInto(READER);

        waitForEditorText(READER, HTML_FALLBACK_TEXT);
    }

    @Test
    public void blockedPackage_styledTextPastePreservesSpan() throws Exception {
        writerSetsStyledText(STYLED_TEXT);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        final Bundle deniedRead = readClipboard(READER);
        assertDirectReadDenied(deniedRead);
        assertThat(deniedRead.getBoolean(SecurePasteCommandProvider.RESULT_IS_STYLED_TEXT))
                .isTrue();

        systemToolbarPasteInto(READER);

        waitForEditorText(READER, STYLED_TEXT);
        assertThat(call(READER, SecurePasteCommandProvider.METHOD_GET_EDITOR_TEXT)
                .getBoolean(SecurePasteCommandProvider.RESULT_HAS_STRIKETHROUGH_SPAN)).isTrue();
    }

    @Test
    public void blockedPackage_styledTextPasteAsPlainTextRemovesSpan() throws Exception {
        // Shape from cts/tests/tests/widget/src/android/widget/cts/TextViewReceiveContentTest.java:
        // CtsWidgetTestCases:TextViewReceiveContentTest#testDefaultReceiver_onReceive_styledText_convertToPlainText.
        // TextView.canPasteAsPlainText() needs its styled-text bit to offer the action.
        writerSetsStyledText(STYLED_TEXT);
        blockPackageClipboardRead(READER);

        systemToolbarPasteAsPlainTextInto(READER);

        waitForEditorText(READER, STYLED_TEXT);
        assertThat(call(READER, SecurePasteCommandProvider.METHOD_GET_EDITOR_TEXT)
                .getBoolean(SecurePasteCommandProvider.RESULT_HAS_STRIKETHROUGH_SPAN)).isFalse();
    }

    @Test
    public void blockedPackage_intentClipPasteSucceeds() throws Exception {
        writerSetsIntentClip();
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        assertDirectReadDenied(READER);
        final String expected = new Intent(SecurePasteCommandProvider.INTENT_CLIP_ACTION)
                .toUri(Intent.URI_INTENT_SCHEME);

        systemToolbarPasteInto(READER);

        waitForEditorText(READER, expected);
    }

    @Test
    public void blockedPackage_uriOnlyClipPasteGrantsUri() throws Exception {
        writerSetsUriOnlyClip();
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        assertDirectReadDenied(READER);
        assertUriReadDenied(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);

        systemToolbarPasteInto(READER);

        waitForEditorText(READER, SecurePasteUriProvider.DATA);
        assertUriReadAllowed(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
        assertUriReadDenied(EDIT_TEXT, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
    }

    @Test
    public void blockedPackage_complexItemPasteGrantsBothUris() throws Exception {
        writerSetsComplexItemClip(COMPLEX_ITEM_TEXT);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        assertDirectReadDenied(READER);
        assertUriReadDenied(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
        assertUriReadDenied(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_INTENT_URI);

        systemToolbarPasteInto(READER);

        waitForEditorText(READER, COMPLEX_ITEM_TEXT);
        assertUriReadAllowed(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
        assertUriReadAllowed(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_INTENT_URI);
        assertUriReadDenied(EDIT_TEXT, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
        assertUriReadDenied(EDIT_TEXT,
                SecurePasteCommandProvider.METHOD_READ_WRITER_INTENT_URI);

        writerSetsText(TEXT_A);

        assertUriReadDenied(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
        assertUriReadDenied(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_INTENT_URI);
    }

    @Test
    public void blockedPackage_multipleTextItemsPasteAllItems() throws Exception {
        writerSetsMultipleTextItemsClip();
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        assertDirectReadDenied(READER);

        systemToolbarPasteInto(READER);

        waitForEditorText(READER, MULTIPLE_ITEMS_TEXT);
    }

    @Test
    public void blockedPackage_unsupportedMimeTypePasteReachesCustomReceiver() throws Exception {
        writerSetsUnsupportedMimeTypeClip();
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        assertDirectReadDenied(READER);
        assertThat(call(READER,
                SecurePasteCommandProvider.METHOD_ENABLE_RECORDING_CONTENT_RECEIVER)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();

        showSystemToolbarForFocusedEditor(READER);
        clickFloatingToolbarItemOrFail(getPasteLabel());

        final Bundle received = call(
                READER, SecurePasteCommandProvider.METHOD_GET_RECEIVED_CONTENT);
        assertThat(received.getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        assertThat(received.getInt(SecurePasteCommandProvider.RESULT_SOURCE))
                .isEqualTo(ContentInfo.SOURCE_CLIPBOARD);
        assertThat(received.getInt(SecurePasteCommandProvider.RESULT_COUNT)).isEqualTo(1);
        assertThat(received.getStringArray(SecurePasteCommandProvider.RESULT_MIME_TYPES))
                .asList().containsExactly("video/mp4");
        assertThat(received.getString(SecurePasteCommandProvider.RESULT_TEXT)).isEqualTo("text");
        assertThat(received.getString(SecurePasteCommandProvider.RESULT_HTML)).isEqualTo("html");
        assertThat(received.getString(SecurePasteCommandProvider.RESULT_URI))
                .isEqualTo(SecurePasteUriProvider.getUri(WRITER).toString());
        assertUriReadAllowed(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
        assertUriReadDenied(EDIT_TEXT, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
    }

    @Test
    public void blockedPackage_emptyTextClipPreservesClipboardPresence() {
        // Shape from cts/tests/tests/text/src/android/text/cts/ClipboardManagerTest.java:
        // CtsTextTestCases:ClipboardManagerTest#testHasText.
        writerSetsText("");
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);

        assertDirectReadDenied(READER);
    }

    @Test
    public void blockedPackage_toolbarGrantExpires() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);

        systemToolbarPasteInto(READER);
        waitForEditorText(READER, TEXT_A);
        sleep(PASTE_GRANT_EXPIRY_WAIT_MILLIS);

        assertDirectReadDenied(READER);
    }

    @Test
    public void blockedPackage_noToolbarAction_noTemporaryGrant() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        focusEditor();
        longClickEditor();

        assertDirectReadDenied(READER);
    }

    @Test
    public void blockedPackage_keyboardPastePolicy() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        focusEditor();

        sendCtrlV();

        waitForEditorText(READER, TEXT_A);
    }

    @Test
    public void blockedPackage_toolbarPasteUriClipGrantsOnlyPasteTarget() throws Exception {
        writerSetsUriClip(URI_TEXT);
        blockPackageClipboardRead(READER);

        systemToolbarPasteInto(READER);

        waitForEditorText(READER, URI_TEXT);
        assertUriReadAllowed(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
        assertUriReadDenied(EDIT_TEXT, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
    }

    @Test
    public void blockedPackage_pastedUriOutlivesClipboardLease() throws Exception {
        writerSetsUriClip(URI_TEXT);
        blockPackageClipboardRead(READER);
        assertUriReadDenied(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);

        systemToolbarPasteInto(READER);
        waitForEditorText(READER, URI_TEXT);
        sleep(PASTE_GRANT_EXPIRY_WAIT_MILLIS);

        assertDirectReadDenied(READER);
        assertUriReadAllowed(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);

        writerSetsText(TEXT_A);

        assertUriReadDenied(READER, SecurePasteCommandProvider.METHOD_READ_WRITER_URI);
    }

    private void assertUriReadAllowed(String pkg, String method) {
        final Bundle result = call(pkg, method);
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_TEXT))
                .isEqualTo(SecurePasteUriProvider.DATA);
    }

    private void assertUriReadDenied(String pkg, String method) {
        assertThat(call(pkg, method)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isFalse();
    }
}
