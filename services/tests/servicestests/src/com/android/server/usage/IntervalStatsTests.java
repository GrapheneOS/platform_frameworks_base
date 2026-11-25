/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.usage;

import static android.app.usage.UsageEvents.Event.MAX_EVENT_TYPE;
import static android.app.usage.UsageEvents.Event.USER_INTERACTION;
import static android.appwidget.flags.Flags.FLAG_ENGAGEMENT_METRICS;

import static com.android.server.usage.PackagesTokenData.UNASSIGNED_TOKEN;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.res.Configuration;
import android.os.PersistableBundle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.ArrayUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class IntervalStatsTests {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int NUMBER_OF_PACKAGES = 7;
    private static final int NUMBER_OF_EVENTS_PER_PACKAGE = 200;
    private static final int NUMBER_OF_EVENTS = NUMBER_OF_PACKAGES * NUMBER_OF_EVENTS_PER_PACKAGE;
    private static final String EXTRA_KEY_INT_ARRAY = "com.example.extra.int_array";
    private static final String EXTRA_KEY_STRING = "com.example.extra.string";
    private static final String EXTRA_KEY_BUNDLE = "com.example.extra.bundle";

    private void populateIntervalStats(IntervalStats intervalStats) {
        final int timeProgression = 23;
        long time = System.currentTimeMillis() - (NUMBER_OF_EVENTS * timeProgression);

        intervalStats.majorVersion = 7;
        intervalStats.minorVersion = 8;
        intervalStats.beginTime = time;
        intervalStats.interactiveTracker.count = 2;
        intervalStats.interactiveTracker.duration = 111111;
        intervalStats.nonInteractiveTracker.count = 3;
        intervalStats.nonInteractiveTracker.duration = 222222;
        intervalStats.keyguardShownTracker.count = 4;
        intervalStats.keyguardShownTracker.duration = 333333;
        intervalStats.keyguardHiddenTracker.count = 5;
        intervalStats.keyguardHiddenTracker.duration = 4444444;

        for (int i = 0; i < NUMBER_OF_EVENTS; i++) {
            UsageEvents.Event event = new UsageEvents.Event();
            final int packageInt = ((i / 3) % NUMBER_OF_PACKAGES); // clusters of 3 events
            event.mPackage = "fake.package.name" + packageInt;
            if (packageInt == 3) {
                // Third app is an instant app
                event.mFlags |= UsageEvents.Event.FLAG_IS_PACKAGE_INSTANT_APP;
            }

            final int instanceId = i % 11;
            event.mClass = ".fake.class.name" + instanceId;
            event.mTimeStamp = time;
            event.mEventType = i % (MAX_EVENT_TYPE + 1); //"random" event type
            event.mInstanceId = instanceId;

            final int rootPackageInt = (i % 5); // 5 "apps" start each task
            event.mTaskRootPackage = "fake.package.name" + rootPackageInt;

            final int rootClassInt = i % 6;
            event.mTaskRootClass = ".fake.class.name" + rootClassInt;

            switch (event.mEventType) {
                case UsageEvents.Event.CONFIGURATION_CHANGE:
                    event.mConfiguration = new Configuration(); //empty config
                    break;
                case UsageEvents.Event.SHORTCUT_INVOCATION:
                    event.mShortcutId = "shortcut" + (i % 8); //"random" shortcut
                    break;
                case UsageEvents.Event.STANDBY_BUCKET_CHANGED:
                    //"random" bucket and reason
                    event.mBucketAndReason = (((i % 5 + 1) * 10) << 16) & (i % 5 + 1) << 8;
                    break;
                case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                    event.mNotificationChannelId = "channel" + (i % 5); //"random" channel
                    break;
                case UsageEvents.Event.LOCUS_ID_SET:
                    event.mLocusId = "locus" + (i % 7); //"random" locus
                    break;
                case UsageEvents.Event.USER_INTERACTION:
                    // "random" user interaction extras.
                    final PersistableBundle extras = new PersistableBundle();
                    extras.putString(UsageStatsManager.EXTRA_EVENT_CATEGORY,
                            "fake.namespace.category" + (i % 13));
                    extras.putString(UsageStatsManager.EXTRA_EVENT_ACTION,
                            "fakeaction" + (i % 13));
                    extras.putIntArray(EXTRA_KEY_INT_ARRAY,
                        new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
                    final char[] longString = new char[256];
                    Arrays.fill(longString, 'a');
                    extras.putString(EXTRA_KEY_STRING, new String(longString));
                    extras.putPersistableBundle(EXTRA_KEY_BUNDLE, new PersistableBundle());
                    event.mExtras = extras;
                    break;
            }

            intervalStats.addEvent(event);
            intervalStats.update(event.mPackage, event.mClass, event.mTimeStamp, event.mEventType,
                    event.mInstanceId);

            time += timeProgression; // Arbitrary progression of time
        }

        final Configuration config1 = new Configuration();
        config1.fontScale = 3.3f;
        config1.mcc = 4;
        intervalStats.getOrCreateConfigurationStats(config1);

        final Configuration config2 = new Configuration();
        config2.mnc = 5;
        config2.setLocale(new Locale("en", "US"));
        intervalStats.getOrCreateConfigurationStats(config2);

        intervalStats.activeConfiguration = config2;
    }

    @Test
    public void testObfuscation() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);

        // data is populated with 7 different "apps"
        assertThat(packagesTokenData.tokensToPackagesMap.size()).isEqualTo(NUMBER_OF_PACKAGES);
        assertThat(packagesTokenData.packagesToTokensMap.size()).isEqualTo(NUMBER_OF_PACKAGES);
        assertThat(packagesTokenData.counter).isEqualTo(NUMBER_OF_PACKAGES + 1);

        assertThat(intervalStats.events.size()).isEqualTo(NUMBER_OF_EVENTS);
        assertThat(intervalStats.packageStats.size()).isEqualTo(NUMBER_OF_PACKAGES);
    }

    @Test
    public void testDeobfuscation() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);
        intervalStats.deobfuscateData(packagesTokenData);

        // ensure deobfuscation doesn't update any of the mappings data
        assertThat(packagesTokenData.tokensToPackagesMap.size()).isEqualTo(NUMBER_OF_PACKAGES);
        assertThat(packagesTokenData.packagesToTokensMap.size()).isEqualTo(NUMBER_OF_PACKAGES);
        assertThat(packagesTokenData.counter).isEqualTo(NUMBER_OF_PACKAGES + 1);

        // ensure deobfuscation didn't remove any events or usage stats
        assertThat(intervalStats.events.size()).isEqualTo(NUMBER_OF_EVENTS);
        assertThat(intervalStats.packageStats.size()).isEqualTo(NUMBER_OF_PACKAGES);
    }

    @RequiresFlagsEnabled(FLAG_ENGAGEMENT_METRICS)
    @Test
    public void testObfuscation_userInteractionExtras() throws Exception {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);

        // validate user interaction extras
        for (int i = 0; i < intervalStats.events.size(); i++) {
            UsageEvents.Event event = intervalStats.events.get(i);
            if (event.mEventType != USER_INTERACTION) continue;
            assertThat(event.mUserInteractionExtrasToken).isNotNull();
            assertThat(event.mUserInteractionExtrasToken.mActionToken).isNotEqualTo(
                UNASSIGNED_TOKEN);
            assertThat(event.mUserInteractionExtrasToken.mCategoryToken).isNotEqualTo(
                UNASSIGNED_TOKEN);
            assertThat(event.mUserInteractionExtrasToken.mTokenizedExtras).isNotNull();
            PersistableBundle tokenizedExtras = PersistableBundle.readFromStream(
                new ByteArrayInputStream(event.mUserInteractionExtrasToken.mTokenizedExtras));
            // Bundle key should be omitted from tokenized extras.
            assertThat(tokenizedExtras.size()).isEqualTo(2);
            int packageToken = packagesTokenData.getPackageTokenOrAdd(event.getPackageName(),
                System.currentTimeMillis());
            for (String keyToken : tokenizedExtras.keySet()) {
                String key = packagesTokenData.getString(packageToken, Integer.parseInt(keyToken));
                assertThat(key).isNotNull();
                switch (key) {
                    case EXTRA_KEY_INT_ARRAY:
                        // int array is truncated
                        assertThat(tokenizedExtras.getIntArray(keyToken)).hasLength(10);
                        break;
                    case EXTRA_KEY_STRING:
                        // String is truncated
                        assertThat(tokenizedExtras.getString(keyToken)).hasLength(127);
                        break;
                    default:
                        throw new Exception("Unexpected key " + key + " in tokenized extras");
                }
            }
        }
    }

    @RequiresFlagsEnabled(FLAG_ENGAGEMENT_METRICS)
    @Test
    public void testDeobfuscation_userInteractionExtras() throws Exception {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);
        intervalStats.deobfuscateData(packagesTokenData);

        // verify user interaction extras are present after deobfuscation
        for (int i = 0; i < intervalStats.events.size(); i++) {
            UsageEvents.Event event = intervalStats.events.get(i);
            if (event.mEventType != USER_INTERACTION) continue;
            assertThat(event.mExtras).isNotNull();
            PersistableBundle extras = event.mExtras;
            // Bundle key is omitted
            assertThat(extras.keySet()).containsExactly(UsageStatsManager.EXTRA_EVENT_CATEGORY,
                UsageStatsManager.EXTRA_EVENT_ACTION, EXTRA_KEY_INT_ARRAY, EXTRA_KEY_STRING);
            int[] array = extras.getIntArray(EXTRA_KEY_INT_ARRAY);
            assertThat(array).hasLength(10);
            for (int j : array) {
                assertThat(array[j]).isEqualTo(j);
            }
            String string = extras.getString(EXTRA_KEY_STRING);
            assertThat(string).hasLength(127);
            for (char c : string.toCharArray()) {
                assertThat(c).isEqualTo('a');
            }
        }
    }

    @Test
    public void testBadDataOnDeobfuscation() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);
        intervalStats.packageStats.clear();

        // remove the mapping for token 2
        packagesTokenData.tokensToPackagesMap.remove(2);

        intervalStats.deobfuscateData(packagesTokenData);
        // deobfuscation should have removed all events mapped to package token 2
        assertThat(intervalStats.events.size())
                .isEqualTo(NUMBER_OF_EVENTS - NUMBER_OF_EVENTS_PER_PACKAGE - 1);
        assertThat(intervalStats.packageStats.size()).isEqualTo(NUMBER_OF_PACKAGES - 1);
    }

    @Test
    public void testBadDataOnDeobfuscation_keepsOrderOfRemainingEvents() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);

        final int removedPackageToken = 2;
        final ArrayList<UsageEvents.Event> expectedEvents = new ArrayList<>();
        for (int i = 0; i < intervalStats.events.size(); i++) {
            final UsageEvents.Event event = intervalStats.events.get(i);
            if (event.mPackageToken != removedPackageToken) {
                expectedEvents.add(event);
            }
        }
        assertThat(expectedEvents.size()).isLessThan(NUMBER_OF_EVENTS);

        packagesTokenData.tokensToPackagesMap.remove(removedPackageToken);

        assertThat(intervalStats.deobfuscateData(packagesTokenData)).isTrue();
        // the events of the removed package are interleaved with the events of the other packages;
        // none of the other events should have been removed or reordered
        assertThat(intervalStats.events.size()).isEqualTo(expectedEvents.size());
        for (int i = 0; i < expectedEvents.size(); i++) {
            assertThat(intervalStats.events.get(i)).isSameInstanceAs(expectedEvents.get(i));
        }
    }

    @Test
    public void testBadPackageDataOnDeobfuscation() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);
        intervalStats.packageStats.clear();

        // remove mapping number 2 within package 3 (random)
        packagesTokenData.tokensToPackagesMap.valueAt(3).remove(2);

        intervalStats.deobfuscateData(packagesTokenData);
        // deobfuscation should not have removed all events for a package - however, it's possible
        // that some events were removed because of how shortcut and notification events are handled
        assertThat(intervalStats.events.size())
                .isGreaterThan(NUMBER_OF_EVENTS - NUMBER_OF_EVENTS_PER_PACKAGE);
        assertThat(intervalStats.packageStats.size()).isEqualTo(NUMBER_OF_PACKAGES);
    }

    // All fields in this list are defined in IntervalStats and persisted - please ensure they're
    // defined correctly in both usagestatsservice.proto and usagestatsservice_v2.proto
    private static final String[] INTERVALSTATS_PERSISTED_FIELDS = {"beginTime", "endTime",
            "mStringCache", "majorVersion", "minorVersion", "interactiveTracker",
            "nonInteractiveTracker", "keyguardShownTracker", "keyguardHiddenTracker",
            "packageStats", "configurations", "activeConfiguration", "events"};
    // All fields in this list are defined in IntervalStats but not persisted
    private static final String[] INTERVALSTATS_IGNORED_FIELDS = {"lastTimeSaved",
            "packageStatsObfuscated", "CURRENT_MAJOR_VERSION", "CURRENT_MINOR_VERSION", "TAG",
            "MAX_EXTRA_ARRAY_LENGTH"};

    @Test
    public void testIntervalStatsFieldsAreKnown() {
        for (Field field : IntervalStats.class.getDeclaredFields()) {
            final String fieldName = field.getName();
            if (!(ArrayUtils.contains(INTERVALSTATS_PERSISTED_FIELDS, fieldName)
                    || ArrayUtils.contains(INTERVALSTATS_IGNORED_FIELDS, fieldName))) {
                assertWithMessage("Found an unknown field: " + fieldName
                        + ". Please correctly update either INTERVALSTATS_PERSISTED_FIELDS"
                        + " or INTERVALSTATS_IGNORED_FIELDS.").fail();
            }
        }
    }
}
