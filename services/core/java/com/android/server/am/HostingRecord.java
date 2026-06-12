/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.os.Process.INVALID_UID;

import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ACTIVITY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ADDED_APPLICATION;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BACKUP;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BOUND_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BROADCAST;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_CONTENT_PROVIDER;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_EMPTY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_LINK_FAIL;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_NEXT_ACTIVITY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_NEXT_TOP_ACTIVITY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ON_HOLD;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_RESTART;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_STARTED_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_SYSTEM;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_TOP_ACTIVITY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_ZYGOTE__HOSTING_ZYGOTE_APP;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_ZYGOTE__HOSTING_ZYGOTE_REGULAR;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_ZYGOTE__HOSTING_ZYGOTE_WEBVIEW;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_ALARM;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_JOB;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_PUSH_MESSAGE;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TYPE__UNKNOWN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class describes various information required to start a process.
 *
 * The {@code mHostingType} field describes the reason why we started a process, and
 * is only used for logging and stats.
 *
 * The {@code mHostingName} field describes the Component for which we are starting the
 * process, and is only used for logging and stats.
 *
 * The {@code mHostingZygote} field describes from which Zygote the new process should be spawned.
 *
 * The {@code mTriggerType} field describes the trigger that started this processs. This could be
 * an alarm or a push-message for a broadcast, for example. This is purely for logging and stats.
 *
 * {@code mDefiningPackageName} contains the packageName of the package that defines the
 * component we want to start; this can be different from the packageName and uid in the
 * ApplicationInfo that we're creating the process with, in case the service is a
 * {@link android.content.Context#BIND_EXTERNAL_SERVICE} service. In that case, the packageName
 * and uid in the ApplicationInfo will be set to those of the caller, not of the defining package.
 *
 * {@code mDefiningUid} contains the uid of the application that defines the component we want to
 * start; this can be different from the packageName and uid in the ApplicationInfo that we're
 * creating the process with, in case the service is a
 * {@link android.content.Context#BIND_EXTERNAL_SERVICE} service. In that case, the packageName
 * and uid in the ApplicationInfo will be set to those of the caller, not of the defining package.
 *
 * {@code mIsTopApp} will be passed to {@link android.os.Process#start}. So Zygote will initialize
 * the process with high priority.
 *
 *  {@code mAction} the broadcast's intent action if the process is started for a broadcast
 *  receiver.
 */
public final class HostingRecord {
    static final int ZYGOTE_TYPE_REGULAR =
            PROCESS_START_TIME__HOSTING_ZYGOTE__HOSTING_ZYGOTE_REGULAR;
    static final int ZYGOTE_TYPE_WEBVIEW =
            PROCESS_START_TIME__HOSTING_ZYGOTE__HOSTING_ZYGOTE_WEBVIEW;
    static final int ZYGOTE_TYPE_APP =
            PROCESS_START_TIME__HOSTING_ZYGOTE__HOSTING_ZYGOTE_APP;

    @IntDef(prefix = { "ZYGOTE_TYPE_" }, value = {
            ZYGOTE_TYPE_REGULAR,
            ZYGOTE_TYPE_WEBVIEW,
            ZYGOTE_TYPE_APP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HostingZygoteType {}

    public static final String HOSTING_TYPE_ACTIVITY = "activity";
    public static final String HOSTING_TYPE_ADDED_APPLICATION = "added application";
    public static final String HOSTING_TYPE_BACKUP = "backup";
    public static final String HOSTING_TYPE_BROADCAST = "broadcast";
    public static final String HOSTING_TYPE_CONTENT_PROVIDER = "content provider";
    public static final String HOSTING_TYPE_LINK_FAIL = "link fail";
    public static final String HOSTING_TYPE_ON_HOLD = "on-hold";
    public static final String HOSTING_TYPE_NEXT_ACTIVITY = "next-activity";
    public static final String HOSTING_TYPE_NEXT_TOP_ACTIVITY = "next-top-activity";
    public static final String HOSTING_TYPE_RESTART = "restart";
    public static final String HOSTING_TYPE_STARTED_SERVICE = "started-service";
    public static final String HOSTING_TYPE_BOUND_SERVICE = "bound-service";
    public static final String HOSTING_TYPE_SYSTEM = "system";
    public static final String HOSTING_TYPE_TOP_ACTIVITY = "top-activity";
    public static final String HOSTING_TYPE_EMPTY = "";

    public static final String TRIGGER_TYPE_UNKNOWN = "unknown";
    public static final String TRIGGER_TYPE_ALARM = "alarm";
    public static final String TRIGGER_TYPE_PUSH_MESSAGE = "push_message";
    public static final String TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA = "push_message_over_quota";
    public static final String TRIGGER_TYPE_JOB = "job";

    @NonNull private final String mHostingType;
    private final String mHostingName;
    @HostingZygoteType private final int mHostingZygote;
    private final String mDefiningPackageName;
    private final int mDefiningUid;
    private final boolean mIsTopApp;
    private final String mDefiningProcessName;
    private final boolean mIsPcc;
    @Nullable private final String mAction;
    @NonNull private final String mTriggerType;
    // This field indicates if the process should be spawned from the Native App Zygote. This is
    // enabled only if the flag {@link android.os.Flags#nativeAppZygote} is enabled.
    private final boolean mIsNativeService;
    private final int mCallerUid;
    @Nullable private final String mCallerProcessName;

    /** The authority of the content provider that triggered the process start. */
    @Nullable private final String mHostingAuthority;

    /** Whether the content provider connection that triggered the process start is stable. */
    private final boolean mIsProviderStable;

    public HostingRecord(@NonNull String hostingType) {
        this(hostingType, null /* hostingName */, ZYGOTE_TYPE_REGULAR,
                null /* definingPackageName */,
                INVALID_UID /* mDefiningUid */, false /* isTopApp */,
                null /* definingProcessName */,
                null /* action */, TRIGGER_TYPE_UNKNOWN, false /* isPcc */,
                false /* isNativeService */, INVALID_UID /* callerUid */,
                null /* callerProcessName */,
                null /* hostingAuthority */, false /* isProviderStable */);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName) {
        this(hostingType, hostingName, ZYGOTE_TYPE_REGULAR);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            @Nullable String action, @Nullable String triggerType, boolean isPcc,
            int callerUid, @Nullable String callerProcessName) {
        this(hostingType, hostingName.toShortString(), ZYGOTE_TYPE_REGULAR,
                null /* definingPackageName */, INVALID_UID /* mDefiningUid */,
                false /* isTopApp */,
                null /* definingProcessName */, action, triggerType, isPcc,
                false /* isNativeService */, callerUid, callerProcessName,
                null /* hostingAuthority */, false /* isProviderStable */);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            String definingPackageName, int definingUid, String definingProcessName,
            String triggerType, boolean isPcc, int callerUid, @Nullable String callerProcessName) {
        this(hostingType, hostingName.toShortString(), ZYGOTE_TYPE_REGULAR,
                definingPackageName, definingUid, false /* isTopApp */,
                definingProcessName, null /* action */, triggerType, isPcc,
                false /* isNativeService */, callerUid, callerProcessName,
                null /* hostingAuthority */, false /* isProviderStable */);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            boolean isTopApp, boolean isPcc) {
        this(hostingType, hostingName.toShortString(), ZYGOTE_TYPE_REGULAR,
                null /* definingPackageName */, INVALID_UID /* mDefiningUid */,
                isTopApp /* isTopApp */,
                null /* definingProcessName */, null /* action */, TRIGGER_TYPE_UNKNOWN, isPcc,
                false /* isNativeService */, INVALID_UID /* callerUid */,
                null /* callerProcessName */,
                null /* hostingAuthority */, false /* isProviderStable */);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            boolean isTopApp, boolean isPcc, int callerUid, @Nullable String callerProcessName) {
        this(hostingType, hostingName.toShortString(), ZYGOTE_TYPE_REGULAR,
                null /* definingPackageName */, INVALID_UID /* mDefiningUid */,
                isTopApp /* isTopApp */,
                null /* definingProcessName */, null /* action */, TRIGGER_TYPE_UNKNOWN, isPcc,
                false /* isNativeService */, callerUid, callerProcessName,
                null /* hostingAuthority */, false /* isProviderStable */);
    }

    public HostingRecord(@NonNull String hostingType, String hostingName) {
        this(hostingType, hostingName, ZYGOTE_TYPE_REGULAR);
    }

    public HostingRecord(@NonNull String hostingType, String hostingName, boolean isPcc) {
        this(hostingType, hostingName, ZYGOTE_TYPE_REGULAR, isPcc);
    }

    private HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            @HostingZygoteType int hostingZygote) {
        this(hostingType, hostingName.toShortString(), hostingZygote);
    }

    private HostingRecord(@NonNull String hostingType, String hostingName,
            @HostingZygoteType int hostingZygote) {
        this(hostingType, hostingName, hostingZygote, null /* definingPackageName */,
                INVALID_UID /* mDefiningUid */, false /* isTopApp */,
                null /* definingProcessName */,
                null /* action */, TRIGGER_TYPE_UNKNOWN, false /* isPcc */,
                false /* isNativeService */, INVALID_UID /* callerUid */,
                null /* callerProcessName */,
                null /* hostingAuthority */, false /* isProviderStable */);
    }

    private HostingRecord(@NonNull String hostingType, String hostingName,
            @HostingZygoteType int hostingZygote,
            boolean isPcc) {
        this(hostingType, hostingName, hostingZygote, null /* definingPackageName */,
                INVALID_UID /* mDefiningUid */, false /* isTopApp */,
                null /* definingProcessName */,
                null /* action */, TRIGGER_TYPE_UNKNOWN, isPcc, false /* isNativeService */,
                INVALID_UID /* callerUid */, null /* callerProcessName */,
                null /* hostingAuthority */, false /* isProviderStable */);
    }

    private HostingRecord(@NonNull String hostingType, String hostingName,
            @HostingZygoteType int hostingZygote,
            String definingPackageName, int definingUid, boolean isTopApp,
            String definingProcessName, @Nullable String action, String triggerType,
            boolean isPcc, boolean isNativeService, int callerUid,
            @Nullable String callerProcessName, @Nullable String hostingAuthority,
            boolean isProviderStable) {
        mHostingType = hostingType;
        mHostingName = hostingName;
        mHostingZygote = hostingZygote;
        mDefiningPackageName = definingPackageName;
        mDefiningUid = definingUid;
        mIsTopApp = isTopApp;
        mDefiningProcessName = definingProcessName;
        mAction = action;
        mTriggerType = triggerType;
        mIsPcc = isPcc;
        mIsNativeService = isNativeService;
        mCallerUid = callerUid;
        mCallerProcessName = callerProcessName;
        mHostingAuthority = hostingAuthority;
        mIsProviderStable = isProviderStable;
    }

    public @HostingZygoteType int getHostingZygote() {
        return mHostingZygote;
    }

    public @NonNull String getType() {
        return mHostingType;
    }

    public String getName() {
        return mHostingName;
    }

    public boolean isTopApp() {
        return mIsTopApp;
    }

    public boolean isPcc() {
        return mIsPcc;
    }

    /**
     * Returns the UID of the package defining the component we want to start. Only valid
     * when {@link #usesAppZygote_()} returns true.
     *
     * @return the UID of the hosting application
     */
    public int getDefiningUid() {
        return mDefiningUid;
    }

    /**
     * Returns the packageName of the package defining the component we want to start. Only valid
     * when {@link #usesAppZygote_()} returns true.
     *
     * @return the packageName of the hosting application
     */
    public String getDefiningPackageName() {
        return mDefiningPackageName;
    }

    /**
     * Returns the processName of the component we want to start as specified in the defining app's
     * manifest.
     *
     * @return the processName of the process in the hosting application
     */
    public String getDefiningProcessName() {
        return mDefiningProcessName;
    }

    /**
     * Returns the broadcast's intent action if the process is started for a broadcast receiver.
     *
     * @return the intent action of the broadcast.
     */
    public @Nullable String getAction() {
        return mAction;
    }

    /** Returns the type of trigger that led to this process start. */
    public @NonNull String getTriggerType() {
        return mTriggerType;
    }

    /**
     * Returns the UID of the process that triggered this process start.
     *
     * @return the UID of the caller process
     */
    public int getCallerUid() {
        return mCallerUid;
    }

    /**
     * Returns the name of the process that triggered this process start.
     *
     * @return the name of the caller process
     */
    public String getCallerProcessName() {
        return mCallerProcessName;
    }

    /**
     * Returns the authority for the content provider that triggered the process start.
     *
     * @return the content provider authority.
     */
    public @Nullable String getHostingAuthority() {
        return mHostingAuthority;
    }

    /**
     * Returns whether the provider connection that triggered the process start is stable.
     *
     * @return true if stable.
     */
    public boolean isProviderStable() {
        return mIsProviderStable;
    }

    /**
     * Creates a HostingRecord for a process that must spawn from the webview zygote
     * @param hostingType type of the component to be hosted in this process
     * @param hostingName name of the component to be hosted in this process
     * @return The constructed HostingRecord
     */
    public static HostingRecord byWebviewZygote(@NonNull String hostingType,
            ComponentName hostingName, String definingPackageName, int definingUid,
            String definingProcessName, int callerUid, @Nullable String callerProcessName) {
        return new HostingRecord(hostingType, hostingName.toShortString(),
                ZYGOTE_TYPE_WEBVIEW, definingPackageName, definingUid, false /* isTopApp */,
                definingProcessName, null /* action */, TRIGGER_TYPE_UNKNOWN, false /* isPcc */,
                false /* isNativeService */, callerUid, callerProcessName,
                null /* authority */, false /* isProviderStable */);
    }

    /**
     * Creates a HostingRecord for a process that must spawn from the application zygote
     * @param hostingType type of the component to be hosted in this process
     * @param hostingName name of the component to be hosted in this process
     * @param definingPackageName name of the package defining the service
     * @param definingUid uid of the package defining the service
     * @param isNativeService if true, the process will be spawned from the Native App Zygote to
     *                        support services with {@code android:nativeService="true"}.
     * @return The constructed HostingRecord
     */
    public static HostingRecord byAppZygote_/* underscore was added to keep track of callers */(@NonNull String hostingType, ComponentName hostingName,
            String definingPackageName,
            int definingUid, String definingProcessName, boolean isNativeService,
            int callerUid, @Nullable String callerProcessName) {
        return new HostingRecord(hostingType, hostingName.toShortString(),
                ZYGOTE_TYPE_APP, definingPackageName, definingUid, false /* isTopApp */,
                definingProcessName, null /* action */, TRIGGER_TYPE_UNKNOWN, false /* isPcc */,
                isNativeService, callerUid, callerProcessName,
                null /* authority */, false /* isProviderStable */);
    }

    /**
     * Creates a HostingRecord for a process that must be started for a content provider.
     * @param hostingName name of the component to be hosted in this process
     * @param authority the authority of the content provider
     * @param isPcc if true, the process will be started in the PCC sandbox
     * @param isProviderStable true if the provider connection is stable
     * @return The constructed HostingRecord
     */
    public static HostingRecord forContentProvider(ComponentName hostingName, boolean isPcc,
            int callerUid, String callerProcessName, String authority, boolean isProviderStable) {
        return new HostingRecord(HostingRecord.HOSTING_TYPE_CONTENT_PROVIDER,
                hostingName.toShortString(), ZYGOTE_TYPE_REGULAR, null /* definingPackageName */,
                INVALID_UID /* definingUid */, false /* isTopApp */, null /* definingProcessName */,
                null /* action */, TRIGGER_TYPE_UNKNOWN, isPcc, false /* isNativeService */,
                callerUid, callerProcessName, authority, isProviderStable);
    }

    /**
     * @return whether the process should spawn from the application zygote
     */
    public boolean usesAppZygote_() { // underscore was added to keep track of callers
        return mHostingZygote == ZYGOTE_TYPE_APP;
    }

    private ApplicationInfo mAppInfoForPreloading;

    public void setAppInfoForPreloading(ApplicationInfo appInfo) {
        if (mAppInfoForPreloading != null) {
            Slog.wtf("HostingRecord", "overwriting mAppInfoForPreloading");
        }
        mAppInfoForPreloading = appInfo;
    }

    public ApplicationInfo getAppInfoForPreloading() {
        return mAppInfoForPreloading;
    }

    /**
     * @return if the process should be spawned from the Native App Zygote
     */
    public boolean usesNativeAppZygote() {
        return mIsNativeService;
    }

    /**
     * @return whether the process should spawn from the webview zygote
     */
    public boolean usesWebviewZygote_() { // underscore was added to keep track of callers
        return mHostingZygote == ZYGOTE_TYPE_WEBVIEW;
    }

    /**
     * Map the string hostingType to enum HostingType defined in ProcessStartTime proto.
     * @param hostingType
     * @return enum HostingType defined in ProcessStartTime proto
     */
    public static int getHostingTypeIdStatsd(@NonNull String hostingType) {
        switch(hostingType) {
            case HOSTING_TYPE_ACTIVITY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ACTIVITY;
            case HOSTING_TYPE_ADDED_APPLICATION:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ADDED_APPLICATION;
            case HOSTING_TYPE_BACKUP:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BACKUP;
            case HOSTING_TYPE_BROADCAST:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BROADCAST;
            case HOSTING_TYPE_CONTENT_PROVIDER:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_CONTENT_PROVIDER;
            case HOSTING_TYPE_LINK_FAIL:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_LINK_FAIL;
            case HOSTING_TYPE_ON_HOLD:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ON_HOLD;
            case HOSTING_TYPE_NEXT_ACTIVITY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_NEXT_ACTIVITY;
            case HOSTING_TYPE_NEXT_TOP_ACTIVITY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_NEXT_TOP_ACTIVITY;
            case HOSTING_TYPE_RESTART:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_RESTART;
            case HOSTING_TYPE_STARTED_SERVICE:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_STARTED_SERVICE;
            case HOSTING_TYPE_BOUND_SERVICE:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BOUND_SERVICE;
            case HOSTING_TYPE_SYSTEM:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_SYSTEM;
            case HOSTING_TYPE_TOP_ACTIVITY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_TOP_ACTIVITY;
            case HOSTING_TYPE_EMPTY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_EMPTY;
            default:
                return PROCESS_START_TIME__TYPE__UNKNOWN;
        }
    }

    /**
     * Map the string triggerType to enum TriggerType defined in ProcessStartTime proto.
     * @param triggerType
     * @return enum TriggerType defined in ProcessStartTime proto
     */
    public static int getTriggerTypeForStatsd(@NonNull String triggerType) {
        switch(triggerType) {
            case TRIGGER_TYPE_ALARM:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_ALARM;
            case TRIGGER_TYPE_PUSH_MESSAGE:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_PUSH_MESSAGE;
            case TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA;
            case TRIGGER_TYPE_JOB:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_JOB;
            default:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_UNKNOWN;
        }
    }

    private static boolean isTypeActivity(String hostingType) {
        return HOSTING_TYPE_ACTIVITY.equals(hostingType)
                || HOSTING_TYPE_NEXT_ACTIVITY.equals(hostingType)
                || HOSTING_TYPE_NEXT_TOP_ACTIVITY.equals(hostingType)
                || HOSTING_TYPE_TOP_ACTIVITY.equals(hostingType);
    }

    public boolean isTypeActivity() {
        return isTypeActivity(mHostingType);
    }
}
