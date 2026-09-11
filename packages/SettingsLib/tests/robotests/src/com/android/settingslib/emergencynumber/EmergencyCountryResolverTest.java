package com.android.settingslib.emergencynumber;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.telephony.TelephonyManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
public class EmergencyCountryResolverTest {
    private final TelephonyManager mTelephony = mock(TelephonyManager.class);
    private final EmergencyCountryResolver mResolver = new EmergencyCountryResolver(mTelephony);

    @Test
    public void roaming_networkCountryPrecedesHomeSimAndLocale() {
        when(mTelephony.getNetworkCountryIso()).thenReturn("ch");
        when(mTelephony.getActiveModemCount()).thenReturn(2);
        when(mTelephony.getNetworkCountryIso(0)).thenReturn("ca");
        when(mTelephony.getNetworkCountryIso(1)).thenReturn("ch");
        when(mTelephony.getSimCountryIso()).thenReturn("ca");
        assertThat(mResolver.resolve(Locale.CANADA)).isEqualTo("CH");
    }

    @Test
    public void absentDefaultNetwork_otherNetworkPrecedesHomeSimAndLocale() {
        when(mTelephony.getNetworkCountryIso()).thenReturn("");
        when(mTelephony.getActiveModemCount()).thenReturn(2);
        when(mTelephony.getNetworkCountryIso(0)).thenReturn("");
        when(mTelephony.getNetworkCountryIso(1)).thenReturn("ch");
        when(mTelephony.getSimCountryIso()).thenReturn("ca");
        assertThat(mResolver.resolve(Locale.CANADA)).isEqualTo("CH");
    }

    @Test
    public void unavailableSlot_stillUsesOtherNetwork() {
        when(mTelephony.getNetworkCountryIso()).thenThrow(new IllegalStateException());
        when(mTelephony.getActiveModemCount()).thenReturn(2);
        when(mTelephony.getNetworkCountryIso(0)).thenThrow(new UnsupportedOperationException());
        when(mTelephony.getNetworkCountryIso(1)).thenReturn("ch");
        when(mTelephony.getSimCountryIso()).thenReturn("ca");
        assertThat(mResolver.resolve(Locale.CANADA)).isEqualTo("CH");
    }

    @Test
    public void invalidSlot_stillUsesSim() {
        when(mTelephony.getNetworkCountryIso()).thenReturn("");
        when(mTelephony.getActiveModemCount()).thenReturn(1);
        when(mTelephony.getNetworkCountryIso(0)).thenThrow(new IllegalArgumentException());
        when(mTelephony.getSimCountryIso()).thenReturn("ca");
        assertThat(mResolver.resolve(Locale.US)).isEqualTo("CA");
    }

    @Test
    public void absentNetwork_simPrecedesLocale() {
        when(mTelephony.getNetworkCountryIso()).thenReturn("");
        when(mTelephony.getActiveModemCount()).thenReturn(2);
        when(mTelephony.getNetworkCountryIso(0)).thenReturn("");
        when(mTelephony.getNetworkCountryIso(1)).thenReturn("");
        when(mTelephony.getSimCountryIso()).thenReturn("ca");
        assertThat(mResolver.resolve(Locale.US)).isEqualTo("CA");
    }

    @Test
    public void unavailableNetwork_stillUsesSim() {
        when(mTelephony.getNetworkCountryIso()).thenThrow(new IllegalStateException());
        when(mTelephony.getSimCountryIso()).thenReturn("gb");
        assertThat(mResolver.resolve(Locale.US)).isEqualTo("GB");
    }

    @Test
    public void noLiveCountry_usesLocaleWithoutRememberingPreviousCountry() {
        when(mTelephony.getNetworkCountryIso()).thenReturn("ch");
        assertThat(mResolver.resolve(Locale.CANADA)).isEqualTo("CH");
        when(mTelephony.getNetworkCountryIso()).thenReturn("");
        assertThat(mResolver.resolve(Locale.CANADA)).isEqualTo("CA");
    }

    @Test
    public void malformedCountriesAndAbsentLocale_useUs() {
        when(mTelephony.getNetworkCountryIso()).thenReturn("123");
        when(mTelephony.getSimCountryIso()).thenReturn("1a");
        assertThat(mResolver.resolve(Locale.ROOT)).isEqualTo("US");
    }
}
