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

import static android.telephony.emergency.EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_NUMBER_SOURCE_SIM;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Util class to help manage emergency numbers
 */
public class EmergencyNumberUtils {
    private static final String TAG = "EmergencyNumberUtils";

    public static final Uri EMERGENCY_NUMBER_OVERRIDE_AUTHORITY = new Uri.Builder().scheme(
            ContentResolver.SCHEME_CONTENT)
            .authority("com.android.emergency.gesture")
            .build();
    public static final String METHOD_NAME_GET_EMERGENCY_NUMBER_OVERRIDE =
            "GET_EMERGENCY_NUMBER_OVERRIDE";
    public static final String METHOD_NAME_SET_EMERGENCY_NUMBER_OVERRIDE =
            "SET_EMERGENCY_NUMBER_OVERRIDE";
    public static final String METHOD_NAME_SET_EMERGENCY_GESTURE = "SET_EMERGENCY_GESTURE";
    public static final String METHOD_NAME_SET_EMERGENCY_GESTURE_UI_SHOWING =
            "SET_EMERGENCY_GESTURE_UI_SHOWING";
    public static final String METHOD_NAME_SET_EMERGENCY_SOUND = "SET_EMERGENCY_SOUND";
    public static final String METHOD_NAME_GET_EMERGENCY_GESTURE_ENABLED = "GET_EMERGENCY_GESTURE";
    public static final String METHOD_NAME_GET_EMERGENCY_GESTURE_SOUND_ENABLED =
            "GET_EMERGENCY_SOUND";
    public static final String EMERGENCY_GESTURE_CALL_NUMBER = "emergency_gesture_call_number";
    public static final String EMERGENCY_GESTURE_UI_SHOWING_VALUE =
            "emergency_gesture_ui_showing_value";
    public static final String EMERGENCY_SETTING_VALUE = "emergency_setting_value";
    public static final int EMERGENCY_SETTING_ON = 1;
    public static final int EMERGENCY_SETTING_OFF = 0;

    @VisibleForTesting
    static final String FALL_BACK_NUMBER = "112";

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final CarrierConfigManager mCarrierConfigManager;

