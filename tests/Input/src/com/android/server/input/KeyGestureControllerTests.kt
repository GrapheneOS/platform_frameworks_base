/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.input

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.PermissionChecker
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.hardware.input.AidlKeyGestureEvent
import android.hardware.input.AppLaunchData
import android.hardware.input.IKeyGestureEventListener
import android.hardware.input.IKeyGestureHandler
import android.hardware.input.InputGestureData
import android.hardware.input.InputManager
import android.hardware.input.KeyGestureEvent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.os.test.TestLooper
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.DisabledOnRavenwood
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.Presubmit
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.DeviceConfig
import android.testing.TestableContext
import android.testing.TestableResources
import android.view.Display.DEFAULT_DISPLAY
import android.view.Display.INVALID_DISPLAY
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import android.view.WindowManagerPolicyConstants.FLAG_INTERACTIVE
import android.view.WindowManagerPolicyConstants.FLAG_TRUSTED
import androidx.test.core.app.ApplicationProvider
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.R
import com.android.internal.accessibility.AccessibilityShortcutController
import com.android.internal.annotations.Keep
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.internal.policy.IShortcutService
import com.android.internal.policy.KeyInterceptionInfo
import com.android.internal.util.FrameworkStatsLog
import com.android.internal.util.ScreenshotHelper
import com.android.internal.util.ScreenshotRequest
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.server.LocalServices
import com.android.server.clipboard.ClipboardManagerInternal
import com.android.server.input.InputManagerService.WindowManagerCallbacks
import com.android.server.input.InputManagerServiceTests.Companion.ACTION_KEY_EVENTS
import com.android.server.input.data.TestDataStore
import com.android.server.wm.WindowManagerInternal
import com.android.server.wm.WindowManagerInternal.InputTargetInfo
import junitparams.JUnitParamsRunner
import junitparams.Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times

/**
 * Tests for {@link KeyGestureController}.
 *
 * Build/Install/Run: atest InputTests:KeyGestureControllerTests
 */
@Presubmit
@RunWith(JUnitParamsRunner::class)
@EnableFlags(
    com.android.hardware.input.Flags.FLAG_ENABLE_COLOR_INVERSION_KEY_GESTURES,
    com.android.hardware.input.Flags.FLAG_ENABLE_SELECT_TO_SPEAK_KEY_GESTURES,
    com.android.hardware.input.Flags.FLAG_ENABLE_TALKBACK_KEY_GESTURES,
    com.android.hardware.input.Flags.FLAG_ENABLE_NEW_26Q2_KEYCODES,
    com.android.hardware.input.Flags.FLAG_ENABLE_QUICK_SETTINGS_PANEL_SHORTCUT,
    com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_SEARCH_DESKTOP_ENTRYPOINTS,
    com.android.hardware.input.Flags.FLAG_ENABLE_NOTE_TAKING_KEYBOARD_SHORTCUT,
)
@DisabledOnRavenwood(reason = "Static mocking in bivalent tests is tricky", bug = 310268946)
class KeyGestureControllerTests {

