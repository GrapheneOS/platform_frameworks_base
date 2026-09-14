package grapheneos.securepaste;

import static com.google.common.truth.Truth.assertThat;

import android.content.ClipDescription;
import android.os.Bundle;

import grapheneos.securepaste.helper.SecurePasteActivity;
import grapheneos.securepaste.helper.SecurePasteCommandProvider;

import org.junit.Test;

import java.util.Arrays;

public class SecurePasteCompatibilityTest extends SecurePasteTestBase {
    @Test
    public void blockedPackage_imePastePolicy() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        enableSecurePasteIme();

        assertThat(call(IME, SecurePasteCommandProvider.METHOD_IME_PASTE)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();

        waitForEditorText(READER, TEXT_A);
    }

    @Test
    public void blockedPackage_defaultImeDoesNotBecomeBroadBypass() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        enableSecurePasteIme();

        assertThat(call(IME, SecurePasteCommandProvider.METHOD_IME_PASTE)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        waitForEditorText(READER, TEXT_A);
        sleep(PASTE_GRANT_EXPIRY_WAIT_MILLIS);

        assertDirectReadDenied(READER);
    }

    @Test
    public void staleImeInputConnectionDoesNotGrantCurrentApp() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        blockPackageClipboardRead(EDIT_TEXT);
        enableSecurePasteIme();
        waitForProviderResult(IME,
                SecurePasteCommandProvider.METHOD_IME_RETAIN_INPUT_CONNECTION);

        launchHelperActivity(EDIT_TEXT);
        waitForProviderResult(IME,
                SecurePasteCommandProvider.METHOD_IME_PASTE_RETAINED);

        assertDirectReadDenied(EDIT_TEXT);
    }

    @Test
    public void blockedPackage_customToolbarNonFrameworkPasteLabelDenied() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(CUSTOM_TOOLBAR);
        launchHelperActivity(CUSTOM_TOOLBAR);
        focusEditor();
        longClickEditor();

        clickFloatingToolbarItemOrFail("Non-framework paste");

        assertThat(getEditorText(CUSTOM_TOOLBAR)).doesNotContain(TEXT_A);
        assertDirectReadDenied(CUSTOM_TOOLBAR);
    }

    @Test
    public void systemSelectionToolbarFeatureFlagEnabled() {
        assertThat(systemSelectionToolbarFlagEnabled()).isTrue();
    }

    @Test
    public void blockedPackage_customToolbarOverflowPasteTitleGrants() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(CUSTOM_TOOLBAR);
        launchHelperActivity(CUSTOM_TOOLBAR);
        focusEditor();
        longClickEditor();

        clickFloatingToolbarOverflowItemOrFail(getPasteLabel());

        waitForEditorText(CUSTOM_TOOLBAR, TEXT_A);
        sleep(PASTE_GRANT_EXPIRY_WAIT_MILLIS);
        assertDirectReadDenied(CUSTOM_TOOLBAR);
    }

    @Test
    public void sharedUidPendingGrantScope() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(SHARED_A);
        blockPackageClipboardRead(SHARED_B);

        launchHelperActivity(SHARED_B);
        launchHelperActivity(SHARED_A);
        focusEditor();
        longClickEditor();

        assertThat(clickFloatingToolbarItem(getPasteLabel())).isTrue();

        assertDirectReadAllowed(SHARED_B, TEXT_A);
        waitForEditorText(SHARED_A, TEXT_A);
    }

    @Test
    public void defaultIme_immutableClipboardAllowIsExplicit() {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(IME);
        enableSecurePasteIme();

        assertDirectReadAllowed(IME, TEXT_A);
    }

    @Test
    public void systemAppImmutableAllowIsExplicit() {
        assertSystemAppPreinstalled(SYSTEM);
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(SYSTEM);
        launchHelperActivity(SYSTEM);

        assertDirectReadAllowed(SYSTEM, TEXT_A);
    }

    @Test
    public void dragStartedMetadataPolicy() throws Exception {
        blockPackageClipboardRead(WRITER);
        launchHelperActivity(WRITER);
        assertThat(call(WRITER, SecurePasteCommandProvider.METHOD_RESET_DRAG_RESULT)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();

        dragByDescription(SecurePasteActivity.DRAG_SOURCE_DESCRIPTION,
                SecurePasteActivity.DRAG_TARGET_DESCRIPTION);

        final Bundle result = waitForDragResult();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_DRAG_START_RESULT))
                .isTrue();

        // ACTION_DRAG_STARTED carries ClipDescription metadata for compatibility, but not the
        // ClipData payload. The payload is only delivered to the user-selected ACTION_DROP target.
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_DESCRIPTION))
                .isEqualTo(SecurePasteActivity.DRAG_LABEL);
        final String[] mimeTypes = result.getStringArray(
                SecurePasteCommandProvider.RESULT_MIME_TYPES);
        assertThat(mimeTypes).isNotNull();
        assertThat(Arrays.asList(mimeTypes)).contains(ClipDescription.MIMETYPE_TEXT_PLAIN);
        assertThat(result.getString(
                SecurePasteCommandProvider.RESULT_DRAG_STARTED_EXTRA_VALUE))
                .isEqualTo(SecurePasteActivity.DRAG_EXTRA_VALUE);
        assertThat(result.getBoolean(
                SecurePasteCommandProvider.RESULT_DRAG_STARTED_HAS_CLIP_DATA)).isFalse();
        assertThat(result.getString(
                SecurePasteCommandProvider.RESULT_DRAG_STARTED_TEXT)).isNull();

        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_DRAG_DROPPED)).isTrue();
        assertThat(result.getBoolean(
                SecurePasteCommandProvider.RESULT_DRAG_DROP_HAS_CLIP_DATA)).isTrue();
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_DRAG_DROP_TEXT))
                .isEqualTo(SecurePasteActivity.DRAG_TEXT);
    }

    @Test
    public void blockedPackage_keycodePastePolicy() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        focusEditor();

        sendPasteKey();

        waitForEditorText(READER, TEXT_A);
    }

    @Test
    public void blockedPackage_shiftInsertPastePolicy() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchHelperActivity(READER);
        focusEditor();

        sendShiftInsert();

        waitForEditorText(READER, TEXT_A);
    }

    private Bundle waitForDragResult() {
        final long deadline = System.currentTimeMillis() + 10_000;
        Bundle result = null;
        while (System.currentTimeMillis() < deadline) {
            result = call(WRITER, SecurePasteCommandProvider.METHOD_GET_DRAG_RESULT);
            if (result.getBoolean(SecurePasteCommandProvider.RESULT_OK)
                    && result.getBoolean(SecurePasteCommandProvider.RESULT_DRAG_DROPPED)) {
                return result;
            }
            sleep(100);
        }
        assertThat(result).isNotNull();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_DRAG_DROPPED)).isTrue();
        return result;
    }
}
