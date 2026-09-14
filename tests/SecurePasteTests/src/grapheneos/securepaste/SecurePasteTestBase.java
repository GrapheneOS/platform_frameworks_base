package grapheneos.securepaste;

import static android.permission.flags.Flags.systemSelectionToolbarEnabled;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import com.android.internal.R;

import grapheneos.securepaste.helper.SecurePasteActivity;
import grapheneos.securepaste.helper.SecurePasteCommandProvider;
import grapheneos.securepaste.helper.SecurePasteKeyEventActivity;

import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.List;

public abstract class SecurePasteTestBase {
    static final String TEST_PKG = "grapheneos.securepaste";
    static final String WRITER = "grapheneos.securepaste.writer";
    static final String READER = "grapheneos.securepaste.reader";
    static final String EDIT_TEXT = "grapheneos.securepaste.edittext";
    static final String JETPACK_COMPOSE = "grapheneos.securepaste.jetpackcompose";
    static final String IME = "grapheneos.securepaste.ime";
    static final String CUSTOM_TOOLBAR = "grapheneos.securepaste.customtoolbar";
    static final String CACHE_CLIENT = "grapheneos.securepaste.cacheclient";
    static final String SHARED_A = "grapheneos.securepaste.shareduid.a";
    static final String SHARED_B = "grapheneos.securepaste.shareduid.b";
    static final String SYSTEM = "grapheneos.securepaste.system";

    static final String TEXT_A = "secure-paste-text-A";
    static final String TEXT_B = "secure-paste-text-B";
    static final String URI_TEXT = "secure-paste-uri-text";
    // The fixed read window lasts one second from the first payload read. Leave scheduling margin
    // before checking that direct reads are denied again.
    static final long PASTE_GRANT_EXPIRY_WAIT_MILLIS = 1_500;

    private static final String ACTIVITY =
            "grapheneos.securepaste.helper.SecurePasteActivity";
    private static final String JETPACK_COMPOSE_ACTIVITY =
            "grapheneos.securepaste.jetpackcompose.SecurePasteJetpackComposeActivity";
    private static final String KEY_EVENT_ACTIVITY =
            "grapheneos.securepaste.helper.SecurePasteKeyEventActivity";
    private static final String ACCESSIBILITY_SERVICE =
            TEST_PKG + "/" + TEST_PKG + ".SecurePasteAccessibilityService";
    private static final String IME_SERVICE = IME + "/.SecurePasteImeService";
    private static final long UI_OBJECT_TIMEOUT_MILLIS = 5_000;
    private static final String[] KNOWN_PACKAGES = {
            WRITER,
            READER,
            EDIT_TEXT,
            JETPACK_COMPOSE,
            IME,
            CUSTOM_TOOLBAR,
            CACHE_CLIENT,
            SHARED_A,
            SHARED_B,
            SYSTEM,
    };
    // Matches the UiAutomator pattern in:
    // frameworks/base/core/tests/coretests/src/android/widget/FloatingToolbarUtils.java
    private static final String TOOLBAR_CONTAINER_RES = "floating_popup_container";

    protected Instrumentation mInstrumentation;
    protected Context mContext;
    protected UiDevice mDevice;
    protected int mUserId;

    private String mOldGlobalClipboardDefault;
    private String mOldEnabledAccessibilityServices;
    private String mOldAccessibilityEnabled;
    private String mOldEnabledInputMethods;
    private String mOldDefaultInputMethod;

    @Before
    public void setUpSecurePasteBase() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();
        mDevice = UiDevice.getInstance(mInstrumentation);
        mUserId = mContext.getUserId();
        Configurator.getInstance().setUiAutomationFlags(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        mDevice.wakeUp();
        shell("wm dismiss-keyguard");
        call(WRITER, SecurePasteCommandProvider.METHOD_CLEAR_CLIP);
        resetAllKnownPackageState();
    }

