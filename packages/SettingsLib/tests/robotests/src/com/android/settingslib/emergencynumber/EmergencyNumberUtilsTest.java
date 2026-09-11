/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settingslib.emergencynumber;

import static android.telephony.emergency.EmergencyNumber.EMERGENCY_CALL_ROUTING_EMERGENCY;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_CALL_ROUTING_NORMAL;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_CALL_ROUTING_UNKNOWN;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_NUMBER_SOURCE_SIM;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED;

import static com.android.settingslib.emergencynumber.EmergencyNumberUtils.EMERGENCY_GESTURE_CALL_NUMBER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.util.ArrayMap;

import com.android.settingslib.emergencynumber.EmergencyNumberUtils.Candidate;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class EmergencyNumberUtilsTest {
    private static final String TELEPHONY_EMERGENCY_NUMBER = "1234";
    private static final String USER_OVERRIDE_EMERGENCY_NUMBER = "5678";

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private CarrierConfigManager mCarrierConfigManager;

    private EmergencyNumberUtils mUtils;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getSystemService(CarrierConfigManager.class)).thenReturn(
                mCarrierConfigManager);
    }

    @Test
    public void getDefaultPoliceNumber_otherSubscription_suppliesCountryPreference() {
        assertCombinedSelection("ch", "112",
                candidate("117", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING),
                candidate("112", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE));
    }

    @Test
    public void getDefaultPoliceNumber_otherSubscription_stripsOwnCarrierPrefix() {
        PersistableBundle config = new PersistableBundle();
        config.putStringArray(CarrierConfigManager.KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY,
                new String[] {"*272"});
        when(mCarrierConfigManager.getConfigForSubId(2)).thenReturn(config);
        EmergencyNumber database = candidate("112", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_DATABASE);
        EmergencyNumber prefixed = candidate("*272911", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_SIM);
        assertCombinedSelection("ca", "911", database, prefixed);

        assertThat(mUtils.getPromotedEmergencyNumbers()).containsExactly(
                new Candidate(prefixed, "911"), new Candidate(database, "112")).inOrder();
        assertThat(prefixed.getNumber()).isEqualTo("*272911");
    }

    @Test
    public void getDefaultPoliceNumber_duplicateNumber_keepsBetterMetadata() {
        assertCombinedSelection("zz", "117",
                candidate("117", EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("118", EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("117", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING));
    }

    @Test
    public void getDefaultPoliceNumber_otherNetworkCountry_precedesHomeSim() {
        when(mTelephonyManager.getActiveModemCount()).thenReturn(2);
        when(mTelephonyManager.getNetworkCountryIso(0)).thenReturn("");
        when(mTelephonyManager.getNetworkCountryIso(1)).thenReturn("ch");
        when(mTelephonyManager.getSimCountryIso()).thenReturn("ca");
        assertCombinedSelection("", "112",
                candidate("112", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("911", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE));
    }

    @Test
    public void getDefaultPoliceNumber_noTelephony_shouldReturnDefault() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(false);
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(
                EmergencyNumberUtils.FALL_BACK_NUMBER);
        assertThat(mUtils.getPromotedEmergencyNumbers()).isEmpty();
    }

    @Test
    public void rankEmergencyNumbers_preservesMetadataDuplicatesAndInputOrder() {
        Candidate first = new Candidate(candidate("112", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_DATABASE), "112");
        Candidate second = new Candidate(candidate("911", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_DATABASE), "911");
        Candidate third = new Candidate(candidate("911", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_DATABASE), "911");
        List<Candidate> input = List.of(first, second, third);

        assertThat(EmergencyNumberUtils.rankEmergencyNumbers(input, "ca"))
                .containsExactly(second, third, first).inOrder();
        assertThat(input).containsExactly(first, second, third).inOrder();
        assertThat(EmergencyNumberUtils.rankEmergencyNumbers(input, null))
                .containsExactly(first, second, third).inOrder();
    }

    @Test
    public void getDefaultPoliceNumber_hasTelephony_shouldLoadFromTelephony() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumberToTelephony(TELEPHONY_EMERGENCY_NUMBER);
        mUtils = new EmergencyNumberUtils(mContext);


        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(TELEPHONY_EMERGENCY_NUMBER);
    }

    @Test
    public void getDefaultPoliceNumber_unspecifiedNumber_shouldReturnExplicitPoliceNumber() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumbersToTelephony(
                createEmergencyNumber("0800117117", EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                        EMERGENCY_CALL_ROUTING_NORMAL),
                createEmergencyNumber("112", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_CALL_ROUTING_EMERGENCY));
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo("112");
    }

    @Test
    public void getDefaultPoliceNumber_normalRoutedUnspecifiedNumber_shouldReturnNumber() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumbersToTelephony(
                createEmergencyNumber("0800117117", EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                        EMERGENCY_CALL_ROUTING_NORMAL));
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo("0800117117");
    }

    @Test
    public void getDefaultPoliceNumber_emergencyRoutedUnspecifiedNumber_shouldReturnNumber() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumbersToTelephony(
                createEmergencyNumber("999", EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                        EMERGENCY_CALL_ROUTING_EMERGENCY));
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo("999");
    }

    @Test
    public void getDefaultPoliceNumber_eligibleUnspecifiedNumber_shouldPreferExplicitPolice() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumbersToTelephony(
                createEmergencyNumber("999", EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                        EMERGENCY_CALL_ROUTING_EMERGENCY),
                createEmergencyNumber("112", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_CALL_ROUTING_EMERGENCY));
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo("112");
    }

    @Test
    public void getDefaultPoliceNumber_unknownRoutedUnspecifiedNumber_shouldReturnNumber() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumbersToTelephony(
                createEmergencyNumber("000", EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                        EMERGENCY_CALL_ROUTING_UNKNOWN));
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo("000");
    }

    @Test
    public void getDefaultPoliceNumber_emptyNumberList_shouldReturnDefault() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumbersToTelephony();
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(
                EmergencyNumberUtils.FALL_BACK_NUMBER);
    }

    @Test
    public void getDefaultPoliceNumber_telephonyUnavailable_shouldReturnDefault() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getEmergencyNumberList()).thenThrow(new IllegalStateException());
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(
                EmergencyNumberUtils.FALL_BACK_NUMBER);
    }

    @Test
    public void getDefaultPoliceNumber_telephonyUnsupported_shouldReturnDefault() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getEmergencyNumberList()).thenThrow(
                new UnsupportedOperationException());
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(
                EmergencyNumberUtils.FALL_BACK_NUMBER);
    }

    @Test
    public void getDefaultPoliceNumber_normalRoutedPoliceNumber_shouldReturnPoliceNumber() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumbersToTelephony(
                createEmergencyNumber("17", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_CALL_ROUTING_NORMAL));
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo("17");
    }

    @Test
    public void getPoliceNumber_hasUserOverride_shouldLoadFromUserOverride() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumberToTelephony(TELEPHONY_EMERGENCY_NUMBER);

        Bundle bundle = new Bundle();
        bundle.putString(EMERGENCY_GESTURE_CALL_NUMBER, USER_OVERRIDE_EMERGENCY_NUMBER);
        when(mContentResolver.call(any(Uri.class), anyString(), nullable(String.class), nullable(
                Bundle.class))).thenReturn(bundle);
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getPoliceNumber()).isEqualTo(USER_OVERRIDE_EMERGENCY_NUMBER);
    }

    @Test
    public void getPoliceNumber_noUserOverride_shouldLoadFromTelephony() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumberToTelephony(TELEPHONY_EMERGENCY_NUMBER);

        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getPoliceNumber()).isEqualTo(TELEPHONY_EMERGENCY_NUMBER);
    }

    @Test
    public void getPoliceNumber_hasCarrierPrefix_shouldRemovePrefix() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        final String prefix = "*272";
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(CarrierConfigManager.KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY,
                new String[]{prefix});
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(bundle);
        addEmergencyNumberToTelephony(prefix + TELEPHONY_EMERGENCY_NUMBER);

        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getPoliceNumber()).isEqualTo(TELEPHONY_EMERGENCY_NUMBER);
    }

    @Test
    public void getDefaultPoliceNumber_swissNetworkDump_prefers112() {
        final int networkAndModem = EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING
                | EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG;
        final int database = EMERGENCY_NUMBER_SOURCE_DATABASE;
        final int modem = EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG;
        final int police = EMERGENCY_SERVICE_CATEGORY_POLICE;
        final int fire = EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE;
        final int ambulance = EMERGENCY_SERVICE_CATEGORY_AMBULANCE;
        final int mountain = EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE;
        final int unspecified = EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED;
        final int unknown = EMERGENCY_CALL_ROUTING_UNKNOWN;
        final int emergency = EMERGENCY_CALL_ROUTING_EMERGENCY;
        final int normal = EMERGENCY_CALL_ROUTING_NORMAL;

        // Preserve the reported Telephony order, including separate database and modem rows.
        // Network 117 comes first; database 0800117117 must not become the SOS default.
        assertSelection("ch", "112",
                dumpEntry("117", "ch", police, networkAndModem, unknown),
                dumpEntry("118", "ch", fire, networkAndModem, unknown),
                dumpEntry("1414", "ch", mountain, networkAndModem, unknown),
                dumpEntry("144", "ch", ambulance, networkAndModem, unknown),
                dumpEntry("0800117117", "ch", unspecified, database, normal),
                dumpEntry("112", "ch", police | ambulance | fire, database, emergency),
                dumpEntry("117", "ch", police, database, emergency),
                dumpEntry("118", "ch", fire, database, normal),
                dumpEntry("1414", "ch", mountain, database, normal),
                dumpEntry("143", "ch", unspecified, database, normal),
                dumpEntry("144", "ch", ambulance, database, normal),
                dumpEntry("145", "ch", unspecified, database, normal),
                dumpEntry("147", "ch", unspecified, database, normal),
                dumpEntry("911", "ch", police | ambulance | fire, database, emergency),
                dumpEntry("112", "", unspecified, modem, unknown),
                dumpEntry("112", "ch", unspecified, modem, unknown),
                dumpEntry("143", "ch", unspecified, modem, unknown),
                dumpEntry("145", "ch", unspecified, modem, unknown),
                dumpEntry("147", "ch", unspecified, modem, unknown),
                dumpEntry("911", "ch", unspecified, modem, unknown));
    }

    @Test
    public void getDefaultPoliceNumber_canadianNetworkDump_prefers911() {
        final int general = EMERGENCY_SERVICE_CATEGORY_POLICE
                | EMERGENCY_SERVICE_CATEGORY_AMBULANCE | EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE;
        final int modem = EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG;
        final int simAndModem = EMERGENCY_NUMBER_SOURCE_SIM | modem;
        final int database = EMERGENCY_NUMBER_SOURCE_DATABASE;
        final int unspecified = EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED;
        final int unknown = EMERGENCY_CALL_ROUTING_UNKNOWN;

        // The database-only 112 and 911 rows have equal categories, routing and sources.
        // Country preference must win even though database 112 precedes database 911.
        assertSelection("ca", "911",
                dumpEntry("911", "ca", general
                        | EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD
                        | EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE,
                        EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING | simAndModem, unknown),
                dumpEntry("112", "ca", unspecified, simAndModem, unknown),
                dumpEntry("112", "ca", general, database, unknown),
                dumpEntry("911", "ca", general, database, unknown),
                dumpEntry("112", "", unspecified, modem, unknown));
    }

    @Test
    public void getDefaultPoliceNumber_koreanCountryOverrideDump_prefers112() {
        final int database = EMERGENCY_NUMBER_SOURCE_DATABASE;
        final int modem = EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG;
        final int sim = EMERGENCY_NUMBER_SOURCE_SIM;
        final int unspecified = EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED;
        final int unknown = EMERGENCY_CALL_ROUTING_UNKNOWN;
        final int emergency = EMERGENCY_CALL_ROUTING_EMERGENCY;

        // Preserve the captured order after overriding the country to KR on a Canadian SIM.
        // Unspecified database 111 precedes police 112; both have emergency routing.
        assertSelection("kr", "112",
                dumpEntry("112", "", unspecified, sim | modem, unknown),
                dumpEntry("911", "", unspecified, sim, unknown),
                dumpEntry("111", "kr", unspecified, database, emergency),
                dumpEntry("112", "kr", EMERGENCY_SERVICE_CATEGORY_POLICE, database, emergency),
                dumpEntry("113", "kr", unspecified, database, emergency),
                dumpEntry("117", "kr", unspecified, database, emergency),
                dumpEntry("118", "kr", unspecified, database, emergency),
                dumpEntry("119", "kr", EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE, database, emergency),
                dumpEntry("122", "kr", EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD, database, emergency),
                dumpEntry("125", "kr", unspecified, database, emergency),
                dumpEntry("112", "ca", unspecified, modem, unknown),
                dumpEntry("911", "ca", unspecified, modem, unknown));
    }

    private EmergencyNumber dumpEntry(String number, String country,
            int categories, int sources, int routing) {
        // MNCs are omitted; the selector does not use them.
        // Use real objects so bitmasks are exposed as the same category/source lists as Telephony.
        return new EmergencyNumber(number, country, "", categories, Collections.emptyList(),
                sources, routing);
    }

    @Test
    public void getDefaultPoliceNumber_countryPreference_precedesPoliceCategory() {
        assertSelection("tr", "112",
                candidate("155", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING),
                candidate("112", EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE));
    }

    @Test
    public void getDefaultPoliceNumber_primary_precedesCategory() {
        assertSelection("in", "112",
                candidate("108", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("112", EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                        EMERGENCY_NUMBER_SOURCE_SIM));
    }

    @Test
    public void getDefaultPoliceNumber_missingPrimary_usesOtherTableCandidate() {
        assertSelection("in", "108",
                candidate("100", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("108", EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                        EMERGENCY_NUMBER_SOURCE_SIM));
    }

    @Test
    public void getDefaultPoliceNumber_missingTableCandidates_ranksAllCategories() {
        assertSelection("ca", "123",
                candidate("100", EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("124", EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("123", EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                        EMERGENCY_NUMBER_SOURCE_SIM));
    }

    @Test
    public void getDefaultPoliceNumber_unknownCountry_categoryPrecedesSource() {
        assertSelection("zz", "123",
                candidate("124", EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("123", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_SIM));
    }

    @Test
    public void getDefaultPoliceNumber_sources_bestSourceWins() {
        EmergencyNumber database = candidate("123", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_DATABASE);
        EmergencyNumber network = candidate("124", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING);
        EmergencyNumber sim = candidate("125", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_SIM);
        EmergencyNumber modem = candidate("126", EMERGENCY_SERVICE_CATEGORY_POLICE,
                EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG);
        assertSelection(null, "123", modem, sim, network, database);
        assertSelection(null, "124", modem, sim, network);
        assertSelection(null, "125", modem, sim);
        when(sim.getEmergencyNumberSources()).thenReturn(List.of(
                EMERGENCY_NUMBER_SOURCE_SIM, EMERGENCY_NUMBER_SOURCE_DATABASE));
        assertSelection(null, "125", network, sim);
    }

    @Test
    public void getDefaultPoliceNumber_equalRanks_preservesInputOrder() {
        assertSelection("zz", "999",
                candidate("999", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("112", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE));
    }

    @Test
    public void getDefaultPoliceNumber_multipleCategories_usesBestCategory() {
        EmergencyNumber multi = candidate("123", EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                EMERGENCY_NUMBER_SOURCE_DATABASE);
        when(multi.getEmergencyServiceCategories()).thenReturn(List.of(
                EMERGENCY_SERVICE_CATEGORY_AMBULANCE, EMERGENCY_SERVICE_CATEGORY_POLICE));
        assertSelection(null, "123",
                candidate("124", EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE), multi);
    }

    @Test
    public void getDefaultPoliceNumber_prefixedTableCandidate_matchesAfterSanitizing() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(CarrierConfigManager.KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY,
                new String[]{"*272"});
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(bundle);
        assertSelection("ca", "911",
                candidate("112", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_DATABASE),
                candidate("*272911", EMERGENCY_SERVICE_CATEGORY_POLICE,
                        EMERGENCY_NUMBER_SOURCE_SIM));
    }

    private void addEmergencyNumberToTelephony(String number) {
        addEmergencyNumbersToTelephony(createEmergencyNumber(number,
                EMERGENCY_SERVICE_CATEGORY_POLICE, EMERGENCY_CALL_ROUTING_EMERGENCY));
    }

    private EmergencyNumber createEmergencyNumber(String number, int categories, int routing) {
        EmergencyNumber emergencyNumber = mock(EmergencyNumber.class);
        when(emergencyNumber.getNumber()).thenReturn(number);
        when(emergencyNumber.getEmergencyServiceCategories()).thenReturn(
                Collections.singletonList(categories));
        when(emergencyNumber.getEmergencyNumberSources()).thenReturn(
                Collections.singletonList(EMERGENCY_NUMBER_SOURCE_DATABASE));
        when(emergencyNumber.getEmergencyCallRouting()).thenReturn(routing);
        return emergencyNumber;
    }

    private void addEmergencyNumbersToTelephony(EmergencyNumber... emergencyNumbers) {
        final int subId = SubscriptionManager.getDefaultSubscriptionId();
        Map<Integer, List<EmergencyNumber>> numbers = new ArrayMap<>();
        List<EmergencyNumber> numbersForSubId = new ArrayList<>();
        Collections.addAll(numbersForSubId, emergencyNumbers);
        // The selector must not sort or filter the supplied list in place.
        numbers.put(subId, Collections.unmodifiableList(numbersForSubId));
        when(mTelephonyManager.getEmergencyNumberList()).thenReturn(numbers);
    }

    private void assertCombinedSelection(String country, String expected,
            EmergencyNumber first, EmergencyNumber... others) {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getNetworkCountryIso()).thenReturn(country);
        Map<Integer, List<EmergencyNumber>> lists = new LinkedHashMap<>();
        lists.put(1, List.of(first));
        lists.put(2, List.of(others));
        lists.put(3, null); // An unavailable subscription must not hide other candidates.
        when(mTelephonyManager.getEmergencyNumberList()).thenReturn(lists);
        mUtils = new EmergencyNumberUtils(mContext);
        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(expected);
        assertThat(lists.get(1)).containsExactly(first);
        assertThat(lists.get(2)).containsExactlyElementsIn(List.of(others)).inOrder();
    }

    private EmergencyNumber candidate(String number, int category, int source) {
        EmergencyNumber result = createEmergencyNumber(number, category,
                EMERGENCY_CALL_ROUTING_UNKNOWN);
        when(result.getEmergencyNumberSources()).thenReturn(List.of(source));
        return result;
    }

    private void assertSelection(String country, String expected, EmergencyNumber... candidates) {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getNetworkCountryIso()).thenReturn(country);
        addEmergencyNumbersToTelephony(candidates);
        mUtils = new EmergencyNumberUtils(mContext);
        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(expected);
    }
}
