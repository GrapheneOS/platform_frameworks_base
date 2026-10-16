/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.airplane.domain.interactor

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class AirplaneModeAuthenticationInteractorTest : SysuiTestCase() {
    private val keyguardManager = mock<KeyguardManager>()
    private val executor = Executor { it.run() }

    @Test
    fun runAfterAuthentication_settingDisabled_runsImmediately() {
        val underTest = createInteractor(authenticationRequired = false)
        var actionRan = false

        underTest.runAfterAuthentication { actionRan = true }

        assertThat(actionRan).isTrue()
        verify(keyguardManager, never()).isDeviceSecure
    }

    @Test
    fun runAfterAuthentication_noSecureLock_runsImmediately() {
        whenever(keyguardManager.isDeviceSecure).thenReturn(false)
        val underTest = createInteractor(authenticationRequired = true)
        var actionRan = false

        underTest.runAfterAuthentication { actionRan = true }

        assertThat(actionRan).isTrue()
    }

    @Test
    fun runAfterAuthentication_secureLock_waitsForSuccessfulAuthentication() {
        whenever(keyguardManager.isDeviceSecure).thenReturn(true)
        val underTest = createInteractor(authenticationRequired = true)
        var callback: BiometricPrompt.AuthenticationCallback? = null
        underTest.showPrompt = { _, _, _, authenticationCallback ->
            callback = authenticationCallback
        }
        var actionRan = false

        underTest.runAfterAuthentication { actionRan = true }

        assertThat(actionRan).isFalse()
        callback!!.onAuthenticationSucceeded(mock())
        assertThat(actionRan).isTrue()
    }

    @Test
    fun runAfterAuthentication_authenticationError_doesNotRunActionAndAllowsRetry() {
        whenever(keyguardManager.isDeviceSecure).thenReturn(true)
        val underTest = createInteractor(authenticationRequired = true)
        var callback: BiometricPrompt.AuthenticationCallback? = null
        var promptCount = 0
        underTest.showPrompt = { _, _, _, authenticationCallback ->
            promptCount++
            callback = authenticationCallback
        }
        var actionCount = 0

        underTest.runAfterAuthentication { actionCount++ }
        callback!!.onAuthenticationError(1, "cancelled")
        underTest.runAfterAuthentication { actionCount++ }

        assertThat(actionCount).isEqualTo(0)
        assertThat(promptCount).isEqualTo(2)
    }

    @Test
    fun runAfterAuthentication_promptAlreadyShowing_ignoresSecondRequest() {
        whenever(keyguardManager.isDeviceSecure).thenReturn(true)
        val underTest = createInteractor(authenticationRequired = true)
        var promptCount = 0
        underTest.showPrompt = { _, _, _, _ -> promptCount++ }

        underTest.runAfterAuthentication {}
        underTest.runAfterAuthentication {}

        assertThat(promptCount).isEqualTo(1)
    }

    @Test
    fun cancelAuthentication_ownedRequest_cancelsSystemPrompt() {
        whenever(keyguardManager.isDeviceSecure).thenReturn(true)
        val underTest = createInteractor(authenticationRequired = true)
        underTest.showPrompt = { _, _, _, _ -> }

        val signal = underTest.runAfterAuthentication {}
        underTest.cancelAuthentication(signal)

        assertThat(signal!!.isCanceled).isTrue()
    }

    @Test
    fun cancelAuthentication_foreignRequest_keepsOwnedPromptActive() {
        whenever(keyguardManager.isDeviceSecure).thenReturn(true)
        val underTest = createInteractor(authenticationRequired = true)
        underTest.showPrompt = { _, _, _, _ -> }
        val ownedSignal = underTest.runAfterAuthentication {}
        val foreignSignal = CancellationSignal()

        underTest.cancelAuthentication(foreignSignal)
        underTest.cancelAuthentication(ownedSignal)

        assertThat(foreignSignal.isCanceled).isFalse()
        assertThat(ownedSignal!!.isCanceled).isTrue()
    }

    @Test
    fun runAfterAuthentication_usesCurrentUserContextForPromptAndSecurityCheck() {
        val userContext = mock<Context>()
        var checkedContext: Context? = null
        val underTest =
            AirplaneModeAuthenticationInteractor(
                authenticationContext = { userContext },
                mainExecutor = executor,
                isAuthenticationRequired = { true },
                isDeviceSecure = {
                    checkedContext = it
                    true
                },
            )
        var promptContext: Context? = null
        underTest.showPrompt = { context, _, _, _ -> promptContext = context }

        underTest.runAfterAuthentication {}

        assertThat(checkedContext).isSameInstanceAs(userContext)
        assertThat(promptContext).isSameInstanceAs(userContext)
    }

    @Test
    fun authenticateIfRequired_authenticationError_returnsFalse() = runTest {
        whenever(keyguardManager.isDeviceSecure).thenReturn(true)
        val underTest = createInteractor(authenticationRequired = true)
        var callback: BiometricPrompt.AuthenticationCallback? = null
        underTest.showPrompt = { _, _, _, authenticationCallback ->
            callback = authenticationCallback
        }

        val result =
            async(start = CoroutineStart.UNDISPATCHED) { underTest.authenticateIfRequired() }
        callback!!.onAuthenticationError(1, "cancelled")

        assertThat(result.await()).isFalse()
    }

    @Test
    fun authenticateIfRequired_authenticationSucceeds_returnsTrue() = runTest {
        whenever(keyguardManager.isDeviceSecure).thenReturn(true)
        val underTest = createInteractor(authenticationRequired = true)
        var callback: BiometricPrompt.AuthenticationCallback? = null
        underTest.showPrompt = { _, _, _, authenticationCallback ->
            callback = authenticationCallback
        }

        val result =
            async(start = CoroutineStart.UNDISPATCHED) { underTest.authenticateIfRequired() }
        callback!!.onAuthenticationSucceeded(mock())

        assertThat(result.await()).isTrue()
    }

    @Test
    fun authenticateIfRequired_coroutineCancelled_cancelsSystemPrompt() = runTest {
        whenever(keyguardManager.isDeviceSecure).thenReturn(true)
        val underTest = createInteractor(authenticationRequired = true)
        var cancellationSignal: CancellationSignal? = null
        underTest.showPrompt = { _, signal, _, _ -> cancellationSignal = signal }

        val result =
            async(start = CoroutineStart.UNDISPATCHED) { underTest.authenticateIfRequired() }
        result.cancelAndJoin()

        assertThat(cancellationSignal!!.isCanceled).isTrue()
    }

    private fun createInteractor(authenticationRequired: Boolean) =
        AirplaneModeAuthenticationInteractor(
            authenticationContext = { mContext },
            mainExecutor = executor,
            isAuthenticationRequired = { authenticationRequired },
            isDeviceSecure = { keyguardManager.isDeviceSecure },
        )
}
