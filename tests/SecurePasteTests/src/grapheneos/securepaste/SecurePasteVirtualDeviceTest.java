package grapheneos.securepaste;

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CLIPBOARD;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;

import android.app.KeyguardManager;
import android.companion.virtual.VirtualDeviceManager.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualKeyboard;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.view.KeyEvent;

import android.virtualdevice.cts.common.VirtualDeviceRule;

import grapheneos.securepaste.helper.SecurePasteCommandProvider;
import grapheneos.securepaste.helper.SecurePasteKeyEventActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Secure Paste coverage for the virtual-device clipboard silos exercised by CTS's
 * {@code android.virtualdevice.cts.applaunch.StreamedAppClipboardTest}.
 *
 * <p>The test package owns the virtual devices so reads of either silo pass the base virtual-device
 * access check. A separate helper UID writes each clip so reads by the blocked test package reach
 * secure paste's device-scoped grant check rather than the clip owner exemption.</p>
 */
public class SecurePasteVirtualDeviceTest extends SecurePasteTestBase {
    private static final long RESULT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    // VirtualDeviceRule uses the CDM test API to skip the role grant, which requires an insecure
    // keyguard.
    @Rule(order = 0)
    public final ExternalResource mInsecureKeyguardRule = new ExternalResource() {
        @Override
        protected void before() {
            final KeyguardManager keyguardManager = getInstrumentation().getContext()
                    .getSystemService(KeyguardManager.class);
            assumeFalse("VirtualDeviceRule requires an insecure keyguard",
                    keyguardManager != null && keyguardManager.isKeyguardSecure());
        }
    };

    @Rule(order = 1)
    public final VirtualDeviceRule mVirtualDeviceRule = VirtualDeviceRule.createDefault();

    private VirtualDeviceEnvironment mFirstDevice;
    private VirtualDeviceEnvironment mSecondDevice;

    @Before
    public void setUpVirtualDevices() throws InterruptedException {
        mFirstDevice = new VirtualDeviceEnvironment(TEXT_A);
        mSecondDevice = new VirtualDeviceEnvironment(TEXT_B);
        blockPackageClipboardRead(TEST_PKG);
    }

    @After
    public void resetTestPackageClipboardPolicy() {
        resetPackageClipboardPolicy(TEST_PKG);
    }

    @Test
    public void blockedPackage_virtualDisplayPasteReadsVirtualDeviceClipboard()
            throws InterruptedException {
        final BlockingQueue<Bundle> results = launchKeyEventActivity(
                mFirstDevice, mFirstDevice.deviceId);

        mFirstDevice.sendCtrlV();

        assertThat(awaitClipboardRead(results)).asList().containsExactly(TEXT_A);
    }

    @Test
    public void blockedPackage_virtualDisplayPasteGrantDoesNotAuthorizeAnotherVirtualDevice()
            throws InterruptedException {
        final BlockingQueue<Bundle> results = launchKeyEventActivity(
                mFirstDevice, mSecondDevice.deviceId);

        mFirstDevice.sendCtrlV();

        assertThat(awaitClipboardRead(results)).asList().containsExactly((String) null);
    }

    @Test
    public void blockedPackage_secondVirtualDisplayPasteGrantDoesNotAuthorizeFirstVirtualDevice()
            throws InterruptedException {
        final BlockingQueue<Bundle> results = launchKeyEventActivity(
                mSecondDevice, mFirstDevice.deviceId);

        mSecondDevice.sendCtrlV();

        assertThat(awaitClipboardRead(results)).asList().containsExactly((String) null);
    }

    @Test
    public void blockedPackage_otherDeviceReadDoesNotConsumePasteGrant()
            throws InterruptedException {
        final BlockingQueue<Bundle> results = launchKeyEventActivity(
                mFirstDevice, mSecondDevice.deviceId, mFirstDevice.deviceId);

        mFirstDevice.sendCtrlV();

        assertThat(awaitClipboardRead(results)).asList()
                .containsExactly((String) null, TEXT_A).inOrder();
    }

