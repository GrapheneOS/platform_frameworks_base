/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.airplane.domain.interactor

import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.tiles.base.domain.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandlerSubject.Companion.assertThat
import com.android.systemui.qs.tiles.base.domain.model.QSTileInputTestKtx.click
import com.android.systemui.qs.tiles.base.domain.model.QSTileInputTestKtx.longClick
import com.android.systemui.qs.tiles.impl.airplane.domain.model.AirplaneModeTileModel
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeAuthenticationInteractor
import com.android.systemui.statusbar.pipeline.airplane.data.repository.airplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.airplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class AirplaneModeTileUserActionInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val airplaneModeRepository = kosmos.airplaneModeRepository
    private val inputHandler = FakeQSTileIntentUserInputHandler()
    private val authenticationInteractor = mock<AirplaneModeAuthenticationInteractor>()

    private val underTest =
        AirplaneModeTileUserActionInteractor(
            kosmos.airplaneModeInteractor,
            authenticationInteractor,
            inputHandler,
        )

    @Test
    fun handleClickInEcmMode() = runTest {
        val isInAirplaneMode = false
        airplaneModeRepository.setIsAirplaneMode(isInAirplaneMode)
        kosmos.fakeMobileConnectionsRepository.setIsInEcmState(true)

        underTest.handleInput(click(AirplaneModeTileModel(isInAirplaneMode)))

        assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action)
                .isEqualTo(TelephonyManager.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS)
        }
        assertThat(airplaneModeRepository.isAirplaneMode.value).isFalse()
    }

    @Test
    fun handleClickNotInEcmMode() = runTest {
        val isInAirplaneMode = false
        airplaneModeRepository.setIsAirplaneMode(isInAirplaneMode)
        kosmos.fakeMobileConnectionsRepository.setIsInEcmState(isInAirplaneMode)

        underTest.handleInput(click(AirplaneModeTileModel(false)))

        assertThat(inputHandler).handledNoInputs()
        assertThat(airplaneModeRepository.isAirplaneMode.value).isTrue()
    }

    @Test
    fun handleClickToDisable_authenticationSucceeds_disablesAirplaneMode() = runTest {
        airplaneModeRepository.setIsAirplaneMode(true)
        whenever(authenticationInteractor.authenticateIfRequired()).thenReturn(true)

        underTest.handleInput(click(AirplaneModeTileModel(true)))

        assertThat(airplaneModeRepository.isAirplaneMode.value).isFalse()
    }

    @Test
    fun handleClickToDisable_authenticationCancelled_keepsAirplaneModeEnabled() = runTest {
        airplaneModeRepository.setIsAirplaneMode(true)
        whenever(authenticationInteractor.authenticateIfRequired()).thenReturn(false)

        underTest.handleInput(click(AirplaneModeTileModel(true)))

        assertThat(airplaneModeRepository.isAirplaneMode.value).isTrue()
    }

    @Test
    fun handleClickToEnable_doesNotAuthenticate() = runTest {
        airplaneModeRepository.setIsAirplaneMode(false)

        underTest.handleInput(click(AirplaneModeTileModel(false)))

        verify(authenticationInteractor, never()).authenticateIfRequired()
        assertThat(airplaneModeRepository.isAirplaneMode.value).isTrue()
    }

    @Test
    fun handleLongClick() = runTest {
        underTest.handleInput(longClick(AirplaneModeTileModel(false)))

        assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        }
    }
}
