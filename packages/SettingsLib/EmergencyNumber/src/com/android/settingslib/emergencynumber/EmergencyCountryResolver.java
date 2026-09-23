package com.android.settingslib.emergencynumber;

import android.telephony.TelephonyManager;

import java.util.Locale;
import java.util.function.Supplier;

/** Resolves one country for SOS ranking, independently of the calling subscription. */
final class EmergencyCountryResolver {
    private final TelephonyManager mTelephony;

    EmergencyCountryResolver(TelephonyManager telephony) {
        mTelephony = telephony;
    }

    String resolve() {
        return resolve(Locale.getDefault());
    }

    String resolve(Locale locale) {
        String country = readCountry(mTelephony::getNetworkCountryIso);
        if (country.isEmpty()) {
            int modemCount = mTelephony.getActiveModemCount();
            for (int slot = 0; slot < modemCount && country.isEmpty(); slot++) {
                int slotIndex = slot;
                country = readCountry(() -> mTelephony.getNetworkCountryIso(slotIndex));
            }
        }
        if (country.isEmpty()) {
            country = readCountry(mTelephony::getSimCountryIso);
        }
        if (!country.isEmpty()) {
            return country;
        }
        country = normalize(locale.getCountry());
        return country.isEmpty() ? "US" : country;
    }

    private static String readCountry(Supplier<String> reader) {
        try {
            return normalize(reader.get());
        } catch (IllegalStateException | UnsupportedOperationException | IllegalArgumentException e) {
            return "";
        }
    }

    private static String normalize(String country) {
        if (country == null || country.length() != 2) {
            return "";
        }
        country = country.toUpperCase(Locale.ROOT);
        for (int i = 0; i < country.length(); i++) {
            if (country.charAt(i) < 'A' || country.charAt(i) > 'Z') {
                return "";
            }
        }
        return country;
    }
}