    public EmergencyNumberUtils(Context context) {
        mContext = context;
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            mTelephonyManager = context.getSystemService(TelephonyManager.class);
            mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        } else {
            mTelephonyManager = null;
            mCarrierConfigManager = null;
        }
    }

    /**
     * Returns the preferred SOS number (which need not be police-only).
     */
    public String getDefaultPoliceNumber() {
        final List<Candidate> candidates = getPromotedEmergencyNumbers();
        if (candidates.isEmpty()) {
            return FALL_BACK_NUMBER;
        }
        return candidates.get(0).number;
    }

    /**
     * Returns the number chosen by user. If user has not provided any number, use default ({@link
     * #getDefaultPoliceNumber()}).
     */
    public String getPoliceNumber() {
        final String userProvidedNumber = getEmergencyNumberOverride();
        return TextUtils.isEmpty(userProvidedNumber)
                ? getDefaultPoliceNumber() : userProvidedNumber;
    }

    /**
     * Sets device-local emergency number override
     */
    public void setEmergencyNumberOverride(String number) {
        final Bundle bundle = new Bundle();
        bundle.putString(EMERGENCY_GESTURE_CALL_NUMBER, number);
        mContext.getContentResolver().call(EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_SET_EMERGENCY_NUMBER_OVERRIDE, null /* args */, bundle);
    }

    /**
     * Enable/disable the emergency gesture setting
     */
    public void setEmergencyGestureEnabled(boolean enabled) {
        final Bundle bundle = new Bundle();
        bundle.putInt(EMERGENCY_SETTING_VALUE,
                enabled ? EMERGENCY_SETTING_ON : EMERGENCY_SETTING_OFF);
        mContext.getContentResolver().call(EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_SET_EMERGENCY_GESTURE, null /* args */, bundle);
    }

    /**
     * Enable/disable the emergency gesture sound setting
     */
    public void setEmergencySoundEnabled(boolean enabled) {
        final Bundle bundle = new Bundle();
        bundle.putInt(EMERGENCY_SETTING_VALUE,
                enabled ? EMERGENCY_SETTING_ON : EMERGENCY_SETTING_OFF);
        mContext.getContentResolver().call(EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_SET_EMERGENCY_SOUND, null /* args */, bundle);
    }

    /**
     * Whether or not emergency gesture is enabled.
     */
    public boolean getEmergencyGestureEnabled() {
        final Bundle bundle = mContext.getContentResolver().call(
                EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_GET_EMERGENCY_GESTURE_ENABLED, null /* args */, null /* bundle */);
        return bundle == null ? true : bundle.getInt(EMERGENCY_SETTING_VALUE, EMERGENCY_SETTING_ON)
                == EMERGENCY_SETTING_ON;
    }

    /**
     * Whether or not emergency gesture sound is enabled.
     */
    public boolean getEmergencyGestureSoundEnabled() {
        final Bundle bundle = mContext.getContentResolver().call(
                EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_GET_EMERGENCY_GESTURE_SOUND_ENABLED, null /* args */,
                null /* bundle */);
        return bundle == null ? true : bundle.getInt(EMERGENCY_SETTING_VALUE, EMERGENCY_SETTING_OFF)
                == EMERGENCY_SETTING_ON;
    }

    private String getEmergencyNumberOverride() {
        final Bundle bundle = mContext.getContentResolver().call(
                EMERGENCY_NUMBER_OVERRIDE_AUTHORITY,
                METHOD_NAME_GET_EMERGENCY_NUMBER_OVERRIDE, null /* args */, null /* bundle */);
        return bundle == null ? null : bundle.getString(EMERGENCY_GESTURE_CALL_NUMBER);
    }

    /**
     * Returns ranked candidates across subscriptions, retaining their metadata and duplicate records.
     * Returns an empty list when emergency number lists are unavailable.
     */
    public List<Candidate> getPromotedEmergencyNumbers() {
        if (mTelephonyManager == null) {
            return new ArrayList<>();
        }
        // Country preferences must see all categories: e.g. the database labels TR 112
        // ambulance-only, even though it is the country's preferred SOS number.
        Map<Integer, List<EmergencyNumber>> allLists;
        try {
            allLists = mTelephonyManager.getEmergencyNumberList();
        } catch (IllegalStateException | UnsupportedOperationException e) {
            Log.w(TAG, "Emergency number lists unavailable", e);
            return new ArrayList<>();
        }
        if (allLists == null || allLists.isEmpty()) {
            Log.w(TAG, "Unable to retrieve emergency number lists!");
            return new ArrayList<>();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<Integer, List<EmergencyNumber>> entry : allLists.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            int subId = entry.getKey();
            Log.d(TAG, "Emergency numbers for subscription id " + entry.getKey());
            String[] prefixes = getCarrierEmergencyNumberPrefixes(mCarrierConfigManager, subId);
            for (EmergencyNumber number : entry.getValue()) {
                Log.d(TAG, "Number " + number);
                candidates.add(new Candidate(number, removePrefix(number, prefixes)));
            }
        }
        if (candidates.isEmpty()) {
            Log.w(TAG, "No promoted emergency number found!");
        }
        String country = new EmergencyCountryResolver(mTelephonyManager).resolve();
        return rankEmergencyNumbers(candidates, country);
    }

    /** A number with its carrier prefix removed, alongside its original emergency number metadata. */
    public record Candidate(EmergencyNumber metadata, String number) {}

    /**
     * Returns a new ranked list, restricting it to primary numbers when any are present.
     * Does not modify the input; ties retain input order. Country is a case insensitive ISO code,
     * or null or empty when unknown. Callers must remove carrier prefixes before ranking.
     */
    public static List<Candidate> rankEmergencyNumbers(List<Candidate> candidates, String country) {
        country = country == null ? "" : country.toUpperCase(Locale.ROOT);
        Set<String> preferences = EmergencyNumberPreferences.getCountryPreferences(country);
        Set<String> primary = EmergencyNumberPreferences.getPrimaryNumbers(country);
        List<Candidate> preferred = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (primary.contains(candidate.number)) {
                preferred.add(candidate);
            }
        }
        // Restrict to primary candidates only when at least one is present.
        if (preferred.isEmpty()) {
            preferred.addAll(candidates);
        }
        // Rank by country preference membership, category, then source, including
        // when no table entry matches. Routing is not a ranking/filtering criterion.
        // Stable sorting preserves input order for ties; never mutate Telephony's list.
        preferred.sort(Comparator
                .comparingInt((Candidate candidate) ->
                        preferences.contains(candidate.number) ? 0 : 1)
                .thenComparingInt(candidate -> preferenceRank(
                        candidate.metadata.getEmergencyServiceCategories(),
                        EMERGENCY_SERVICE_CATEGORY_POLICE, EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                        EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE))
                .thenComparingInt(candidate -> preferenceRank(
                        candidate.metadata.getEmergencyNumberSources(),
                        EMERGENCY_NUMBER_SOURCE_DATABASE, EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING,
                        EMERGENCY_NUMBER_SOURCE_SIM)));
        return preferred;
    }

    private static int preferenceRank(List<Integer> values, int... preferences) {
        for (int i = 0; i < preferences.length; i++) {
            if (values.contains(preferences[i])) {
                return i;
            }
        }
        return preferences.length;
    }

    private String removePrefix(EmergencyNumber emergencyNumber, String[] prefixes) {
        String number = emergencyNumber.getNumber();
        if (prefixes == null || prefixes.length == 0) {
            return number;
        }
        for (String prefix : prefixes) {
            int prefixStartIndex = number.indexOf(prefix);
            if (prefixStartIndex != 0) {
                continue;
            }
            Log.d(TAG, "Removing prefix " + prefix + " from " + number);
            return number.substring(prefix.length());
        }
        return number;
    }

    private static String[] getCarrierEmergencyNumberPrefixes(
            CarrierConfigManager carrierConfigManager, int subId) {
        PersistableBundle b = carrierConfigManager.getConfigForSubId(subId);
        return b == null
                ? null
                : b.getStringArray(CarrierConfigManager.KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY);
    }
}
