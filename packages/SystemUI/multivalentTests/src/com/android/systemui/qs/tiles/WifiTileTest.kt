/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles

import android.os.Handler
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfigProvider
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfigTestBuilder
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.qs.tiles.dialog.InternetDetailsViewModel
import com.android.systemui.qs.tiles.impl.wifi.domain.interactor.WifiTileDataInteractor
import com.android.systemui.qs.tiles.impl.wifi.domain.interactor.WifiTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.wifi.domain.model.WifiTileModel
import com.android.systemui.qs.tiles.impl.wifi.ui.mapper.WifiTileMapper
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class WifiTileTest : SysuiTestCase() {

    @Mock private lateinit var host: QSHost
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var uiEventLogger: QsEventLogger
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var qsTileConfigProvider: QSTileConfigProvider

    // Mocks for the new-arch components
    @Mock private lateinit var dataInteractor: WifiTileDataInteractor
    @Mock private lateinit var tileMapper: WifiTileMapper
    @Mock private lateinit var userActionInteractor: WifiTileUserActionInteractor
    @Mock private lateinit var internetDetailsViewModelFactory: InternetDetailsViewModel.Factory

    private lateinit var testableLooper: TestableLooper
    private lateinit var underTest: WifiTile
    private val tileDataFlow =
        MutableStateFlow<WifiTileModel>(mock(WifiTileModel.Inactive::class.java))

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        whenever(host.context).thenReturn(context)
        whenever(host.userContext).thenReturn(context)
        whenever(host.userId).thenReturn(0)

        whenever(dataInteractor.tileData()).thenReturn(tileDataFlow)

        whenever(qsTileConfigProvider.getConfig(WifiTile.TILE_SPEC))
            .thenReturn(
                QSTileConfigTestBuilder.build {
                    uiConfig =
                        QSTileUIConfig.Resource(
                            iconRes = R.drawable.ic_signal_wifi_off,
                            labelRes = R.string.quick_settings_internet_label,
                        )
                }
            )

        whenever(keyguardStateController.isMethodSecure).thenReturn(false)
        whenever(host.keyguardStateController).thenReturn(keyguardStateController)

        underTest =
            WifiTile(
                host,
                uiEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                qsTileConfigProvider,
                dataInteractor,
                tileMapper,
                userActionInteractor,
                internetDetailsViewModelFactory,
            )

        underTest.initialize()
        underTest.setListening(this, true)
        testableLooper.processAllMessages()
    }

    @After
    fun tearDown() {
        underTest.setListening(this, false)
        underTest.destroy()
        testableLooper.processAllMessages()
    }

    @Test
    fun tileDataChanges_triggersMapperWithNewModel() {
        val model = mock(WifiTileModel.Active::class.java)
        whenever(tileMapper.map(any(), any())).thenReturn(mock(QSTileState::class.java))

        tileDataFlow.value = model
        testableLooper.processAllMessages()

        verify(tileMapper).map(any(), eq(model))
    }

    @Test
    fun click_callsUserActionInteractor() = runTest {
        val expandable = mock(Expandable::class.java)
        underTest.click(expandable)
        testableLooper.processAllMessages()

        verify(userActionInteractor).handleClick(expandable)
    }

    @Test
    fun secondaryClick_callsUserActionInteractor() {
        val expandable = mock(Expandable::class.java)
        underTest.secondaryClick(expandable)
        testableLooper.processAllMessages()

        verify(userActionInteractor).handleSecondaryClick(expandable)
    }

    @Test
    fun isAvailable_fromDataInteractor_isTrue() {
        whenever(dataInteractor.isAvailable()).thenReturn(true)

        assertThat(underTest.isAvailable).isTrue()
    }

    @Test
    fun isAvailable_fromDataInteractor_isFalse() {
        whenever(dataInteractor.isAvailable()).thenReturn(false)

        assertThat(underTest.isAvailable).isFalse()
    }
}