    @After
    public void tearDownSecurePasteBase() throws Throwable {
        Throwable failure = null;
        failure = runCleanup(failure,
                () -> call(WRITER, SecurePasteCommandProvider.METHOD_CLEAR_CLIP));
        for (String pkg : KNOWN_PACKAGES) {
            failure = runCleanup(failure, () -> resetPackageClipboardPolicy(pkg));
        }
        failure = restoreGlobalClipboardDefault(failure);
        failure = restoreAccessibilityState(failure);
        failure = restoreImeState(failure);
        for (String pkg : KNOWN_PACKAGES) {
            if (IME.equals(pkg)) {
                // It may have been the active IME before the test.
                continue;
            }
            failure = runCleanup(failure,
                    () -> shell("am force-stop --user " + mUserId + " " + pkg));
        }
        failure = runCleanup(failure, () -> mDevice.pressHome());
        if (failure != null) {
            throw failure;
        }
    }

    protected void writerSetsText(String text) {
        packageSetsText(WRITER, text);
    }

    protected void packageSetsText(String pkg, String text) {
        final Bundle extras = new Bundle();
        extras.putString(SecurePasteCommandProvider.EXTRA_TEXT, text);
        assertThat(call(pkg, SecurePasteCommandProvider.METHOD_SET_CLIP_TEXT, extras)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
    }

    protected void writerSetsHtml(String text, String html) {
        final Bundle extras = new Bundle();
        extras.putString(SecurePasteCommandProvider.EXTRA_TEXT, text);
        extras.putString(SecurePasteCommandProvider.EXTRA_HTML, html);
        assertThat(call(WRITER, SecurePasteCommandProvider.METHOD_SET_CLIP_HTML, extras)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
    }

    protected void writerSetsStyledText(String text) {
        final Bundle extras = new Bundle();
        extras.putString(SecurePasteCommandProvider.EXTRA_TEXT, text);
        assertThat(call(WRITER, SecurePasteCommandProvider.METHOD_SET_CLIP_STYLED_TEXT, extras)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
    }

    protected void writerSetsIntentClip() {
        assertWriterSetsClip(SecurePasteCommandProvider.METHOD_SET_CLIP_INTENT);
    }

    protected void writerSetsUriClip(String text) {
        final Bundle extras = new Bundle();
        extras.putString(SecurePasteCommandProvider.EXTRA_TEXT, text);
        assertThat(call(WRITER, SecurePasteCommandProvider.METHOD_SET_CLIP_URI, extras)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
    }

    protected void writerSetsUriOnlyClip() {
        assertWriterSetsClip(SecurePasteCommandProvider.METHOD_SET_CLIP_URI_ONLY);
    }

    protected void writerSetsComplexItemClip(String text) {
        final Bundle extras = new Bundle();
        extras.putString(SecurePasteCommandProvider.EXTRA_TEXT, text);
        assertThat(call(WRITER, SecurePasteCommandProvider.METHOD_SET_CLIP_COMPLEX_ITEM, extras)
                .getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
    }

    protected void writerSetsMultipleTextItemsClip() {
        assertWriterSetsClip(SecurePasteCommandProvider.METHOD_SET_CLIP_MULTIPLE_TEXT_ITEMS);
    }

    protected void writerSetsUnsupportedMimeTypeClip() {
        assertWriterSetsClip(SecurePasteCommandProvider.METHOD_SET_CLIP_UNSUPPORTED_MIME_TYPE);
    }

    private void assertWriterSetsClip(String method) {
        assertThat(call(WRITER, method).getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
    }

    protected Bundle readClipboard(String pkg) {
        return call(pkg, SecurePasteCommandProvider.METHOD_READ_CLIP);
    }

    protected void assertDirectReadDenied(String pkg) {
        assertDirectReadDenied(readClipboard(pkg));
    }

    protected void assertDirectReadDenied(Bundle result) {
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_TEXT)).isNull();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_OK)).isFalse();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_HAS_CLIP)).isTrue();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_HAS_TEXT)).isTrue();
        assertThat(result.containsKey(SecurePasteCommandProvider.RESULT_DESCRIPTION)).isTrue();
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_DESCRIPTION)).isNull();
        assertThat(result.getStringArray(SecurePasteCommandProvider.RESULT_MIME_TYPES))
                .isNotEmpty();
        assertThat(result.getLong(SecurePasteCommandProvider.RESULT_TIMESTAMP)).isGreaterThan(0);
    }

    protected void assertDirectReadAllowed(String pkg, String expected) {
        final Bundle result = readClipboard(pkg);
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_EXCEPTION)).isNull();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_TEXT)).contains(expected);
        assertThat(result.getString(SecurePasteCommandProvider.RESULT_DESCRIPTION))
                .isEqualTo(SecurePasteCommandProvider.TEXT_CLIP_LABEL);
    }

    protected void blockPackageClipboardRead(String pkg) {
        shell("cmd package edit-gos-package-state " + pkg + " " + mUserId
                + " add-flag ALLOW_CLIPBOARD_READ_NON_DEFAULT"
                + " clear-flag ALLOW_CLIPBOARD_READ");
    }

    protected void allowPackageClipboardRead(String pkg) {
        shell("cmd package edit-gos-package-state " + pkg + " " + mUserId
                + " add-flag ALLOW_CLIPBOARD_READ_NON_DEFAULT"
                + " add-flag ALLOW_CLIPBOARD_READ");
    }

    protected void resetPackageClipboardPolicy(String pkg) {
        shell("cmd package edit-gos-package-state " + pkg + " " + mUserId
                + " clear-flag ALLOW_CLIPBOARD_READ_NON_DEFAULT"
                + " clear-flag ALLOW_CLIPBOARD_READ");
    }

    protected void setGlobalClipboardDefault(boolean allowed) {
        if (mOldGlobalClipboardDefault == null) {
            mOldGlobalClipboardDefault = shell("settings get global "
                    + Settings.Global.ALLOW_CLIPBOARD_READ_BY_DEFAULT).trim();
        }
        shell("settings put global " + Settings.Global.ALLOW_CLIPBOARD_READ_BY_DEFAULT + " "
                + (allowed ? "1" : "0"));
    }

    protected void assertSystemAppPreinstalled(String pkg) {
        final String path = shell("pm path --user " + mUserId + " " + pkg).trim();
        assertThat(path).startsWith("package:/system/");
    }

    protected void launchHelperActivity(String pkg) {
        shell("am start -W --user " + mUserId + " -n " + pkg + "/" + activityName(pkg));
        waitForProviderResult(pkg, SecurePasteCommandProvider.METHOD_ACTIVITY_READY);
        call(pkg, SecurePasteCommandProvider.METHOD_REQUEST_FOCUS);
        mDevice.waitForIdle();
    }

    protected void launchKeyEventActivity() {
        launchKeyEventActivityWithArgs("");
    }

    protected void launchKeyEventActivityWithDelayedClipboardRead(long delayMillis) {
        launchKeyEventActivityWithArgs(" --el "
                + SecurePasteKeyEventActivity.EXTRA_CLIPBOARD_READ_DELAY_MILLIS + " "
                + delayMillis);
    }

    protected void launchKeyEventActivityWithoutClipboardRead() {
        launchKeyEventActivityWithArgs(" --ez "
                + SecurePasteKeyEventActivity.EXTRA_READ_CLIPBOARD_ON_SHORTCUT + " false");
    }

    private void launchKeyEventActivityWithArgs(String extraArgs) {
        shell("am start -W --user " + mUserId + " -n " + READER + "/" + KEY_EVENT_ACTIVITY
                + extraArgs);
        final UiObject2 result = mDevice.wait(Until.findObject(
                By.desc(SecurePasteKeyEventActivity.RESULT_DESCRIPTION)),
                UI_OBJECT_TIMEOUT_MILLIS);
        assertThat(result).isNotNull();
        mDevice.waitForIdle();
    }

    protected void focusKeyEventTarget(boolean editor) throws UiObjectNotFoundException {
        clickByDescription(editor
                ? SecurePasteKeyEventActivity.EDITOR_DESCRIPTION
                : SecurePasteKeyEventActivity.NON_EDITOR_DESCRIPTION);
    }

    protected String waitForKeyEventResult(String key, String clipText) {
        final String expectedPrefix = "key=" + key + ";";
        final String expectedClip = ";clip=" + clipText + ";";
        final long deadline = System.currentTimeMillis() + UI_OBJECT_TIMEOUT_MILLIS;
        String text = null;
        while (System.currentTimeMillis() < deadline) {
            final UiObject2 result = mDevice.findObject(
                    By.desc(SecurePasteKeyEventActivity.RESULT_DESCRIPTION));
            text = result == null ? null : result.getText();
            if (text != null && text.startsWith(expectedPrefix) && text.contains(expectedClip)) {
                return text;
            }
            sleep(100);
        }
        assertThat(text).startsWith(expectedPrefix);
        assertThat(text).contains(expectedClip);
        return text;
    }

    protected void waitForKeyInputConnection() {
        final String expected = ";inputConnectionCreated=true;";
        final long deadline = System.currentTimeMillis() + UI_OBJECT_TIMEOUT_MILLIS;
        String text = null;
        while (System.currentTimeMillis() < deadline) {
            final UiObject2 result = mDevice.findObject(
                    By.desc(SecurePasteKeyEventActivity.RESULT_DESCRIPTION));
            text = result == null ? null : result.getText();
            if (text != null && text.contains(expected)) {
                return;
            }
            sleep(100);
        }
        assertThat(text).contains(expected);
    }

    protected void focusEditor() throws UiObjectNotFoundException {
        final UiObject editor = mDevice.findObject(
                new UiSelector().description(SecurePasteActivity.EDITOR_DESCRIPTION));
        assertThat(editor.exists()).isTrue();
        editor.click();
        mDevice.waitForIdle();
    }

    protected void longClickEditor() throws UiObjectNotFoundException {
        final UiObject editor = mDevice.findObject(
                new UiSelector().description(SecurePasteActivity.EDITOR_DESCRIPTION));
        assertThat(editor.exists()).isTrue();
        editor.longClick();
        mDevice.waitForIdle();
    }

    protected void systemToolbarPasteInto(String pkg) throws Exception {
        showSystemToolbarForEditor(pkg);
        clickFloatingToolbarItemOrFail(getPasteLabel());
    }

    protected void showSystemToolbarForEditor(String pkg) throws Exception {
        launchHelperActivity(pkg);
        showSystemToolbarForFocusedEditor(pkg);
    }

    protected void showSystemToolbarForFocusedEditor(String pkg) throws Exception {
        focusEditor();
        if (JETPACK_COMPOSE.equals(pkg)) {
            accessibilityLongClickEditor();
        } else {
            longClickEditor();
        }
    }

    protected void systemToolbarPasteAsPlainTextInto(String pkg) throws Exception {
        showSystemToolbarForEditor(pkg);
        final String label = getPasteAsPlainTextLabel();
        if (!clickFloatingToolbarItem(label)) {
            clickFloatingToolbarOverflowItemOrFail(label);
        }
    }

    protected void clickByDescription(String description) throws UiObjectNotFoundException {
        final UiObject object = waitForObjectByDescriptionOrText(description);
        assertThat(object.exists()).isTrue();
        object.click();
        mDevice.waitForIdle();
    }

    protected void dragByDescription(String sourceDescription, String targetDescription)
            throws UiObjectNotFoundException {
        final UiObject source = waitForObjectByDescriptionOrText(sourceDescription);
        final UiObject target = waitForObjectByDescriptionOrText(targetDescription);
        assertThat(source.exists()).isTrue();
        assertThat(target.exists()).isTrue();
        // startDragAndDrop requires an active pointer down; CTS drives it with injected input too:
        // cts/tests/framework/base/windowmanager/src/android/server/wm/draganddrop/DragDropTest.java
        assertThat(source.dragTo(target, 80)).isTrue();
        mDevice.waitForIdle();
    }

    protected boolean clickFloatingToolbarItem(String text) {
        final UiObject2 item = findFloatingToolbarItem(text);
        if (item == null) {
            return false;
        }
        item.click();
        mDevice.waitForIdle();
        return true;
    }

    protected boolean hasFloatingToolbarItem(String text) {
        return findFloatingToolbarItem(text) != null;
    }

    protected void assertFloatingToolbarItemPresent(String text) {
        if (hasFloatingToolbarItem(text)) {
            return;
        }
        fail("Expected floating toolbar item \"" + text + "\" but visible items were "
                + floatingToolbarItemTexts());
    }

    protected void clickFloatingToolbarItemOrFail(String text) {
        if (clickFloatingToolbarItem(text)) {
            return;
        }
        fail("Expected to click floating toolbar item \"" + text + "\" but visible items were "
                + floatingToolbarItemTexts());
    }

    protected void clickFloatingToolbarOverflowItemOrFail(String text) {
        final UiObject2 toolbar = mDevice.wait(Until.findObject(floatingToolbarSelector()),
                UI_OBJECT_TIMEOUT_MILLIS);
        assertThat(toolbar).isNotNull();
        final UiObject2 overflowButton = toolbar.findObject(By.desc(Resources.getSystem()
                .getString(R.string.floating_toolbar_open_overflow_description)));
        assertThat(overflowButton).isNotNull();
        overflowButton.click();

        final UiObject2 item = mDevice.wait(Until.findObject(
                floatingToolbarSelector().hasDescendant(By.text(text))),
                UI_OBJECT_TIMEOUT_MILLIS);
        assertThat(item).isNotNull();
        final UiObject2 itemText = item.findObject(By.text(text));
        assertThat(itemText).isNotNull();
        final UiObject2 clickableItem = findClickableAncestor(itemText);
        assertThat(clickableItem).isNotNull();
        clickableItem.click();
        mDevice.waitForIdle();
    }

    private void accessibilityLongClickEditor() {
        // Compose maps framework ACTION_LONG_CLICK to its OnLongClick semantics action.
        final long deadline = System.currentTimeMillis() + UI_OBJECT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final AccessibilityNodeInfo root =
                    mInstrumentation.getUiAutomation().getRootInActiveWindow();
            final AccessibilityNodeInfo editor = findAccessibilityNodeByDescription(
                    root, SecurePasteActivity.EDITOR_DESCRIPTION);
            if (editor != null && performAccessibilityActionOnNodeSubtreeOrParent(
                    editor, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                mDevice.waitForIdle();
                return;
            }
            sleep(100);
        }
        fail("Unable to perform accessibility long-click on editor");
    }

    private boolean performAccessibilityActionOnNodeSubtreeOrParent(
            AccessibilityNodeInfo node, int action) {
        if (performAccessibilityActionInSubtree(node, action)) {
            return true;
        }
        AccessibilityNodeInfo current = node.getParent();
        while (current != null) {
            if (current.performAction(action)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean performAccessibilityActionInSubtree(AccessibilityNodeInfo node, int action) {
        if (node == null) {
            return false;
        }
        if (node.performAction(action)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (performAccessibilityActionInSubtree(node.getChild(i), action)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findAccessibilityNodeByDescription(
            AccessibilityNodeInfo node, String description) {
        if (node == null) {
            return null;
        }
        final CharSequence nodeDescription = node.getContentDescription();
        if (nodeDescription != null && nodeDescription.toString().contains(description)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            final AccessibilityNodeInfo match =
                    findAccessibilityNodeByDescription(node.getChild(i), description);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private UiObject2 findFloatingToolbarItem(String text) {
        final UiObject2 toolbar = mDevice.wait(Until.findObject(
                floatingToolbarSelector().hasDescendant(By.text(text))),
                UI_OBJECT_TIMEOUT_MILLIS);
        return toolbar == null ? null
                : findClickableAncestor(toolbar.findObject(By.text(text)));
    }

    private UiObject2 findClickableAncestor(UiObject2 object) {
        while (object != null && !object.isClickable()) {
            object = object.getParent();
        }
        return object;
    }

    private List<String> floatingToolbarItemTexts() {
        final UiObject2 toolbar = mDevice.wait(Until.findObject(floatingToolbarSelector()),
                UI_OBJECT_TIMEOUT_MILLIS);
        final List<String> texts = new ArrayList<>();
        collectText(toolbar, texts);
        return texts;
    }

    private BySelector floatingToolbarSelector() {
        return By.res("android", TOOLBAR_CONTAINER_RES);
    }

    private void collectText(UiObject2 object, List<String> texts) {
        if (object == null) {
            return;
        }
        addTextIfNotEmpty(texts, object.getText());
        addTextIfNotEmpty(texts, object.getContentDescription());
        for (UiObject2 child : object.getChildren()) {
            collectText(child, texts);
        }
    }

    private void addTextIfNotEmpty(List<String> texts, String text) {
        if (text != null && !text.isEmpty() && !texts.contains(text)) {
            texts.add(text);
        }
    }

    private UiObject waitForObjectByDescriptionOrText(String text) {
        final long deadline = System.currentTimeMillis() + UI_OBJECT_TIMEOUT_MILLIS;
        UiObject object = findObjectByDescriptionOrText(text);
        while (!object.exists() && System.currentTimeMillis() < deadline) {
            sleep(100);
            object = findObjectByDescriptionOrText(text);
        }
        return object;
    }

    private UiObject findObjectByDescriptionOrText(String text) {
        UiObject object = mDevice.findObject(new UiSelector().description(text));
        if (!object.exists()) {
            object = mDevice.findObject(new UiSelector().descriptionContains(text));
        }
        if (!object.exists()) {
            object = mDevice.findObject(new UiSelector().text(text));
        }
        if (!object.exists()) {
            object = mDevice.findObject(new UiSelector().textContains(text));
        }
        return object;
    }

    protected void waitForEditorText(String pkg, String expected) {
        final long deadline = System.currentTimeMillis() + 10_000;
        String text = null;
        while (System.currentTimeMillis() < deadline) {
            text = call(pkg, SecurePasteCommandProvider.METHOD_GET_EDITOR_TEXT)
                    .getString(SecurePasteCommandProvider.RESULT_TEXT);
            if (text != null && text.contains(expected)) {
                return;
            }
            sleep(100);
        }
        assertThat(text).contains(expected);
    }

    protected String getEditorText(String pkg) {
        return call(pkg, SecurePasteCommandProvider.METHOD_GET_EDITOR_TEXT)
                .getString(SecurePasteCommandProvider.RESULT_TEXT);
    }

    protected void sendCtrlV() {
        mDevice.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON);
        mDevice.waitForIdle();
    }

    protected void sendCtrlShiftV() {
        mDevice.pressKeyCode(KeyEvent.KEYCODE_V,
                KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
        mDevice.waitForIdle();
    }

    protected void sendCtrlAltV() {
        mDevice.pressKeyCode(KeyEvent.KEYCODE_V,
                KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON);
        mDevice.waitForIdle();
    }

    protected void sendPasteKey() {
        mDevice.pressKeyCode(KeyEvent.KEYCODE_PASTE);
        mDevice.waitForIdle();
    }

    protected void sendShiftInsert() {
        mDevice.pressKeyCode(KeyEvent.KEYCODE_INSERT, KeyEvent.META_SHIFT_ON);
        mDevice.waitForIdle();
    }

    protected void enableAccessibilityService() {
        backupAccessibilityState();
        putSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ACCESSIBILITY_SERVICE);
        putSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
        waitForProviderResult(TEST_PKG, SecurePasteCommandProvider.METHOD_ACCESSIBILITY_CONNECTED);
    }

    protected void enableSecurePasteIme() {
        enableSecurePasteIme(READER);
    }

    protected void enableSecurePasteIme(String targetPackage) {
        backupImeState();
        shell("ime enable --user " + mUserId + " " + IME_SERVICE);
        shell("ime set --user " + mUserId + " " + IME_SERVICE);
        if (!waitForDefaultIme(IME_SERVICE, 5_000)) {
            putSecureSetting(Settings.Secure.DEFAULT_INPUT_METHOD, IME_SERVICE);
            shell("ime set --user " + mUserId + " " + IME_SERVICE);
        }
        assertThat(waitForDefaultIme(IME_SERVICE, 10_000)).isTrue();
        launchHelperActivity(targetPackage);
        waitForProviderResult(IME, SecurePasteCommandProvider.METHOD_IME_READY);
    }

    protected boolean systemSelectionToolbarFlagEnabled() {
        return systemSelectionToolbarEnabled();
    }

    protected Bundle call(String pkg, String method) {
        return call(pkg, method, null);
    }

    protected Bundle call(String pkg, String method, Bundle extras) {
        final Bundle result = mContext.getContentResolver().call(
                Uri.parse("content://" + pkg + ".provider"), method, null, extras);
        if (result == null) {
            fail("No provider result for " + pkg + " method " + method);
        }
        return result;
    }

    protected String shell(String command) {
        try {
            return mDevice.executeShellCommand(command);
        } catch (Exception e) {
            throw new AssertionError("Shell command failed: " + command, e);
        }
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected String getPasteLabel() {
        return mContext.getString(android.R.string.paste);
    }

    protected String getPasteAsPlainTextLabel() {
        return mContext.getString(android.R.string.paste_as_plain_text);
    }

    protected void waitForProviderResult(String pkg, String method) {
        final long deadline = System.currentTimeMillis() + 10_000;
        Bundle result = null;
        while (System.currentTimeMillis() < deadline) {
            result = call(pkg, method);
            if (result.getBoolean(SecurePasteCommandProvider.RESULT_OK)) {
                return;
            }
            sleep(100);
        }
        assertThat(result).isNotNull();
        assertThat(result.getBoolean(SecurePasteCommandProvider.RESULT_OK)).isTrue();
    }

    private boolean waitForDefaultIme(String ime, long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final String current = getSecureSetting(Settings.Secure.DEFAULT_INPUT_METHOD).trim();
            if (current.equals(ime) || current.equals(IME + "/" + IME + ".SecurePasteImeService")) {
                return true;
            }
            sleep(100);
        }
        return false;
    }

    private void backupAccessibilityState() {
        if (mOldEnabledAccessibilityServices == null) {
            final String enabledServices = getSecureSetting(
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            final String accessibilityEnabled = getSecureSetting(
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            mOldEnabledAccessibilityServices = enabledServices;
            mOldAccessibilityEnabled = accessibilityEnabled;
        }
    }

    private void backupImeState() {
        if (mOldEnabledInputMethods == null) {
            final String enabledInputMethods = getSecureSetting(
                    Settings.Secure.ENABLED_INPUT_METHODS);
            final String defaultInputMethod = getSecureSetting(
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            mOldEnabledInputMethods = enabledInputMethods;
            mOldDefaultInputMethod = defaultInputMethod;
        }
    }

    private Throwable restoreAccessibilityState(Throwable failure) {
        if (mOldEnabledAccessibilityServices != null) {
            failure = runCleanup(failure, () -> restoreSecureSetting(
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    mOldEnabledAccessibilityServices));
            failure = runCleanup(failure, () -> restoreSecureSetting(
                    Settings.Secure.ACCESSIBILITY_ENABLED, mOldAccessibilityEnabled));
        }
        return failure;
    }

    private Throwable restoreImeState(Throwable failure) {
        if (mOldEnabledInputMethods != null) {
            failure = runCleanup(failure, () -> restoreSecureSetting(
                    Settings.Secure.ENABLED_INPUT_METHODS, mOldEnabledInputMethods));
            failure = runCleanup(failure, () -> restoreSecureSetting(
                    Settings.Secure.DEFAULT_INPUT_METHOD, mOldDefaultInputMethod));
        }
        return failure;
    }

    private Throwable restoreGlobalClipboardDefault(Throwable failure) {
        if (mOldGlobalClipboardDefault == null) {
            return failure;
        }
        if ("null".equals(mOldGlobalClipboardDefault)) {
            return runCleanup(failure, () -> shell("settings delete global "
                    + Settings.Global.ALLOW_CLIPBOARD_READ_BY_DEFAULT));
        } else {
            return runCleanup(failure, () -> shell("settings put global "
                    + Settings.Global.ALLOW_CLIPBOARD_READ_BY_DEFAULT + " "
                    + mOldGlobalClipboardDefault));
        }
    }

    private String getSecureSetting(String key) {
        return shell("settings get --user " + mUserId + " secure " + key).trim();
    }

    private void putSecureSetting(String key, String value) {
        shell("settings put --user " + mUserId + " secure " + key + " " + value);
    }

    private void restoreSecureSetting(String key, String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) {
            shell("settings delete --user " + mUserId + " secure " + key);
        } else {
            shell("settings put --user " + mUserId + " secure " + key + " " + value);
        }
    }

    private void resetAllKnownPackageState() {
        for (String pkg : KNOWN_PACKAGES) {
            resetPackageClipboardPolicy(pkg);
        }
    }

    private String activityName(String pkg) {
        return JETPACK_COMPOSE.equals(pkg) ? JETPACK_COMPOSE_ACTIVITY : ACTIVITY;
    }

    private static Throwable runCleanup(Throwable failure, CleanupAction action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (failure == null) {
                return t;
            }
            failure.addSuppressed(t);
        }
        return failure;
    }

    private interface CleanupAction {
        void run() throws Exception;
    }
}