    companion object {
        const val DEVICE_ID = 1
        const val USER_ID = 10
        const val SECOND_USER_ID = 11
        val HOME_GESTURE_COMPLETE_EVENT =
            KeyGestureEvent.Builder()
                .setDeviceId(DEVICE_ID)
                .setKeycodes(intArrayOf(KeyEvent.KEYCODE_H))
                .setModifierState(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                .setAction(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
                .build()
        val MODIFIER =
            mapOf(
                KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON),
                KeyEvent.KEYCODE_CTRL_RIGHT to
                    (KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_CTRL_ON),
                KeyEvent.KEYCODE_ALT_LEFT to (KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON),
                KeyEvent.KEYCODE_ALT_RIGHT to (KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON),
                KeyEvent.KEYCODE_SHIFT_LEFT to
                    (KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON),
                KeyEvent.KEYCODE_SHIFT_RIGHT to
                    (KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON),
                KeyEvent.KEYCODE_META_LEFT to (KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_ON),
                KeyEvent.KEYCODE_META_RIGHT to
                    (KeyEvent.META_META_RIGHT_ON or KeyEvent.META_META_ON),
            )
        const val SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH = 0
        const val SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY = 1
        const val SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY = 0
        const val SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL = 1
        const val SETTINGS_KEY_BEHAVIOR_NOTHING = 2
        const val SYSTEM_PID = 0
        const val TEST_PID = 10
        const val RANDOM_PID1 = 11
        const val RANDOM_PID2 = 12
        const val RANDOM_DISPLAY_ID = 123
        const val TEST_UID = 12345
        const val SCREENSHOT_CHORD_DELAY: Long = 1000
        // Current default multi-key press timeout used in KeyCombinationManager
        const val COMBINE_KEY_DELAY_MILLIS: Long = 150
        const val LONG_PRESS_DELAY_FOR_ESCAPE_MILLIS: Long = 1000
        // App delegate that consumes all keys that it receives
        val BLOCKING_APP = AppDelegate { _ -> true }
        // App delegate that doesn't consume any keys that it receives
        val PASS_THROUGH_APP = AppDelegate { _ -> false }
    }

    @JvmField
    @Rule
    val extendedMockitoRule =
        ExtendedMockitoRule.Builder(this)
            .mockStatic(FrameworkStatsLog::class.java)
            .mockStatic(KeyCharacterMap::class.java)
            .mockStatic(DeviceConfig::class.java)
            .mockStatic(LocalServices::class.java)
            .mockStatic(PermissionChecker::class.java)
            .build()!!

    @JvmField @Rule val rule = SetFlagsRule()

    @JvmField
    @Rule
    val testableContext = TestableContext(ApplicationProvider.getApplicationContext())

    @Mock private lateinit var inputManager: InputManager
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var wmCallbacks: WindowManagerCallbacks
    @Mock private lateinit var accessibilityShortcutController: AccessibilityShortcutController
    @Mock private lateinit var screenshotHelper: ScreenshotHelper
    @Mock private lateinit var windowManagerInternal: WindowManagerInternal
    @Mock private lateinit var clipboardManagerInternal: ClipboardManagerInternal
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var roleManager: RoleManager

    private var currentPid = 0
    private lateinit var testableResources: TestableResources
    private lateinit var keyGestureController: KeyGestureController
    private lateinit var testLooper: TestLooper
    private lateinit var testDataStore: TestDataStore
    private var events = mutableListOf<KeyGestureEvent>()

    @Before
    fun setup() {
        testableResources = testableContext.orCreateTestableResources
        setupInputDevices()
        setupBehaviors()
        testLooper = TestLooper()
        testDataStore = TestDataStore()
        currentPid = Process.myPid()
        ExtendedMockito.doReturn(windowManagerInternal).`when` {
            LocalServices.getService(ArgumentMatchers.eq(WindowManagerInternal::class.java))
        }
        testableContext.addMockSystemService(Context.USER_SERVICE, userManager)
        Mockito.`when`(userManager.getUserHandles(anyBoolean()))
            .thenReturn(listOf(UserHandle(USER_ID), UserHandle(SECOND_USER_ID)))
    }

    private fun setupBehaviors() {
        testableResources.addOverride(R.bool.config_enableScreenshotChord, true)
        ExtendedMockito.`when`(
                DeviceConfig.getLong(
                    eq(DeviceConfig.NAMESPACE_SYSTEMUI),
                    eq(SystemUiDeviceConfigFlags.SCREENSHOT_KEYCHORD_DELAY),
                    anyLong(),
                )
            )
            .thenReturn(SCREENSHOT_CHORD_DELAY)
        Mockito.`when`(packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
            .thenReturn(true)
        Mockito.`when`(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            .thenReturn(true)
        testableContext.setMockPackageManager(packageManager)
        Mockito.`when`(roleManager.isRoleAvailable(RoleManager.ROLE_NOTES)).thenReturn(true)
        testableContext.addMockSystemService(RoleManager::class.java, roleManager)
        testableResources.addOverride(
            R.integer.config_searchKeyBehavior,
            SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY,
        )
        testableResources.addOverride(
            R.integer.config_settingsKeyBehavior,
            SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY,
        )
    }

    private fun setupBookmarks(bookmarkRes: Int) {
        val testBookmarks: XmlResourceParser = testableContext.resources.getXml(bookmarkRes)
        testableResources.addOverride(R.xml.bookmarks, testBookmarks)
    }

    private fun setupInputDevices() {
        val correctIm = testableContext.getSystemService(InputManager::class.java)!!
        val virtualDevice = correctIm.getInputDevice(KeyCharacterMap.VIRTUAL_KEYBOARD)!!
        val kcm = virtualDevice.keyCharacterMap!!
        val keyboardDevice = InputDevice.Builder().setId(DEVICE_ID).build()
        testableContext.addMockSystemService(Context.INPUT_SERVICE, inputManager)
        Mockito.`when`(inputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID))
        Mockito.`when`(inputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardDevice)
        // Need to mock KCM, to allow AppLaunchShortcutManager correctly initialize
        // bookmarks (for legacy bookmarks we use "character" instead of keycodes to define
        // key combinations, so we use Virtual KCM to reverse map character to keycodes)
        ExtendedMockito.`when`(KeyCharacterMap.load(anyInt())).thenReturn(kcm)
    }

    private fun setupKeyGestureController() {
        keyGestureController =
            KeyGestureController(
                testableContext,
                testLooper.looper,
                testLooper.looper,
                testDataStore.getDataStore(),
                object : KeyGestureController.Injector() {
                    override fun getAccessibilityShortcutController(
                        context: Context?,
                        handler: Handler?,
                    ): AccessibilityShortcutController {
                        return accessibilityShortcutController
                    }

                    override fun getScreenshotHelper(context: Context?): ScreenshotHelper {
                        return screenshotHelper
                    }
                },
            )
        Mockito.`when`(inputManager.registerKeyGestureEventHandler(any(), any())).thenAnswer {
            val gestures = it.getArgument<List<Int>>(0)
            if (gestures != null) {
                val handler = it.getArgument<InputManager.KeyGestureEventHandler>(1)
                requireNotNull(handler) {
                    "Handler argument cannot be null when gestures are provided"
                }
                keyGestureController.registerKeyGestureHandler(
                    gestures.toIntArray(),
                    KeyGestureHandler { event, token ->
                        handler.handleKeyGestureEvent(KeyGestureEvent(event), token)
                    },
                    SYSTEM_PID,
                )
            }
        }
        keyGestureController.setWindowManagerCallbacks(wmCallbacks)
        Mockito.`when`(wmCallbacks.isKeyguardLocked(anyInt())).thenReturn(false)
        Mockito.`when`(
                accessibilityShortcutController.isAccessibilityShortcutAvailable(anyBoolean())
            )
            .thenReturn(true)
        Mockito.`when`(inputManager.appLaunchBookmarks).thenAnswer {
            keyGestureController.appLaunchBookmarks.map { bookmark -> InputGestureData(bookmark) }
        }
        keyGestureController.systemRunning()
        keyGestureController.setCurrentUserId(USER_ID)
        testLooper.dispatchAll()
    }

    private fun notifyHomeGestureCompleted() {
        keyGestureController.notifyKeyGestureCompleted(
            DEVICE_ID,
            intArrayOf(KeyEvent.KEYCODE_H),
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
        )
    }

    @Test
    fun testKeyGestureEvent_registerUnregisterListener() {
        setupKeyGestureController()
        val events = mutableListOf<KeyGestureEvent>()
        val listener = KeyGestureEventListener { event -> events.add(KeyGestureEvent(event)) }

        // Register key gesture event listener
        keyGestureController.registerKeyGestureEventListener(listener, 0)
        notifyHomeGestureCompleted()
        testLooper.dispatchAll()
        assertEquals("Listener should get callbacks on key gesture event completed", 1, events.size)
        assertEquals(
            "Listener should get callback for key gesture complete event",
            HOME_GESTURE_COMPLETE_EVENT,
            events[0],
        )

        // Unregister listener
        events.clear()
        keyGestureController.unregisterKeyGestureEventListener(listener, 0)
        notifyHomeGestureCompleted()
        testLooper.dispatchAll()
        assertEquals("Listener should not get callback after being unregistered", 0, events.size)
    }

    // All system gestures (should be handled if there is no capturing focused window)
    @Keep
    private fun systemGesturesTestArguments(): Array<KeyGestureData> {
        return KeyGestureTestData.NON_CAPTURABLE_SYSTEM_GESTURES +
            KeyGestureTestData.CAPTURABLE_STATEFUL_SYSTEM_GESTURES +
            KeyGestureTestData.CAPTURABLE_SYSTEM_GESTURES
    }

    @Test
    @Parameters(method = "systemGesturesTestArguments")
    @EnableFlags(
        com.android.hardware.input.Flags.FLAG_ENABLE_PARTIAL_SCREENSHOT_KEYBOARD_SHORTCUT,
        com.android.hardware.input.Flags.FLAG_KEYBOARD_BACKLIGHT_SHORTCUTS,
        com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_INPUT_TRIGGER,
        com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_CURSOR_DESKTOP_ENTRYPOINTS,
    )
    fun testKeyGestures(test: KeyGestureData) {
        setupKeyGestureController()
        testKeyGestureProduced(test, PASS_THROUGH_APP)
    }

    @Keep
    private fun multiKeyGestureArguments(): Array<KeyGestureData> {
        return KeyGestureTestData.MULTI_KEY_SYSTEM_GESTURES
    }

    @Test
    @Parameters(method = "multiKeyGestureArguments")
    fun testMultiKeyGestures(test: KeyGestureData) {
        setupKeyGestureController()
        testKeyGestureProduced(test, BLOCKING_APP)
    }

    @Test
    @EnableFlags(
        com.android.hardware.input.Flags.FLAG_ENABLE_PARTIAL_SCREENSHOT_KEYBOARD_SHORTCUT,
        com.android.hardware.input.Flags.FLAG_KEYBOARD_BACKLIGHT_SHORTCUTS,
        com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_INPUT_TRIGGER,
        com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_CURSOR_DESKTOP_ENTRYPOINTS,
    )
    fun testCustomKeyGesturesNotAllowedForSystemGestures() {
        setupKeyGestureController()
        for (systemGesture in systemGesturesTestArguments()) {
            if (systemGesture.expectedModifierState == 0) {
                continue
            }
            val builder =
                InputGestureData.Builder()
                    .setKeyGestureType(systemGesture.expectedKeyGestureType)
                    .setTrigger(
                        InputGestureData.createKeyTrigger(
                            systemGesture.expectedKeys[0],
                            systemGesture.expectedModifierState,
                        )
                    )
            if (systemGesture.expectedAppLaunchData != null) {
                builder.setAppLaunchData(systemGesture.expectedAppLaunchData)
            }
            assertEquals(
                "Can't set custom gesture trigger used by system gesture, $systemGesture",
                InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE,
                keyGestureController.addCustomInputGesture(USER_ID, builder.build().aidlData),
            )
        }
    }

    @Keep
    private fun bookmarkArguments(): Array<KeyGestureData> {
        return KeyGestureTestData.TEST_BOOKMARKS_DATA
    }

    @Test
    @Parameters(method = "bookmarkArguments")
    fun testBookmarks(test: KeyGestureData) {
        setupBookmarks(com.android.test.input.R.xml.bookmarks)
        setupKeyGestureController()
        testKeyGestureProduced(test, BLOCKING_APP)
    }

    @Test
    @Parameters(method = "bookmarkArguments")
    fun testBookmarksLegacy(test: KeyGestureData) {
        setupBookmarks(com.android.test.input.R.xml.bookmarks_legacy)
        setupKeyGestureController()
        testKeyGestureProduced(test, BLOCKING_APP)
    }

    @Test
    fun testCustomKeyGesturesNotAllowedForBookmarks() {
        setupBookmarks(com.android.test.input.R.xml.bookmarks_legacy)
        setupKeyGestureController()
        for (bookmark in bookmarkArguments()) {
            if (bookmark.expectedModifierState == 0) {
                continue
            }
            val builder =
                InputGestureData.Builder()
                    .setKeyGestureType(bookmark.expectedKeyGestureType)
                    .setTrigger(
                        InputGestureData.createKeyTrigger(
                            bookmark.expectedKeys[0],
                            bookmark.expectedModifierState,
                        )
                    )
            if (bookmark.expectedAppLaunchData != null) {
                builder.setAppLaunchData(bookmark.expectedAppLaunchData)
            }
            assertEquals(
                "Can't set custom gesture trigger used by a bookmark, $bookmark",
                InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE,
                keyGestureController.addCustomInputGesture(USER_ID, builder.build().aidlData),
            )
        }
    }

    @Keep
    private fun nonCapturableKeyGestures(): Array<KeyGestureData> {
        return KeyGestureTestData.NON_CAPTURABLE_SYSTEM_GESTURES
    }

    @Test
    @Parameters(method = "nonCapturableKeyGestures")
    @EnableFlags(
        com.android.hardware.input.Flags.FLAG_ENABLE_PARTIAL_SCREENSHOT_KEYBOARD_SHORTCUT,
        com.android.hardware.input.Flags.FLAG_KEYBOARD_BACKLIGHT_SHORTCUTS,
        com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_INPUT_TRIGGER,
    )
    fun testKeyGestures_withKeyCapture_nonCapturableGestures(test: KeyGestureData) {
        setupKeyGestureController()
        enableKeyCaptureForFocussedWindow()
        testKeyGestureProduced(test, BLOCKING_APP)
    }

    @Keep
    private fun capturableKeyGestures(): Array<KeyGestureData> {
        return KeyGestureTestData.CAPTURABLE_STATEFUL_SYSTEM_GESTURES +
            KeyGestureTestData.CAPTURABLE_SYSTEM_GESTURES
    }

    @Test
    @Parameters(method = "capturableKeyGestures")
    @EnableFlags(
        com.android.hardware.input.Flags.FLAG_ENABLE_PARTIAL_SCREENSHOT_KEYBOARD_SHORTCUT,
        com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_CURSOR_DESKTOP_ENTRYPOINTS,
    )
    fun testKeyGestures_withKeyCapture_capturableGestures(test: KeyGestureData) {
        setupKeyGestureController()
        enableKeyCaptureForFocussedWindow()
        testKeyGestureNotProduced(test, BLOCKING_APP)
    }

    @Keep
    private fun capturableKeyGestures_handledAsFallback(): Array<KeyGestureData> {
        return KeyGestureTestData.CAPTURABLE_SYSTEM_GESTURES
    }

    @Test
    @Parameters(method = "capturableKeyGestures_handledAsFallback")
    @EnableFlags(
        com.android.hardware.input.Flags.FLAG_ENABLE_PARTIAL_SCREENSHOT_KEYBOARD_SHORTCUT,
        com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_CURSOR_DESKTOP_ENTRYPOINTS,
    )
    fun testKeyGestures_withKeyCapture_capturableGesturesHandledAsFallback(test: KeyGestureData) {
        setupKeyGestureController()
        enableKeyCaptureForFocussedWindow()
        testKeyGestureProduced(test, PASS_THROUGH_APP)
    }

    @Test
    fun testKeycodesFullyConsumed_irrespectiveOfHandlers() {
        setupKeyGestureController()
        val testKeys =
            intArrayOf(
                KeyEvent.KEYCODE_RECENT_APPS,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_BRIGHTNESS_UP,
                KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
                KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN,
                KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP,
                KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE,
                KeyEvent.KEYCODE_ALL_APPS,
                KeyEvent.KEYCODE_NOTIFICATION,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_LANGUAGE_SWITCH,
                KeyEvent.KEYCODE_SCREENSHOT,
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT,
                KeyEvent.KEYCODE_ASSIST,
                KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
                KeyEvent.KEYCODE_DO_NOT_DISTURB,
                KeyEvent.KEYCODE_LOCK,
                KeyEvent.KEYCODE_FULLSCREEN,
                KeyEvent.KEYCODE_ACCESSIBILITY,
                KeyEvent.KEYCODE_CONTEXTUAL_SEARCH,
            )

        var sentToApp = 0
        for (key in testKeys) {
            sendKeys(
                intArrayOf(key),
                AppDelegate {
                    sentToApp++
                    false
                },
            )
        }
        assertEquals("No system keys should be sent to app", 0, sentToApp)
    }

    @Test
    fun testSearchKeyGestures_defaultSearch() {
        testableResources.addOverride(
            R.integer.config_searchKeyBehavior,
            SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH,
        )
        setupKeyGestureController()
        testKeyGestureNotProduced(
            KeyGestureData(
                "SEARCH -> Default Search",
                intArrayOf(KeyEvent.KEYCODE_SEARCH),
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH,
            ),
            BLOCKING_APP,
        )
    }

    @Test
    fun testSettingKeyGestures_doNothing() {
        testableResources.addOverride(
            R.integer.config_settingsKeyBehavior,
            SETTINGS_KEY_BEHAVIOR_NOTHING,
        )
        setupKeyGestureController()

        testKeyGestureNotProduced(
            KeyGestureData(
                "SETTINGS -> Do Nothing",
                intArrayOf(KeyEvent.KEYCODE_SETTINGS),
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS,
            ),
            BLOCKING_APP,
        )
        testKeyGestureNotProduced(
            KeyGestureData(
                "SETTINGS -> Do Nothing",
                intArrayOf(KeyEvent.KEYCODE_SETTINGS),
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
            ),
            BLOCKING_APP,
        )
    }

    @Test
    fun testSettingKeyGestures_notificationPanel() {
        testableResources.addOverride(
            R.integer.config_settingsKeyBehavior,
            SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL,
        )
        setupKeyGestureController()
        testKeyGestureProduced(
            KeyGestureData(
                "SETTINGS -> Toggle Notification Panel",
                intArrayOf(KeyEvent.KEYCODE_SETTINGS),
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                intArrayOf(KeyEvent.KEYCODE_SETTINGS),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
            ),
            BLOCKING_APP,
        )
    }

    @Test
    fun testCapsLockPressNotified() {
        setupKeyGestureController()
        val events = mutableListOf<KeyGestureEvent>()
        val listener = KeyGestureEventListener { event -> events.add(KeyGestureEvent(event)) }

        keyGestureController.registerKeyGestureEventListener(listener, 0)
        sendKeys(intArrayOf(KeyEvent.KEYCODE_CAPS_LOCK), assertKeysFullyConsumed = false)
        testLooper.dispatchAll()
        assertEquals("Listener should get callbacks on key gesture event completed", 1, events.size)
        assertEquals(
            "Listener should get callback for Toggle Caps Lock key gesture complete event",
            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
            events[0].keyGestureType,
        )
    }

    @Keep
    private fun customInputGesturesTestArguments(): Array<KeyGestureData> {
        return arrayOf(
            KeyGestureData(
                "META + ALT + Q -> Go Home",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_ALT_LEFT,
                    KeyEvent.KEYCODE_Q,
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
                intArrayOf(KeyEvent.KEYCODE_Q),
                KeyEvent.META_META_ON or KeyEvent.META_ALT_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
            ),
            KeyGestureData(
                "CTRL + SHIFT + Q -> Launch app",
                intArrayOf(
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_SHIFT_LEFT,
                    KeyEvent.KEYCODE_Q,
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION,
                intArrayOf(KeyEvent.KEYCODE_Q),
                KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
                AppLaunchData.createLaunchDataForComponent("com.test", "com.test.BookmarkTest"),
            ),
            KeyGestureData(
                "CTRL + Overview -> Toggle Notification Panel",
                intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_RECENT_APPS),
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                intArrayOf(KeyEvent.KEYCODE_RECENT_APPS),
                KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
            ),
            KeyGestureData(
                "SHIFT + Brightness Up -> Keyboard Backlight Up",
                intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_BRIGHTNESS_UP),
                KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP,
                intArrayOf(KeyEvent.KEYCODE_BRIGHTNESS_UP),
                KeyEvent.META_SHIFT_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
            ),
        )
    }

    @Test
    @Parameters(method = "customInputGesturesTestArguments")
    fun testCustomKeyGestures(test: KeyGestureData) {
        setupKeyGestureController()
        val trigger =
            InputGestureData.createKeyTrigger(test.expectedKeys[0], test.expectedModifierState)
        val builder =
            InputGestureData.Builder()
                .setKeyGestureType(test.expectedKeyGestureType)
                .setTrigger(trigger)
        if (test.expectedAppLaunchData != null) {
            builder.setAppLaunchData(test.expectedAppLaunchData)
        }
        val inputGestureData = builder.build()

        assertNull(test.toString(), keyGestureController.getInputGesture(0, trigger.aidlTrigger))
        assertEquals(
            test.toString(),
            InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS,
            keyGestureController.addCustomInputGesture(USER_ID, builder.build().aidlData),
        )
        assertEquals(
            test.toString(),
            inputGestureData.aidlData,
            keyGestureController.getInputGesture(USER_ID, trigger.aidlTrigger),
        )
        testKeyGestureProduced(test, BLOCKING_APP)
    }

    @Test
    @Parameters(method = "customInputGesturesTestArguments")
    fun testCustomKeyGesturesSavedAndLoadedByController(test: KeyGestureData) {
        setupKeyGestureController()
        val builder =
            InputGestureData.Builder()
                .setKeyGestureType(test.expectedKeyGestureType)
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        test.expectedKeys[0],
                        test.expectedModifierState,
                    )
                )
        if (test.expectedAppLaunchData != null) {
            builder.setAppLaunchData(test.expectedAppLaunchData)
        }
        val inputGestureData = builder.build()
        keyGestureController.addCustomInputGesture(USER_ID, inputGestureData.aidlData)
        testLooper.dispatchAll()

