/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.user.data.repository

import android.annotation.SuppressLint
import android.annotation.UserIdInt
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.app.tracing.coroutines.runBlockingTraced as runBlocking
import com.android.systemui.Flags
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import java.text.Collator
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@SysUISingleton
class UserRepositoryImpl
@Inject
constructor(
    @Application private val appContext: Context,
    @Main private val resources: Resources,
    private val manager: UserManager,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val globalSettings: GlobalSettings,
    private val tracker: UserTracker,
    private val devicePolicyManager: DevicePolicyManager,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val activityManager: ActivityManager,
    private val userIconProvider: UserIconProvider,
) : UserRepository {

    private val _userSwitcherSettings: StateFlow<UserSwitcherSettingsModel> =
        globalSettings
            .observerFlow(
                names =
                    arrayOf(
                        SETTING_SIMPLE_USER_SWITCHER,
                        Settings.Global.ADD_USERS_WHEN_LOCKED,
                        Settings.Global.USER_SWITCHER_ENABLED,
                    )
            )
            .onStart { emit(Unit) } // Forces an initial update.
            .map { getSettings() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    if (Flags.doNotUseRunBlocking()) {
                        UserSwitcherSettingsModel()
                    } else {
                        runBlocking { getSettings() }
                    },
            )
    override val userSwitcherSettings: Flow<UserSwitcherSettingsModel> = _userSwitcherSettings

    private val _userInfos = MutableStateFlow<List<UserInfo>?>(null)
    override val userInfos: Flow<List<UserInfo>> = _userInfos.filterNotNull()

    init {
        // Sort the users again when Settings changes the custom order.
        globalSettings
            .observerFlow(Settings.Global.USER_SWITCHER_ORDER)
            .onEach { refreshUsers() }
            .launchIn(applicationScope)
    }

    override var mainUserId: Int = UserHandle.USER_NULL
        private set

    override var lastSelectedNonGuestUserId: Int = UserHandle.USER_NULL
        private set

    override val isGuestUserAutoCreated: Boolean =
        appContext.resources.getBoolean(com.android.internal.R.bool.config_guestUserAutoCreated)

    private var _isGuestUserResetting: Boolean = false
    override var isGuestUserResetting: Boolean = _isGuestUserResetting

    override val isGuestUserCreationScheduled = AtomicBoolean()

    override val isStatusBarUserChipEnabled: Boolean =
        appContext.resources.getBoolean(R.bool.flag_user_switcher_chip)

    override var secondaryUserId: Int = UserHandle.USER_NULL

    override var isRefreshUsersPaused: Boolean = false

    override val selectedUser: StateFlow<SelectedUserModel> = run {
        // Some callbacks don't modify the selection status, so maintain the current value.
        var currentSelectionStatus = SelectionStatus.SELECTION_COMPLETE
        conflatedCallbackFlow {
                fun send(selectionStatus: SelectionStatus) {
                    currentSelectionStatus = selectionStatus
                    trySendWithFailureLogging(
                        SelectedUserModel(tracker.userInfo, selectionStatus),
                        TAG,
                    )
                }

                val callback =
                    object : UserTracker.Callback {
                        override fun onBeforeUserSwitching(newUser: Int) {
                            send(SelectionStatus.SELECTION_IN_PROGRESS)
                        }

                        override fun onUserChanged(newUser: Int, userContext: Context) {
                            send(SelectionStatus.SELECTION_COMPLETE)
                        }

                        override fun onProfilesChanged(profiles: List<UserInfo>) {
                            send(currentSelectionStatus)
                        }
                    }

                tracker.addCallback(callback, mainDispatcher.asExecutor())
                send(currentSelectionStatus)

                awaitClose { tracker.removeCallback(callback) }
            }
            .onEach {
                if (!it.userInfo.isGuest) {
                    lastSelectedNonGuestUserId = it.userInfo.id
                }
            }
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                initialValue = SelectedUserModel(tracker.userInfo, currentSelectionStatus),
            )
    }

    override val selectedUserInfo: Flow<UserInfo> = selectedUser.map { it.userInfo }

    /** Cold flow that emits upon ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED broadcast. */
    private val devicePolicyManagerStateChangeEvents: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(
                IntentFilter(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED)
            )
            .onStart { emit(Unit) }

    @SuppressLint("MissingPermission")
    override val isPolicyManagerLogoutEnabled: StateFlow<Boolean> =
        selectedUser
            .flatMapLatestConflated { selectedUser ->
                if (selectedUser.selectionStatus == SelectionStatus.SELECTION_COMPLETE) {
                    devicePolicyManagerStateChangeEvents
                        .map { _ ->
                            devicePolicyManager.isLogoutEnabled() &&
                                devicePolicyManager.logoutUser != null
                        }
                        .flowOn(backgroundDispatcher)
                } else {
                    flowOf(false)
                }
            }
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    override fun isUserUnlocked(userHandle: UserHandle?): Flow<Boolean> =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(Intent.ACTION_USER_UNLOCKED))
            .map { getUnlockedState(userHandle) }
            .onStart { emit(getUnlockedState(userHandle)) }

    private suspend fun getUnlockedState(userHandle: UserHandle?): Boolean {
        return withContext(backgroundDispatcher) {
            userHandle?.let { user -> manager.isUserUnlocked(user) } ?: false
        }
    }

    @SuppressLint("MissingPermission")
    override val isUserManagerLogoutEnabled: StateFlow<Boolean> =
        selectedUser
            .map { selectedUser ->
                when {
                    selectedUser.selectionStatus != SelectionStatus.SELECTION_COMPLETE -> false

                    else ->
                        withContext(backgroundDispatcher) {
                            manager.getUserLogoutability(selectedUser.userInfo.id) ==
                                UserManager.LOGOUTABILITY_STATUS_OK
                        }
                }
            }
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    override val isCurrentUserHeadlessSystemUser: StateFlow<Boolean> =
        selectedUser
            .map { selectedUser -> selectedUser.isHeadlessSystemUser() }
            .distinctUntilChanged()
            .stateIn(
                applicationScope,
                SharingStarted.Lazily,
                selectedUser.value.isHeadlessSystemUser(),
            )

    @SuppressLint("MissingPermission")
    override suspend fun logOutWithPolicyManager() {
        if (isPolicyManagerLogoutEnabled.value) {
            withContext(backgroundDispatcher) { devicePolicyManager.logoutUser() }
        }
    }

    override suspend fun logOutWithUserManager() {
        if (isUserManagerLogoutEnabled.value) {
            withContext(backgroundDispatcher) {
                val currentUserId = tracker.userId
                activityManager.logoutUser(currentUserId)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun refreshUsers() {
        applicationScope.launch {
            _userInfos.value =
                withContext(backgroundDispatcher) {
                    val order = getUserOrder()
                    manager.aliveUsers
                        // Users are sorted by the custom order from Settings, then by name.
                        .sortedWith(compareBy(Collator.getInstance()) { it.name.orEmpty() })
                        .sortedBy { order[it.serialNumber] ?: Int.MAX_VALUE }
                        // The main user is always first and the guest user is always last.
                        .sortedBy { !it.isMain }
                        .sortedBy { it.isGuest }
                }

            if (mainUserId == UserHandle.USER_NULL) {
                val mainUser = withContext(backgroundDispatcher) { manager.mainUser }
                mainUser?.let { mainUserId = it.identifier }
            }
        }
    }

    private fun getUserOrder(): Map<Int, Int> =
        globalSettings
            .getString(Settings.Global.USER_SWITCHER_ORDER)
            .orEmpty()
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .withIndex()
            .associate { (index, serialNumber) -> serialNumber to index }

    override fun getSelectedUserInfo(): UserInfo {
        return selectedUser.value.userInfo
    }

    override fun isSimpleUserSwitcher(): Boolean {
        return _userSwitcherSettings.value.isSimpleUserSwitcher
    }

    override fun isUserSwitcherEnabled(): Boolean {
        return _userSwitcherSettings.value.isUserSwitcherEnabled
    }

    override suspend fun getMainUserId(): Int? {
        return withContext(backgroundDispatcher) { manager.mainUser?.identifier }
    }

    override suspend fun getUserImage(@UserIdInt userId: Int, iconSize: Int): Drawable {
        return userIconProvider.getUserImage(userId, iconSize)
    }

    override fun clearUserImageCacheForUser(@UserIdInt userId: Int) {
        userIconProvider.clearCacheForUser(userId)
    }

    private suspend fun getSettings(): UserSwitcherSettingsModel {
        return withContext(backgroundDispatcher) {
            if (
                // TODO(b/378068979): remove once login screen-specific logic
                // is implemented at framework level.
                appContext.resources.getBoolean(
                    com.android.internal.R.bool.config_userSwitchingMustGoThroughLoginScreen
                )
            ) {
                UserSwitcherSettingsModel(
                    isSimpleUserSwitcher = false,
                    isAddUsersFromLockscreen = false,
                    isUserSwitcherEnabled = false,
                )
            } else {
                val isSimpleUserSwitcher =
                    globalSettings.getInt(
                        SETTING_SIMPLE_USER_SWITCHER,
                        if (
                            appContext.resources.getBoolean(
                                com.android.internal.R.bool.config_expandLockScreenUserSwitcher
                            )
                        ) {
                            1
                        } else {
                            0
                        },
                    ) != 0

                val isAddUsersFromLockscreen =
                    globalSettings.getInt(Settings.Global.ADD_USERS_WHEN_LOCKED, 0) != 0

                val isUserSwitcherEnabled =
                    globalSettings.getInt(
                        Settings.Global.USER_SWITCHER_ENABLED,
                        if (
                            appContext.resources.getBoolean(
                                com.android.internal.R.bool.config_showUserSwitcherByDefault
                            )
                        ) {
                            1
                        } else {
                            0
                        },
                    ) != 0
                UserSwitcherSettingsModel(
                    isSimpleUserSwitcher = isSimpleUserSwitcher,
                    isAddUsersFromLockscreen = isAddUsersFromLockscreen,
                    isUserSwitcherEnabled = isUserSwitcherEnabled,
                )
            }
        }
    }

    private fun SelectedUserModel.isHeadlessSystemUser(): Boolean {
        return userInfo.id == UserHandle.USER_SYSTEM && !userInfo.isFull
    }

    companion object {
        private const val TAG = "UserRepository"
        @VisibleForTesting const val SETTING_SIMPLE_USER_SWITCHER = "lockscreenSimpleUserSwitcher"
    }
}