    private BlockingQueue<Bundle> launchKeyEventActivity(
            VirtualDeviceEnvironment environment, int... deviceIds) throws InterruptedException {
        final BlockingQueue<Bundle> results = new LinkedBlockingQueue<>();
        final Intent intent = new Intent()
                .setComponent(new ComponentName(
                        TEST_PKG, SecurePasteKeyEventActivity.class.getName()))
                .putExtra(SecurePasteKeyEventActivity.EXTRA_CLIPBOARD_READ_DEVICE_IDS, deviceIds)
                .putExtra(SecurePasteKeyEventActivity.EXTRA_RESULT_RECEIVER,
                        new RemoteCallback(results::add))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mVirtualDeviceRule.sendIntentToDisplay(intent, environment.display);
        awaitResult(results, result -> result.getBoolean(
                SecurePasteKeyEventActivity.RESULT_HAS_WINDOW_FOCUS)
                && result.getInt(SecurePasteKeyEventActivity.RESULT_DEVICE_ID)
                == environment.deviceId);
        return results;
    }

    private String[] awaitClipboardRead(BlockingQueue<Bundle> results)
            throws InterruptedException {
        return awaitResult(results, result -> result.containsKey(
                SecurePasteKeyEventActivity.RESULT_CLIP_TEXTS))
                .getStringArray(SecurePasteKeyEventActivity.RESULT_CLIP_TEXTS);
    }

    private Bundle awaitResult(BlockingQueue<Bundle> results, Predicate<Bundle> predicate)
            throws InterruptedException {
        final long deadline = System.nanoTime() + RESULT_TIMEOUT_NANOS;
        while (true) {
            final long remainingNanos = deadline - System.nanoTime();
            assertThat(remainingNanos).isGreaterThan(0);
            final Bundle result = results.poll(remainingNanos, TimeUnit.NANOSECONDS);
            assertThat(result).isNotNull();
            if (predicate.test(result)) {
                return result;
            }
        }
    }

    private final class VirtualDeviceEnvironment {
        final int deviceId;
        final VirtualDisplay display;
        final VirtualKeyboard keyboard;

        @SuppressWarnings("deprecation")
        VirtualDeviceEnvironment(String clipText) throws InterruptedException {
            // Match the isolated clipboard and trusted display setup used by
            // android.virtualdevice.cts.applaunch.StreamedAppClipboardTest.
            final VirtualDevice device = mVirtualDeviceRule.createManagedVirtualDevice(
                    new VirtualDeviceParams.Builder()
                            .setLockState(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED)
                            .setDevicePolicy(POLICY_TYPE_CLIPBOARD, DEVICE_POLICY_DEFAULT)
                            .build());
            deviceId = device.getDeviceId();
            display = mVirtualDeviceRule.createManagedVirtualDisplayWithFlags(device,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            keyboard = device.createVirtualKeyboard(
                    display, "secure-paste-keyboard-" + deviceId, 1, 1);
            final BlockingQueue<Bundle> results = new LinkedBlockingQueue<>();
            final Intent intent = new Intent()
                    .setComponent(new ComponentName(
                            WRITER, SecurePasteKeyEventActivity.class.getName()))
                    .putExtra(SecurePasteKeyEventActivity.EXTRA_CLIP_TEXT_TO_SET, clipText)
                    .putExtra(SecurePasteKeyEventActivity.EXTRA_RESULT_RECEIVER,
                            new RemoteCallback(results::add))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mVirtualDeviceRule.sendIntentToDisplay(intent, display);
            awaitResult(results, result -> result.getBoolean(
                    SecurePasteKeyEventActivity.RESULT_HAS_WINDOW_FOCUS)
                    && result.getInt(SecurePasteKeyEventActivity.RESULT_DEVICE_ID) == deviceId);
        }

        void sendCtrlV() {
            sendKey(KeyEvent.KEYCODE_CTRL_LEFT, VirtualKeyEvent.ACTION_DOWN);
            sendKey(KeyEvent.KEYCODE_V, VirtualKeyEvent.ACTION_DOWN);
            sendKey(KeyEvent.KEYCODE_V, VirtualKeyEvent.ACTION_UP);
            sendKey(KeyEvent.KEYCODE_CTRL_LEFT, VirtualKeyEvent.ACTION_UP);
        }

        private void sendKey(int keyCode, int action) {
            keyboard.sendKeyEvent(new VirtualKeyEvent.Builder()
                    .setKeyCode(keyCode)
                    .setAction(action)
                    .build());
        }
    }
}
