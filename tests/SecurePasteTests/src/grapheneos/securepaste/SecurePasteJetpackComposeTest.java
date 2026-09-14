package grapheneos.securepaste;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;

import grapheneos.securepaste.helper.SecurePasteCommandProvider;

import org.junit.Test;

public class SecurePasteJetpackComposeTest extends SecurePasteTestBase {
    private static final String FIELD_MODE_VALUE = "value";
    private static final String FIELD_MODE_STATE = "state";

    @Test
    public void blockedJetpackCompose_valueBasedMetadataShowsPasteAndGrants()
            throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(JETPACK_COMPOSE);

        // Compose checks primaryClipDescription before showing Paste and reads the payload only
        // from the Paste callback.
        showSystemToolbarForEditor(JETPACK_COMPOSE);

        assertFloatingToolbarItemPresent(getPasteLabel());
        assertDirectReadDenied(JETPACK_COMPOSE);
        clickFloatingToolbarItemOrFail(getPasteLabel());
        waitForEditorText(JETPACK_COMPOSE, TEXT_A);
    }

    @Test
    public void blockedJetpackCompose_stateBasedMetadataShowsPasteAndGrants()
            throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(JETPACK_COMPOSE);
        launchHelperActivity(JETPACK_COMPOSE);
        setJetpackComposeFieldMode(FIELD_MODE_STATE);

        // Use the physical gesture exercised by Compose's state-based text toolbar path.
        focusEditor();
        longClickEditor();
        assertFloatingToolbarItemPresent(getPasteLabel());
        assertDirectReadDenied(JETPACK_COMPOSE);

        clickFloatingToolbarItemOrFail(getPasteLabel());
        waitForEditorText(JETPACK_COMPOSE, TEXT_A);
    }

    @Test
    public void blockedJetpackCompose_valueBasedImePasteGrants() throws Exception {
        assertImePasteGrants(FIELD_MODE_VALUE);
    }

    @Test
    public void blockedJetpackCompose_stateBasedImePasteGrants() throws Exception {
        assertImePasteGrants(FIELD_MODE_STATE);
    }

    private void assertImePasteGrants(String fieldMode) throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(JETPACK_COMPOSE);
        enableSecurePasteIme(JETPACK_COMPOSE);
        setJetpackComposeFieldMode(fieldMode);
        focusEditor();
        assertDirectReadDenied(JETPACK_COMPOSE);

        waitForProviderResult(IME, SecurePasteCommandProvider.METHOD_IME_PASTE);

        waitForEditorText(JETPACK_COMPOSE, TEXT_A);
    }

    private void setJetpackComposeFieldMode(String mode) {
        final Bundle extras = new Bundle();
        extras.putString(SecurePasteCommandProvider.EXTRA_MODE, mode);
        assertThat(call(JETPACK_COMPOSE,
                SecurePasteCommandProvider.METHOD_SET_JETPACK_COMPOSE_FIELD_MODE, extras)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        mDevice.waitForIdle();
    }
}
