/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_UNKNOWN;
import static android.content.ContentResolver.SCHEME_CONTENT;
import static android.content.Intent.FILL_IN_ACTION;
import static android.content.pm.ApplicationInfo.BACKUP_AGENT_PROCESS_MAIN;
import static android.content.pm.ApplicationInfo.BACKUP_AGENT_PROCESS_PCC;
import static android.os.PowerExemptionManager.REASON_DENIED;
import static android.os.UserHandle.USER_ALL;
import static android.util.DebugUtils.valueToString;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.am.ActivityManagerInternalTest.CustomThread;
import static com.android.server.am.ActivityManagerService.Injector;
import static com.android.server.am.ProcessList.NETWORK_STATE_BLOCK;
import static com.android.server.am.ProcessList.NETWORK_STATE_NO_CHANGE;
import static com.android.server.am.ProcessList.NETWORK_STATE_UNBLOCK;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessState;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.ApplicationThreadConstants;
import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.app.ForegroundServiceDelegationOptions;
import android.app.IUidObserver;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.SyncNotedAppOp;
import android.app.backup.BackupAnnotations;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.AllowComponentAccessPolicyInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.content.pm.SignedPackage;
import android.content.pm.SigningDetails;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AppZygote;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.IpcDataCache;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.instrumentation.IOffsetCallback;
import android.os.instrumentation.MethodDescriptor;
import android.permission.IPermissionManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;

import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.os.SomeArgs;
import com.android.sdksandbox.flags.Flags;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.am.BroadcastController.StickyBroadcast;
import com.android.server.am.ProcessList.IsolatedUidRange;
import com.android.server.am.ProcessList.IsolatedUidRangeAllocator;
import com.android.server.am.UidObserverController.ChangeRecord;
import com.android.server.appop.AppOpsService;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.notification.NotificationManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.privatecompute.PccSandboxManagerInternal;
import com.android.server.privatecompute.PrivateComputeStatsLogUtil;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.verification.VerificationMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Test class for {@link ActivityManagerService}.
 *
 * Build/Install/Run:
 *  atest FrameworksMockingServicesTests:ActivityManagerServiceTest
 */
@Presubmit
@SmallTest
public class ActivityManagerServiceTest {
    private static final String TAG = ActivityManagerServiceTest.class.getSimpleName();

    private static final int TEST_USER = 11;

    private static final String TEST_ACTION1 = "com.android.server.am.TEST_ACTION1";
    private static final String TEST_ACTION2 = "com.android.server.am.TEST_ACTION2";
    private static final String TEST_ACTION3 = "com.android.server.am.TEST_ACTION3";

    private static final String TEST_EXTRA_KEY1 = "com.android.server.am.TEST_EXTRA_KEY1";
    private static final String TEST_EXTRA_VALUE1 = "com.android.server.am.TEST_EXTRA_VALUE1";

    private static final String TEST_PACKAGE_NAME = "com.android.server.am.testpackage";

    private static final String PROPERTY_APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS =
            "apply_sdk_sandbox_audit_restrictions";
    private static final String PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS =
            "apply_sdk_sandbox_next_restrictions";
    private static final String APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS = ":isSdkSandboxAudit";
    private static final String APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS = ":isSdkSandboxNext";
    private static final int TEST_UID = 11111;
    private static final int TEST_PID = 22222;
    private static final String TEST_PACKAGE = "com.test.package";
    private static final int USER_ID = 666;

    // Example UIDs for different process types
    private static final int PCC_UID_1 = Process.FIRST_PCC_UID;
    private static final int REGULAR_UID = 10200;
    private static final int REGULAR_UID_2 = 10201;

    private static final String PCC_PACKAGE_1 = "com.pcc.package1";
    private static final String REGULAR_PACKAGE = "com.regular.package";
    private static final String REGULAR_PACKAGE_2 = "com.regular.package2";

    private static final long TEST_PROC_STATE_SEQ1 = 555;
    private static final long TEST_PROC_STATE_SEQ2 = 556;

    private static final String TEST_AUTHORITY = "test_authority";
    private static final String TEST_MIME_TYPE = "application/test_type";
    private static final Uri TEST_URI = Uri.parse("content://com.example/people");
    private static final int TEST_CREATOR_UID = 12345;
    private static final String TEST_CREATOR_PACKAGE = "android.content.testCreatorPackage";
    private static final String TEST_TYPE = "testType";
    private static final String TEST_IDENTIFIER = "testIdentifier";
    private static final String TEST_CATEGORY = "testCategory";
    private static final String TEST_LAUNCH_TOKEN = "testLaunchToken";
    private static final ComponentName TEST_COMPONENT = new ComponentName(TEST_PACKAGE,
            "TestClass");
    private static final int ALL_SET_FLAG = 0xFFFFFFFF;

    private static final int[] UID_RECORD_CHANGES = {
        UidRecord.CHANGE_PROCSTATE,
        UidRecord.CHANGE_GONE,
        UidRecord.CHANGE_GONE | UidRecord.CHANGE_IDLE,
        UidRecord.CHANGE_IDLE,
        UidRecord.CHANGE_ACTIVE,
        UidRecord.CHANGE_CAPABILITY,
    };

    private static final long USAGE_STATS_INTERACTION = 10 * 60 * 1000L;
    private static final long SERVICE_USAGE_INTERACTION = 60 * 1000;
    private static final String TEST_CALLER_PKG = "com.caller.package";
    private static final int TEST_CALLER_UID = 10001;
    private static final String TEST_TARGET_PKG = "com.target.package";
    private static final int TEST_TARGET_UID = 10002;
    private static final int TEST_USER_ID = 0;
    private static final String SYSTEM_PKG = "android";
    private static final String CERT_A = "CERT_A"; // For Caller
    private static final String CERT_B = "CERT_B"; // For Target

    // A different package name from TEST_PACKAGE for testing
    private static final String TEST_OTHER_PACKAGE = "com.other.package";
    private static final String TEST_CALLER_NAME = "test";

    private static ProcessList.ProcessListSettingsListener sProcessListSettingsListener;

    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private Context mContext = getInstrumentation().getTargetContext();

    @Mock private AppOpsService mAppOpsService;
    @Mock private UserController mUserController;
    @Mock private IPackageManager mPackageManager;
    @Mock private IPermissionManager mPermissionManager;
    @Mock private BatteryStatsService mBatteryStatsService;
    @Mock private PackageManagerInternal mPackageManagerInternal;
    @Mock private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    @Mock private NotificationManagerInternal mNotificationManagerInternal;
    @Mock private JobSchedulerInternal mJobSchedulerInternal;
    @Mock private ContentResolver mContentResolver;
    @Mock private PccSandboxManagerInternal mMockPccSandboxManagerInternal;