        // Reinitialize the gesture controller simulating a login/logout for the user.
        setupKeyGestureController()
        testLooper.dispatchAll()

        // Test input gesture still produced with the new controller
        testKeyGestureProduced(test, BLOCKING_APP)
    }

    @Test
    @Parameters(method = "customInputGesturesTestArguments")
    fun testCustomKeyGesturesNotProducedForOtherUser(test: KeyGestureData) {
        setupKeyGestureController()
        val builder =
            InputGestureData.Builder()
                .setKeyGestureType(test.expectedKeyGestureType)
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        test.expectedKeys[0],
                        test.expectedModifierState,
                    )
                )
        if (test.expectedAppLaunchData != null) {
            builder.setAppLaunchData(test.expectedAppLaunchData)
        }
        val inputGestureData = builder.build()
        keyGestureController.addCustomInputGesture(USER_ID, inputGestureData.aidlData)
        testLooper.dispatchAll()

        keyGestureController.setCurrentUserId(SECOND_USER_ID)
        testKeyGestureNotProduced(test, BLOCKING_APP)
    }

    @Test
    fun testCustomKeyGesture_addedForNonCurrentUser_savedCorrectly() {
        setupKeyGestureController()
        val inputGestureData1 =
            InputGestureData.Builder()
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                .setTrigger(
                    InputGestureData.createKeyTrigger(KeyEvent.KEYCODE_H, KeyEvent.META_ALT_ON)
                )
                .build()
        keyGestureController.addCustomInputGesture(SECOND_USER_ID, inputGestureData1.aidlData)
        testLooper.dispatchAll()

        // Re-initialize again with USER_ID as current user
        setupKeyGestureController()
        keyGestureController.setCurrentUserId(USER_ID)
        testLooper.dispatchAll()

        val inputGestureData2 =
            InputGestureData.Builder()
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL)
                .setTrigger(
                    InputGestureData.createKeyTrigger(KeyEvent.KEYCODE_N, KeyEvent.META_ALT_ON)
                )
                .build()
        keyGestureController.addCustomInputGesture(SECOND_USER_ID, inputGestureData2.aidlData)

        // Irrespective of the current user, the data for second user should be updated correctly
        assertEquals(
            2,
            keyGestureController.getCustomInputGestures(SECOND_USER_ID, /* filter= */ null).size,
        )
    }

    @Test
    @Parameters(method = "customInputGesturesTestArguments")
    fun testCustomKeyGestureRestoredFromBackup(test: KeyGestureData) {
        setupKeyGestureController()
        val builder =
            InputGestureData.Builder()
                .setKeyGestureType(test.expectedKeyGestureType)
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        test.expectedKeys[0],
                        test.expectedModifierState,
                    )
                )
        if (test.expectedAppLaunchData != null) {
            builder.setAppLaunchData(test.expectedAppLaunchData)
        }
        val inputGestureData = builder.build()

        keyGestureController.setCurrentUserId(USER_ID)
        testLooper.dispatchAll()
        keyGestureController.addCustomInputGesture(USER_ID, inputGestureData.aidlData)
        testLooper.dispatchAll()
        val backupData = keyGestureController.getInputGestureBackupPayload(USER_ID)

        // Delete the old data and reinitialize the controller simulating a "fresh" install.
        testDataStore.clear()
        setupKeyGestureController()
        keyGestureController.setCurrentUserId(USER_ID)
        testLooper.dispatchAll()

        // Initially there should be no gestures registered.
        var savedInputGestures = keyGestureController.getCustomInputGestures(USER_ID, null)
        assertEquals(
            "Test: $test doesn't produce correct number of saved input gestures",
            0,
            savedInputGestures.size,
        )

        // After the restore, there should be the original gesture re-registered.
        keyGestureController.applyInputGesturesBackupPayload(backupData, USER_ID)
        savedInputGestures = keyGestureController.getCustomInputGestures(USER_ID, null)
        assertEquals(
            "Test: $test doesn't produce correct number of saved input gestures",
            1,
            savedInputGestures.size,
        )
        assertEquals(
            "Test: $test doesn't produce correct input gesture data",
            inputGestureData,
            InputGestureData(savedInputGestures[0]),
        )
    }

    class TouchpadTestData(
        val name: String,
        val touchpadGestureType: Int,
        val expectedKeyGestureType: Int,
        val expectedAction: Int,
        val expectedAppLaunchData: AppLaunchData? = null,
    ) {
        override fun toString(): String = name
    }

    @Keep
    private fun customTouchpadGesturesTestArguments(): Array<TouchpadTestData> {
        return arrayOf(
            TouchpadTestData(
                "3 Finger Tap -> Go Home",
                InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP,
                KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
                KeyGestureEvent.ACTION_GESTURE_COMPLETE,
            ),
            TouchpadTestData(
                "3 Finger Tap -> Launch app",
                InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP,
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION,
                KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                AppLaunchData.createLaunchDataForComponent("com.test", "com.test.BookmarkTest"),
            ),
        )
    }

    @Test
    @Parameters(method = "customTouchpadGesturesTestArguments")
    fun testCustomTouchpadGesture(test: TouchpadTestData) {
        setupKeyGestureController()
        val builder =
            InputGestureData.Builder()
                .setKeyGestureType(test.expectedKeyGestureType)
                .setTrigger(InputGestureData.createTouchpadTrigger(test.touchpadGestureType))
        if (test.expectedAppLaunchData != null) {
            builder.setAppLaunchData(test.expectedAppLaunchData)
        }
        val inputGestureData = builder.build()

        keyGestureController.addCustomInputGesture(USER_ID, inputGestureData.aidlData)

        val handledEvents = mutableListOf<KeyGestureEvent>()
        val handler = KeyGestureHandler { event, _ -> handledEvents.add(KeyGestureEvent(event)) }
        keyGestureController.registerKeyGestureHandler(
            intArrayOf(test.expectedKeyGestureType),
            handler,
            TEST_PID,
        )
        handledEvents.clear()

        keyGestureController.handleTouchpadGesture(test.touchpadGestureType)

        assertEquals(
            "Test: $test doesn't produce correct number of key gesture events",
            1,
            handledEvents.size,
        )
        val event = handledEvents[0]
        assertEquals(
            "Test: $test doesn't produce correct key gesture type",
            test.expectedKeyGestureType,
            event.keyGestureType,
        )
        assertEquals(
            "Test: $test doesn't produce correct key gesture action",
            test.expectedAction,
            event.action,
        )
        assertEquals(
            "Test: $test doesn't produce correct app launch data",
            test.expectedAppLaunchData,
            event.appLaunchData,
        )

        keyGestureController.unregisterKeyGestureHandler(handler, TEST_PID)
    }

    @Test
    @Parameters(method = "customTouchpadGesturesTestArguments")
    fun testCustomTouchpadGesturesSavedAndLoadedByController(test: TouchpadTestData) {
        setupKeyGestureController()
        val builder =
            InputGestureData.Builder()
                .setKeyGestureType(test.expectedKeyGestureType)
                .setTrigger(InputGestureData.createTouchpadTrigger(test.touchpadGestureType))
        if (test.expectedAppLaunchData != null) {
            builder.setAppLaunchData(test.expectedAppLaunchData)
        }
        val inputGestureData = builder.build()
        keyGestureController.setCurrentUserId(USER_ID)
        testLooper.dispatchAll()
        keyGestureController.addCustomInputGesture(USER_ID, inputGestureData.aidlData)
        testLooper.dispatchAll()

        // Reinitialize the gesture controller simulating a login/logout for the user.
        setupKeyGestureController()
        keyGestureController.setCurrentUserId(USER_ID)
        testLooper.dispatchAll()

        val savedInputGestures = keyGestureController.getCustomInputGestures(USER_ID, null)
        assertEquals(
            "Test: $test doesn't produce correct number of saved input gestures",
            1,
            savedInputGestures.size,
        )
        assertEquals(
            "Test: $test doesn't produce correct input gesture data",
            inputGestureData,
            InputGestureData(savedInputGestures[0]),
        )
    }

    @Test
    @Parameters(method = "customTouchpadGesturesTestArguments")
    fun testCustomTouchpadGesturesRestoredFromBackup(test: TouchpadTestData) {
        setupKeyGestureController()
        val builder =
            InputGestureData.Builder()
                .setKeyGestureType(test.expectedKeyGestureType)
                .setTrigger(InputGestureData.createTouchpadTrigger(test.touchpadGestureType))
        if (test.expectedAppLaunchData != null) {
            builder.setAppLaunchData(test.expectedAppLaunchData)
        }
        val inputGestureData = builder.build()
        keyGestureController.setCurrentUserId(USER_ID)
        testLooper.dispatchAll()
        keyGestureController.addCustomInputGesture(USER_ID, inputGestureData.aidlData)
        testLooper.dispatchAll()
        val backupData = keyGestureController.getInputGestureBackupPayload(USER_ID)

        // Delete the old data and reinitialize the controller simulating a "fresh" install.
        testDataStore.clear()
        setupKeyGestureController()
        keyGestureController.setCurrentUserId(USER_ID)
        testLooper.dispatchAll()

        // Initially there should be no gestures registered.
        var savedInputGestures = keyGestureController.getCustomInputGestures(USER_ID, null)
        assertEquals(
            "Test: $test doesn't produce correct number of saved input gestures",
            0,
            savedInputGestures.size,
        )

        // After the restore, there should be the original gesture re-registered.
        keyGestureController.applyInputGesturesBackupPayload(backupData, USER_ID)
        savedInputGestures = keyGestureController.getCustomInputGestures(USER_ID, null)
        assertEquals(
            "Test: $test doesn't produce correct number of saved input gestures",
            1,
            savedInputGestures.size,
        )
        assertEquals(
            "Test: $test doesn't produce correct input gesture data",
            inputGestureData,
            InputGestureData(savedInputGestures[0]),
        )
    }

    @Test
    fun testAccessibilityShortcutPressed() {
        setupKeyGestureController()

        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_Z)
        )
        Mockito.verify(accessibilityShortcutController, times(1)).performAccessibilityShortcut()
    }

    @Test
    fun testAccessibilityShortcutChordPressed() {
        setupKeyGestureController()

        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN),
            // Assuming this value is always greater than the accessibility shortcut timeout, which
            // currently defaults to 3000ms
            timeDelayMs = 10000,
        )
        Mockito.verify(accessibilityShortcutController, times(1)).performAccessibilityShortcut()
    }

    @Test
    fun testAccessibilityTvShortcutChordPressed() {
        setupKeyGestureController()

        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN),
            timeDelayMs = 10000,
            assertKeysFullyConsumed = false,
        )
        Mockito.verify(accessibilityShortcutController, times(1)).performAccessibilityShortcut()
    }

    @Test
    fun testAccessibilityShortcutChordPressedForLessThanTimeout() {
        setupKeyGestureController()

        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN),
            timeDelayMs = 0,
        )
        Mockito.verify(accessibilityShortcutController, never()).performAccessibilityShortcut()
    }

    @Test
    fun testAccessibilityTvShortcutChordPressedForLessThanTimeout() {
        setupKeyGestureController()

        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN),
            timeDelayMs = 0,
            assertKeysFullyConsumed = false,
        )
        Mockito.verify(accessibilityShortcutController, never()).performAccessibilityShortcut()
    }

    @Keep
    private fun keyCodesUsedForKeyCombinations(): Array<Int> {
        return arrayOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_STEM_PRIMARY,
        )
    }

    @Test
    @Parameters(method = "keyCodesUsedForKeyCombinations")
    fun testInterceptKeyCombinationForAccessibility_blocksKey_whenOngoingKeyCombination(
        keyCode: Int
    ) {
        setupKeyGestureController()

        val now = SystemClock.uptimeMillis()
        val downEvent =
            KeyEvent.obtain(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                /* repeat= */ 0,
                /* metaState */ 0,
                DEVICE_ID,
                /* scanCode= */ 0,
                /* flags= */ 0,
                InputDevice.SOURCE_KEYBOARD,
                /* displayId = */ 0,
                /* characters= */ "",
            )
        keyGestureController.interceptKeyBeforeQueueing(downEvent, FLAG_INTERACTIVE)
        testLooper.dispatchAll()

        // Assert that interceptKeyCombinationBeforeAccessibility returns the delay to wait
        // until key can be forwarded to A11y services (should be > 0 if there is ongoing gesture)
        assertTrue(keyGestureController.interceptKeyCombinationBeforeAccessibility(downEvent) > 0)
    }

    @Test
    @Parameters(method = "keyCodesUsedForKeyCombinations")
    fun testInterceptKeyCombinationForAccessibility_letsKeyPass_whenKeyCombinationTimedOut(
        keyCode: Int
    ) {
        setupKeyGestureController()

        val now = SystemClock.uptimeMillis()
        val downEvent =
            KeyEvent.obtain(
                now - COMBINE_KEY_DELAY_MILLIS,
                now - COMBINE_KEY_DELAY_MILLIS,
                KeyEvent.ACTION_DOWN,
                keyCode,
                /* repeat= */ 0,
                /* metaState */ 0,
                DEVICE_ID,
                /* scanCode= */ 0,
                /* flags= */ 0,
                InputDevice.SOURCE_KEYBOARD,
                /* displayId = */ 0,
                /* characters= */ "",
            )
        keyGestureController.interceptKeyBeforeQueueing(downEvent, FLAG_INTERACTIVE)
        testLooper.dispatchAll()

        assertEquals(0, keyGestureController.interceptKeyCombinationBeforeAccessibility(downEvent))
    }

    @Keep
    private fun screenshotTestArguments(): Array<KeyGestureData> {
        return arrayOf(
            KeyGestureData(
                "META + S -> Take Screenshot",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_S),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_S),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
            ),
            KeyGestureData(
                "SCREENSHOT -> Take Screenshot",
                intArrayOf(KeyEvent.KEYCODE_SCREENSHOT),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_SCREENSHOT),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
            ),
            KeyGestureData(
                "SYSRQ -> Take Screenshot",
                intArrayOf(KeyEvent.KEYCODE_SYSRQ),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_SYSRQ),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
            ),
        )
    }

    @Test
    @Parameters(method = "screenshotTestArguments")
    @DisableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_PARTIAL_SCREENSHOT_KEYBOARD_SHORTCUT)
    fun testScreenshotShortcuts(testData: KeyGestureData) {
        setupKeyGestureController()
        sendKeys(testData.keys, displayId = RANDOM_DISPLAY_ID)

        val requestCaptor = argumentCaptor<ScreenshotRequest>()
        Mockito.verify(screenshotHelper, times(1))
            .takeScreenshot(requestCaptor.capture(), any(), any())
        assertEquals(
            /* message= */ testData.name,
            WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER,
            requestCaptor.lastValue.source,
        )
        assertEquals(
            /* message= */ testData.name,
            RANDOM_DISPLAY_ID,
            requestCaptor.lastValue.displayId,
        )
    }

    @Keep
    private fun partialScreenshotTestArguments(): Array<KeyGestureData> {
        return arrayOf(
            KeyGestureData(
                "SYSRQ -> Take Screenshot (Flag ON, No Handler)",
                intArrayOf(KeyEvent.KEYCODE_SYSRQ),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_SYSRQ),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
                // Prevent testKeyGestureProduced() helper from registering a dummy handler.
                isGestureHandlerRegistered = true,
            ),
            KeyGestureData(
                "SCREENSHOT -> Take Screenshot (Flag ON, No Handler)",
                intArrayOf(KeyEvent.KEYCODE_SCREENSHOT),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_SCREENSHOT),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
                // Prevent testKeyGestureProduced() helper from registering a dummy handler.
                isGestureHandlerRegistered = true,
            ),
            KeyGestureData(
                "SYSRQ -> Take Partial Screenshot (Flag ON, Handler registered)",
                intArrayOf(KeyEvent.KEYCODE_SYSRQ),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_PARTIAL_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_SYSRQ),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
                // Tells testKeyGestureProduced() helper to register a dummy handler.
                isGestureHandlerRegistered = false,
            ),
            KeyGestureData(
                "SCREENSHOT -> Take Partial Screenshot (Flag ON, Handler registered)",
                intArrayOf(KeyEvent.KEYCODE_SCREENSHOT),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_PARTIAL_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_SCREENSHOT),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE),
                // Tells testKeyGestureProduced() helper to register a dummy handler.
                isGestureHandlerRegistered = false,
            ),
        )
    }

    @Test
    @Parameters(method = "partialScreenshotTestArguments")
    @EnableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_PARTIAL_SCREENSHOT_KEYBOARD_SHORTCUT)
    fun testPartialScreenshotShortcuts(testData: KeyGestureData) {
        setupKeyGestureController()
        testKeyGestureProduced(testData, PASS_THROUGH_APP)

        if (testData.isGestureHandlerRegistered) {
            // Dummy handler was not registered for KEY_GESTURE_TYPE_TAKE_PARTIAL_SCREENSHOT.
            // Default action of taking a screenshot is expected.
            val requestCaptor = argumentCaptor<ScreenshotRequest>()
            Mockito.verify(screenshotHelper, times(1))
                .takeScreenshot(requestCaptor.capture(), any(), any())
            assertEquals(
                /* message= */ testData.name,
                WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER,
                requestCaptor.lastValue.source,
            )
        } else {
            // A dummy handler is registered for KEY_GESTURE_TYPE_TAKE_PARTIAL_SCREENSHOT. No
            // screenshot should be taken.
            Mockito.verify(screenshotHelper, never())
                .takeScreenshot(any(ScreenshotRequest::class.java), any(), any())
        }
    }

    @Test
    fun testScreenshotShortcutChordPressed_forMoreThanTimeout() {
        setupKeyGestureController()

        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER),
            timeDelayMs = 10 * SCREENSHOT_CHORD_DELAY,
        )
        val requestCaptor = argumentCaptor<ScreenshotRequest>()
        Mockito.verify(screenshotHelper, times(1))
            .takeScreenshot(requestCaptor.capture(), any(), any())
        assertEquals(
            WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD,
            requestCaptor.lastValue.source,
        )
        assertEquals(DEFAULT_DISPLAY, requestCaptor.lastValue.displayId)
    }

    @Test
    fun testScreenshotShortcutChordPressed_forLessThanTimeout() {
        setupKeyGestureController()

        sendKeys(intArrayOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER), timeDelayMs = 0)
        Mockito.verify(screenshotHelper, never())
            .takeScreenshot(any(ScreenshotRequest::class.java), any(), any())
    }

    @Test
    fun testWearScreenshotShortcutChordPressed_forMoreThanTimeout() {
        setupKeyGestureController()

        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_STEM_PRIMARY),
            timeDelayMs = 10 * SCREENSHOT_CHORD_DELAY,
        )
        val requestCaptor = argumentCaptor<ScreenshotRequest>()
        Mockito.verify(screenshotHelper, times(1))
            .takeScreenshot(requestCaptor.capture(), any(), any())
        assertEquals(
            WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD,
            requestCaptor.lastValue.source,
        )
        assertEquals(DEFAULT_DISPLAY, requestCaptor.lastValue.displayId)
    }

    @Test
    fun testWearScreenshotShortcutChordPressed_forLessThanTimeout() {
        setupKeyGestureController()

        sendKeys(intArrayOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_STEM_PRIMARY), timeDelayMs = 0)
        Mockito.verify(screenshotHelper, never())
            .takeScreenshot(any(ScreenshotRequest::class.java), any(), any())
    }

    @Test
    fun testUnableToRegisterFromSamePidTwice() {
        setupKeyGestureController()

        val handler1 = KeyGestureHandler { _, _ -> }
        val handler2 = KeyGestureHandler { _, _ -> }
        keyGestureController.registerKeyGestureHandler(
            intArrayOf(KeyGestureEvent.KEY_GESTURE_TYPE_HOME),
            handler1,
            RANDOM_PID1,
        )

        assertThrows(IllegalStateException::class.java) {
            keyGestureController.registerKeyGestureHandler(
                intArrayOf(KeyGestureEvent.KEY_GESTURE_TYPE_BACK),
                handler2,
                RANDOM_PID1,
            )
        }
    }

    @Test
    fun testUnableToRegisterSameGestureTwice() {
        setupKeyGestureController()

        val handler1 = KeyGestureHandler { _, _ -> }
        val handler2 = KeyGestureHandler { _, _ -> }
        keyGestureController.registerKeyGestureHandler(
            intArrayOf(KeyGestureEvent.KEY_GESTURE_TYPE_HOME),
            handler1,
            RANDOM_PID1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            keyGestureController.registerKeyGestureHandler(
                intArrayOf(KeyGestureEvent.KEY_GESTURE_TYPE_HOME),
                handler2,
                RANDOM_PID2,
            )
        }
    }

    @Test
    fun testUnableToRegisterEmptyListOfGestures() {
        setupKeyGestureController()

        val handler = KeyGestureHandler { _, _ -> }

        assertThrows(IllegalArgumentException::class.java) {
            keyGestureController.registerKeyGestureHandler(intArrayOf(), handler, RANDOM_PID1)
        }
    }

    @Test
    fun testGestureHandlerNotCalledOnceUnregistered() {
        setupKeyGestureController()

        var callbackCount = 0
        val handler1 = KeyGestureHandler { _, _ -> callbackCount++ }
        keyGestureController.registerKeyGestureHandler(
            intArrayOf(KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS),
            handler1,
            TEST_PID,
        )
        sendKeys(intArrayOf(KeyEvent.KEYCODE_RECENT_APPS))
        assertEquals(1, callbackCount)

        keyGestureController.unregisterKeyGestureHandler(handler1, TEST_PID)

        // Callback should not be sent after unregister
        sendKeys(intArrayOf(KeyEvent.KEYCODE_RECENT_APPS))
        assertEquals(1, callbackCount)
    }

    @Test
    fun testActionKeyEventsForwardedToFocusedWindow_whenCorrectlyRequested() {
        setupKeyGestureController()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */ true,
            /* hasPrivateFlag = */ true,
        )
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(any(), any())).thenReturn(true)

        for (event in ACTION_KEY_EVENTS) {
            assertEquals(0, keyGestureController.interceptKeyBeforeDispatching(null, event, 0))
        }
    }

    @Test
    fun testActionKeyEventsNotForwardedToFocusedWindow_whenNoPermissions() {
        setupKeyGestureController()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */ false,
            /* hasPrivateFlag = */ true,
        )
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(any(), any())).thenReturn(true)

        for (event in ACTION_KEY_EVENTS) {
            assertNotEquals(0, keyGestureController.interceptKeyBeforeDispatching(null, event, 0))
        }
    }

    @Test
    fun testActionKeyEventsNotForwardedToFocusedWindow_whenNoPrivateFlag() {
        setupKeyGestureController()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */ true,
            /* hasPrivateFlag = */ false,
        )
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(any(), any())).thenReturn(true)

        for (event in ACTION_KEY_EVENTS) {
            assertNotEquals(0, keyGestureController.interceptKeyBeforeDispatching(null, event, 0))
        }
    }

    @Test
    fun testKeyEventsForwardedToFocusedWindow_whenWmAllows() {
        setupKeyGestureController()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */ false,
            /* hasPrivateFlag = */ false,
        )
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(any(), any())).thenReturn(false)

        val event =
            KeyEvent(
                /* downTime= */ 0,
                /* eventTime= */ 0,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SPACE,
                /* repeat= */ 0,
                KeyEvent.META_CTRL_ON,
            )
        assertEquals(0, keyGestureController.interceptKeyBeforeDispatching(null, event, 0))
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_FIX_SEARCH_MODIFIER_FALLBACKS)
    fun testInterceptKeyBeforeDispatchingWithFallthroughEvent() {
        val mockKcm = Mockito.mock(KeyCharacterMap::class.java)
        ExtendedMockito.`when`(KeyCharacterMap.load(anyInt())).thenReturn(mockKcm)
        setupKeyGestureController()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */ false,
            /* hasPrivateFlag = */ false,
        )
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(any(), any())).thenReturn(false)

        // Create a fallback for a key event with a meta modifier. Should result in -2,
        // which represents the fallback event, which indicates that original key event will
        // be ignored (not sent to app) and instead the fallback will be created and sent to the
        // app.
        val fallbackAction: KeyCharacterMap.FallbackAction = KeyCharacterMap.FallbackAction.obtain()
        fallbackAction.keyCode = KeyEvent.KEYCODE_SEARCH
        Mockito.`when`(mockKcm.getFallbackAction(anyInt(), anyInt())).thenReturn(fallbackAction)

        val event =
            KeyEvent(
                /* downTime= */ 0,
                /* eventTime= */ 0,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MACRO_1, // Random valid keycode
                /* repeat= */ 0,
                KeyEvent.META_META_ON,
            )
        assertEquals(-2, keyGestureController.interceptKeyBeforeDispatching(null, event, 0))
    }

    @Test
    fun testKeyEventsNotForwardedToFocusedWindow_whenWmConsumes() {
        setupKeyGestureController()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */ false,
            /* hasPrivateFlag = */ false,
        )
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(any(), any())).thenReturn(true)

        val event =
            KeyEvent(
                /* downTime= */ 0,
                /* eventTime= */ 0,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SPACE,
                /* repeat= */ 0,
                KeyEvent.META_CTRL_ON,
            )
        assertEquals(-1, keyGestureController.interceptKeyBeforeDispatching(null, event, 0))
    }

    @Test
    fun testSecurePasteShortcuts_passThroughBeforeDispatch() {
        setupKeyGestureController()
        val focus = Binder()
        configureSecurePasteGrant(focus)
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(any(), any())).thenReturn(false)

        var shortcutCallCount = 0
        val shortcutService =
            object : IShortcutService.Stub() {
                override fun notifyShortcutKeyPressed(shortcutCode: Long) {
                    shortcutCallCount++
                }
            }
        registerPasteShortcutServices(shortcutService)

        for (event in createPasteShortcutEvents()) {
            assertEquals(
                0,
                keyGestureController.interceptKeyBeforeDispatching(focus, event, FLAG_TRUSTED),
            )
            Mockito.verify(wmCallbacks).interceptKeyBeforeDispatching(focus, event)
        }
        assertEquals(0, shortcutCallCount)
    }

    @Test
    fun testSecurePasteShortcuts_notInterceptedWhenUnhandled() {
        setupKeyGestureController()
        val focus = Binder()

        var shortcutCallCount = 0
        val shortcutService =
            object : IShortcutService.Stub() {
                override fun notifyShortcutKeyPressed(shortcutCode: Long) {
                    shortcutCallCount++
                }
            }
        registerPasteShortcutServices(shortcutService)

        for (event in createPasteShortcutEvents()) {
            assertFalse(keyGestureController.interceptUnhandledKey(event, focus))
        }
        assertEquals(0, shortcutCallCount)
    }

    @Test
    fun testSecurePasteGrant_onlyForTrustedInitialDown() {
        setupKeyGestureController()
        val focus = Binder()
        configureSecurePasteGrant(focus)
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(any(), any())).thenReturn(false)

        val initialDown = createPasteKeyEvent(KeyEvent.ACTION_DOWN)
        val repeatDown = createPasteKeyEvent(KeyEvent.ACTION_DOWN, repeatCount = 1)
        val up = createPasteKeyEvent(KeyEvent.ACTION_UP)
        val canceledDown =
            createPasteKeyEvent(KeyEvent.ACTION_DOWN, eventFlags = KeyEvent.FLAG_CANCELED)

        assertEquals(
            0,
            keyGestureController.interceptKeyBeforeDispatching(focus, initialDown, FLAG_TRUSTED),
        )
        assertEquals(
            0,
            keyGestureController.interceptKeyBeforeDispatching(focus, repeatDown, FLAG_TRUSTED),
        )
        assertEquals(
            0,
            keyGestureController.interceptKeyBeforeDispatching(focus, up, FLAG_TRUSTED),
        )
        assertEquals(
            0,
            keyGestureController.interceptKeyBeforeDispatching(focus, canceledDown, FLAG_TRUSTED),
        )
        assertEquals(
            0,
            keyGestureController.interceptKeyBeforeDispatching(
                focus,
                createPasteKeyEvent(KeyEvent.ACTION_DOWN),
                /* policyFlags = */ 0,
            ),
        )
        Mockito.`when`(windowManagerInternal.getInputTargetInfo(focus))
            .thenReturn(InputTargetInfo(TEST_UID, INVALID_DISPLAY))
        assertEquals(
            0,
            keyGestureController.interceptKeyBeforeDispatching(
                focus,
                createPasteKeyEvent(KeyEvent.ACTION_DOWN),
                FLAG_TRUSTED,
            ),
        )

        Mockito.verify(clipboardManagerInternal, times(1))
            .createPasteGrantForDisplay(TEST_UID, DEFAULT_DISPLAY)
        Mockito.verifyNoMoreInteractions(clipboardManagerInternal)
    }

    @Test
    fun testSecurePasteGrant_notCreatedWhenWmConsumes() {
        setupKeyGestureController()
        val focus = Binder()
        configureSecurePasteGrant(focus)
        Mockito.`when`(wmCallbacks.interceptKeyBeforeDispatching(Mockito.eq(focus), any()))
            .thenReturn(true)
        val event = createPasteKeyEvent(KeyEvent.ACTION_DOWN)

        assertEquals(
            -1,
            keyGestureController.interceptKeyBeforeDispatching(focus, event, FLAG_TRUSTED),
        )
        Mockito.verify(clipboardManagerInternal, never())
            .createPasteGrantForDisplay(anyInt(), anyInt())
    }

    @Test
    fun testLongPressEscape_withKeyCapture_exitGestureCompleted() {
        setupKeyGestureController()
        enableKeyCaptureForFocussedWindow()
        val events = mutableListOf<KeyGestureEvent>()
        val handler = KeyGestureHandler { event, _ -> events.add(KeyGestureEvent(event)) }
        keyGestureController.registerKeyGestureHandler(
            intArrayOf(KeyGestureEvent.KEY_GESTURE_TYPE_QUIT_FOCUSED_TASK),
            handler,
            TEST_PID,
        )
        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_ESCAPE),
            timeDelayMs = 2 * LONG_PRESS_DELAY_FOR_ESCAPE_MILLIS,
            appDelegate = BLOCKING_APP,
        )
        keyGestureController.unregisterKeyGestureHandler(handler, TEST_PID)
        assertEquals(2, events.size)
        assertEquals(KeyGestureEvent.ACTION_GESTURE_COMPLETE, events[1].action)
        assertFalse(events[1].isCancelled)
    }

    @Test
    fun testLongPressEscape_withoutKeyCapture_exitGestureNotCalled() {
        setupKeyGestureController()
        val events = mutableListOf<KeyGestureEvent>()
        val handler = KeyGestureHandler { event, _ -> events.add(KeyGestureEvent(event)) }
        keyGestureController.registerKeyGestureHandler(
            intArrayOf(KeyGestureEvent.KEY_GESTURE_TYPE_QUIT_FOCUSED_TASK),
            handler,
            TEST_PID,
        )
        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_ESCAPE),
            timeDelayMs = 2 * LONG_PRESS_DELAY_FOR_ESCAPE_MILLIS,
            appDelegate = BLOCKING_APP,
        )
        keyGestureController.unregisterKeyGestureHandler(handler, TEST_PID)
        assertEquals(0, events.size)
    }

    @Test
    fun testLongPressEscape_withKeyCapture_insufficientDuration_exitGestureCancelled() {
        setupKeyGestureController()
        enableKeyCaptureForFocussedWindow()
        val events = mutableListOf<KeyGestureEvent>()
        val handler = KeyGestureHandler { event, _ -> events.add(KeyGestureEvent(event)) }
        keyGestureController.registerKeyGestureHandler(
            intArrayOf(KeyGestureEvent.KEY_GESTURE_TYPE_QUIT_FOCUSED_TASK),
            handler,
            TEST_PID,
        )
        sendKeys(
            intArrayOf(KeyEvent.KEYCODE_ESCAPE),
            timeDelayMs = LONG_PRESS_DELAY_FOR_ESCAPE_MILLIS / 2,
            appDelegate = BLOCKING_APP,
        )
        keyGestureController.unregisterKeyGestureHandler(handler, TEST_PID)
        assertEquals(2, events.size)
        assertEquals(KeyGestureEvent.ACTION_GESTURE_COMPLETE, events[1].action)
        assertTrue(events[1].isCancelled)
    }

    private fun testKeyGestureProduced(test: KeyGestureData, appDelegate: AppDelegate) {
        val events = mutableListOf<KeyGestureEvent>()
        val listener = KeyGestureEventListener { event -> events.add(KeyGestureEvent(event)) }
        var handler: KeyGestureHandler? = null
        keyGestureController.registerKeyGestureEventListener(listener, TEST_PID)
        if (!test.isGestureHandlerRegistered) {
            // Register a dummy handler so that gestures are generated
            handler = KeyGestureHandler { _, _ -> }
            keyGestureController.registerKeyGestureHandler(
                intArrayOf(test.expectedKeyGestureType),
                handler,
                TEST_PID,
            )
        }

        sendKeys(test.keys, appDelegate)

        assertEquals(
            "Test: $test doesn't produce correct number of key gesture events",
            test.expectedActions.size,
            events.size,
        )
        for (i in events.indices) {
            val event = events[i]
            assertArrayEquals(
                "Test: $test doesn't produce correct key gesture keycodes",
                test.expectedKeys,
                event.keycodes,
            )
            assertEquals(
                "Test: $test doesn't produce correct key gesture modifier state",
                test.expectedModifierState,
                event.modifierState,
            )
            assertEquals(
                "Test: $test doesn't produce correct key gesture type",
                test.expectedKeyGestureType,
                event.keyGestureType,
            )
            assertEquals(
                "Test: $test doesn't produce correct key gesture action",
                test.expectedActions[i],
                event.action,
            )
            assertEquals(
                "Test: $test doesn't produce correct app launch data",
                test.expectedAppLaunchData,
                event.appLaunchData,
            )
        }

        keyGestureController.unregisterKeyGestureEventListener(listener, TEST_PID)
        if (handler != null) {
            keyGestureController.unregisterKeyGestureHandler(handler, TEST_PID)
        }
    }

    private fun testKeyGestureNotProduced(test: KeyGestureData, appDelegate: AppDelegate) {
        val events = mutableListOf<KeyGestureEvent>()
        val listener = KeyGestureEventListener { event -> events.add(KeyGestureEvent(event)) }
        var handler: KeyGestureHandler? = null
        keyGestureController.registerKeyGestureEventListener(listener, TEST_PID)
        if (!test.isGestureHandlerRegistered) {
            // Register a dummy handler so that gestures are generated
            handler = KeyGestureHandler { _, _ -> }
            keyGestureController.registerKeyGestureHandler(
                intArrayOf(test.expectedKeyGestureType),
                handler,
                TEST_PID,
            )
        }
        events.clear()

        sendKeys(test.expectedKeys, appDelegate)
        assertEquals("Test: ${test.name} should not produce Key gesture", 0, events.size)

        keyGestureController.unregisterKeyGestureEventListener(listener, TEST_PID)
        if (handler != null) {
            keyGestureController.unregisterKeyGestureHandler(handler, TEST_PID)
        }
    }

    private fun sendKeys(
        testKeys: IntArray,
        appDelegate: AppDelegate = PASS_THROUGH_APP,
        timeDelayMs: Long = 0,
        displayId: Int = DEFAULT_DISPLAY,
        assertKeysFullyConsumed: Boolean = true,
    ) {
        var metaState = 0
        val now = SystemClock.uptimeMillis()
        for (key in testKeys) {
            val downEvent =
                KeyEvent.obtain(
                    now,
                    now,
                    KeyEvent.ACTION_DOWN,
                    key,
                    /* repeat= */ 0,
                    metaState,
                    DEVICE_ID,
                    /* scanCode= */ 0,
                    /* flags= */ 0,
                    InputDevice.SOURCE_KEYBOARD,
                    displayId,
                    /* characters= */ "",
                )
            val consumed = interceptKey(downEvent, appDelegate)
            if (assertKeysFullyConsumed) {
                assertTrue("Key $downEvent should be consumed", consumed)
            }
            metaState = metaState or MODIFIER.getOrDefault(key, 0)

            downEvent.recycle()
            testLooper.dispatchAll()
        }

        if (timeDelayMs > 0) {
            testLooper.moveTimeForward(timeDelayMs)
            testLooper.dispatchAll()
        }

        for (key in testKeys.reversed()) {
            val upEvent =
                KeyEvent.obtain(
                    now,
                    now,
                    KeyEvent.ACTION_UP,
                    key,
                    /* repeat= */ 0,
                    metaState,
                    DEVICE_ID,
                    /* scanCode= */ 0,
                    /* flags= */ 0,
                    InputDevice.SOURCE_KEYBOARD,
                    displayId,
                    /* characters= */ "",
                )
            val consumed = interceptKey(upEvent, appDelegate)
            if (assertKeysFullyConsumed) {
                assertTrue("Key $upEvent should be consumed", consumed)
            }
            metaState = metaState and MODIFIER.getOrDefault(key, 0).inv()

            upEvent.recycle()
            testLooper.dispatchAll()
        }
    }

    private fun interceptKey(event: KeyEvent, appDelegate: AppDelegate): Boolean {
        keyGestureController.interceptKeyBeforeQueueing(event, FLAG_INTERACTIVE)
        testLooper.dispatchAll()

        if (keyGestureController.interceptKeyBeforeDispatching(null, event, 0) != 0L) {
            return true
        }
        if (appDelegate.consumeKey(event)) {
            return true
        }
        if (keyGestureController.interceptUnhandledKey(event, null)) {
            return true
        }
        if (KeyEvent.isModifierKey(event.keyCode)) {
            return true
        }
        return false
    }

    private fun configureSecurePasteGrant(focus: IBinder) {
        Mockito.`when`(windowManagerInternal.getInputTargetInfo(focus))
            .thenReturn(InputTargetInfo(TEST_UID, DEFAULT_DISPLAY))
        ExtendedMockito.doReturn(clipboardManagerInternal).`when` {
            LocalServices.getService(
                ArgumentMatchers.eq(ClipboardManagerInternal::class.java)
            )
        }
    }

    private fun registerPasteShortcutServices(shortcutService: IShortcutService) {
        for (event in createPasteShortcutEvents()) {
            val shortcutCode =
                event.keyCode.toLong() or (event.metaState.toLong() shl Integer.SIZE)
            keyGestureController.registerShortcutKey(shortcutCode, shortcutService)
        }
    }

    private fun createPasteShortcutEvents(): List<KeyEvent> =
        listOf(
            createPasteKeyEvent(KeyEvent.ACTION_DOWN),
            createPasteKeyEvent(
                KeyEvent.ACTION_DOWN,
                metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
            ),
            createPasteKeyEvent(
                KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_INSERT,
                metaState = KeyEvent.META_SHIFT_ON,
            ),
            createPasteKeyEvent(
                KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_PASTE,
                metaState = 0,
            ),
        )

    private fun createPasteKeyEvent(
        action: Int,
        keyCode: Int = KeyEvent.KEYCODE_V,
        metaState: Int = KeyEvent.META_CTRL_ON,
        repeatCount: Int = 0,
        eventFlags: Int = 0,
    ): KeyEvent =
        KeyEvent(
            /* downTime = */ 0,
            /* eventTime = */ 0,
            action,
            keyCode,
            repeatCount,
            metaState,
            DEVICE_ID,
            /* scanCode = */ 0,
            eventFlags,
            InputDevice.SOURCE_KEYBOARD,
        )

    fun overrideSendActionKeyEventsToFocusedWindow(
        hasPermission: Boolean,
        hasPrivateFlag: Boolean,
    ) {
        ExtendedMockito.doReturn(
                if (hasPermission) {
                    PermissionChecker.PERMISSION_GRANTED
                } else {
                    PermissionChecker.PERMISSION_HARD_DENIED
                }
            )
            .`when` {
                PermissionChecker.checkPermissionForDataDelivery(
                    any(),
                    eq(Manifest.permission.OVERRIDE_SYSTEM_KEY_BEHAVIOR_IN_FOCUSED_WINDOW),
                    anyInt(),
                    anyInt(),
                    any(),
                    any(),
                    any(),
                )
            }

        val info =
            KeyInterceptionInfo(
                /* type = */ 0,
                if (hasPrivateFlag) {
                    WindowManager.LayoutParams.PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS
                } else {
                    0
                },
                /* inputFeatures = */ 0,
                "title",
                /* uid = */ 0,
            )
        Mockito.`when`(windowManagerInternal.getKeyInterceptionInfoFromToken(any()))
            .thenReturn(info)
    }

    fun enableKeyCaptureForFocussedWindow() {
        ExtendedMockito.doReturn(PermissionChecker.PERMISSION_GRANTED).`when` {
            PermissionChecker.checkPermissionForDataDelivery(
                any(),
                eq(Manifest.permission.CAPTURE_KEYBOARD),
                anyInt(),
                anyInt(),
                any(),
                any(),
                any(),
            )
        }

        val info =
            KeyInterceptionInfo(
                /* type = */ 0,
                /* flags = */ 0,
                WindowManager.LayoutParams.INPUT_FEATURE_CAPTURE_KEYBOARD,
                "title",
                /* uid = */ 0,
            )
        Mockito.`when`(windowManagerInternal.getKeyInterceptionInfoFromToken(any()))
            .thenReturn(info)
    }

    inner class KeyGestureEventListener(
        private var listener: (event: AidlKeyGestureEvent) -> Unit
    ) : IKeyGestureEventListener.Stub() {
        override fun onKeyGestureEvent(event: AidlKeyGestureEvent) {
            listener(event)
        }
    }

    inner class KeyGestureHandler(
        private var handler: (event: AidlKeyGestureEvent, token: IBinder?) -> Unit
    ) : IKeyGestureHandler.Stub() {
        override fun handleKeyGesture(event: AidlKeyGestureEvent, token: IBinder?) {
            handler(event, token)
        }
    }

    class AppDelegate(private var keyConsumer: (event: KeyEvent) -> Boolean) {
        fun consumeKey(event: KeyEvent): Boolean {
            return keyConsumer.invoke(event)
        }
    }
}
