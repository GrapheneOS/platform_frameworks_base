package grapheneos.securepaste;

import static com.google.common.truth.Truth.assertThat;

import grapheneos.securepaste.helper.SecurePasteCommandProvider;

import org.junit.Test;

public class SecurePasteAccessibilityTest extends SecurePasteTestBase {
    @Test
    public void blockedPackage_accessibilityActionPasteSucceeds() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        enableAccessibilityService();

        assertThat(call(TEST_PKG, SecurePasteCommandProvider.METHOD_ACCESSIBILITY_PASTE)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();

        waitForEditorText(READER, TEXT_A);
    }

    @Test
    public void blockedPackage_accessibilityPasteDoesNotEnableDirectRead() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        enableAccessibilityService();

        assertThat(call(TEST_PKG, SecurePasteCommandProvider.METHOD_ACCESSIBILITY_PASTE)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        waitForEditorText(READER, TEXT_A);
        sleep(PASTE_GRANT_EXPIRY_WAIT_MILLIS);

        assertDirectReadDenied(READER);
    }

    @Test
    public void accessibilityPaste_withoutFocusedEditorDenied() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        call(READER, SecurePasteCommandProvider.METHOD_CLEAR_FOCUS);
        mDevice.pressHome();
        enableAccessibilityService();

        assertThat(call(TEST_PKG, SecurePasteCommandProvider.METHOD_ACCESSIBILITY_PASTE)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isFalse();
    }

    @Test
    public void accessibilityPaste_usesFocusedWindowInsteadOfStaleEditorInfo() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        blockPackageClipboardRead(EDIT_TEXT);
        launchHelperActivity(READER);
        enableAccessibilityService();
        launchHelperActivity(EDIT_TEXT);

        assertThat(call(TEST_PKG, SecurePasteCommandProvider.METHOD_ACCESSIBILITY_PASTE)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        waitForEditorText(EDIT_TEXT, TEXT_A);
    }
}