    private TestInjector mInjector;
    private ActivityManagerService mAms;
    private ActiveServices mActiveServices;
    private HandlerThread mHandlerThread;
    private TestHandler mHandler;
    private MockitoSession mMockingSession;
    private ProcessRecord mProcessRecord;

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(AppGlobals.class)
                .spyStatic(ServiceManager.class)
                .spyStatic(PrivateComputeStatsLogUtil.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);

        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);
        LocalServices.addService(ActivityTaskManagerInternal.class, mActivityTaskManagerInternal);
        LocalServices.addService(NotificationManagerInternal.class, mNotificationManagerInternal);
        LocalServices.addService(JobSchedulerInternal.class, mJobSchedulerInternal);

        doReturn(new ComponentName("", "")).when(mPackageManagerInternal)
                .getSystemUiServiceComponent();
        doReturn(true).when(mPackageManagerInternal).isSameApp(anyString(), anyInt(), anyInt());

        doReturn(mPackageManager).when(AppGlobals::getPackageManager);
        doReturn(mPermissionManager).when(AppGlobals::getPermissionManager);
        doReturn(new String[]{""}).when(mPackageManager).getPackagesForUid(eq(Process.myUid()));

        LocalServices.addService(PccSandboxManagerInternal.class, mMockPccSandboxManagerInternal);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new TestHandler(mHandlerThread.getLooper());
        mInjector = new TestInjector(new TestContext(mContext, mContentResolver));
        doAnswer(invocation -> {
            final int userId = invocation.getArgument(2);
            return userId;
        }).when(mUserController).handleIncomingUser(anyInt(), anyInt(), anyInt(), anyBoolean(),
                anyInt(), any(), any());
        doReturn(true).when(mUserController).isUserOrItsParentRunning(anyInt());
        mAms = new ActivityManagerService(mInjector, mServiceThreadRule.getThread(),
                mUserController);
        mAms.mConstants.mNetworkAccessTimeoutMs = 2000;
        mAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        mAms.mActivityTaskManager.initialize(null, null, mAms.mProcessStateController,
                mHandler.getLooper());
        mAms.mAtmInternal = mActivityTaskManagerInternal;
        mAms.mUsageStatsService = mock(UsageStatsManagerInternal.class);
        mHandler.setRunnablesToIgnore(
                List.of(mAms.mUidObserverController.getDispatchRunnableForTest()));

        // Required for updating DeviceConfig.
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.WRITE_DEVICE_CONFIG,
                        Manifest.permission.WRITE_ALLOWLISTED_DEVICE_CONFIG);
        sProcessListSettingsListener = mAms.mProcessList.getProcessListSettingsListener();
        assertThat(sProcessListSettingsListener).isNotNull();

        mProcessRecord = spy(new ProcessRecord(mAms, mContext.getApplicationInfo(), "name", 12345));

        // Ensure certain services and constants are defined properly.
        assertEquals(USAGE_STATS_INTERACTION,
                mAms.mConstants.USAGE_STATS_INTERACTION_INTERVAL_POST_S);
        assertEquals(SERVICE_USAGE_INTERACTION,
                mAms.mConstants.SERVICE_USAGE_INTERACTION_TIME_POST_S);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void bindPccBackupAgent_logsPccBackupAgentStarted() throws Exception {
        runBindBackupAgentAndVerifyLogging(BACKUP_AGENT_PROCESS_PCC, PCC_UID_1, true, times(1));
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void bindPccBackupAgent_processStartFailed_doesNotLogPccBackupAgentStarted()
            throws Exception {
        runBindBackupAgentAndVerifyLogging(BACKUP_AGENT_PROCESS_PCC, PCC_UID_1, false, never());
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void bindPccBackupAgent_nonPccProcess_doesNotLogPccBackupAgentStarted()
            throws Exception {
        runBindBackupAgentAndVerifyLogging(BACKUP_AGENT_PROCESS_MAIN, -1, true, never());
    }

    @Test
    @RequiresFlagsDisabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void bindPccBackupAgent_pccFlagDisabled_doesNotLogPccBackupAgentStarted()
            throws Exception {
        runBindBackupAgentAndVerifyLogging(BACKUP_AGENT_PROCESS_PCC, PCC_UID_1, true, never());
    }

    private void runBindBackupAgentAndVerifyLogging(int backupAgentProcess, int expectedPccUid,
            boolean startSucceeds, VerificationMode mode) throws Exception {
        ActivityManagerService spyAms = spy(mAms);

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = TEST_PACKAGE;
        applicationInfo.processName = TEST_PACKAGE;
        applicationInfo.uid = TEST_UID;
        applicationInfo.pccUid = expectedPccUid;
        applicationInfo.backupAgentProcess = backupAgentProcess;

        doReturn(applicationInfo).when(mPackageManager).getApplicationInfo(eq(TEST_PACKAGE),
                anyLong(), anyInt());

        doReturn(null).when(spyAms).getProcessRecordLocked(eq(TEST_PACKAGE), anyInt());

        ProcessRecord appRec = startSucceeds
                ? new ProcessRecord(mAms, applicationInfo, TAG, expectedPccUid)
                : null;

        doReturn(appRec).when(spyAms).startProcessLocked(anyString(), any(), anyBoolean(),
                anyInt(), any(), anyInt(), anyBoolean(), anyBoolean());

        spyAms.bindBackupAgent(TEST_PACKAGE, ApplicationThreadConstants.BACKUP_MODE_FULL,
                UserHandle.USER_SYSTEM, BackupAnnotations.BackupDestination.CLOUD,
                /* shouldUseRestrictedMode= */ true);

        ExtendedMockito.verify(() -> PrivateComputeStatsLogUtil.logPccBackupAgentStarted(), mode);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_SystemUid_BypassesCheck() {
        // Setup a Target that blocks everyone
        setupAllowComponentAccessPolicy(TEST_TARGET_PKG, TEST_USER_ID, List.of());
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result = mAms.validateAssociationAllowedLocked(
                SYSTEM_PKG, Process.SYSTEM_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                ActivityManagerService.ASSOCIATION_TYPE_SERVICE, null);

        assertTrue("System UID (1000) must always bypass checks", result);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_SameUid_BypassesCheck() {
        // Setup a Target that blocks everyone
        setupAllowComponentAccessPolicy(TEST_TARGET_PKG, TEST_USER_ID, List.of());
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result = mAms.validateAssociationAllowedLocked(
                TEST_TARGET_PKG, TEST_TARGET_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                ActivityManagerService.ASSOCIATION_TYPE_SERVICE, null);

        assertTrue("Same UID must always bypass checks", result);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testValidateAssociationAllowed_pccToRegular_isDelegatedToPccSandboxManager() {
        when(mMockPccSandboxManagerInternal.validateAssociationAllowed(
                eq(PCC_UID_1), eq(PCC_PACKAGE_1), eq(REGULAR_UID), eq(REGULAR_PACKAGE),
                anyInt(), nullable(Bundle.class)))
                .thenReturn(false);

        boolean allowed = mAms.validateAssociationAllowedLocked(
                PCC_PACKAGE_1, PCC_UID_1, REGULAR_PACKAGE, REGULAR_UID,
                ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /*debugTag=*/ null);

        assertFalse("Association between a PCC UID and a regular UID should be denied", allowed);
    }

    // ActivityManagerService allows associations if there are no associations defined.
    // This test ensures that the PCC logic is NOT triggered for regular UIDs.
    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testValidateAssociationAllowed_regularToRegular_fallsThrough() {
        boolean allowed = mAms.validateAssociationAllowedLocked(
                REGULAR_PACKAGE, REGULAR_UID, REGULAR_PACKAGE_2, REGULAR_UID_2,
                ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /*debugTag=*/ null);

        assertTrue("Association between two regular UIDs with no restrictions is allowed", allowed);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_ComponentAccess_NoPolicy_Allowed() {
        setupAllowComponentAccessPolicy(TEST_CALLER_PKG, TEST_USER_ID, /* allowedPackages= */ null);
        setupAllowComponentAccessPolicy(TEST_TARGET_PKG, TEST_USER_ID, /* allowedPackages= */ null);

        setupPackageSigning(TEST_CALLER_PKG, CERT_A);
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result =
                mAms.validateAssociationAllowedLocked(
                        TEST_CALLER_PKG, TEST_CALLER_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                        ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertTrue("Association should be allowed when no policies exist", result);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_ComponentAccess_EgressBlock_Denied() {
        List<SignedPackage> callerRules = List.of(createSignedPackage(
                "com.trusted.service", /* cert= */ null));
        setupAllowComponentAccessPolicy(TEST_CALLER_PKG, TEST_USER_ID, callerRules);
        setupAllowComponentAccessPolicy(
                TEST_TARGET_PKG, TEST_USER_ID,  /* allowedPackages= */ null);
        setupPackageSigning(TEST_CALLER_PKG, CERT_A);
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result =
                mAms.validateAssociationAllowedLocked(
                        TEST_CALLER_PKG, TEST_CALLER_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                        ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertFalse("Caller's policy should block access to unknown target", result);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_ComponentAccess_IngressBlock_Denied() {
        setupAllowComponentAccessPolicy(TEST_CALLER_PKG, TEST_USER_ID, /* allowedPackages= */ null);

        List<SignedPackage> targetRules =
                List.of(createSignedPackage("com.trusted.client", /* cert= */ null));
        setupAllowComponentAccessPolicy(TEST_TARGET_PKG, TEST_USER_ID, targetRules);

        setupPackageSigning(TEST_CALLER_PKG, CERT_A);
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result =
                mAms.validateAssociationAllowedLocked(
                        TEST_CALLER_PKG, TEST_CALLER_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                        ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertFalse("Target's policy should block access from unknown caller", result);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_ComponentAccess_MutualAllow_Allowed() {
        setupAllowComponentAccessPolicy(
                TEST_CALLER_PKG,
                TEST_USER_ID,
                List.of(createSignedPackage(TEST_TARGET_PKG, CERT_B)));

        setupAllowComponentAccessPolicy(
                TEST_TARGET_PKG,
                TEST_USER_ID,
                List.of(createSignedPackage(TEST_CALLER_PKG, CERT_A)));

        setupPackageSigning(TEST_CALLER_PKG, CERT_A);
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result =
                mAms.validateAssociationAllowedLocked(
                        TEST_CALLER_PKG, TEST_CALLER_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                        ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertTrue("Mutual trust should pass", result);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_ComponentAccess_CertMismatch_Denied() {
        List<SignedPackage> rules = List.of(createSignedPackage(TEST_CALLER_PKG, "CERT_OFFICIAL"));
        setupAllowComponentAccessPolicy(TEST_TARGET_PKG, TEST_USER_ID, rules);
        setupAllowComponentAccessPolicy(TEST_CALLER_PKG, TEST_USER_ID, /* allowedPackages= */ null);

        setupPackageSigning(TEST_CALLER_PKG, "CERT_HACKED");
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result =
                mAms.validateAssociationAllowedLocked(
                        TEST_CALLER_PKG, TEST_CALLER_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                        ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertFalse("Certificate mismatch should block access", result);
    }

    @Test
    @RequiresFlagsDisabled(
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_ComponentAccess_FlagDisabled_IgnoresPolicy() {
        List<SignedPackage> targetRules =
                List.of(createSignedPackage("com.some.other.app", /* cert= */ null));
        setupAllowComponentAccessPolicy(TEST_TARGET_PKG, TEST_USER_ID, targetRules);
        setupAllowComponentAccessPolicy(TEST_CALLER_PKG, TEST_USER_ID, /* allowedPackages= */null);

        setupPackageSigning(TEST_CALLER_PKG, CERT_A);
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result =
                mAms.validateAssociationAllowedLocked(
                        TEST_CALLER_PKG, TEST_CALLER_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                        ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertTrue("When flag is disabled, manifest policies must be ignored", result);
    }

    @Test
    @RequiresFlagsEnabled(
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testValidateAssociationAllowedPerSystemLocked_NonPcc_NoAssociations_Allowed()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(SystemConfig.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            SystemConfig mockSystemConfig = mock(SystemConfig.class);
            ExtendedMockito.doReturn(mockSystemConfig).when(SystemConfig::getInstance);

            // Return an empty map to simulate no explicit sysconfig rules for either package
            ArrayMap<String, ArraySet<String>> allowedAssociations = new ArrayMap<>();
            when(mockSystemConfig.getAllowedAssociations()).thenReturn(allowedAssociations);

            boolean allowed =
                    mAms.validateAssociationAllowedPerSystemLocked(
                            REGULAR_PACKAGE,
                            REGULAR_UID,
                            REGULAR_PACKAGE_2,
                            REGULAR_UID_2,
                            ActivityManagerService.ASSOCIATION_TYPE_SERVICE,
                            null);

            assertTrue("Association should be allowed for non-PCC packages with no sysconfig rules",
                    allowed);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    // ------------------------------------------------------------------------
    // --- PCC SystemConfig and PccSandboxManager Tests ------
    // ------------------------------------------------------------------------
    @Test
    @RequiresFlagsEnabled(
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void
            testValidateAssociationAllowedPerSystemLocked_PccManagerDeniedNoAssociations() {
        when(mMockPccSandboxManagerInternal.validateAssociationAllowed(
                anyInt(), anyString(), anyInt(), anyString(), anyInt(),
                nullable(Bundle.class))).thenReturn(false);

        boolean allowed =
                mAms.validateAssociationAllowedPerSystemLocked(
                        PCC_PACKAGE_1,
                        PCC_UID_1,
                        REGULAR_PACKAGE,
                        REGULAR_UID,
                        ActivityManagerService.ASSOCIATION_TYPE_SERVICE,
                        null);

        assertFalse("Association should be denied with no explicit sysconfig associations",
                allowed);
    }

    @Test
    @RequiresFlagsEnabled(
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testValidateAssociationAllowedPerSystemLocked_PccManagerDenied_SysConfigAllowed()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(SystemConfig.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            when(mMockPccSandboxManagerInternal.validateAssociationAllowed(
                    anyInt(), anyString(), anyInt(), anyString(), anyInt(),
                    nullable(Bundle.class)))
                    .thenReturn(false);

            SystemConfig mockSystemConfig = mock(SystemConfig.class);
            ExtendedMockito.doReturn(mockSystemConfig).when(SystemConfig::getInstance);

            ArrayMap<String, ArraySet<String>> allowedAssociations = new ArrayMap<>();
            allowedAssociations.put(PCC_PACKAGE_1, new ArraySet<>(new String[] {REGULAR_PACKAGE}));
            allowedAssociations.put(REGULAR_PACKAGE, new ArraySet<>(new String[] {PCC_PACKAGE_1}));
            when(mockSystemConfig.getAllowedAssociations()).thenReturn(allowedAssociations);

            ApplicationInfo pccAppInfo = new ApplicationInfo();
            pccAppInfo.flags = 0;
            ApplicationInfo regularAppInfo = new ApplicationInfo();
            regularAppInfo.flags = 0;
            when(mPackageManager.getApplicationInfo(eq(PCC_PACKAGE_1), anyLong(), anyInt()))
                    .thenReturn(pccAppInfo);
            when(mPackageManager.getApplicationInfo(eq(REGULAR_PACKAGE), anyLong(), anyInt()))
                    .thenReturn(regularAppInfo);

            boolean allowed =
                    mAms.validateAssociationAllowedPerSystemLocked(
                            PCC_PACKAGE_1,
                            PCC_UID_1,
                            REGULAR_PACKAGE,
                            REGULAR_UID,
                            ActivityManagerService.ASSOCIATION_TYPE_SERVICE,
                            null);

            assertTrue(
                    "Association should be allowed by sysconfig if PccSandboxManager denies",
                    allowed);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    @RequiresFlagsEnabled(
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testValidateAssociationAllowedPerSystemLocked_PccManagerDenied_SysConfigDenied()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(SystemConfig.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            when(mMockPccSandboxManagerInternal.validateAssociationAllowed(
                    anyInt(), anyString(), anyInt(), anyString(), anyInt(),
                    nullable(Bundle.class)))
                    .thenReturn(false);

            SystemConfig mockSystemConfig = mock(SystemConfig.class);
            ExtendedMockito.doReturn(mockSystemConfig).when(SystemConfig::getInstance);

            ArrayMap<String, ArraySet<String>> allowedAssociations = new ArrayMap<>();
            allowedAssociations.put(
                    PCC_PACKAGE_1, new ArraySet<>(new String[] {"some.other.package"}));
            allowedAssociations.put(REGULAR_PACKAGE, new ArraySet<>(new String[] {PCC_PACKAGE_1}));
            when(mockSystemConfig.getAllowedAssociations()).thenReturn(allowedAssociations);

            ApplicationInfo pccAppInfo = new ApplicationInfo();
            pccAppInfo.flags = 0;
            ApplicationInfo regularAppInfo = new ApplicationInfo();
            regularAppInfo.flags = 0;
            when(mPackageManager.getApplicationInfo(eq(PCC_PACKAGE_1), anyLong(), anyInt()))
                    .thenReturn(pccAppInfo);
            when(mPackageManager.getApplicationInfo(eq(REGULAR_PACKAGE), anyLong(), anyInt()))
                    .thenReturn(regularAppInfo);

            boolean allowed =
                    mAms.validateAssociationAllowedPerSystemLocked(
                            PCC_PACKAGE_1,
                            PCC_UID_1,
                            REGULAR_PACKAGE,
                            REGULAR_UID,
                            ActivityManagerService.ASSOCIATION_TYPE_SERVICE,
                            null);

            assertFalse("Association should be denied by sysconfig", allowed);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    // ------------------------------------------------------------------------
    // --- PCC Component Access Tests ------
    // ------------------------------------------------------------------------
    @Test
    @RequiresFlagsEnabled({
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT,
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS
    })
    public void testValidateAssociation_ComponentAccess_PccUidNoTag_Denied() {
        setupAllowComponentAccessPolicy(TEST_TARGET_PKG, TEST_USER_ID, List.of());
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        when(mMockPccSandboxManagerInternal.validateAssociationAllowed(
                anyInt(), anyString(), anyInt(), anyString(), anyInt(), nullable(Bundle.class)))
                .thenReturn(true);

        boolean result = mAms.validateAssociationAllowedLocked(
                PCC_PACKAGE_1, PCC_UID_1, TEST_TARGET_PKG, TEST_TARGET_UID,
                ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertFalse("PCC UID accessing an Untrusted App must respect "
                + "component access restrictions (Deny)", result);
    }

    @Test
    @RequiresFlagsEnabled({
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT,
            android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS
    })
    public void testValidateAssociation_ComponentAccess_PccUid_AllowedByManifest() {
        // Manifest explicitly allows PCC package
        List<SignedPackage> targetRules =
                List.of(createSignedPackage(PCC_PACKAGE_1, CERT_A));
        setupAllowComponentAccessPolicy(TEST_TARGET_PKG, TEST_USER_ID, targetRules);

        // Mutual trust setup
        List<SignedPackage> pccRules =
                List.of(createSignedPackage(TEST_TARGET_PKG, CERT_B));
        setupAllowComponentAccessPolicy(PCC_PACKAGE_1, TEST_USER_ID, pccRules);
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);
        setupPackageSigning(PCC_PACKAGE_1, CERT_A);

        when(mMockPccSandboxManagerInternal.validateAssociationAllowed(
                anyInt(), anyString(), anyInt(), anyString(), anyInt(), nullable(Bundle.class)))
                .thenReturn(true);

        boolean result = mAms.validateAssociationAllowedLocked(
                PCC_PACKAGE_1, PCC_UID_1, TEST_TARGET_PKG, TEST_TARGET_UID,
                ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertTrue("PCC UID accessing an Untrusted App should be allowed"
                        + " if the manifest explicitly permits it", result);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_ComponentAccess_NonPreInstalledApp_NoDigest_Rejected() {
        // Set up a policy for the caller that allows the target, but with NO certificate digest
        List<SignedPackage> callerRules = List.of(
                createSignedPackage(TEST_TARGET_PKG, /* cert= */ null));
        setupAllowComponentAccessPolicy(TEST_CALLER_PKG, TEST_USER_ID, callerRules);

        // Standard setup for the target app (assumed to be non-system/untrusted by default)
        setupPackageSigning(TEST_TARGET_PKG, CERT_B);

        boolean result = mAms.validateAssociationAllowedLocked(
                TEST_CALLER_PKG, TEST_CALLER_UID, TEST_TARGET_PKG, TEST_TARGET_UID,
                ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertFalse("Non-preinstalled apps without a certificate digest must be rejected", result);
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_ALLOW_COMPONENT_ACCESS)
    public void testValidateAssociation_ComponentAccess_PreinstalledApp_NoDigest_Allowed() {
        // Set up a policy for the caller that allows a SYSTEM package without a digest
        List<SignedPackage> callerRules = List.of(
                createSignedPackage(SYSTEM_PKG, /* cert= */ null));
        setupAllowComponentAccessPolicy(TEST_CALLER_PKG, TEST_USER_ID, callerRules);

        boolean result = mAms.validateAssociationAllowedLocked(
                TEST_CALLER_PKG, TEST_CALLER_UID, SYSTEM_PKG, Process.SYSTEM_UID,
                ActivityManagerService.ASSOCIATION_TYPE_SERVICE, /* debugTag= */ null);

        assertTrue("Preinstalled apps should be allowed without a digest", result);
    }

    private void mockNoteOperation() {
        SyncNotedAppOp allowed = new SyncNotedAppOp(AppOpsManager.MODE_ALLOWED,
                AppOpsManager.OP_GET_USAGE_STATS, null, mContext.getPackageName());
        when(mAppOpsService.noteOperation(eq(AppOpsManager.OP_GET_USAGE_STATS), eq(Process.myUid()),
                nullable(String.class), nullable(String.class), any(Boolean.class),
                nullable(String.class), any(Boolean.class))).thenReturn(allowed);
    }

    @After
    public void tearDown() {
        mHandlerThread.quit();
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .dropShellPermissionIdentity();
        if (sProcessListSettingsListener != null) {
            sProcessListSettingsListener.unregisterObserver();
        }
        clearInvocations(mNotificationManagerInternal);

        LocalServices.removeServiceForTest(PccSandboxManagerInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.removeServiceForTest(NotificationManagerInternal.class);
        LocalServices.removeServiceForTest(JobSchedulerInternal.class);

        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void assertProcessRecordState(long fgInteractionTime, boolean reportedInteraction,
            long interactionEventTime) {
        assertEquals("Foreground interaction time was not updated correctly.",
                fgInteractionTime, mProcessRecord.getFgInteractionTime());
        assertEquals("Interaction was not updated correctly.",
                reportedInteraction, mProcessRecord.getHasReportedInteraction());
        assertEquals("Interaction event time was not updated correctly.",
                interactionEventTime, mProcessRecord.getInteractionEventTime());
    }

    private void maybeUpdateUsageStats(ProcessRecord app, @ProcessState int newProcState,
            long nowElapsed) {
        // The old state is not relevant for the current logic being tested, so we pass
        // PROCESS_STATE_NONEXISTENT.
        final int oldProcState = PROCESS_STATE_NONEXISTENT;
        synchronized (mAms) {
            synchronized (mAms.mProcLock) {
                mAms.maybeUpdateUsageStatsLSP(app, oldProcState, newProcState, nowElapsed);
            }
        }
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStatePersistentUI() {
        final long elapsedTime = 0L;

        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_PERSISTENT_UI, elapsedTime);
        assertProcessRecordState(0L, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateTop() {
        final long elapsedTime = 0L;
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_TOP, elapsedTime);

        assertProcessRecordState(0L, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateTop_PreviousInteraction() {
        final long elapsedTime = 0L;
        mProcessRecord.setHasReportedInteraction(true);
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_TOP, elapsedTime);

        assertProcessRecordState(0L, true, 0L);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateTop_PastUsageInterval() {
        final long elapsedTime = 3 * USAGE_STATS_INTERACTION;
        mProcessRecord.setHasReportedInteraction(true);
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_TOP, elapsedTime);

        assertProcessRecordState(0L, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateBoundTop() {
        final long elapsedTime = 0L;
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_BOUND_TOP, elapsedTime);

        assertProcessRecordState(0L, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGS() {
        final long elapsedTime = 0L;
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_FOREGROUND_SERVICE, elapsedTime);

        assertProcessRecordState(elapsedTime, false, 0L);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGS_ShortInteraction() {
        final long elapsedTime = 0L;
        final long fgInteractionTime = 1000L;
        mProcessRecord.setFgInteractionTime(fgInteractionTime);
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_FOREGROUND_SERVICE, elapsedTime);

        assertProcessRecordState(fgInteractionTime, false, 0L);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGS_LongInteraction() {
        final long elapsedTime = 2 * SERVICE_USAGE_INTERACTION;
        final long fgInteractionTime = 1000L;
        mProcessRecord.setFgInteractionTime(fgInteractionTime);
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_FOREGROUND_SERVICE, elapsedTime);

        assertProcessRecordState(fgInteractionTime, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGS_PreviousLongInteraction() {
        final long elapsedTime = 2 * SERVICE_USAGE_INTERACTION;
        final long fgInteractionTime = 1000L;
        mProcessRecord.setFgInteractionTime(fgInteractionTime);
        mProcessRecord.setHasReportedInteraction(true);
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_FOREGROUND_SERVICE, elapsedTime);

        assertProcessRecordState(fgInteractionTime, true, 0L);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGSLocation() {
        final long elapsedTime = 0L;
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_FOREGROUND_SERVICE, elapsedTime);

        assertProcessRecordState(elapsedTime, false, 0L);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateBFGS() {
        final long elapsedTime = 0L;
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, elapsedTime);

        assertProcessRecordState(0L, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateImportantFG() {
        final long elapsedTime = 0L;
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_IMPORTANT_FOREGROUND, elapsedTime);

        assertProcessRecordState(0L, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateImportantFG_PreviousInteraction() {
        final long elapsedTime = 0L;
        mProcessRecord.setHasReportedInteraction(true);
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_IMPORTANT_FOREGROUND, elapsedTime);

        assertProcessRecordState(0L, true, 0L);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateImportantFG_PastUsageInterval() {
        final long elapsedTime = 3 * USAGE_STATS_INTERACTION;
        mProcessRecord.setHasReportedInteraction(true);
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_IMPORTANT_FOREGROUND, elapsedTime);

        assertProcessRecordState(0L, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateImportantBG() {
        final long elapsedTime = 0L;
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_IMPORTANT_BACKGROUND, elapsedTime);

        assertProcessRecordState(0L, false, 0L);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateService() {
        final long elapsedTime = 0L;
        maybeUpdateUsageStats(mProcessRecord, PROCESS_STATE_SERVICE, elapsedTime);

        assertProcessRecordState(0L, false, 0L);
    }

    @SuppressWarnings("GuardedBy")
    @MediumTest
    @Test
    public void incrementProcStateSeqAndNotifyAppsLocked() throws Exception {

        final UidRecord uidRec = addUidRecord(TEST_UID);
        addUidRecord(TEST_UID + 1);

        // Uid state is not moving from background to foreground or vice versa.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_TOP, // prevState
                PROCESS_STATE_TOP, // curState
                NETWORK_STATE_NO_CHANGE, // expectedBlockState
                false); // expectNotify

        // Uid state is moving from foreground to background.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_FOREGROUND_SERVICE, // prevState
                PROCESS_STATE_SERVICE, // curState
                NETWORK_STATE_UNBLOCK, // expectedBlockState
                true); // expectNotify

        // Explicitly setting the seq counter for more verification.
        mAms.mProcessList.setProcStateSeqCounter(42);

        // Uid state is not moving from background to foreground or vice versa.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_TRANSIENT_BACKGROUND, // prevState
                PROCESS_STATE_IMPORTANT_BACKGROUND, // curState
                NETWORK_STATE_NO_CHANGE, // expectedBlockState
                false); // expectNotify

        // Uid state is moving from background to foreground.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_LAST_ACTIVITY, // prevState
                PROCESS_STATE_TOP, // curState
                NETWORK_STATE_BLOCK, // expectedBlockState
                false); // expectNotify

        // verify waiting threads are not notified.
        uidRec.procStateSeqWaitingForNetwork = 0;
        // Uid state is moving from foreground to background.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_FOREGROUND_SERVICE, // prevState
                PROCESS_STATE_SERVICE, // curState
                NETWORK_STATE_UNBLOCK, // expectedBlockState
                false); // expectNotify
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    public void defaultSdkSandboxNextRestrictions() throws Exception {
        sProcessListSettingsListener.onPropertiesChanged(
                new DeviceConfig.Properties(
                DeviceConfig.NAMESPACE_ADSERVICES,
                Map.of(PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS, "")));
        assertThat(
            sProcessListSettingsListener.applySdkSandboxRestrictionsNext())
            .isFalse();
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    public void doNotApplySdkSandboxNextRestrictions() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().spyStatic(Process.class).startMocking();
        try {
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                    DeviceConfig.NAMESPACE_ADSERVICES,
                    Map.of(PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS, "false")));
            assertThat(
                sProcessListSettingsListener.applySdkSandboxRestrictionsNext())
                .isFalse();
            ExtendedMockito.doReturn(true).when(() -> Process.isSdkSandboxUid(anyInt()));
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = "com.android.sdksandbox";
            info.seInfo = "default:targetSdkVersion=34:complete";
            final ProcessRecord appRec = new ProcessRecord(
                    mAms, info, TAG, Process.FIRST_SDK_SANDBOX_UID,
                    /* sdkSandboxClientPackageName= */ "com.example.client",
                    /* definingUid= */ 0, /* definingProcessName= */ "");
            assertThat(mAms.mProcessList.updateSeInfo(appRec)).doesNotContain(
                    APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    public void applySdkSandboxNextRestrictions() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().spyStatic(Process.class).startMocking();
        try {
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                    DeviceConfig.NAMESPACE_ADSERVICES,
                    Map.of(PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS, "true")));
            assertThat(
                sProcessListSettingsListener.applySdkSandboxRestrictionsNext())
                .isTrue();
            ExtendedMockito.doReturn(true).when(() -> Process.isSdkSandboxUid(anyInt()));
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = "com.android.sdksandbox";
            info.seInfo = "default:targetSdkVersion=34:complete";
            final ProcessRecord appRec = new ProcessRecord(
                    mAms, info, TAG, Process.FIRST_SDK_SANDBOX_UID,
                    /* sdkSandboxClientPackageName= */ "com.example.client",
                    /* definingUid= */ 0, /* definingProcessName= */ "");
            assertThat(mAms.mProcessList.updateSeInfo(appRec)).contains(
                    APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SELINUX_SDK_SANDBOX_AUDIT)
    public void applySdkSandboxAuditRestrictions() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().spyStatic(Process.class).startMocking();
        try {
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                            DeviceConfig.NAMESPACE_ADSERVICES,
                            Map.of(PROPERTY_APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS, "true")));
            assertThat(sProcessListSettingsListener.applySdkSandboxRestrictionsAudit()).isTrue();
            ExtendedMockito.doReturn(true).when(() -> Process.isSdkSandboxUid(anyInt()));
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = "com.android.sdksandbox";
            info.seInfo = "default:targetSdkVersion=34:complete";
            final ProcessRecord appRec =
                    new ProcessRecord(
                            mAms,
                            info,
                            TAG,
                            Process.FIRST_SDK_SANDBOX_UID,
                            /* sdkSandboxClientPackageName= */ "com.example.client",
                            /* definingUid= */ 0,
                            /* definingProcessName= */ "");
            assertThat(mAms.mProcessList.updateSeInfo(appRec))
                    .contains(APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    public void applySdkSandboxNextAndAuditRestrictions() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().spyStatic(Process.class).startMocking();
        try {
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                            DeviceConfig.NAMESPACE_ADSERVICES,
                            Map.of(PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS, "true")));
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                            DeviceConfig.NAMESPACE_ADSERVICES,
                            Map.of(PROPERTY_APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS, "true")));
            assertThat(sProcessListSettingsListener.applySdkSandboxRestrictionsNext()).isTrue();
            assertThat(sProcessListSettingsListener.applySdkSandboxRestrictionsAudit()).isTrue();
            ExtendedMockito.doReturn(true).when(() -> Process.isSdkSandboxUid(anyInt()));
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = "com.android.sdksandbox";
            info.seInfo = "default:targetSdkVersion=34:complete";
            final ProcessRecord appRec =
                    new ProcessRecord(
                            mAms,
                            info,
                            TAG,
                            Process.FIRST_SDK_SANDBOX_UID,
                            /* sdkSandboxClientPackageName= */ "com.example.client",
                            /* definingUid= */ 0,
                            /* definingProcessName= */ "");
            assertThat(mAms.mProcessList.updateSeInfo(appRec))
                    .contains(APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS);
            assertThat(mAms.mProcessList.updateSeInfo(appRec))
                    .doesNotContain(APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    private UidRecord addUidRecord(int uid) {
        return addUidRecord(uid, "");
    }

    @SuppressWarnings("GuardedBy")
    private UidRecord addUidRecord(int uid, String packageName) {
        final UidRecord uidRec = new UidRecord(uid, mAms);
        uidRec.procStateSeqWaitingForNetwork = 1;
        uidRec.hasInternetPermission = true;
        mAms.mProcessList.mActiveUids.put(uid, uidRec);

        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.processName = packageName;
        info.uid = uid;

        final ProcessRecord appRec = new ProcessRecord(mAms, info, info.processName, uid);
        final ProcessStatsService tracker = mAms.mProcessStats;
        final ApplicationThreadDeferred appThread = mock(ApplicationThreadDeferred.class);
        doReturn(mock(IBinder.class)).when(appThread).asBinder();
        appRec.makeActive(appThread, tracker);
        mAms.mProcessList.addProcessNameLocked(appRec);
        mAms.mProcessList.getLruProcessesLSP().add(appRec);

        return uidRec;
    }

    @SuppressWarnings("GuardedBy")
    private void verifySeqCounterAndInteractions(UidRecord uidRec, int prevState, int curState,
            int expectedBlockState, boolean expectNotify) throws Exception {
        CustomThread thread = new CustomThread(uidRec.networkStateLock);
        thread.startAndWait("Unexpected state for " + uidRec);

        mAms.mProcessStateController.setUidSetProcState(uidRec, prevState);
        mAms.mProcessStateController.setUidCurProcState(uidRec, curState);
        final long beforeProcStateSeq = mAms.mProcessList.getProcStateSeqCounter();

        mAms.mProcessList.incrementProcStateSeqLSP(mAms.mProcessList.mActiveUids);
        mAms.mProcessList.notifyProcStateChangedForNetworkLOSP(mAms.mProcessList.mActiveUids);

        final long afterProcStateSeq = beforeProcStateSeq
                + mAms.mProcessList.mActiveUids.size();
        assertEquals("beforeProcStateSeq=" + beforeProcStateSeq
                        + ",activeUids.size=" + mAms.mProcessList.mActiveUids.size(),
                afterProcStateSeq, mAms.mProcessList.getProcStateSeqCounter());
        assertTrue("beforeProcStateSeq=" + beforeProcStateSeq
                        + ",afterProcStateSeq=" + afterProcStateSeq
                        + ",uidCurProcStateSeq=" + uidRec.getCurProcStateSeq(),
                uidRec.getCurProcStateSeq() > beforeProcStateSeq
                        && uidRec.getCurProcStateSeq() <= afterProcStateSeq);

        for (int i = mAms.mProcessList.getLruSizeLOSP() - 1; i >= 0; --i) {
            final ProcessRecord app = mAms.mProcessList.getLruProcessesLOSP().get(i);
            // AMS should notify apps only for block states other than NETWORK_STATE_NO_CHANGE.
            if (app.uid == uidRec.getUid() && expectedBlockState == NETWORK_STATE_BLOCK) {
                verify(app.getThread()).setNetworkBlockSeq(uidRec.getCurProcStateSeq());
            } else {
                verifyNoMoreInteractions(app.getThread());
            }
            Mockito.reset(app.getThread());
        }

        if (expectNotify) {
            thread.assertTerminated("Unexpected state for " + uidRec);
        } else {
            thread.assertWaiting("Unexpected state for " + uidRec);
            thread.interrupt();
        }
    }

    private void validateAppZygoteIsolatedUidRange(IsolatedUidRange uidRange) {
        assertNotNull(uidRange);
        assertTrue(uidRange.mFirstUid >= Process.FIRST_APP_ZYGOTE_ISOLATED_UID
                && uidRange.mFirstUid <= Process.LAST_APP_ZYGOTE_ISOLATED_UID);
        assertTrue(uidRange.mLastUid >= Process.FIRST_APP_ZYGOTE_ISOLATED_UID
                && uidRange.mLastUid <= Process.LAST_APP_ZYGOTE_ISOLATED_UID);
        assertTrue(uidRange.mLastUid > uidRange.mFirstUid
                && ((uidRange.mLastUid - uidRange.mFirstUid + 1)
                     == Process.NUM_UIDS_PER_APP_ZYGOTE));
    }

    private void verifyUidRangesNoOverlap(IsolatedUidRange uidRange1, IsolatedUidRange uidRange2) {
        IsolatedUidRange lowRange = uidRange1.mFirstUid <= uidRange2.mFirstUid
                ? uidRange1 : uidRange2;
        IsolatedUidRange highRange = lowRange == uidRange1  ? uidRange2 : uidRange1;

        assertTrue(highRange.mFirstUid > lowRange.mLastUid);
    }

    @Test
    public void testIsolatedUidRangeAllocator() {
        final IsolatedUidRangeAllocator allocator = mAms.mProcessList.mAppIsolatedUidRangeAllocator;

        // Create initial range
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.processName = "com.android.test.app";
        appInfo.uid = 10000;
        final IsolatedUidRange range = allocator.getOrCreateIsolatedUidRangeLocked(
                appInfo.processName, appInfo.uid);
        validateAppZygoteIsolatedUidRange(range);
        verifyIsolatedUidAllocator(range);

        // Create a second range
        ApplicationInfo appInfo2 = new ApplicationInfo();
        appInfo2.processName = "com.android.test.app2";
        appInfo2.uid = 10001;
        IsolatedUidRange range2 = allocator.getOrCreateIsolatedUidRangeLocked(
                appInfo2.processName, appInfo2.uid);
        validateAppZygoteIsolatedUidRange(range2);
        verifyIsolatedUidAllocator(range2);

        // Verify ranges don't overlap
        verifyUidRangesNoOverlap(range, range2);

        // Free range, reallocate and verify
        allocator.freeUidRangeLocked(appInfo2);
        range2 = allocator.getOrCreateIsolatedUidRangeLocked(appInfo2.processName, appInfo2.uid);
        validateAppZygoteIsolatedUidRange(range2);
        verifyUidRangesNoOverlap(range, range2);
        verifyIsolatedUidAllocator(range2);

        // Free both
        allocator.freeUidRangeLocked(appInfo);
        allocator.freeUidRangeLocked(appInfo2);

        // Verify for a secondary user
        ApplicationInfo appInfo3 = new ApplicationInfo();
        appInfo3.processName = "com.android.test.app";
        appInfo3.uid = 1010000;
        final IsolatedUidRange range3 = allocator.getOrCreateIsolatedUidRangeLocked(
                appInfo3.processName, appInfo3.uid);
        validateAppZygoteIsolatedUidRange(range3);
        verifyIsolatedUidAllocator(range3);

        allocator.freeUidRangeLocked(appInfo3);
        // Try to allocate the maximum number of UID ranges
        int maxNumUidRanges = (Process.LAST_APP_ZYGOTE_ISOLATED_UID
                - Process.FIRST_APP_ZYGOTE_ISOLATED_UID + 1) / Process.NUM_UIDS_PER_APP_ZYGOTE;
        for (int i = 0; i < maxNumUidRanges; i++) {
            appInfo = new ApplicationInfo();
            appInfo.uid = 10000 + i;
            appInfo.processName = "com.android.test.app" + Integer.toString(i);
            IsolatedUidRange uidRange = allocator.getOrCreateIsolatedUidRangeLocked(
                    appInfo.processName, appInfo.uid);
            validateAppZygoteIsolatedUidRange(uidRange);
            verifyIsolatedUidAllocator(uidRange);
        }

        // Try to allocate another one and make sure it fails
        appInfo = new ApplicationInfo();
        appInfo.uid = 9000;
        appInfo.processName = "com.android.test.app.failed";
        IsolatedUidRange failedRange = allocator.getOrCreateIsolatedUidRangeLocked(
                appInfo.processName, appInfo.uid);

        assertNull(failedRange);
    }

    public void verifyIsolatedUid(ProcessList.IsolatedUidRange range, int uid) {
        assertTrue(uid >= range.mFirstUid && uid <= range.mLastUid);
    }

    public void verifyIsolatedUidAllocator(ProcessList.IsolatedUidRange range) {
        int uid = range.allocateIsolatedUidLocked(0);
        verifyIsolatedUid(range, uid);

        int uid2 = range.allocateIsolatedUidLocked(0);
        verifyIsolatedUid(range, uid2);
        assertTrue(uid2 != uid);

        // Free both
        range.freeIsolatedUidLocked(uid);
        range.freeIsolatedUidLocked(uid2);

        // Allocate the entire range
        for (int i = 0; i < (range.mLastUid - range.mFirstUid + 1); ++i) {
            uid = range.allocateIsolatedUidLocked(0);
            verifyIsolatedUid(range, uid);
        }

        // Ensure the next one fails
        uid = range.allocateIsolatedUidLocked(0);
        assertEquals(uid, -1);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testFifoSwitch() {
        addUidRecord(TEST_UID, TEST_PACKAGE);
        final ProcessRecord fifoProc = mAms.getProcessRecordLocked(TEST_PACKAGE, TEST_UID);
        final var wpc = fifoProc.getWindowProcessController();
        spyOn(wpc);
        doReturn(true).when(wpc).useFifoUiScheduling();
        fifoProc.makeActive(new ApplicationThreadDeferred(fifoProc.getThread()),
                mAms.mProcessStats);
        assertTrue(fifoProc.useFifoUiScheduling());
        assertTrue(mAms.mSpecifiedFifoProcesses.contains(fifoProc));

        // If there is a request to use more CPU resource (e.g. camera), the current fifo process
        // should switch the capability of using fifo.
        final UidRecord uidRecord = addUidRecord(TEST_UID + 1, TEST_PACKAGE + 1);
        mAms.mProcessStateController.setUidCurProcState(uidRecord, PROCESS_STATE_TOP);
        mAms.adjustFifoProcessesIfNeeded(uidRecord.getUid(), false /* allowSpecifiedFifo */);
        assertFalse(fifoProc.useFifoUiScheduling());
        mAms.adjustFifoProcessesIfNeeded(uidRecord.getUid(), true /* allowSpecifiedFifo */);
        assertTrue(fifoProc.useFifoUiScheduling());

        fifoProc.makeInactive(mAms.mProcessStats);
        assertFalse(mAms.mSpecifiedFifoProcesses.contains(fifoProc));
    }

    @Test
    public void testGlobalIsolatedUidAllocator() {
        final IsolatedUidRange globalUidRange = mAms.mProcessList.mGlobalIsolatedUids;
        assertEquals(globalUidRange.mFirstUid, Process.FIRST_ISOLATED_UID);
        assertEquals(globalUidRange.mLastUid, Process.LAST_ISOLATED_UID);
        verifyIsolatedUidAllocator(globalUidRange);
    }

    @Test
    public void testBlockStateForUid() {
        final UidRecord uidRec = new UidRecord(TEST_UID, mAms);
        int expectedBlockState;

        final String errorTemplate = "Block state should be %s, prevState: %s, curState: %s";
        Function<Integer, String> errorMsg = (blockState) -> {
            return String.format(errorTemplate,
                    valueToString(ActivityManagerService.class, "NETWORK_STATE_", blockState),
                    valueToString(ActivityManager.class, "PROCESS_STATE_",
                        uidRec.getSetProcState()),
                    valueToString(ActivityManager.class, "PROCESS_STATE_", uidRec.getCurProcState())
            );
        };

        // No change in uid state
        mAms.mProcessStateController.setUidSetProcState(uidRec, PROCESS_STATE_RECEIVER);
        mAms.mProcessStateController.setUidCurProcState(uidRec, PROCESS_STATE_RECEIVER);
        expectedBlockState = NETWORK_STATE_NO_CHANGE;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Foreground to foreground
        mAms.mProcessStateController.setUidSetProcState(uidRec, PROCESS_STATE_FOREGROUND_SERVICE);
        mAms.mProcessStateController.setUidCurProcState(uidRec,
                PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        expectedBlockState = NETWORK_STATE_NO_CHANGE;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Background to background
        mAms.mProcessStateController.setUidSetProcState(uidRec, PROCESS_STATE_CACHED_ACTIVITY);
        mAms.mProcessStateController.setUidCurProcState(uidRec, PROCESS_STATE_CACHED_EMPTY);
        expectedBlockState = NETWORK_STATE_NO_CHANGE;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Background to background
        mAms.mProcessStateController.setUidSetProcState(uidRec, PROCESS_STATE_NONEXISTENT);
        mAms.mProcessStateController.setUidCurProcState(uidRec, PROCESS_STATE_CACHED_ACTIVITY);
        expectedBlockState = NETWORK_STATE_NO_CHANGE;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Background to foreground
        mAms.mProcessStateController.setUidSetProcState(uidRec, PROCESS_STATE_SERVICE);
        mAms.mProcessStateController.setUidCurProcState(uidRec, PROCESS_STATE_FOREGROUND_SERVICE);
        expectedBlockState = NETWORK_STATE_BLOCK;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Foreground to background
        mAms.mProcessStateController.setUidSetProcState(uidRec, PROCESS_STATE_TOP);
        mAms.mProcessStateController.setUidCurProcState(uidRec, PROCESS_STATE_LAST_ACTIVITY);
        expectedBlockState = NETWORK_STATE_UNBLOCK;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));
    }

    /**
     * This test verifies that process state changes are dispatched to observers based on the
     * changes they wanted to listen (this is specified when registering the observer).
     */
    @Test
    public void testDispatchUids_dispatchNeededChanges() throws RemoteException {
        mockNoteOperation();

        final int[] changesToObserve = {
            ActivityManager.UID_OBSERVER_PROCSTATE,
            ActivityManager.UID_OBSERVER_GONE,
            ActivityManager.UID_OBSERVER_IDLE,
            ActivityManager.UID_OBSERVER_ACTIVE,
            ActivityManager.UID_OBSERVER_CAPABILITY,
            ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE
                    | ActivityManager.UID_OBSERVER_ACTIVE | ActivityManager.UID_OBSERVER_IDLE
                    | ActivityManager.UID_OBSERVER_CAPABILITY
        };
        final IUidObserver[] observers = new IUidObserver.Stub[changesToObserve.length];
        for (int i = 0; i < observers.length; ++i) {
            observers[i] = mock(IUidObserver.Stub.class);
            when(observers[i].asBinder()).thenReturn((IBinder) observers[i]);
            mAms.registerUidObserver(observers[i], changesToObserve[i] /* which */,
                    ActivityManager.PROCESS_STATE_UNKNOWN /* cutpoint */, null /* caller */);

            // When we invoke AMS.registerUidObserver, there are some interactions with observers[i]
            // mock in RemoteCallbackList class. We don't want to test those interactions and
            // at the same time, we don't want those to interfere with verifyNoMoreInteractions.
            // So, resetting the mock here.
            Mockito.reset(observers[i]);
        }

        // Add pending uid records each corresponding to a different change type UidRecord.CHANGE_*
        final int[] changesForPendingUidRecords = UID_RECORD_CHANGES;

        final int[] procStatesForPendingUidRecords = {
            ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
            ActivityManager.PROCESS_STATE_NONEXISTENT,
            ActivityManager.PROCESS_STATE_CACHED_EMPTY,
            ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
            ActivityManager.PROCESS_STATE_TOP,
            ActivityManager.PROCESS_STATE_TOP,
        };
        final int[] capabilitiesForPendingUidRecords = {
            ActivityManager.PROCESS_CAPABILITY_ALL,
            ActivityManager.PROCESS_CAPABILITY_NONE,
            ActivityManager.PROCESS_CAPABILITY_NONE,
            ActivityManager.PROCESS_CAPABILITY_NONE,
            ActivityManager.PROCESS_CAPABILITY_NONE,
            ActivityManager.PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK,
        };
        final Map<Integer, ChangeRecord> changeItems = new HashMap<>();
        for (int i = 0; i < changesForPendingUidRecords.length; ++i) {
            final ChangeRecord pendingChange = new ChangeRecord();
            pendingChange.change = changesForPendingUidRecords[i];
            pendingChange.uid = i;
            pendingChange.procState = procStatesForPendingUidRecords[i];
            pendingChange.procStateSeq = i;
            pendingChange.capability = capabilitiesForPendingUidRecords[i];
            changeItems.put(changesForPendingUidRecords[i], pendingChange);
            addPendingUidChange(pendingChange);
        }

        mAms.mUidObserverController.dispatchUidsChanged();
        // Verify the required changes have been dispatched to observers.
        for (int i = 0; i < observers.length; ++i) {
            final int changeToObserve = changesToObserve[i];
            final IUidObserver observerToTest = observers[i];
            if ((changeToObserve & ActivityManager.UID_OBSERVER_IDLE) != 0) {
                // Observer listens to uid idle changes, so change items corresponding to
                // UidRecord.CHANGE_IDLE or UidRecord.CHANGE_IDLE_GONE needs to be
                // delivered to this observer.
                final int[] changesToVerify = {
                    UidRecord.CHANGE_IDLE,
                    UidRecord.CHANGE_GONE | UidRecord.CHANGE_IDLE
                };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidIdle(changeItem.uid, changeItem.ephemeral);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_ACTIVE) != 0) {
                // Observer listens to uid active changes, so change items corresponding to
                // UidRecord.CHANGE_ACTIVE needs to be delivered to this observer.
                final int[] changesToVerify = { UidRecord.CHANGE_ACTIVE };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidActive(changeItem.uid);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_GONE) != 0) {
                // Observer listens to uid gone changes, so change items corresponding to
                // UidRecord.CHANGE_GONE or UidRecord.CHANGE_IDLE_GONE needs to be
                // delivered to this observer.
                final int[] changesToVerify = {
                        UidRecord.CHANGE_GONE,
                        UidRecord.CHANGE_GONE | UidRecord.CHANGE_IDLE
                };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidGone(changeItem.uid, changeItem.ephemeral);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_PROCSTATE) != 0
                    || (changeToObserve & ActivityManager.UID_OBSERVER_CAPABILITY) != 0) {
                // Observer listens to uid procState changes, so change items corresponding to
                // UidRecord.CHANGE_PROCSTATE or UidRecord.CHANGE_IDLE or UidRecord.CHANGE_ACTIVE
                // needs to be delivered to this observer.
                final IntArray changesToVerify = new IntArray();
                if ((changeToObserve & ActivityManager.UID_OBSERVER_PROCSTATE) == 0) {
                    changesToVerify.add(UidRecord.CHANGE_CAPABILITY);
                } else {
                    changesToVerify.add(UidRecord.CHANGE_PROCSTATE);
                    changesToVerify.add(UidRecord.CHANGE_ACTIVE);
                    changesToVerify.add(UidRecord.CHANGE_IDLE);
                    changesToVerify.add(UidRecord.CHANGE_CAPABILITY);
                }
                verifyObserverReceivedChanges(observerToTest, changesToVerify.toArray(),
                        changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidStateChanged(changeItem.uid,
                                    changeItem.procState, changeItem.procStateSeq,
                                    changeItem.capability);
                        });
            }
            // Verify there are no other callbacks for this observer.
            verifyNoMoreInteractions(observerToTest);
        }
    }

    @Test
    public void testBroadcastStickyIntent() {
        final Intent intent1 = new Intent(TEST_ACTION1);
        final Intent intent2 = new Intent(TEST_ACTION2)
                .putExtra(TEST_EXTRA_KEY1, TEST_EXTRA_VALUE1);
        final Intent intent3 = new Intent(TEST_ACTION3);
        final BroadcastOptions options = BroadcastOptions.makeWithDeferUntilActive(true);

        broadcastIntent(intent1, null, true);
        assertStickyBroadcasts(mAms.getStickyBroadcastsForTest(TEST_ACTION1, TEST_USER),
                StickyBroadcast.create(intent1, false, Process.myUid(), PROCESS_STATE_UNKNOWN,
                        null));
        assertNull(mAms.getStickyBroadcastsForTest(TEST_ACTION2, TEST_USER));
        assertNull(mAms.getStickyBroadcastsForTest(TEST_ACTION3, TEST_USER));

        broadcastIntent(intent2, options.toBundle(), true);
        assertStickyBroadcasts(mAms.getStickyBroadcastsForTest(TEST_ACTION1, TEST_USER),
                StickyBroadcast.create(intent1, false, Process.myUid(), PROCESS_STATE_UNKNOWN,
                        null));
        assertStickyBroadcasts(mAms.getStickyBroadcastsForTest(TEST_ACTION2, TEST_USER),
                StickyBroadcast.create(intent2, true, Process.myUid(), PROCESS_STATE_UNKNOWN,
                        null));
        assertNull(mAms.getStickyBroadcastsForTest(TEST_ACTION3, TEST_USER));

        broadcastIntent(intent3, null, true);
        assertStickyBroadcasts(mAms.getStickyBroadcastsForTest(TEST_ACTION1, TEST_USER),
                StickyBroadcast.create(intent1, false, Process.myUid(), PROCESS_STATE_UNKNOWN,
                        null));
        assertStickyBroadcasts(mAms.getStickyBroadcastsForTest(TEST_ACTION2, TEST_USER),
                StickyBroadcast.create(intent2, true, Process.myUid(), PROCESS_STATE_UNKNOWN,
                        null));
        assertStickyBroadcasts(mAms.getStickyBroadcastsForTest(TEST_ACTION3, TEST_USER),
                StickyBroadcast.create(intent3, false, Process.myUid(), PROCESS_STATE_UNKNOWN,
                        null));
    }

    @Test
    @SuppressWarnings("GuardedBy")
    public void testBroadcastStickyIntent_verifyTypeNotResolved() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().mockStatic(IpcDataCache.class).startMocking();

        try {
            final Intent intent = new Intent(TEST_ACTION1);
            final Uri uri = new Uri.Builder()
                    .scheme(SCHEME_CONTENT)
                    .authority(TEST_AUTHORITY)
                    .path("green")
                    .build();
            intent.setData(uri);
            broadcastIntent(intent, null, true, TEST_MIME_TYPE, USER_ALL);
            assertStickyBroadcasts(mAms.getStickyBroadcastsForTest(TEST_ACTION1, USER_ALL),
                    StickyBroadcast.create(intent, false, Process.myUid(), PROCESS_STATE_UNKNOWN,
                            TEST_MIME_TYPE));
            when(mContentResolver.getType(uri)).thenReturn(TEST_MIME_TYPE);
            ExtendedMockito.doNothing().when(
                    () -> IpcDataCache.invalidateCache(anyString(), anyString()));

            addUidRecord(TEST_UID, TEST_PACKAGE);
            final ProcessRecord procRecord = mAms.getProcessRecordLocked(TEST_PACKAGE, TEST_UID);
            final IntentFilter intentFilter = new IntentFilter(TEST_ACTION1);
            intentFilter.addDataType(TEST_MIME_TYPE);
            final Intent resultIntent = mAms.registerReceiverWithFeature(procRecord.getThread(),
                    TEST_PACKAGE, null, null, null, intentFilter, null, TEST_USER,
                    Context.RECEIVER_EXPORTED);
            assertNotNull(resultIntent);
            verify(mContentResolver, never()).getType(any());
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    private void broadcastIntent(Intent intent, Bundle options, boolean sticky) {
        broadcastIntent(intent, options, sticky, null, TEST_USER);
    }

    @SuppressWarnings("GuardedBy")
    private void broadcastIntent(Intent intent, Bundle options, boolean sticky,
            String resolvedType, int userId) {
        final int res = mAms.broadcastIntentLocked(null, null, null, intent, resolvedType, null, 0,
                null, null, null, null, null, 0, options, false, sticky,
                Process.myPid(), Process.myUid(), Process.myUid(), Process.myPid(), userId);
        assertEquals(ActivityManager.BROADCAST_SUCCESS, res);
    }

    private void assertStickyBroadcasts(ArrayList<StickyBroadcast> actualBroadcasts,
            StickyBroadcast... expectedBroadcasts) {
        final String errMsg = "Expected: " + Arrays.toString(expectedBroadcasts)
                + "; Actual: " + Arrays.toString(actualBroadcasts.toArray());
        assertEquals(errMsg, expectedBroadcasts.length, actualBroadcasts.size());
        for (int i = 0; i < expectedBroadcasts.length; ++i) {
            final StickyBroadcast expected = expectedBroadcasts[i];
            final StickyBroadcast actual = actualBroadcasts.get(i);
            assertTrue(errMsg, areEquals(expected, actual));
        }
    }

    private boolean areEquals(StickyBroadcast a, StickyBroadcast b) {
        if (!Objects.equals(a.intent.getAction(), b.intent.getAction())) {
            return false;
        }
        if (!Bundle.kindofEquals(a.intent.getExtras(), b.intent.getExtras())) {
            return false;
        }
        if (a.deferUntilActive != b.deferUntilActive) {
            return false;
        }
        if (a.originalCallingUid != b.originalCallingUid) {
            return false;
        }
        if (!Objects.equals(a.resolvedDataType, b.resolvedDataType)) {
            return false;
        }
        return true;
    }

    private interface ObserverChangesVerifier {
        void verify(IUidObserver observer, ChangeRecord changeItem) throws RemoteException;
    }

    private void verifyObserverReceivedChanges(IUidObserver observer, int[] changesToVerify,
            Map<Integer, ChangeRecord> changeItems, ObserverChangesVerifier verifier)
            throws RemoteException {
        for (int change : changesToVerify) {
            final ChangeRecord changeItem = changeItems.get(change);
            verifier.verify(observer, changeItem);
        }
    }

    /**
     * This test verifies that process state changes are dispatched to observers only when they
     * change across the cutpoint (this is specified when registering the observer).
     */
    @Test
    public void testDispatchUidChanges_procStateCutpoint() throws RemoteException {
        mockNoteOperation();

        final IUidObserver observer = mock(IUidObserver.Stub.class);

        when(observer.asBinder()).thenReturn((IBinder) observer);
        mAms.registerUidObserver(observer, ActivityManager.UID_OBSERVER_PROCSTATE /* which */,
                ActivityManager.PROCESS_STATE_SERVICE /* cutpoint */, null /* callingPackage */);
        // When we invoke AMS.registerUidObserver, there are some interactions with observer
        // mock in RemoteCallbackList class. We don't want to test those interactions and
        // at the same time, we don't want those to interfere with verifyNoMoreInteractions.
        // So, resetting the mock here.
        Mockito.reset(observer);

        final ChangeRecord changeItem = new ChangeRecord();
        changeItem.uid = TEST_UID;
        changeItem.change = UidRecord.CHANGE_PROCSTATE;
        changeItem.procState = ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
        changeItem.procStateSeq = 111;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // First process state message is always delivered regardless of whether the process state
        // change is above or below the cutpoint (PROCESS_STATE_SERVICE).
        verify(observer).onUidStateChanged(TEST_UID,
                changeItem.procState, changeItem.procStateSeq,
                ActivityManager.PROCESS_CAPABILITY_NONE);
        verifyNoMoreInteractions(observer);

        changeItem.procState = ActivityManager.PROCESS_STATE_RECEIVER;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // Previous process state change is below cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is also below cutpoint, so no callback will be invoked.
        verifyNoMoreInteractions(observer);

        changeItem.procState = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // Previous process state change is below cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is above cutpoint, so callback will be invoked with the
        // current process state change.
        verify(observer).onUidStateChanged(TEST_UID,
                changeItem.procState, changeItem.procStateSeq,
                ActivityManager.PROCESS_CAPABILITY_NONE);
        verifyNoMoreInteractions(observer);

        changeItem.procState = ActivityManager.PROCESS_STATE_TOP;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // Previous process state change is above cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is also above cutpoint, so no callback will be invoked.
        verifyNoMoreInteractions(observer);

        changeItem.procState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // Previous process state change is above cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is below cutpoint, so callback will be invoked with the
        // current process state change.
        verify(observer).onUidStateChanged(TEST_UID,
                changeItem.procState, changeItem.procStateSeq,
                ActivityManager.PROCESS_CAPABILITY_NONE);
        verifyNoMoreInteractions(observer);
    }

    /**
     * This test verifies that {@link UidObserverController#getValidateUidsForTest()} which is a
     * part of dumpsys is correctly updated.
     */
    @Test
    public void testDispatchUidChanges_validateUidsUpdated() {
        mockNoteOperation();

        final int[] changesForPendingItems = UID_RECORD_CHANGES;

        final int[] procStatesForPendingItems = {
            ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
            ActivityManager.PROCESS_STATE_CACHED_EMPTY,
            ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
            ActivityManager.PROCESS_STATE_SERVICE,
            ActivityManager.PROCESS_STATE_RECEIVER,
        };
        final ArrayList<ChangeRecord> pendingItemsForUids =
                new ArrayList<>(procStatesForPendingItems.length);
        for (int i = 0; i < procStatesForPendingItems.length; ++i) {
            final ChangeRecord item = new ChangeRecord();
            item.uid = i;
            item.change = changesForPendingItems[i];
            item.procState = procStatesForPendingItems[i];
            pendingItemsForUids.add(i, item);
        }

        // Verify that when there no observers listening to uid state changes, then there will
        // be no changes to validateUids.
        addPendingUidChanges(pendingItemsForUids);
        mAms.mUidObserverController.dispatchUidsChanged();
        assertEquals("No observers registered, so validateUids should be empty",
                0, mAms.mUidObserverController.getValidateUidsForTest().size());

        final IUidObserver observer = mock(IUidObserver.Stub.class);
        when(observer.asBinder()).thenReturn((IBinder) observer);
        mAms.registerUidObserver(observer, 0, 0, null);
        // Verify that when observers are registered, then validateUids is correctly updated.
        addPendingUidChanges(pendingItemsForUids);
        mAms.mUidObserverController.dispatchUidsChanged();
        for (int i = 0; i < pendingItemsForUids.size(); ++i) {
            final ChangeRecord item = pendingItemsForUids.get(i);
            final UidObserverController.ValidateUidRecord validateUidRecord =
                    mAms.mUidObserverController.getValidateUidsForTest().get(item.uid);
            if ((item.change & UidRecord.CHANGE_GONE) != 0) {
                assertNull("validateUidRecord should be null since the change is either "
                        + "CHANGE_GONE or CHANGE_GONE_IDLE", validateUidRecord);
            } else {
                assertNotNull("validateUidRecord should not be null since the change is neither "
                        + "CHANGE_GONE nor CHANGE_GONE_IDLE", validateUidRecord);
                assertEquals("processState: " + item.procState + " curProcState: "
                        + validateUidRecord.getProcState() + " should have been equal",
                        item.procState, validateUidRecord.getProcState());
                if (item.change == UidRecord.CHANGE_IDLE) {
                    assertTrue("UidRecord.idle should be updated to true for CHANGE_IDLE",
                            validateUidRecord.isIdle());
                } else if (item.change == UidRecord.CHANGE_ACTIVE) {
                    assertFalse("UidRecord.idle should be updated to false for CHANGE_ACTIVE",
                            validateUidRecord.isIdle());
                }
            }
        }

        // Verify that when uid state changes to CHANGE_GONE or CHANGE_GONE_IDLE, then it
        // will be removed from validateUids.
        assertNotEquals("validateUids should not be empty", 0,
                mAms.mUidObserverController.getValidateUidsForTest().size());
        for (int i = 0; i < pendingItemsForUids.size(); ++i) {
            final ChangeRecord item = pendingItemsForUids.get(i);
            // Assign CHANGE_GONE_IDLE to some items and CHANGE_GONE to the others, using even/odd
            // distribution for this assignment.
            item.change = (i % 2) == 0 ? (UidRecord.CHANGE_GONE | UidRecord.CHANGE_IDLE)
                    : UidRecord.CHANGE_GONE;
        }
        addPendingUidChanges(pendingItemsForUids);
        mAms.mUidObserverController.dispatchUidsChanged();
        assertEquals("validateUids should be empty, size="
                + mAms.mUidObserverController.getValidateUidsForTest().size(),
                        0, mAms.mUidObserverController.getValidateUidsForTest().size());
    }

    @Test
    public void testEnqueueUidChangeLocked_nullUidRecord() {
        // Use "null" uidRecord to make sure there is no crash.
        mAms.enqueueUidChangeLocked(null, TEST_UID, UidRecord.CHANGE_ACTIVE);
    }

    @MediumTest
    @Test
    public void testEnqueueUidChangeLocked_dispatchUidsChanged() {
        final UidRecord uidRecord = new UidRecord(TEST_UID, mAms);
        final int expectedProcState = PROCESS_STATE_SERVICE;
        mAms.mProcessStateController.setUidSetProcState(uidRecord, expectedProcState);
        mAms.mProcessStateController.setUidCurProcStateSeq(uidRecord, TEST_PROC_STATE_SEQ1);

        // Test with no pending uid records.
        for (int i = 0; i < UID_RECORD_CHANGES.length; ++i) {
            final int changeToDispatch = UID_RECORD_CHANGES[i];

            // Reset the current state
            mHandler.reset();
            clearPendingUidChanges();
            uidRecord.pendingChange.isPending = false;

            mAms.enqueueUidChangeLocked(uidRecord, -1, changeToDispatch);

            // Verify that pendingChange is updated correctly.
            final ChangeRecord pendingChange = uidRecord.pendingChange;
            assertTrue(pendingChange.isPending);
            assertEquals(TEST_UID, pendingChange.uid);
            assertEquals(expectedProcState, pendingChange.procState);
            assertEquals(TEST_PROC_STATE_SEQ1, pendingChange.procStateSeq);

            // TODO: Verify that DISPATCH_UIDS_CHANGED_UI_MSG is posted to handler.
        }
    }

    @MediumTest
    @Test
    public void testWaitForNetworkStateUpdate() throws Exception {
        // Check there is no crash when there is no UidRecord for myUid
        mAms.waitForNetworkStateUpdate(TEST_PROC_STATE_SEQ1);

        // Verify there is not waiting when the procStateSeq in the request already has
        // an updated network state.
        verifyWaitingForNetworkStateUpdate(
                TEST_PROC_STATE_SEQ1, // curProcStateSeq
                TEST_PROC_STATE_SEQ1, // lastNetworkUpdatedProcStateSeq
                TEST_PROC_STATE_SEQ1, // procStateSeqToWait
                false); // expectWait

        // Verify waiting for network works
        verifyWaitingForNetworkStateUpdate(
                TEST_PROC_STATE_SEQ1, // curProcStateSeq
                TEST_PROC_STATE_SEQ1 - 1, // lastNetworkUpdatedProcStateSeq
                TEST_PROC_STATE_SEQ1, // procStateSeqToWait
                true); // expectWait
    }

    @Test
    public void testGetDisplayIdsForStartingBackgroundUsers() {
        mInjector.secondaryDisplayIdsForStartingBackgroundUsers = new int[]{4, 8, 15, 16, 23, 42};

        int [] displayIds = mAms.getDisplayIdsForStartingVisibleBackgroundUsers();

        assertWithMessage("mAms.getDisplayIdsForStartingVisibleBackgroundUsers()")
                .that(displayIds).asList().containsExactly(4, 8, 15, 16, 23, 42);
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay_invalidDisplay() {
        mInjector.secondaryDisplayIdsForStartingBackgroundUsers = new int[]{4, 8, 15, 16, 23, 42};

        assertThrows(IllegalArgumentException.class,
                () -> mAms.startUserInBackgroundVisibleOnDisplay(USER_ID, 666,
                        /* unlockProgressListener= */ null));

        assertWithMessage("UserController.startUserOnSecondaryDisplay() calls")
                .that(mInjector.usersStartedOnSecondaryDisplays).isEmpty();
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay_validDisplay_failed() {
        mInjector.secondaryDisplayIdsForStartingBackgroundUsers = new int[]{ 4, 8, 15, 16, 23, 42 };
        mInjector.returnValueForstartUserOnSecondaryDisplay = false;

        boolean started = mAms.startUserInBackgroundVisibleOnDisplay(USER_ID, 42,
                /* unlockProgressListener= */ null);
        Log.v(TAG, "Started: " + started);

        assertWithMessage("mAms.startUserInBackgroundOnDisplay(%s, 42)", USER_ID)
                .that(started).isFalse();
        assertWithMessage("UserController.startUserOnSecondaryDisplay() calls")
                .that(mInjector.usersStartedOnSecondaryDisplays)
                .containsExactly(new Pair<>(USER_ID, 42));
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay_validDisplay_success() {
        mInjector.secondaryDisplayIdsForStartingBackgroundUsers = new int[]{ 4, 8, 15, 16, 23, 42 };
        mInjector.returnValueForstartUserOnSecondaryDisplay = true;

        boolean started = mAms.startUserInBackgroundVisibleOnDisplay(USER_ID, 42,
                /* unlockProgressListener= */ null);
        Log.v(TAG, "Started: " + started);

        assertWithMessage("mAms.startUserInBackgroundOnDisplay(%s, 42)", USER_ID)
                .that(started).isTrue();
        assertWithMessage("UserController.startUserOnDisplay() calls")
                .that(mInjector.usersStartedOnSecondaryDisplays)
                .containsExactly(new Pair<>(USER_ID, 42));
    }

    @Test
    @RequiresFlagsEnabled(android.security.Flags.FLAG_PREVENT_INTENT_REDIRECT)
    public void testAddCreatorToken() {
        Intent intent = new Intent();
        Intent extraIntent = new Intent("EXTRA_INTENT_ACTION");
        intent.putExtra("EXTRA_INTENT0", extraIntent);
        Intent nestedIntent = new Intent("NESTED_INTENT_ACTION");
        extraIntent.putExtra("NESTED_INTENT", nestedIntent);

        intent.collectExtraIntentKeys();
        mAms.addCreatorToken(intent, TEST_PACKAGE);

        ActivityManagerService.IntentCreatorToken token =
                (ActivityManagerService.IntentCreatorToken) extraIntent.getCreatorToken();
        assertThat(token).isNotNull();
        assertThat(token.getCreatorUid()).isEqualTo(mInjector.getCallingUid());
        assertThat(token.getCreatorPackage()).isEqualTo(TEST_PACKAGE);

        token = (ActivityManagerService.IntentCreatorToken) nestedIntent.getCreatorToken();
        assertThat(token).isNotNull();
        assertThat(token.getCreatorUid()).isEqualTo(mInjector.getCallingUid());
        assertThat(token.getCreatorPackage()).isEqualTo(TEST_PACKAGE);
    }

    @Test
    @RequiresFlagsEnabled(android.security.Flags.FLAG_PREVENT_INTENT_REDIRECT)
    public void testAddCreatorTokenForFillingIntent() {
        Intent intent = new Intent();
        Intent extraIntent = new Intent("EXTRA_INTENT_ACTION");
        intent.putExtra("EXTRA_INTENT0", extraIntent);
        Intent fillinIntent = new Intent();
        Intent fillinExtraIntent = new Intent("FILLIN_EXTRA_INTENT_ACTION");
        fillinIntent.putExtra("FILLIN_EXTRA_INTENT0", fillinExtraIntent);

        fillinIntent.collectExtraIntentKeys();
        intent.fillIn(fillinIntent, FILL_IN_ACTION);

        mAms.addCreatorToken(fillinIntent, TEST_PACKAGE);

        fillinExtraIntent = intent.getParcelableExtra("FILLIN_EXTRA_INTENT0", Intent.class);

        ActivityManagerService.IntentCreatorToken token =
                (ActivityManagerService.IntentCreatorToken) fillinExtraIntent.getCreatorToken();
        assertThat(token).isNotNull();
        assertThat(token.getCreatorUid()).isEqualTo(mInjector.getCallingUid());
        assertThat(token.getCreatorPackage()).isEqualTo(TEST_PACKAGE);
    }

    @Test
    @RequiresFlagsEnabled(android.security.Flags.FLAG_PREVENT_INTENT_REDIRECT)
    public void testCheckCreatorToken() {
        Intent intent = new Intent();
        Intent extraIntent = new Intent("EXTRA_INTENT_ACTION");
        intent.putExtra("EXTRA_INTENT", extraIntent);
        Intent nestedIntent = new Intent("NESTED_INTENT_ACTION");
        extraIntent.putExtra("NESTED_INTENT", nestedIntent);

        intent.collectExtraIntentKeys();

        // mimic client hack and sneak in an extra intent without going thru collectExtraIntentKeys.
        Intent extraIntent2 = new Intent("EXTRA_INTENT_ACTION2");
        intent.putExtra("EXTRA_INTENT2", extraIntent2);

        // mock parceling on the client side, unparcling on the system server side, then
        // addCreatorToken on system server side.
        final Parcel parcel = Parcel.obtain();
        intent.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        Intent newIntent = new Intent();
        newIntent.readFromParcel(parcel);
        intent = newIntent;
        mAms.addCreatorToken(intent, TEST_PACKAGE);
        // entering the target app's process.
        intent.checkCreatorToken();

        Intent extraIntent3 = new Intent("EXTRA_INTENT_ACTION3");
        intent.putExtra("EXTRA_INTENT3", extraIntent3);

        extraIntent = intent.getParcelableExtra("EXTRA_INTENT", Intent.class);
        extraIntent2 = intent.getParcelableExtra("EXTRA_INTENT2", Intent.class);
        extraIntent3 = intent.getParcelableExtra("EXTRA_INTENT3", Intent.class);
        nestedIntent = extraIntent.getParcelableExtra("NESTED_INTENT", Intent.class);

        assertThat(extraIntent.getExtendedFlags()
                & Intent.EXTENDED_FLAG_MISSING_CREATOR_OR_INVALID_TOKEN).isEqualTo(0);
        assertThat(nestedIntent.getExtendedFlags()
                & Intent.EXTENDED_FLAG_MISSING_CREATOR_OR_INVALID_TOKEN).isEqualTo(0);
        // sneaked in intent should have EXTENDED_FLAG_MISSING_CREATOR_OR_INVALID_TOKEN set.
        assertThat(extraIntent2.getExtendedFlags()
                & Intent.EXTENDED_FLAG_MISSING_CREATOR_OR_INVALID_TOKEN).isNotEqualTo(0);
        // local created intent should not have EXTENDED_FLAG_MISSING_CREATOR_OR_INVALID_TOKEN set.
        assertThat(extraIntent3.getExtendedFlags()
                & Intent.EXTENDED_FLAG_MISSING_CREATOR_OR_INVALID_TOKEN).isEqualTo(0);
    }

    @Test
    public void testUseCloneForCreatorTokenAndOriginalIntent_createSameIntentCreatorToken() {
        Intent testIntent = new Intent(TEST_ACTION1)
                .setComponent(TEST_COMPONENT)
                .setDataAndType(TEST_URI, TEST_TYPE)
                .setIdentifier(TEST_IDENTIFIER)
                .addCategory(TEST_CATEGORY);
        testIntent.setOriginalIntent(new Intent(TEST_ACTION2));
        testIntent.setSelector(new Intent(TEST_ACTION3));
        testIntent.setSourceBounds(new Rect(0, 0, 100, 100));
        testIntent.setLaunchToken(TEST_LAUNCH_TOKEN);
        testIntent.addFlags(ALL_SET_FLAG)
                .addExtendedFlags(ALL_SET_FLAG);
        ClipData testClipData = ClipData.newHtmlText("label", "text", "<html/>");
        testClipData.addItem(new ClipData.Item(new Intent(TEST_ACTION1)));
        testClipData.addItem(new ClipData.Item(TEST_URI));
        testIntent.putExtra(TEST_EXTRA_KEY1, TEST_EXTRA_VALUE1);

        ActivityManagerService.IntentCreatorToken tokenForFullIntent =
                new ActivityManagerService.IntentCreatorToken(TEST_CREATOR_UID,
                        TEST_CREATOR_PACKAGE, testIntent);
        ActivityManagerService.IntentCreatorToken tokenForCloneIntent =
                new ActivityManagerService.IntentCreatorToken(TEST_CREATOR_UID,
                        TEST_CREATOR_PACKAGE, testIntent.cloneForCreatorToken());

        assertThat(tokenForFullIntent.getKeyFields()).isEqualTo(tokenForCloneIntent.getKeyFields());
    }

    @Test
    public void testCanLaunchClipDataIntent() {
        ClipData clipData = ClipData.newIntent("test", new Intent("test"));
        clipData.prepareToLeaveProcess(true);
        // skip mimicking sending clipData to another app because it will just be parceled and
        // un-parceled.
        Intent intent = clipData.getItemAt(0).getIntent();
        // default intent redirect protection won't block an intent nested in a top level ClipData.
        assertThat(intent.getExtendedFlags()
                & Intent.EXTENDED_FLAG_MISSING_CREATOR_OR_INVALID_TOKEN).isEqualTo(0);
    }

    @Test
    public void getExecutableMethodFileOffsets_nullThread_throwsIllegalStateException() {
        final String processName = "test.process";
        final int pid = 1234;
        final int uid = 5678;
        final ProcessRecord mockProcessRecord = mock(ProcessRecord.class);
        final MethodDescriptor mockMethodDescriptor = mock(MethodDescriptor.class);
        final IOffsetCallback mockCallback = mock(IOffsetCallback.class);

        // Spy on the real ProcessList instance to mock its behavior.
        spyOn(mAms.mProcessList);

        // Stub getProcessRecordLocked to return our mock record.
        doReturn(mockProcessRecord).when(mAms.mProcessList).getProcessRecordLocked(processName,
                uid);
        // Stub getPid to match the request.
        when(mockProcessRecord.getPid()).thenReturn(pid);
        // Stub getThread to return null, simulating a process that's not fully attached.
        when(mockProcessRecord.getThread()).thenReturn(null);

        // Execute the method and assert that it throws the expected exception.
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                mAms.mInternal.getExecutableMethodFileOffsets(
                        processName, pid, uid, mockMethodDescriptor, mockCallback));

        // Verify the exception message.
        assertThat(thrown.getMessage()).isEqualTo("app ActivityThread is null");
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testGetActivityInfoForUser_PccUid() {
        int pccUid = android.os.Process.FIRST_PCC_UID + 5;
        ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.uid = 10123;
        aInfo.applicationInfo.pccUid = pccUid;
        aInfo.flags |= ActivityInfo.FLAG_RUN_IN_PCC_SANDBOX;

        // Should return original aInfo because pccUid < PER_USER_RANGE
        ActivityInfo result = mAms.getActivityInfoForUser(aInfo, 0);
        assertEquals(aInfo, result);
        assertEquals(pccUid, result.getUid());
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testGetActivityInfoForUser_NormalUid() {
        int appUid = 10123;
        ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.uid = appUid;
        aInfo.applicationInfo.pccUid = -1;

        ActivityInfo result = mAms.getActivityInfoForUser(aInfo, 0);
        assertEquals(aInfo, result);
        assertEquals(appUid, result.getUid());
    }

    private void setupAllowComponentAccessPolicy(
            String ownerPkg, int userId, List<SignedPackage> allowedPackages) {
        AllowComponentAccessPolicyInfo policyInfo =
                (allowedPackages == null)
                        ? null
                        : new AllowComponentAccessPolicyInfo(allowedPackages);

        doReturn(policyInfo)
                .when(mPackageManagerInternal)
                .getAllowComponentAccessPolicyInfo(eq(ownerPkg), eq(userId));
    }

    private void setupPackageSigning(String pkgName, String sha256Cert) {
        AndroidPackage mockPkg = mock(AndroidPackage.class);
        SigningDetails mockSigning = mock(SigningDetails.class);

        if (sha256Cert != null) {
            byte[] certBytes = sha256Cert.getBytes();
            lenient().when(mockSigning.hasSha256Certificate(aryEq(certBytes))).thenReturn(true);
        }

        lenient().when(mockPkg.getSigningDetails()).thenReturn(mockSigning);
        lenient().when(mPackageManagerInternal.getPackage(eq(pkgName))).thenReturn(mockPkg);
    }

    private SignedPackage createSignedPackage(String pkg, String cert) {
        return new SignedPackage(pkg, cert != null ? cert.getBytes() : /* certBytes= */ null);
    }

    private void verifyWaitingForNetworkStateUpdate(long curProcStateSeq,
            long lastNetworkUpdatedProcStateSeq,
            final long procStateSeqToWait, boolean expectWait) throws Exception {
        final UidRecord record = new UidRecord(Process.myUid(), mAms);
        mAms.mProcessStateController.setUidCurProcStateSeq(record, curProcStateSeq);
        record.lastNetworkUpdatedProcStateSeq = lastNetworkUpdatedProcStateSeq;
        mAms.mProcessList.mActiveUids.put(Process.myUid(), record);

        CustomThread thread = new CustomThread(record.networkStateLock, new Runnable() {
            @Override
            public void run() {
                mAms.waitForNetworkStateUpdate(procStateSeqToWait);
            }
        });
        final String errMsg = "Unexpected state for " + record;
        if (expectWait) {
            thread.startAndWait(errMsg, true);
            thread.assertTimedWaiting(errMsg);
            synchronized (record.networkStateLock) {
                record.networkStateLock.notifyAll();
            }
            thread.assertTerminated(errMsg);
            assertTrue(thread.mNotified);
            assertEquals(0, record.procStateSeqWaitingForNetwork);
        } else {
            thread.start();
            thread.assertTerminated(errMsg);
        }

        mAms.mProcessList.mActiveUids.clear();
    }

    private void addPendingUidChange(ChangeRecord record) {
        mAms.mUidObserverController.getPendingUidChangesForTest().add(record);
    }

    private void addPendingUidChanges(ArrayList<ChangeRecord> changes) {
        final ArrayList<ChangeRecord> pendingChanges =
                mAms.mUidObserverController.getPendingUidChangesForTest();
        for (int i = 0; i < changes.size(); ++i) {
            final ChangeRecord record = changes.get(i);
            pendingChanges.add(record);
        }
    }

    private void clearPendingUidChanges() {
        mAms.mUidObserverController.getPendingUidChangesForTest().clear();
    }

    @Test
    public void testStartForegroundServiceDelegateWithNotification() throws Exception {
        testForegroundServiceDelegate(true, true);
    }

    @Test
    public void testStartForegroundServiceDelegateWithoutNotification() throws Exception {
        testForegroundServiceDelegate(true, false);
    }

    // Tests the start/stop foreground service delegate System Apis exposed to mainline modules.
    @Test
    @RequiresFlagsEnabled(com.android.server.am.Flags.FLAG_FGS_DELEGATE_SYSTEM_API)
    public void testStartForegroundServiceDelegateSystemApis() throws Exception {
        testForegroundServiceDelegate(false, false);
    }

    @SuppressWarnings("GuardedBy")
    private void testForegroundServiceDelegate(
            // If true, it will construct ForegroundServiceDelegationOptions else
            // ForegroundServiceDelegationParams and calls the appropriate method.
            boolean useOptions,
            boolean withNotification) throws Exception {
        mockNoteOperation();

        final int notificationId = 42;
        final Notification notification = mock(Notification.class);

        addUidRecord(TEST_UID);
        final ProcessRecord app = mAms.mProcessList.getLruProcessesLSP().get(0);
        app.mPid = TEST_PID;
        app.info.packageName = TEST_PACKAGE_NAME;
        app.info.processName = TEST_PACKAGE_NAME;

        doReturn(app.info).when(mPackageManager).getApplicationInfo(
                eq(app.info.packageName), anyLong(), anyInt());

        doReturn(true).when(mActiveServices)
                .canStartForegroundServiceLocked(anyInt(), anyInt(), anyString());
        doReturn(REASON_DENIED).when(mActiveServices)
                .shouldAllowFgsWhileInUsePermissionLocked(anyString(), anyInt(), anyInt(),
                        any(ProcessRecord.class), any(BackgroundStartPrivileges.class));

        doReturn(true).when(mNotificationManagerInternal).areNotificationsEnabledForPackage(
                anyString(), anyInt());
        doReturn(mock(Icon.class)).when(notification).getSmallIcon();
        doReturn("").when(notification).getChannelId();
        doReturn(mock(NotificationChannel.class)).when(mNotificationManagerInternal)
                .getNotificationChannel(anyString(), anyInt(), anyString());

        mAms.mAppProfiler.mCachedAppsWatermarkData.mCachedAppHighWatermark = Integer.MAX_VALUE;

        ForegroundServiceDelegationOptions fgsdo = null;
        ForegroundServiceDelegationParams fgsdp = null;

        if (useOptions) {
            final ForegroundServiceDelegationOptions.Builder optionsBuilder =
                    new ForegroundServiceDelegationOptions.Builder()
                            .setClientPid(app.mPid)
                            .setClientUid(app.uid)
                            .setClientPackageName(app.info.packageName)
                            .setClientAppThread(app.getThread())
                            .setSticky(false)
                            .setClientInstanceName(
                                    "SpecialUseFgsDelegate_"
                                            + Process.myUid()
                                            + "_"
                                            + app.uid
                                            + "_"
                                            + app.info.packageName)
                            .setForegroundServiceTypes(
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                            .setDelegationService(
                                ForegroundServiceDelegationOptions.DELEGATION_SERVICE_SPECIAL_USE);
            if (withNotification) {
                optionsBuilder.setClientNotification(notificationId, notification);
            }
            fgsdo = optionsBuilder.build();
        } else {
            // Spy on the real mPidsSelfLocked map object to mock its behavior.
            spyOn(mAms.mPidsSelfLocked);
            doReturn(app).when(mAms.mPidsSelfLocked).get(anyInt());
            fgsdp = new ForegroundServiceDelegationParams(
                app.mPid,
                app.uid,
                app.info.packageName,  // clientPackageName
                false,  // isSticky
                "SpecialUseFgsDelegate_"
                    + Process.myUid()
                    + "_"
                    + app.uid
                    + "_"
                    + app.info.packageName,  // clientInstanceName
                // foregroundServiceTypes
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                // delegationReason
                ForegroundServiceDelegationParams.DELEGATION_REASON_SPECIAL_USE);
        }

        final CountDownLatch[] latchHolder = new CountDownLatch[1];
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                latchHolder[0].countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                latchHolder[0].countDown();
            }
        };

        latchHolder[0] = new CountDownLatch(1);
        if (useOptions) {
            mAms.mInternal.startForegroundServiceDelegate(fgsdo, conn);
        } else {
            ((ActivityManagerLocal) mAms.mInternal).startForegroundServiceDelegate(fgsdp, conn);
        }

        assertThat(latchHolder[0].await(5, TimeUnit.SECONDS)).isTrue();
        assertEquals(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, app.getProcState());
        final long timeoutMs = 5000L;
        final VerificationMode mode = withNotification
                ? timeout(timeoutMs) : after(timeoutMs).atMost(0);
        verify(mNotificationManagerInternal, mode)
                .enqueueNotification(eq(app.info.packageName), eq(app.info.packageName),
                        eq(app.info.uid), eq(app.mPid), eq(null),
                        eq(notificationId), eq(notification), anyInt(), eq(true));

        latchHolder[0] = new CountDownLatch(1);
        if (useOptions) {
            mAms.mInternal.stopForegroundServiceDelegate(fgsdo);
        } else {
            ((ActivityManagerLocal) mAms.mInternal).stopForegroundServiceDelegate(fgsdp);
        }

        assertThat(latchHolder[0].await(5, TimeUnit.SECONDS)).isTrue();
        assertEquals(ActivityManager.PROCESS_STATE_CACHED_EMPTY, app.getProcState());
        verify(mNotificationManagerInternal, mode)
                .cancelNotification(eq(app.info.packageName), eq(app.info.packageName),
                        eq(app.info.uid), eq(app.mPid), eq(null),
                        eq(notificationId), anyInt());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void bindBackupAgent_fullBackup_shouldUseRestrictedMode_setsInFullBackup()
            throws Exception {
        ActivityManagerService spyAms = spy(mAms);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = TEST_PACKAGE;
        applicationInfo.processName = TEST_PACKAGE;
        applicationInfo.uid = TEST_UID;
        doReturn(applicationInfo).when(mPackageManager).getApplicationInfo(eq(TEST_PACKAGE),
                anyLong(), anyInt());
        ProcessRecord appRec = new ProcessRecord(mAms, applicationInfo, TAG, TEST_UID);
        doReturn(appRec).when(spyAms).getProcessRecordLocked(eq(TEST_PACKAGE), eq(TEST_UID));

        spyAms.bindBackupAgent(TEST_PACKAGE, ApplicationThreadConstants.BACKUP_MODE_FULL,
                UserHandle.USER_SYSTEM,
                BackupAnnotations.BackupDestination.CLOUD, /* shouldUseRestrictedMode= */
                true);

        assertThat(appRec.isInFullBackup()).isTrue();
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void bindBackupAgent_fullBackup_shouldNotUseRestrictedMode_doesNotSetInFullBackup()
            throws Exception {
        ActivityManagerService spyAms = spy(mAms);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = TEST_PACKAGE;
        applicationInfo.processName = TEST_PACKAGE;
        applicationInfo.uid = TEST_UID;
        doReturn(applicationInfo).when(mPackageManager).getApplicationInfo(eq(TEST_PACKAGE),
                anyLong(), anyInt());
        ProcessRecord appRec = new ProcessRecord(mAms, applicationInfo, TAG, TEST_UID);
        doReturn(appRec).when(spyAms).getProcessRecordLocked(eq(TEST_PACKAGE), eq(TEST_UID));

        spyAms.bindBackupAgent(TEST_PACKAGE, ApplicationThreadConstants.BACKUP_MODE_FULL,
                UserHandle.USER_SYSTEM,
                BackupAnnotations.BackupDestination.CLOUD, /* shouldUseRestrictedMode= */
                false);

        assertThat(appRec.isInFullBackup()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void bindPccBackupAgent_usesPccUid()
            throws Exception {
        ActivityManagerService spyAms = spy(mAms);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = TEST_PACKAGE;
        applicationInfo.processName = TEST_PACKAGE;
        applicationInfo.uid = TEST_UID;
        applicationInfo.pccUid = PCC_UID_1;
        applicationInfo.backupAgentProcess = BACKUP_AGENT_PROCESS_PCC;
        doReturn(applicationInfo).when(mPackageManager).getApplicationInfo(eq(TEST_PACKAGE),
                anyLong(), anyInt());
        ProcessRecord appRec = new ProcessRecord(mAms, applicationInfo, TAG, PCC_UID_1);

        doReturn(appRec).when(spyAms).getProcessRecordLocked(eq(TEST_PACKAGE), eq(PCC_UID_1));
        spyAms.bindBackupAgent(TEST_PACKAGE, ApplicationThreadConstants.BACKUP_MODE_FULL,
                UserHandle.USER_SYSTEM,
                BackupAnnotations.BackupDestination.CLOUD, /* shouldUseRestrictedMode= */
                true);

        verify(spyAms).getProcessRecordLocked(eq(TEST_PACKAGE), eq(PCC_UID_1));
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void bindMainBackupAgent_usesAppUid()
            throws Exception {
        ActivityManagerService spyAms = spy(mAms);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = TEST_PACKAGE;
        applicationInfo.processName = TEST_PACKAGE;
        applicationInfo.uid = TEST_UID;
        applicationInfo.pccUid = PCC_UID_1;
        applicationInfo.backupAgentProcess = BACKUP_AGENT_PROCESS_MAIN;
        doReturn(applicationInfo).when(mPackageManager).getApplicationInfo(eq(TEST_PACKAGE),
                anyLong(), anyInt());
        ProcessRecord appRec = new ProcessRecord(mAms, applicationInfo, TAG, TEST_UID);

        doReturn(appRec).when(spyAms).getProcessRecordLocked(eq(TEST_PACKAGE), eq(TEST_UID));
        spyAms.bindBackupAgent(TEST_PACKAGE, ApplicationThreadConstants.BACKUP_MODE_FULL,
                UserHandle.USER_SYSTEM,
                BackupAnnotations.BackupDestination.CLOUD, /* shouldUseRestrictedMode= */
                true);

        verify(spyAms).getProcessRecordLocked(eq(TEST_PACKAGE), eq(TEST_UID));
    }

    @Test
    public void testCallsForegroundServiceOptionsWithDefault_throwsException()
            throws Exception {
        final ForegroundServiceDelegationOptions.Builder optionsBuilder =
                new ForegroundServiceDelegationOptions.Builder()
                .setClientPid(1)
                .setClientUid(2)
                .setClientPackageName("package_name")
                .setSticky(false)
                .setClientInstanceName("dummy")
                .setForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                .setDelegationService(
                        ForegroundServiceDelegationOptions.DELEGATION_SERVICE_DEFAULT);
        Exception e = assertThrows(
            IllegalArgumentException.class, () -> optionsBuilder.build());

        assertThat(e).hasMessageThat().isEqualTo(
            "Default is not allowed to be passed in. "
            + "Use a more specific Delegation Service Identifier!");
    }

    @Test
    public void testServiceForegroundTimeoutAnrWarningMsg() {
        doNothing()
                .when(mActiveServices)
                .serviceForegroundAnrWarning(any(ServiceRecord.class), anyInt(), anyLong());

        ServiceRecord serviceRecord = mock(ServiceRecord.class);
        int anrId = 10;
        long elapsedTimeMs = 5000L;

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = serviceRecord;
        args.argi1 = anrId;
        args.argl1 = elapsedTimeMs;

        Message msg =
                mAms.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_ANR_WARNING_MSG);
        msg.obj = args;

        mAms.mHandler.handleMessage(msg);

        verify(mActiveServices)
                .serviceForegroundAnrWarning(eq(serviceRecord), eq(anrId), eq(elapsedTimeMs));
    }

    @Test
    public void testServiceTimeoutWarningMsg() {
        doNothing()
                .when(mActiveServices)
                .serviceTimeoutAnrWarning(any(ProcessRecord.class), anyInt(), anyLong());

        ProcessRecord processRecord = mock(ProcessRecord.class);
        int anrId = 10;
        long elapsedTimeMs = 5000L;

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = processRecord;
        args.argi1 = anrId;
        args.argl1 = elapsedTimeMs;

        Message msg =
                mAms.mHandler.obtainMessage(ActivityManagerService.SERVICE_TIMEOUT_WARNING_MSG);
        msg.obj = args;

        mAms.mHandler.handleMessage(msg);

        verify(mActiveServices)
                .serviceTimeoutAnrWarning(eq(processRecord), eq(anrId), eq(elapsedTimeMs));
    }

    @Test
    public void testShortFgsAnrTimeoutWarningMsg() {
        doNothing()
                .when(mActiveServices)
                .onShortFgsAnrTimeoutWarning(any(ServiceRecord.class), anyInt(), anyLong());

        ServiceRecord serviceRecord = mock(ServiceRecord.class);
        int anrId = 10;
        long elapsedTimeMs = 5000L;

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = serviceRecord;
        args.argi1 = anrId;
        args.argl1 = elapsedTimeMs;

        Message msg =
                mAms.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_SHORT_FGS_ANR_TIMEOUT_WARNING_MSG);
        msg.obj = args;

        mAms.mHandler.handleMessage(msg);

        verify(mActiveServices)
                .onShortFgsAnrTimeoutWarning(eq(serviceRecord), eq(anrId), eq(elapsedTimeMs));
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_NATIVE_APP_ZYGOTE)
    public void testCreateAppZygoteForProcessIfNeeded_managedAndNative() throws Exception {
        final int appUid = 10001;
        final String packageName = "com.test.app";
        final String processName = "com.test.app";

        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.processName = processName;
        info.uid = appUid;

        mAms.mProcessList.mAppIsolatedUidRangeAllocator.getOrCreateIsolatedUidRangeLocked(
                processName, appUid);

        HostingRecord managedHostingRecord = HostingRecord.byAppZygote_(
                HostingRecord.HOSTING_TYPE_BOUND_SERVICE,
                new ComponentName(packageName, "ManagedService"),
                packageName, appUid, processName, false /* isNativeService */,
                Process.INVALID_UID /* callerUid */, null /* callerProcessName */);
        ProcessRecord managedApp = new ProcessRecord(mAms, info, processName, appUid);
        managedApp.setHostingRecord(managedHostingRecord);

        HostingRecord nativeHostingRecord = HostingRecord.byAppZygote_(
                HostingRecord.HOSTING_TYPE_BOUND_SERVICE,
                new ComponentName(packageName, "NativeService"),
                packageName, appUid, processName, true /* isNativeService */,
                Process.INVALID_UID /* callerUid */, null /* callerProcessName */);
        ProcessRecord nativeApp = new ProcessRecord(mAms, info, processName, appUid);
        nativeApp.setHostingRecord(nativeHostingRecord);

        spyOn(mAms.mProcessList);

        AppZygote managedZygoteMock = mock(AppZygote.class);
        AppZygote nativeZygoteMock = mock(AppZygote.class);

        doReturn(info).when(managedZygoteMock).getAppInfo();
        doReturn(info).when(nativeZygoteMock).getAppInfo();
        doReturn(managedZygoteMock).when(mAms.mProcessList).newAppZygote(
                any(), any(), anyInt(), anyInt(), anyInt(), eq(false), anyString());
        doReturn(nativeZygoteMock).when(mAms.mProcessList).newAppZygote(
                any(), any(), anyInt(), anyInt(), anyInt(), eq(true), anyString());

        AppZygote managedZygote = mAms.mProcessList.createAppZygoteForProcessIfNeeded(managedApp);
        assertEquals(managedZygoteMock, managedZygote);

        AppZygote nativeZygote = mAms.mProcessList.createAppZygoteForProcessIfNeeded(nativeApp);
        assertEquals(nativeZygoteMock, nativeZygote);

        assertEquals(managedZygote,
                mAms.mProcessList.mAppZygotes.get(processName + "_zygote", appUid));
        assertEquals(nativeZygote,
                mAms.mProcessList.mAppZygotes.get(processName + "_zygote_native", appUid));
    }

    @Test
    @RequiresFlagsDisabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testisOutgoingTransactionsAuditable_flagDisabled() {
        assertFalse(mAms.mProcessList.isOutgoingTransactionsAuditable(PCC_UID_1));
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testisOutgoingTransactionsAuditable_flagEnabled_pccUid() {
        assertTrue(mAms.mProcessList.isOutgoingTransactionsAuditable(PCC_UID_1));
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testisOutgoingTransactionsAuditable_flagEnabled_pcsUid() {
        when(mMockPccSandboxManagerInternal.isPrivateComputeServicesUid(REGULAR_UID))
                .thenReturn(true);
        assertTrue(mAms.mProcessList.isOutgoingTransactionsAuditable(REGULAR_UID));
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testisOutgoingTransactionsAuditable_flagEnabled_regularUid() {
        when(mMockPccSandboxManagerInternal.isPrivateComputeServicesUid(REGULAR_UID))
                .thenReturn(false);
        assertFalse(mAms.mProcessList.isOutgoingTransactionsAuditable(REGULAR_UID));
    }

    @Test
    @RequiresFlagsEnabled(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public void testisOutgoingTransactionsAuditable_flagEnabled_noManager() {
        LocalServices.removeServiceForTest(PccSandboxManagerInternal.class);
        assertFalse(mAms.mProcessList.isOutgoingTransactionsAuditable(REGULAR_UID));
    }

    @Test
    public void testEnforceDumpPermissionForPackage_hasPermission() {
        ActivityManagerService spyAms = spy(mAms);
        spyAms.mPackageManagerInt = mPackageManagerInternal;
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(spyAms)
                .checkCallingPermission(anyString());
        final int testPackageUid = 12345;
        doReturn(testPackageUid)
                .when(mPackageManagerInternal)
                .getPackageUid(eq(TEST_PACKAGE), anyLong(), anyInt());

        int uid =
                spyAms.enforceDumpPermissionForPackage(
                        TEST_PACKAGE, TEST_USER_ID, TEST_UID, TEST_CALLER_NAME);
        assertEquals(testPackageUid, uid);
    }

    @Test
    public void testEnforceDumpPermissionForPackage_noPermission_ownPackage() {
        ActivityManagerService spyAms = spy(mAms);
        spyAms.mPackageManagerInt = mPackageManagerInternal;
        doReturn(PackageManager.PERMISSION_DENIED).when(spyAms).checkCallingPermission(anyString());
        spyOn(spyAms.mContext);
        PackageManager pm = mock(PackageManager.class);
        doReturn(pm).when(spyAms.mContext).getPackageManager();
        doReturn(new String[] {TEST_PACKAGE}).when(pm).getPackagesForUid(TEST_UID);

        int uid =
                spyAms.enforceDumpPermissionForPackage(
                        TEST_PACKAGE, TEST_USER_ID, TEST_UID, TEST_CALLER_NAME);
        assertEquals(UserHandle.getUid(TEST_USER_ID, UserHandle.getAppId(TEST_UID)), uid);
    }

    @Test
    public void testEnforceDumpPermissionForPackage_noPermission_otherPackage() {
        ActivityManagerService spyAms = spy(mAms);
        spyAms.mPackageManagerInt = mPackageManagerInternal;
        doReturn(PackageManager.PERMISSION_DENIED).when(spyAms).checkCallingPermission(anyString());
        spyOn(spyAms.mContext);
        PackageManager pm = mock(PackageManager.class);
        doReturn(pm).when(spyAms.mContext).getPackageManager();
        doReturn(new String[] {TEST_OTHER_PACKAGE}).when(pm).getPackagesForUid(TEST_UID);

        assertThrows(
                SecurityException.class,
                () -> {
                    spyAms.enforceDumpPermissionForPackage(
                            TEST_PACKAGE, TEST_USER_ID, TEST_UID, TEST_CALLER_NAME);
                });
    }

    @Test
    public void testIsPccAssociationAllowedBySysConfig_nullInputs_returnsFalse() {
        // Null inputs should safely and immediately return false
        assertFalse("Should safely handle null caller package",
                mAms.isPccAssociationAllowedBySysConfigLocked(null, TEST_TARGET_PKG));
        assertFalse("Should safely handle null target package",
                mAms.isPccAssociationAllowedBySysConfigLocked(TEST_CALLER_PKG, null));
        assertFalse("Should safely handle all null inputs",
                mAms.isPccAssociationAllowedBySysConfigLocked(null, null));
    }

    @Test
    public void testIsPccAssociationAllowedBySysConfig_lazyInitialization_cachesSysConfig()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(SystemConfig.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            SystemConfig mockSystemConfig = mock(SystemConfig.class);
            ExtendedMockito.doReturn(mockSystemConfig).when(SystemConfig::getInstance);
            when(mockSystemConfig.getAllowedAssociations()).thenReturn(new ArrayMap<>());

            // First call: Should trigger ensureAllowedAssociations() and query SystemConfig
            mAms.isPccAssociationAllowedBySysConfigLocked(TEST_CALLER_PKG, TEST_TARGET_PKG);
            verify(mockSystemConfig, times(1)).getAllowedAssociations();

            // Second call: Should use the cached mAllowedAssociations map
            mAms.isPccAssociationAllowedBySysConfigLocked(TEST_CALLER_PKG, TEST_TARGET_PKG);
            verify(mockSystemConfig, times(1)).getAllowedAssociations();
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testIsPccAssociationAllowedBySysConfig_appliesRulesBidirectionally()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(SystemConfig.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            SystemConfig mockSystemConfig = mock(SystemConfig.class);
            ExtendedMockito.doReturn(mockSystemConfig).when(SystemConfig::getInstance);

            // Set up SysConfig to allow an association between TEST_CALLER_PKG and TEST_TARGET_PKG
            ArrayMap<String, ArraySet<String>> allowedAssociations = new ArrayMap<>();
            allowedAssociations.put(TEST_CALLER_PKG,
                    new ArraySet<>(new String[] {TEST_TARGET_PKG}));
            when(mockSystemConfig.getAllowedAssociations()).thenReturn(allowedAssociations);

            // Assert 1: Caller -> Target (Forward check)
            assertTrue("Association should be allowed when caller explicitly allows target",
                    mAms.isPccAssociationAllowedBySysConfigLocked(
                            TEST_CALLER_PKG, TEST_TARGET_PKG));

            // Assert 2: Target -> Caller (Reverse check, method should handle bidirectionally)
            assertTrue("Association should be allowed when target explicitly allows caller",
                    mAms.isPccAssociationAllowedBySysConfigLocked(
                            TEST_TARGET_PKG, TEST_CALLER_PKG));

            // Assert 3: Unrelated packages
            assertFalse("Association should be denied for unrelated packages",
                    mAms.isPccAssociationAllowedBySysConfigLocked(
                            TEST_CALLER_PKG, "com.unrelated.pkg"));
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testIsPccAssociationAllowedBySysConfig_emptyOrMissingRules_returnsFalse()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(SystemConfig.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            SystemConfig mockSystemConfig = mock(SystemConfig.class);
            ExtendedMockito.doReturn(mockSystemConfig).when(SystemConfig::getInstance);

            // Empty SysConfig associations
            when(mockSystemConfig.getAllowedAssociations()).thenReturn(new ArrayMap<>());

            assertFalse("Should return false when no explicit sysconfig associations exist",
                    mAms.isPccAssociationAllowedBySysConfigLocked(
                            TEST_CALLER_PKG, TEST_TARGET_PKG));
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testIsPccAssociationAllowedBySysConfig_mutualConsent_enforced() throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(SystemConfig.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            SystemConfig mockSystemConfig = mock(SystemConfig.class);
            ExtendedMockito.doReturn(mockSystemConfig).when(SystemConfig::getInstance);

            ArrayMap<String, ArraySet<String>> allowedAssociations = new ArrayMap<>();

            // Setup: Caller explicitly allows Target
            allowedAssociations.put(TEST_CALLER_PKG,
                    new ArraySet<>(new String[] {TEST_TARGET_PKG}));

            // Setup: Target defines rules, but DOES NOT allow Caller
            allowedAssociations.put(TEST_TARGET_PKG,
                    new ArraySet<>(new String[] {"com.some.other.pkg"}));

            when(mockSystemConfig.getAllowedAssociations()).thenReturn(allowedAssociations);

            // Assert 1: Since Target defines an allowlist that omits Caller, it MUST be denied.
            assertFalse(
                    "Association should be denied if target has an allowlist that omits the caller",
                    mAms.isPccAssociationAllowedBySysConfigLocked(
                            TEST_CALLER_PKG, TEST_TARGET_PKG));

            // Fix the Target so it mutually allows the Caller
            allowedAssociations.get(TEST_TARGET_PKG).add(TEST_CALLER_PKG);

            // Assert 2: Now both sides mutually consent
            assertTrue(
                    "Association should be allowed when both sides explicitly allow each other",
                    mAms.isPccAssociationAllowedBySysConfigLocked(
                            TEST_CALLER_PKG, TEST_TARGET_PKG));
        } finally {
            mockitoSession.finishMocking();
        }
    }

    private static class TestHandler extends Handler {
        private static final long WAIT_FOR_MSG_TIMEOUT_MS = 4000; // 4 sec
        private static final long WAIT_FOR_MSG_INTERVAL_MS = 400; // 0.4 sec

        private final Set<Integer> mMsgsHandled = new HashSet<>();
        private final List<Runnable> mRunnablesToIgnore = new ArrayList<>();

        TestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void dispatchMessage(Message msg) {
            if (msg.getCallback() != null && mRunnablesToIgnore.contains(msg.getCallback())) {
                return;
            }
            super.dispatchMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            mMsgsHandled.add(msg.what);
        }

        public void waitForMessage(int msg) {
            final long endTime = System.currentTimeMillis() + WAIT_FOR_MSG_TIMEOUT_MS;
            while (!mMsgsHandled.contains(msg) && System.currentTimeMillis() < endTime) {
                SystemClock.sleep(WAIT_FOR_MSG_INTERVAL_MS);
            }
            if (!mMsgsHandled.contains(msg)) {
                fail("Timed out waiting for the message to be handled, msg: " + msg);
            }
        }

        public void setRunnablesToIgnore(List<Runnable> runnables) {
            mRunnablesToIgnore.clear();
            mRunnablesToIgnore.addAll(runnables);
        }

        public void reset() {
            mMsgsHandled.clear();
        }
    }

    private class TestInjector extends Injector {
        public boolean restricted = true;
        public int[] secondaryDisplayIdsForStartingBackgroundUsers;

        public boolean returnValueForstartUserOnSecondaryDisplay;
        public List<Pair<Integer, Integer>> usersStartedOnSecondaryDisplays = new ArrayList<>();

        TestInjector(Context context) {
            super(context);
        }

        @Override
        public AppOpsService getAppOpsService(Handler handler) {
            return mAppOpsService;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandler;
        }

        @Override
        public boolean isNetworkRestrictedForUid(int uid) {
            return restricted;
        }

        @Override
        public int[] getDisplayIdsForStartingVisibleBackgroundUsers() {
            return secondaryDisplayIdsForStartingBackgroundUsers;
        }

        @Override
        public boolean startUserInBackgroundVisibleOnDisplay(int userId, int displayId,
                IProgressListener unlockProgressListener) {
            usersStartedOnSecondaryDisplays.add(new Pair<>(userId, displayId));
            return returnValueForstartUserOnSecondaryDisplay;
        }

        @Override
        public ActiveServices getActiveServices(ActivityManagerService service) {
            if (mActiveServices == null) {
                mActiveServices = spy(new ActiveServices(service));
            }
            return mActiveServices;
        }

        @Override
        public BatteryStatsService getBatteryStatsService() {
            return mBatteryStatsService;
        }
    }

    private static class TestContext extends ContextWrapper {
        private final ContentResolver mContentResolver;

        TestContext(Context context, ContentResolver contentResolver) {
            super(context);
            mContentResolver = contentResolver;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
