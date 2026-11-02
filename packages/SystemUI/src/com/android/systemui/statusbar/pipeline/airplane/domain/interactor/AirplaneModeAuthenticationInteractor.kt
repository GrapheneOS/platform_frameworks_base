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
import android.ext.settings.ExtSettings
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Requests fresh device authentication before user-initiated airplane mode disablement. */
@SysUISingleton
class AirplaneModeAuthenticationInteractor
@VisibleForTesting
internal constructor(
    private val authenticationContext: () -> Context,
    @Main private val mainExecutor: Executor,
    private val isAuthenticationRequired: (Context) -> Boolean,
    private val isDeviceSecure: (Context) -> Boolean,
) {
    @Inject
    constructor(
        userTracker: UserTracker,
        @Main mainExecutor: Executor,
    ) : this(
        authenticationContext = { userTracker.userContext },
        mainExecutor,
        isAuthenticationRequired = { context ->
            ExtSettings.REQUIRE_AUTHENTICATION_TO_DISABLE_AIRPLANE_MODE.get(context)
        },
        isDeviceSecure = { context ->
            context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        },
    )

    private val promptLock = Any()
    private var cancellationSignal: CancellationSignal? = null

    @VisibleForTesting
    internal var showPrompt:
        (Context, CancellationSignal, Executor, BiometricPrompt.AuthenticationCallback) -> Unit =
        { context, signal, executor, callback ->
            BiometricPrompt.Builder(context)
                .setTitle(context.getString(R.string.airplane_mode_authentication_title))
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .setConfirmationRequired(true)
                .setAllowBackgroundAuthentication(true)
                .build()
                .authenticate(signal, executor, callback)
        }

    /** Runs [action] immediately when disabled, or after successful fresh authentication. */
    fun runAfterAuthentication(action: Runnable): CancellationSignal? =
        requestAuthentication { authenticated ->
            if (authenticated) action.run()
        }

    /** Cancels [signal] only if it still owns the active prompt. */
    fun cancelAuthentication(signal: CancellationSignal?) {
        if (signal != null) cancelAuthenticationSignal(signal)
    }

    /** Returns whether fresh authentication succeeded or was not required. */
    suspend fun authenticateIfRequired(): Boolean =
        suspendCancellableCoroutine { continuation ->
            val signal = requestAuthentication { authenticated ->
                if (continuation.isActive) continuation.resume(authenticated)
            }
            continuation.invokeOnCancellation {
                if (signal != null) cancelAuthenticationSignal(signal)
            }
        }

    private fun requestAuthentication(onResult: (Boolean) -> Unit): CancellationSignal? {
        val context = authenticationContext()
        if (!isAuthenticationRequired(context) || !isDeviceSecure(context)) {
            onResult(true)
            return null
        }

        val signal =
            synchronized(promptLock) {
                if (cancellationSignal == null) {
                    CancellationSignal().also { cancellationSignal = it }
                } else {
                    null
                }
            }
        if (signal == null) {
            onResult(false)
            return null
        }

        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    completeAuthentication(signal, onResult, true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    completeAuthentication(signal, onResult, false)
                }
            }

        try {
            showPrompt(context, signal, mainExecutor, callback)
        } catch (e: RuntimeException) {
            completeAuthentication(signal, onResult, false)
        }
        return signal
    }

    private fun completeAuthentication(
        signal: CancellationSignal,
        onResult: (Boolean) -> Unit,
        authenticated: Boolean,
    ) {
        val shouldComplete =
            synchronized(promptLock) {
                if (cancellationSignal !== signal) {
                    false
                } else {
                    cancellationSignal = null
                    true
                }
            }
        if (shouldComplete) onResult(authenticated)
    }

    private fun cancelAuthenticationSignal(signal: CancellationSignal) {
        val shouldCancel =
            synchronized(promptLock) {
                if (cancellationSignal !== signal) {
                    false
                } else {
                    cancellationSignal = null
                    true
                }
            }
        if (shouldCancel) signal.cancel()
    }
}
