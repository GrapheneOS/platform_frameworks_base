/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server;

import static android.view.flags.Flags.FLAG_SENSITIVE_CONTENT_APP_PROTECTION;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.role.RoleManager;
import android.companion.AssociationRequest;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.content.pm.PackageManagerInternal;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.util.ArraySet;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.server.wm.WindowManagerInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RequiresFlagsEnabled(FLAG_SENSITIVE_CONTENT_APP_PROTECTION)
/**
 * Test {@link SensitiveContentProtectionManagerService} for sensitive on screen content
 * protection, the service protects sensitive content during screen share.
 */
public class SensitiveContentProtectionManagerServiceContentTest {
    private final PackageInfo mPackageInfo =
            new PackageInfo("test.package", 12345, new Binder());
    private final String mScreenRecorderPackage = "test.screen.recorder.package";
    private final String mExemptedScreenRecorderPackage = "test.exempted.screen.recorder.package";
    private SensitiveContentProtectionManagerService mSensitiveContentProtectionManagerService;
    private MediaProjectionManager.Callback mMediaPorjectionCallback;

    @Mock private WindowManagerInternal mWindowManager;
    @Mock private MediaProjectionManager mProjectionManager;
    @Mock private PackageManagerInternal mPackageManagerInternal;
    @Mock private RoleManager mRoleManager;
    private MediaProjectionInfo mMediaProjectionInfo;

    @Captor
    private ArgumentCaptor<MediaProjectionManager.Callback> mMediaProjectionCallbackCaptor;
    @Captor
    private ArgumentCaptor<ArraySet<PackageInfo>> mPackageInfoCaptor;

