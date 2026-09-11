package grapheneos.securepaste;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.input.InputGestureData;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.view.KeyEvent;

import org.junit.Test;

public class SecurePasteKeyboardTest extends SecurePasteTestBase {
    private static final long DELAYED_CLIPBOARD_READ_MILLIS = 1_500;

    @Test
    public void pasteShortcutsCannotBeCustomGestures() {
        final InputManager inputManager = mContext.getSystemService(InputManager.class);
        assertThat(inputManager).isNotNull();
        final InputGestureData.Trigger[] triggers = {
                InputGestureData.createKeyTrigger(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
                InputGestureData.createKeyTrigger(KeyEvent.KEYCODE_V,
                        KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON),
                InputGestureData.createKeyTrigger(KeyEvent.KEYCODE_INSERT,
                        KeyEvent.META_SHIFT_ON),
                InputGestureData.createKeyTrigger(KeyEvent.KEYCODE_PASTE, 0),
        };

        for (InputGestureData.Trigger trigger : triggers) {
            final InputGestureData gesture = new InputGestureData.Builder()
                    .setTrigger(trigger)
                    .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                    .build();
            final int result = inputManager.addCustomInputGesture(gesture);
            if (result == InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS) {
                inputManager.removeCustomInputGesture(gesture);
            }
            assertThat(result).isEqualTo(
                    InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE);
        }
    }

    @Test
    public void blockedPackage_keyboardPasteShortcutsReachAppHandler() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchKeyEventActivity();
        focusKeyEventTarget(false);

        sendCtrlV();
        assertAppHandledShortcut("CTRL_V", TEXT_A);

        writerSetsText(TEXT_B);
        sendShiftInsert();
        assertAppHandledShortcut("SHIFT_INSERT", TEXT_B);

        writerSetsText(TEXT_A);
        sendPasteKey();
        assertAppHandledShortcut("PASTE", TEXT_A);
    }

    @Test
    public void blockedPackage_ctrlShiftVReachesAppHandlerWithUnsupportedInputConnection()
            throws Exception {
        writerSetsHtml(TEXT_A, "<b>" + TEXT_A + "</b>");
        blockPackageClipboardRead(READER);
        launchKeyEventActivity();
        focusKeyEventTarget(true);
        waitForKeyInputConnection();

        sendCtrlShiftV();

        assertAppHandledShortcut("CTRL_SHIFT_V", TEXT_A);
    }

    @Test
    public void blockedPackage_unrelatedShortcutDoesNotGrantClipboardAccess() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchKeyEventActivity();
        focusKeyEventTarget(false);

        sendCtrlAltV();

        assertAppHandledShortcut("CTRL_ALT_V", "null");
    }

    @Test
    public void blockedPackage_keyboardPasteGrantDoesNotSurviveClipboardChange() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchKeyEventActivity();
        focusKeyEventTarget(false);

        sendCtrlV();
        assertAppHandledShortcut("CTRL_V", TEXT_A);
        writerSetsText(TEXT_B);

        assertDirectReadDenied(READER);
    }

    @Test
    public void blockedPackage_delayedKeyboardHandlerReadsAfterMetadataProbe() throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        launchKeyEventActivityWithDelayedClipboardRead(DELAYED_CLIPBOARD_READ_MILLIS);
        focusKeyEventTarget(false);

        sendCtrlV();

        // Some UI toolkits probe clipboard availability before posting their payload work. The
        // delayed handler must still complete the Paste action without leaving broad access.
        assertAppHandledShortcut("CTRL_V", TEXT_A);
        sleep(PASTE_GRANT_EXPIRY_WAIT_MILLIS);
        assertDirectReadDenied(READER);
    }

    @Test
    public void blockedPackage_clipboardChangeInvalidatesUnreadKeyboardPaste()
            throws Exception {
        writerSetsText(TEXT_A);
        blockPackageClipboardRead(READER);
        sendKeyboardPasteWithoutReadingClipboard();

        writerSetsText(TEXT_B);

        assertDirectReadDenied(READER);
    }

    private void sendKeyboardPasteWithoutReadingClipboard() throws Exception {
        launchKeyEventActivityWithoutClipboardRead();
        focusKeyEventTarget(false);

        sendCtrlV();

        waitForKeyEventResult("CTRL_V", "null");
    }

    private void assertAppHandledShortcut(String key, String clipText) {
        final String result = waitForKeyEventResult(key, clipText);
        assertThat(result).contains(";contextActions=0;");
    }
}
