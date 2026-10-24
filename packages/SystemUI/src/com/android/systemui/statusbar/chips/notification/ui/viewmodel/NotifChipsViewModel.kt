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

package com.android.systemui.statusbar.chips.notification.ui.viewmodel

import android.app.Notification
import android.app.Notification.ResolvedBasicCompactContent
import android.content.ComponentName
import android.content.Context
import androidx.annotation.ColorRes
import com.android.internal.jank.Cuj
import com.android.internal.logging.InstanceId
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.ComposableControllerFactory
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.StatusBarChipsReturnAnimations
import com.android.systemui.statusbar.chips.notification.domain.interactor.StatusBarNotificationChipsInteractor
import com.android.systemui.statusbar.chips.notification.domain.model.NotificationChipModel
import com.android.systemui.statusbar.chips.ui.model.Chronometer
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.EventTime
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ChipTransitionHelper.Companion.TransitionAwareChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ChipTransitionHelper.Companion.TransitionState
import com.android.systemui.statusbar.chips.ui.viewmodel.ChipTransitionHelper.Companion.buildTransitionManager
import com.android.systemui.statusbar.chips.ui.viewmodel.ChipTransitionHelper.Companion.shouldChipBeHidden
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createNotificationToggleClickBehavior
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.isShowingHeadsUpFromChipTap
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.domain.model.TopPinnedState
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.Metric
import com.android.systemui.statusbar.notification.shared.NotificationChipFromCompactContent
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A view model for status bar chips for promoted ongoing notifications. */
@SysUISingleton
class NotifChipsViewModel
@Inject
constructor(
    @Main private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    private val notifChipsInteractor: StatusBarNotificationChipsInteractor,
    headsUpNotificationInteractor: HeadsUpNotificationInteractor,
    private val systemClock: SystemClock,
    private val activityStarter: ActivityStarter,
    @StatusBarChipsLog private val logBuffer: LogBuffer,
) {
    private val logger = Logger(logBuffer, "NotifChipVM".pad())

    // Since we're combining the chip state and the transition state flows, getting the old value by
    // using [pairwise()] would confuse things. This is because if the calculation is triggered by
    // a change in transition state, the chip state will still show the previous and current values,
    // making it difficult to figure out what actually changed. Instead we cache the old value here,
    // so that at each update we can keep track of what actually changed.
    private var latestChips: Map<String, TransitionAwareChipModel> = mapOf()
    private var latestTransitionStates: Map<String, TransitionState> = mapOf()

    /**
     * Used internally to determine when a launch or return animation is in progress, as these
     * require special handling.
     */
    private val transitionStates: MutableStateFlow<Map<String, TransitionState>> =
        MutableStateFlow(mapOf())

    /**
     * The controller factory that the call chip uses to register and unregister its transition
     * animations.
     */
    private val transitionControllerFactories: MutableMap<String, ComposableControllerFactory> =
        mutableMapOf()

    /**
     * A flow that prunes the incoming [NotificationChipModel] instances to just the information
     * each status bar chip needs.
     */
    private val notificationChipsWithPrunedContent: Flow<List<PrunedNotificationChipModel>> =
        notifChipsInteractor.allNotificationChips
            .pairwise(initialValue = emptyList())
            .map { (oldChips, currentChips) ->
                if (StatusBarChipsReturnAnimations.isEnabled) {
                    // Find chips that were present before and are gone, and unregister their
                    // transitions.
                    oldChips
                        .filter { !currentChips.map { it.key }.toSet().contains(it.key) }
                        .forEach {
                            activityStarter.unregisterTransition(
                                buildCookie(it.packageName, it.appName)
                            )
                            transitionControllerFactories.remove(it.key)
                        }
                }

                currentChips.filterByPackage().map { it.toPrunedModel() }
            }
            .distinctUntilChanged()

    /**
     * Filters all the chips down to just the most important chip per package so we don't show
     * multiple chips for the same app.
     */
    private fun List<NotificationChipModel>.filterByPackage(): List<NotificationChipModel> {
        return this.groupBy { it.packageName }.map { (_, chips) -> chips[0] }
    }

    private fun NotificationChipModel.toPrunedModel(): PrunedNotificationChipModel {
        // Chips can show while the device is locked (for example over an app shown on top of the
        // lock screen), so only use the private version if the content isn't sensitive right now.
        val content = promotedContent.privateVersion

        val chipTextVariants: List<String>?
        val chipTime: PromotedNotificationContentModel.When?
        val chipChronometer: Chronometer?
        val chipChronometerFormat: OngoingActivityChipModel.Content.Timer.Format?
        val chipSemanticStyle: Int?

        if (isSensitive) {
            // Show only the short critical text of the app's public version, or else just the
            // icon: never private text or time. The public version is the private one itself if
            // the notification didn't need redaction when its content was last extracted.
            val publicVersion =
                promotedContent.publicVersion.takeIf { it !== promotedContent.privateVersion }
            chipTextVariants = publicVersion?.shortCriticalText?.let { listOf(it) }
            chipTime = null
            chipChronometer = null
            chipChronometerFormat = null
            chipSemanticStyle = null
        } else if (NotificationChipFromCompactContent.isEnabled) {
            if (content.compactContent is ResolvedBasicCompactContent) {
                val contentText = content.compactContent.text
                chipTextVariants =
                    contentText
                        ?.takeUnless { it is Notification.Metric.TimeDifference }
                        ?.toValueString(context)
                        ?.textVariants
                chipChronometer =
                    (contentText as? Notification.Metric.TimeDifference)?.toChronometer()
                chipChronometerFormat =
                    (contentText as? Notification.Metric.TimeDifference)?.toChronometerFormat()
                chipSemanticStyle =
                    content.compactContent.semanticStyle.takeIf {
                        it != Notification.SEMANTIC_STYLE_UNSPECIFIED
                    }
                chipTime = null
            } else {
                throw IllegalStateException("Unknown compactContent: ${content.compactContent}")
            }
        } else {
            val firstMetricValue = content.metrics?.firstOrNull()
            val textFromMetric =
                (firstMetricValue as? Metric.Text)?.textVariants?.first()?.toString()
            val timeFromMetric = (firstMetricValue as? Metric.TimeDifference)?.toWhen()
            val timeFromWhen =
                when (val rawTime = content.time) {
                    null -> null
                    is PromotedNotificationContentModel.When.Time -> {
                        if (
                            rawTime.currentTimeMillis >=
                                systemClock.currentTimeMillis() + FUTURE_TIME_THRESHOLD_MILLIS
                        ) {
                            rawTime
                        } else {
                            // Don't show a `when` time that's close to now or in the past because
                            // it's likely that the app didn't intentionally set the `when` time to
                            // be shown in the status bar chip.
                            // TODO(b/393369213): If a notification sets a `when` time in the future
                            // and then that time comes and goes, the chip *will* start showing
                            // times in the past. Not going to fix this right now because the
                            // Compose implementation automatically handles this for us and we're
                            // hoping to launch the notification chips at the same time as the
                            // Compose chips.
                            null
                        }
                    }
                    is PromotedNotificationContentModel.When.Chronometer -> rawTime
                }

            chipTextVariants = (content.shortCriticalText ?: textFromMetric)?.let { x -> listOf(x) }
            chipTime = timeFromMetric ?: timeFromWhen
            chipChronometer = null
            chipChronometerFormat = null
            chipSemanticStyle = null
        }

        return PrunedNotificationChipModel(
            key = key,
            packageName = packageName,
            appName = appName,
            componentName = componentName,
            statusBarChipIconView = statusBarChipIconView,
            textVariants = chipTextVariants,
            time = chipTime,
            chronometer = chipChronometer,
            chronometerFormat =
                chipChronometerFormat ?: OngoingActivityChipModel.Content.Timer.Format.CHRONOMETER,
            semanticStyle = chipSemanticStyle,
            isAppVisible = isAppVisible,
            instanceId = instanceId,
            isScreenShareNotification = isScreenShareNotification,
        )
    }

    private fun Notification.Metric.TimeDifference.toChronometer(): Chronometer =
        if (this.pausedDuration != null) Chronometer.Paused(this.pausedDuration!!)
        else
            Chronometer.Running(
                if (this.zeroElapsedRealtime != null)
                    EventTime.ElapsedRealtime(this.zeroElapsedRealtime!!)
                else if (this.zeroTime != null) EventTime.ClockTime(this.zeroTime!!)
                else throw IllegalArgumentException("Invalid TimeDifference: $this"),
                isCountdown = this.isTimer,
            )

    private fun Notification.Metric.TimeDifference.toChronometerFormat():
        OngoingActivityChipModel.Content.Timer.Format =
        if (this.format == Notification.Metric.TimeDifference.FORMAT_ADAPTIVE)
            OngoingActivityChipModel.Content.Timer.Format.ADAPTIVE
        else OngoingActivityChipModel.Content.Timer.Format.CHRONOMETER

    // TODO: b/462677827 - Delete when inlining NOTIFICATION_CHIP_FROM_COMPACT_CONTENT
    private fun Metric.TimeDifference.toWhen(): PromotedNotificationContentModel.When? =
        when (this) {
            is Metric.TimeDifference.Paused ->
                // paused timers will need to be supported in the UI layer,
                // but it's also fine for now to just decide not to show anything
                null
            is Metric.TimeDifference.Instant ->
                if (useAdaptiveFormat) {
                    PromotedNotificationContentModel.When.Time(
                        currentTimeMillis = zeroTime.toEpochMilli()
                    )
                } else {
                    PromotedNotificationContentModel.When.Chronometer(
                        elapsedRealtimeMillis =
                            systemClock.toElapsedRealtime(fromSystemTime = zeroTime.toEpochMilli()),
                        isCountDown = isTimer,
                    )
                }
            is Metric.TimeDifference.ElapsedRealtime ->
                if (useAdaptiveFormat) {
                    PromotedNotificationContentModel.When.Time(
                        currentTimeMillis =
                            systemClock.toSystemTime(fromElapsedRealtime = zeroElapsedRealtime)
                    )
                } else {
                    PromotedNotificationContentModel.When.Chronometer(
                        elapsedRealtimeMillis = zeroElapsedRealtime,
                        isCountDown = isTimer,
                    )
                }
        }

    /** Converts a system time (epoch millis) to elapsed realtime. */
    // TODO: b/462677827 - Delete when inlining NOTIFICATION_CHIP_FROM_COMPACT_CONTENT
    private fun SystemClock.toElapsedRealtime(fromSystemTime: Long): Long =
        fromSystemTime + (elapsedRealtime() - currentTimeMillis())

    /** Converts an elapsed realtime to a system time (epoch millis). */
    // TODO: b/462677827 - Delete when inlining NOTIFICATION_CHIP_FROM_COMPACT_CONTENT
    private fun SystemClock.toSystemTime(fromElapsedRealtime: Long): Long =
        fromElapsedRealtime + (currentTimeMillis() - elapsedRealtime())

    private val chipsWithReturnAnimations: Flow<List<OngoingActivityChipModel.Active>> =
        if (StatusBarChipsReturnAnimations.isEnabled) {
            combine(
                    notificationChipsWithPrunedContent,
                    transitionStates,
                    headsUpNotificationInteractor.statusBarHeadsUpState,
                ) { notifications, transitionStates, headsUpState ->
                    val oldChips = latestChips
                    val newChips = mutableMapOf<String, TransitionAwareChipModel>()
                    val oldTransitionStates = latestTransitionStates
                    latestTransitionStates = transitionStates

                    val chips =
                        notifications.map {
                            val transitionAwareChip =
                                TransitionAwareChipModel.Active(
                                    cookie = buildCookie(it.packageName, it.appName),
                                    component = it.componentName,
                                    isAppVisible = it.isAppVisible,
                                    returnCujType = Cuj.CUJ_STATUS_BAR_APP_RETURN_TO_ONGOING_CHIP,
                                )
                            newChips[it.key] = transitionAwareChip

                            val isHidden =
                                shouldChipBeHidden(
                                    oldState = oldChips[it.key],
                                    newState = transitionAwareChip,
                                    oldTransitionState =
                                        oldTransitionStates[it.key] ?: TransitionState.NoTransition,
                                    newTransitionState =
                                        transitionStates[it.key] ?: TransitionState.NoTransition,
                                )
                            it.toActivityChipModel(headsUpState, transitionAwareChip, isHidden)
                        }

                    latestChips = newChips
                    chips
                }
                .distinctUntilChanged()
        } else {
            MutableStateFlow(emptyList<OngoingActivityChipModel.Active>()).asStateFlow()
        }

    private val chipsWithoutReturnAnimations: Flow<List<OngoingActivityChipModel.Active>> =
        if (!StatusBarChipsReturnAnimations.isEnabled) {
            combine(
                    notificationChipsWithPrunedContent,
                    headsUpNotificationInteractor.statusBarHeadsUpState,
                ) { notifications, headsUpState ->
                    notifications.map { it.toActivityChipModel(headsUpState) }
                }
                .distinctUntilChanged()
        } else {
            MutableStateFlow(emptyList<OngoingActivityChipModel.Active>()).asStateFlow()
        }

    /**
     * A flow modeling the current notification chips. Emits an empty list if there are no
     * notifications that are eligible to show a status bar chip.
     */
    val chips: Flow<List<OngoingActivityChipModel.Active>> =
        if (StatusBarChipsReturnAnimations.isEnabled) {
            chipsWithReturnAnimations
        } else {
            chipsWithoutReturnAnimations
        }

    /** Converts the notification to the [OngoingActivityChipModel] object. */
    private fun PrunedNotificationChipModel.toActivityChipModel(
        headsUpState: TopPinnedState,
        transitionAwareChip: TransitionAwareChipModel? = null,
        isHidden: Boolean? = null,
    ): OngoingActivityChipModel.Active {
        val contentDescription = getContentDescription(this.appName)
        // Note: ResolvedBasicCompactContent has an option for SOURCE_APP_ICON, but it's not used by
        // AOSP and not choosable by apps. So for now we only behave as if it's SOURCE_SMALL_ICON.
        val icon =
            OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon(
                this.key,
                contentDescription,
            )

        val colors =
            if (NotificationChipFromCompactContent.isEnabled && this.semanticStyle != null) {
                ColorsModel.SystemThemedWithOverride(textRes = this.semanticStyle.toColorResource())
            } else {
                ColorsModel.SystemThemed
            }

        // If the app that posted this notification is visible, we want to hide the chip
        // because information between the status bar chip and the app itself could be
        // out-of-sync (like a timer that's slightly off)
        val isHidden = isHidden ?: this.isAppVisible

        val isShowingHeadsUpFromChipTap = headsUpState.isShowingHeadsUpFromChipTap(this.key)
        val clickBehavior =
            createNotificationToggleClickBehavior(
                applicationScope = applicationScope,
                notifChipsInteractor = notifChipsInteractor,
                logger = logger,
                notificationKey = this.key,
                isShowingHeadsUpFromChipTap = isShowingHeadsUpFromChipTap,
            )

        val content: OngoingActivityChipModel.Content =
            when {
                isShowingHeadsUpFromChipTap -> {
                    // If the user tapped this chip to show the HUN, we want to just show the icon
                    // because the HUN will show the rest of the information.
                    // Similar behavior to [CallChipViewModel].
                    OngoingActivityChipModel.Content.IconOnly
                }
                textVariants != null && textVariants.size == 1 ->
                    OngoingActivityChipModel.Content.Text(text = textVariants.first())
                textVariants != null ->
                    OngoingActivityChipModel.Content.TextVariants(textVariants = textVariants)
                NotificationChipFromCompactContent.isEnabled && chronometer != null ->
                    OngoingActivityChipModel.Content.Timer(
                        value = chronometer,
                        format = chronometerFormat,
                        timeSource = systemClock,
                    )
                NotificationChipFromCompactContent.isEnabled ->
                    OngoingActivityChipModel.Content.IconOnly
                else -> {
                    when (time) {
                        null -> OngoingActivityChipModel.Content.IconOnly
                        is PromotedNotificationContentModel.When.Time -> {
                            OngoingActivityChipModel.Content.ShortTimeDelta(
                                time = time.currentTimeMillis,
                                timeSource = systemClock,
                            )
                        }
                        is PromotedNotificationContentModel.When.Chronometer -> {
                            OngoingActivityChipModel.Content.Timer(
                                value =
                                    Chronometer.Running(
                                        EventTime.ElapsedRealtime(time.elapsedRealtimeMillis),
                                        isCountdown = time.isCountDown,
                                    ),
                                timeSource = systemClock,
                            )
                        }
                    }
                }
            }

        val transitionManager =
            if (StatusBarChipsReturnAnimations.isEnabled) {
                buildTransitionManager(
                        chip = transitionAwareChip,
                        transitionState =
                            transitionStates.value[key] ?: TransitionState.NoTransition,
                        updateTransitionState = { updatedState ->
                            transitionStates.value =
                                updateTransitionState(transitionStates.value, key, updatedState)
                        },
                        scope = applicationScope,
                        factory = transitionControllerFactories[key],
                        activityStarter = activityStarter,
                    )
                    ?.also {
                        it.controllerFactory?.let { factory ->
                            transitionControllerFactories[key] = factory
                        }
                    }
            } else {
                null
            }

        return OngoingActivityChipModel.Active(
            key = key,
            notificationKey = key,
            managingPackageName = packageName,
            isImportantForPrivacy = false,
            icon = icon,
            content = content,
            colors = colors,
            clickBehavior = clickBehavior,
            isHidden = isHidden,
            transitionManager = transitionManager,
            instanceId = instanceId,
            isScreenShareNotification = isScreenShareNotification,
        )
    }

    @ColorRes
    private fun @receiver:Notification.SemanticStyle Int.toColorResource(): Int? =
        Notification.semanticStyleToColorRes(this)

    private fun getContentDescription(appName: String): ContentDescription {
        val ongoingDescription =
            context.getString(R.string.ongoing_notification_extra_content_description)
        return ContentDescription.Loaded(
            context.getString(
                R.string.accessibility_desc_notification_icon,
                appName,
                ongoingDescription,
            )
        )
    }

    /**
     * Model that prunes data from [NotificationChipModel] to just the information the status bar
     * chip needs.
     *
     * Used so that we don't re-create the chip [OngoingActivityChipModel] classes with new click
     * listeners unless absolutely necessary, which helps the chips re-compose less frequently. See
     * b/393456147.
     */
    private data class PrunedNotificationChipModel(
        val key: String,
        val packageName: String,
        val appName: String,
        val componentName: ComponentName?,
        val statusBarChipIconView: StatusBarIconView?,
        /**
         * Options for the text to show in the chip, or null if text shouldn't be shown. Text takes
         * precedence over [time]/[chronometer].
         */
        val textVariants: List<String>?,
        /** The chronometer to show in the chip, or null if it shouldn't be shown. */
        val chronometer: Chronometer?,
        val chronometerFormat: OngoingActivityChipModel.Content.Timer.Format =
            OngoingActivityChipModel.Content.Timer.Format.CHRONOMETER,
        @Notification.SemanticStyle val semanticStyle: Int?,
        /** The time to show in the chip, or null if the time shouldn't be shown. */
        // TODO: b/462677827 - Delete when inlining NOTIFICATION_CHIP_FROM_COMPACT_CONTENT
        val time: PromotedNotificationContentModel.When?,
        val isAppVisible: Boolean,
        val instanceId: InstanceId?,
        val isScreenShareNotification: Boolean,
    )

    companion object {
        /**
         * Notifications must have a `when` time of at least 1 minute in the future in order for the
         * status bar chip to show the time.
         */
        private const val FUTURE_TIME_THRESHOLD_MILLIS = 60 * 1000

        /**
         * Builds a deterministic transition cookie for a notification chip from its package and app
         * name.
         */
        private fun buildCookie(
            packageName: String,
            appName: String,
        ): ActivityTransitionAnimator.TransitionCookie =
            ActivityTransitionAnimator.TransitionCookie(
                "${NotificationChipModel::class.java}:$packageName:$appName"
            )

        /**
         * Returns a copy of transition states with the one matching [key] updated to [newState].
         */
        private fun updateTransitionState(
            originalStates: Map<String, TransitionState>,
            key: String,
            newState: TransitionState,
        ): Map<String, TransitionState> {
            val newStates = originalStates.toMutableMap()
            newStates[key] = newState
            return newStates
        }
    }
}