    @Rule
    public final TestableContext mContext =
            new TestableContext(getInstrumentation().getTargetContext(), null);
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSensitiveContentProtectionManagerService =
                new SensitiveContentProtectionManagerService(mContext);
        mSensitiveContentProtectionManagerService.init(mProjectionManager, mWindowManager,
                mPackageManagerInternal, mRoleManager,
                new ArraySet<>(Set.of(mExemptedScreenRecorderPackage)));
        verify(mProjectionManager).addCallback(mMediaProjectionCallbackCaptor.capture(), any());
        mMediaPorjectionCallback = mMediaProjectionCallbackCaptor.getValue();
        mMediaProjectionInfo =
                new MediaProjectionInfo(mScreenRecorderPackage, Process.myUserHandle(), null);
    }

    @Test
    public void testExemptedRecorderPackageForScreenCapture() {
        MediaProjectionInfo exemptedRecorderPackage = new MediaProjectionInfo(
                mExemptedScreenRecorderPackage, Process.myUserHandle(), null);
        mMediaPorjectionCallback.onStart(exemptedRecorderPackage);
        mSensitiveContentProtectionManagerService.setSensitiveContentProtection(
                mPackageInfo.getWindowToken(), mPackageInfo.getPkg(), mPackageInfo.getUid(), true);
        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void testBlockAppWindowForScreenCapture() {
        mMediaPorjectionCallback.onStart(mMediaProjectionInfo);
        mSensitiveContentProtectionManagerService.setSensitiveContentProtection(
                mPackageInfo.getWindowToken(), mPackageInfo.getPkg(), mPackageInfo.getUid(), true);
        verify(mWindowManager, atLeast(1))
                .addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
        assertThat(mPackageInfoCaptor.getValue()).containsExactly(mPackageInfo);
    }

    @Test
    public void testUnblockAppWindowForScreenCapture() {
        mMediaPorjectionCallback.onStart(mMediaProjectionInfo);
        mSensitiveContentProtectionManagerService.setSensitiveContentProtection(
                mPackageInfo.getWindowToken(), mPackageInfo.getPkg(), mPackageInfo.getUid(), false);
        verify(mWindowManager).removeBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
        assertThat(mPackageInfoCaptor.getValue()).containsExactly(mPackageInfo);
    }

    @Test
    public void testAppWindowIsUnblockedBeforeScreenCapture() {
        // when screen sharing is not active, no app window should be blocked.
        mSensitiveContentProtectionManagerService.setSensitiveContentProtection(
                mPackageInfo.getWindowToken(), mPackageInfo.getPkg(), mPackageInfo.getUid(), true);
        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void testAppWindowsAreUnblockedOnScreenCaptureEnd() {
        mMediaPorjectionCallback.onStart(mMediaProjectionInfo);
        mSensitiveContentProtectionManagerService.setSensitiveContentProtection(
                mPackageInfo.getWindowToken(), mPackageInfo.getPkg(), mPackageInfo.getUid(), true);
        // when screen sharing ends, all blocked app windows should be cleared.
        mMediaPorjectionCallback.onStop(mMediaProjectionInfo);
        verify(mWindowManager).clearBlockedApps();
    }

    @Test
    public void testScreenRecordingOverridesApplyToExemptedRecorder() {
        int userId = Process.myUserHandle().getIdentifier();
        PackageStateInternal blockedApp = mockPackageState("test.blocked", 10001, userId, false);
        PackageStateInternal allowedApp = mockPackageState("test.allowed", 10002, userId, true);
        PackageStateInternal defaultApp = mockPackageState("test.default", 10003, userId, null);
        doAnswer(invocation -> {
            Consumer<PackageStateInternal> action = invocation.getArgument(0);
            action.accept(blockedApp);
            action.accept(allowedApp);
            action.accept(defaultApp);
            return null;
        }).when(mPackageManagerInternal).forEachPackageState(any());

        MediaProjectionInfo exemptedRecorderPackage = new MediaProjectionInfo(
                mExemptedScreenRecorderPackage, Process.myUserHandle(), null);
        mMediaPorjectionCallback.onStart(exemptedRecorderPackage);
        verify(mWindowManager).setScreenRecordingOverrides(
                new ArraySet<>(Set.of(new PackageInfo("test.blocked",
                        UserHandle.getUid(userId, 10001)))),
                new ArraySet<>(Set.of(new PackageInfo("test.allowed",
                        UserHandle.getUid(userId, 10002)))));

        mMediaPorjectionCallback.onStop(exemptedRecorderPackage);
        verify(mWindowManager).setScreenRecordingOverrides(new ArraySet<>(), new ArraySet<>());
    }

    private static PackageStateInternal mockPackageState(String packageName, int appId,
            int userId, Boolean allowScreenRecording) {
        long flags = 0;
        if (allowScreenRecording != null) {
            flags |= 1L << GosPackageStateFlag.ALLOW_SCREEN_RECORDING_NON_DEFAULT;
            if (allowScreenRecording) {
                flags |= 1L << GosPackageStateFlag.ALLOW_SCREEN_RECORDING;
            }
        }
        PackageUserStateInternal userState = mock(PackageUserStateInternal.class);
        when(userState.isInstalled()).thenReturn(true);
        when(userState.getGosPackageState()).thenReturn(
                new GosPackageState(flags, 0L, null, null));
        SparseArray<PackageUserStateInternal> userStates = new SparseArray<>();
        userStates.put(userId, userState);

        PackageStateInternal ps = mock(PackageStateInternal.class);
        when(ps.getPackageName()).thenReturn(packageName);
        when(ps.getAppId()).thenReturn(appId);
        doReturn(userStates).when(ps).getUserStates();
        return ps;
    }

    @Test
    public void testAutofillServicePackageExemption() {
        String testAutofillService = mScreenRecorderPackage + "/com.example.SampleAutofillService";
        int userId = Process.myUserHandle().getIdentifier();
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.AUTOFILL_SERVICE, testAutofillService, userId);

        mMediaPorjectionCallback.onStart(mMediaProjectionInfo);
        mSensitiveContentProtectionManagerService.setSensitiveContentProtection(
                mPackageInfo.getWindowToken(), mPackageInfo.getPkg(), mPackageInfo.getUid(), true);
        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void testDeveloperOptionDisableFeature() {
        mockDisabledViaDeveloperOption();
        mMediaProjectionCallbackCaptor.getValue().onStart(mMediaProjectionInfo);
        mSensitiveContentProtectionManagerService.setSensitiveContentProtection(
                mPackageInfo.getWindowToken(), mPackageInfo.getPkg(), mPackageInfo.getUid(), true);
        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void testAppStreamingRoleHolderExemption() {
        when(mRoleManager.getRoleHoldersAsUser(
                AssociationRequest.DEVICE_PROFILE_APP_STREAMING,
                mMediaProjectionInfo.getUserHandle())).thenReturn(
                List.of(mMediaProjectionInfo.getPackageName()));

        mMediaPorjectionCallback.onStart(mMediaProjectionInfo);
        mSensitiveContentProtectionManagerService.setSensitiveContentProtection(
                mPackageInfo.getWindowToken(), mPackageInfo.getPkg(), mPackageInfo.getUid(), true);
        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    private void mockDisabledViaDeveloperOption() {
        Settings.Global.putInt(
                mContext.getContentResolver(),
                Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS,
                1);
    }
}
