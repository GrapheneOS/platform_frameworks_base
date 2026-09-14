package com.android.systemui.smartspace.service;

import static android.app.smartspace.SmartspaceTarget.FEATURE_DATE_ALARM_ZEN_MEDIA;
import static android.app.smartspace.SmartspaceTarget.UI_TEMPLATE_DEFAULT;

import android.app.AlarmManager;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceSessionId;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.BaseTemplateData.SubItemInfo;
import android.app.smartspace.uitemplatedata.CombinedCardsTemplateData;
import android.app.smartspace.uitemplatedata.Icon;
import android.app.smartspace.uitemplatedata.Text;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.service.smartspace.SmartspaceService;
import android.text.TextUtils;
import android.util.ArrayMap;

import androidx.annotation.NonNull;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.keyguard.smartspace.LockscreenSmartspaceGeneralView;
import com.android.systemui.media.NotificationMediaManager;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.Assert;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

/**
 * SmartspaceService implementation that currently only powers the lockscreen's general smartspace
 * view. Some of the underlying business logic is based on the legacy KeyguardSliceProvider.
 */
public class SystemUISmartspaceService extends SmartspaceService implements
        NextAlarmController.NextAlarmChangeCallback, ZenModeController.Callback,
        StatusBarStateController.StateListener, NotificationMediaManager.MediaListener {

    // Only display alarms that are triggering within this number of hours.
    private static final int ALARM_VISIBILITY_HOURS = 12;

    // State needed for the Service to remain informed of device state.
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final NextAlarmController mNextAlarmController;
    private final ZenModeController mZenModeController;
    private final StatusBarStateController mStatusBarStateController;
    private final NotificationMediaManager mNotificationMediaManager;
    private final UserTracker mUserTracker;
    private final SecureSettings mSecureSettings;

    // Stores the sessions that are associated with a surface.
    private Map<String, Set<SmartspaceSessionId>> mSurfaceSessions;
    // Stores the surface that is associated with a session.
    private Map<SmartspaceSessionId, String> mSessionSurface;

    // State needed for date display.
    private  String mDatePattern;
    private DateFormat mDateFormat;
    private String mFormattedDate;

    // State needed for alarm display.
    private AlarmManager.AlarmClockInfo mNextAlarm;
    private String mFormattedNextAlarmDate;

    // State needed for media display.
    private boolean mDozing;
    private boolean mMediaIsVisible;
    private CharSequence mMediaTitle;
    private CharSequence mMediaArtist;

    private final BroadcastReceiver mDateUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_DATE_CHANGED.equals(action)) {
                updateFormattedDate();
            } else if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                // mDateFormat is dependent on locale, so clear it.
                mDateFormat = null;
                updateFormattedDate();
            }
        }
    };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTimeChanged() {
                    updateFormattedDate();
                }

                @Override
                public void onTimeZoneChanged(TimeZone timeZone) {
                    updateFormattedDate();
                }
            };

    @Inject
    public SystemUISmartspaceService(
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            NextAlarmController nextAlarmController,
            ZenModeController zenModeController,
            StatusBarStateController statusBarStateController,
            NotificationMediaManager notificationMediaManager,
            UserTracker userTracker,
            SecureSettings secureSettings) {
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mNextAlarmController = nextAlarmController;
        mZenModeController = zenModeController;
        mStatusBarStateController = statusBarStateController;
        mNotificationMediaManager = notificationMediaManager;
        mUserTracker = userTracker;
        mSecureSettings = secureSettings;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mSurfaceSessions = new ArrayMap<>();
        mSessionSurface = new ArrayMap<>();

        // KeyguardSliceProvider uses the aod pattern regardless of aod setting, we follow suit.
        mDatePattern = getString(R.string.system_ui_aod_date_pattern);

        registerStateUpdates();
    }

    private void registerStateUpdates() {
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        // Fires immediately, so must be called after dependent state is initialized.
        mNextAlarmController.addCallback(this);
        mZenModeController.addCallback(this);
        mStatusBarStateController.addCallback(this);
        mNotificationMediaManager.addCallback(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        registerReceiver(mDateUpdateReceiver, filter, null /* permission*/, null /* scheduler */);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterStateUpdates();
    }

    private void unregisterStateUpdates() {
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mNextAlarmController.removeCallback(this);
        mZenModeController.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
        mNotificationMediaManager.removeCallback(this);

        unregisterReceiver(mDateUpdateReceiver);
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        mFormattedNextAlarmDate = getFormattedAlarmDate(nextAlarm);
        updateLockscreenClients();
    }

    @Override
    public void onZenChanged(int zen) {
        updateLockscreenClients();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        updateLockscreenClients();
    }

    @Override
    public void onPrimaryMetadataOrStateChanged(MediaMetadata metadata,
            @PlaybackState.State int state) {
        boolean nextVisible = NotificationMediaManager.isPlayingState(state);

        CharSequence title = null;
        if (metadata != null) {
            title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) {
                title = getString(R.string.music_controls_no_title);
            }
        }
        CharSequence artist = metadata == null ? null : metadata.getText(
                MediaMetadata.METADATA_KEY_ARTIST);

        if (nextVisible == mMediaIsVisible && TextUtils.equals(title, mMediaTitle)
                && TextUtils.equals(artist, mMediaArtist)) {
            return;
        }

        mMediaTitle = title;
        mMediaArtist = artist;
        mMediaIsVisible = nextVisible;
        updateLockscreenClients();
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        boolean shouldDisplayMediaPrevious = shouldDisplayMedia();
        mDozing = isDozing;
        if (shouldDisplayMedia() != shouldDisplayMediaPrevious) {
            updateLockscreenClients();
        }
    }

    private void updateFormattedDate() {
        if (mDateFormat == null) {
            final Locale l = Locale.getDefault();
            DateFormat format = DateFormat.getInstanceForSkeleton(mDatePattern, l);
            // See KeyguardSliceProvider for explanation of why CAPITALIZATION_FOR_STANDALONE is not
            // used.
            format.setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE);
            mDateFormat = format;
        }
        Date currentTime = new Date();
        currentTime.setTime(System.currentTimeMillis());
        mFormattedDate = mDateFormat.format(currentTime);

        updateLockscreenClients();
    }

    private void updateLockscreenClients() {
        Assert.isMainThread();
        Set<SmartspaceSessionId> sessions = mSurfaceSessions.get(
                BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD);

        if (sessions != null) {
            List<SmartspaceTarget> targets = createLockscreenTargets();
            for (SmartspaceSessionId sessionId : sessions) {
                updateSmartspaceTargets(sessionId, targets);
            }
        }
    }

    private List<SmartspaceTarget> createLockscreenTargets() {
        return List.of(createLockscreenGeneralTarget());
    }

    private SmartspaceTarget createLockscreenGeneralTarget() {
        List<BaseTemplateData> templates = new ArrayList<>();

        BaseTemplateData dateTemplate = createDateTemplate();
        if (dateTemplate != null) {
            templates.add(dateTemplate);
        }
        BaseTemplateData alarmTemplate = createAlarmTemplate();
        if (alarmTemplate != null) {
            templates.add(alarmTemplate);
        }
        BaseTemplateData zenModeTemplate = createZenModeTemplate();
        if (zenModeTemplate != null) {
            templates.add(zenModeTemplate);
        }
        BaseTemplateData mediaTemplate = createMediaTemplate();
        if (mediaTemplate != null) {
            templates.add(mediaTemplate);
        }

        // Treat the lockscreen general surface as being the combination of multiple logical
        // smartspace cards. This might be slightly bending the definition of card, but the public
        // smartspace code and surrounding comments imply Google are also bending definitions within
        // their proprietary rendering code in order to make things fit. Another reasonable approach
        // would be to create a new DynamicRowsTemplateData template type and add our four rows to
        // that. In any case, squashing all the data into a BaseTemplateType with its oddly-named
        // member variables would be confusing.
        CombinedCardsTemplateData.Builder templateBuilder = new CombinedCardsTemplateData.Builder(
                templates);

        SmartspaceTarget.Builder targetBuilder = createLockscreenGeneralTargetBuilder();
        targetBuilder.setTemplateData(templateBuilder.build());
        // This isn't used anywhere currently.
        targetBuilder.setFeatureType(FEATURE_DATE_ALARM_ZEN_MEDIA);

        return targetBuilder.build();
    }

    private SmartspaceTarget.Builder createLockscreenGeneralTargetBuilder() {
        String identifier = UUID.randomUUID().toString();
        var componentName = new ComponentName(this, LockscreenSmartspaceGeneralView.class);
        UserHandle user = mUserTracker.getUserHandle();
        return new SmartspaceTarget.Builder(identifier, componentName, user);
    }

    private BaseTemplateData createDateTemplate() {
        if (mFormattedDate == null) {
            return null;
        }

        return new BaseTemplateData.Builder(UI_TEMPLATE_DEFAULT)
                .setPrimaryItem(new SubItemInfo.Builder()
                        .setText(new Text.Builder(mFormattedDate).build())
                        .build())
                .build();
    }

    private BaseTemplateData createAlarmTemplate() {
        if (!shouldDisplayAlarm(mNextAlarm)) {
            return null;
        }

        var alarmIcon = android.graphics.drawable.Icon.createWithResource(this,
                        R.drawable.ic_access_alarms_big);

        return new BaseTemplateData.Builder(UI_TEMPLATE_DEFAULT)
                .setPrimaryItem(new SubItemInfo.Builder()
                        .setText(new Text.Builder(mFormattedNextAlarmDate).build())
                        .setIcon(new Icon.Builder(alarmIcon).build())
                        .build())
                .build();
    }

    private static boolean shouldDisplayAlarm(AlarmManager.AlarmClockInfo alarm) {
        if (alarm == null) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long delta = alarm.getTriggerTime() - currentTime;
        return delta > 0 && delta <= TimeUnit.HOURS.toMillis(ALARM_VISIBILITY_HOURS);
    }

    private String getFormattedAlarmDate(AlarmManager.AlarmClockInfo alarm) {
        if (alarm == null) {
            return null;
        }

        String pattern = android.text.format.DateFormat.is24HourFormat(this,
                mUserTracker.getUserId()) ? "HH:mm" : "h:mm";
        return android.text.format.DateFormat.format(pattern, alarm.getTriggerTime())
                .toString();
    }

    private BaseTemplateData createZenModeTemplate() {
        if (!isZenModeEnabled()) {
            return null;
        }

        var icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.stat_sys_dnd);

        return new BaseTemplateData.Builder(UI_TEMPLATE_DEFAULT)
                .setPrimaryItem(new SubItemInfo.Builder()
                        .setIcon(new Icon.Builder(icon).build())
                        .build())
                .build();
    }

    private boolean isZenModeEnabled() {
        return mZenModeController.getZen() != Settings.Global.ZEN_MODE_OFF;
    }

    private BaseTemplateData createMediaTemplate() {
        if (!shouldDisplayMedia()) {
            return null;
        }

        var templateBuilder = new BaseTemplateData.Builder(UI_TEMPLATE_DEFAULT);

        templateBuilder.setPrimaryItem(new SubItemInfo.Builder()
                .setText(new Text.Builder(mMediaTitle).build())
                .build());

        if (!TextUtils.isEmpty(mMediaArtist)) {
            android.graphics.drawable.Icon mediaIcon = mNotificationMediaManager == null ?
                    null : mNotificationMediaManager.getMediaIcon();
            if (mediaIcon != null) {
                templateBuilder.setSubtitleItem(new SubItemInfo.Builder()
                        .setText(new Text.Builder(mMediaArtist).build())
                        .setIcon(new Icon.Builder(mediaIcon).build())
                        .build());
            }
        }
        return templateBuilder.build();
    }

    private boolean shouldDisplayMedia() {
        return !TextUtils.isEmpty(mMediaTitle) && mMediaIsVisible && mDozing
                && mSecureSettings.getBoolForUser(
                        Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, true, mUserTracker.getUserId());
    }

    @Override
    public void onCreateSmartspaceSession(@NonNull SmartspaceConfig config,
            @NonNull SmartspaceSessionId sessionId) {
        String surface = config.getUiSurface();

        Set<SmartspaceSessionId> sessions = mSurfaceSessions.get(surface);
        if (sessions == null) {
            sessions = new HashSet<>();
            mSurfaceSessions.put(surface, sessions);
        }
        sessions.add(sessionId);

        mSessionSurface.put(sessionId, surface);
    }

    // We don't need bidirectional communication for our simple smartspace implementation.
    @Override
    public void notifySmartspaceEvent(@NonNull SmartspaceSessionId sessionId,
            @NonNull SmartspaceTargetEvent event) {}

    @Override
    public void onRequestSmartspaceUpdate(@NonNull SmartspaceSessionId sessionId) {
        String surface = mSessionSurface.get(sessionId);
        if (surface == null) {
            return;
        }
        if (surface.equals(BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
            List<SmartspaceTarget> targets = createLockscreenTargets();
            updateSmartspaceTargets(sessionId, targets);
        }
    }

    @Override
    public void onDestroySmartspaceSession(@NonNull SmartspaceSessionId sessionId) {
        String surface = mSessionSurface.get(sessionId);
        mSessionSurface.remove(sessionId);
        Set<SmartspaceSessionId> sessionsForSurface = mSurfaceSessions.get(surface);
        if (sessionsForSurface != null) {
            sessionsForSurface.remove(sessionId);
        }
    }

    @Override
    public void onDestroy(@NonNull SmartspaceSessionId sessionId) {
        throw new UnsupportedOperationException("This method should not be called");
    }
}
