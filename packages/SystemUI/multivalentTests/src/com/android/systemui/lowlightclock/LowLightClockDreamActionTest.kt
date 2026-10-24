/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.lowlightclock

import android.content.ComponentName
import android.content.Context
import android.content.packageManager
import android.content.pm.PackageManager
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.dream.lowlight.LowLightDreamManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.settings.UserTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class LowLightClockDreamActionTest : SysuiTestCase() {
    val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val ambientLightMode: MutableStateFlow<Int> =
        MutableStateFlow(LowLightDreamManager.AMBIENT_LIGHT_MODE_UNKNOWN)

    private val Kosmos.lowLightDreamManager: LowLightDreamManager by
        Kosmos.Fixture {
            mock<LowLightDreamManager> {
                on { setAmbientLightMode(any()) } doAnswer
                    { invocation ->
                        val mode = invocation.arguments[0] as Int
                        ambientLightMode.value = mode
                    }
            }
        }

    private var Kosmos.dreamComponent: ComponentName? by
        Kosmos.Fixture { ComponentName("test", "test.LowLightDream") }

    private val userContext: Context = mock()
    private val userTracker: UserTracker = mock()

    private val Kosmos.underTest: LowLightClockDreamAction by
        Kosmos.Fixture {
            whenever(userTracker.createCurrentUserContext(context)).thenReturn(userContext)
            whenever(userContext.packageManager).thenReturn(packageManager)
            LowLightClockDreamAction(
                context = context,
                userTracker = userTracker,
                lowLightDreamService = dreamComponent,
                lowLightDreamManager = { lowLightDreamManager },
            )
        }

    @Test
    fun enable_usesCurrentUserPackageManager() =
        kosmos.runTest {
            val action = underTest
            val currentUserPackageManager: PackageManager = mock()
            whenever(userContext.packageManager).thenReturn(currentUserPackageManager)
            whenever(currentUserPackageManager.getComponentEnabledSetting(dreamComponent!!))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

            action.setEnabled(true)

            verify(userTracker).createCurrentUserContext(context)
            verify(currentUserPackageManager)
                .setComponentEnabledSetting(
                    dreamComponent!!,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            verify(packageManager, never()).setComponentEnabledSetting(any(), any(), any())
        }

    @Test
    fun testLowLightClockDreamAction_lowLightToggledOnEnable() =
        kosmos.runTest {
            val mode by collectLastValue(ambientLightMode)
            underTest.setEnabled(true)

            val job = testScope.backgroundScope.launch { underTest.activate() }

            assertThat(mode).isEqualTo(LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT)

            job.cancel()
            assertThat(mode).isEqualTo(LowLightDreamManager.AMBIENT_LIGHT_MODE_REGULAR)
        }

    @Test
    fun testLowLightClockDreamAction_dreamComponentEnabledOnce() =
        kosmos.runTest {
            val job = testScope.backgroundScope.launch { underTest.activate() }
            verify(packageManager)
                .setComponentEnabledSetting(
                    eq(dreamComponent!!),
                    eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                    eq(PackageManager.DONT_KILL_APP),
                )
            clearInvocations(packageManager)
            whenever(packageManager.getComponentEnabledSetting(dreamComponent!!))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

            job.cancel()

            testScope.backgroundScope.launch { underTest.activate() }

            verify(packageManager, never()).setComponentEnabledSetting(any(), any(), any())
        }
}
