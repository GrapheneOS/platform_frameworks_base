/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.pm;

import static com.android.server.devicepolicy.DpmTestUtils.assertRestrictions;
import static com.android.server.devicepolicy.DpmTestUtils.newRestrictions;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.util.Pair;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link com.android.server.pm.UserRestrictionsUtils}.
 *
 * <p>Run with:<pre>
   atest UserRestrictionsUtilsTest
 * </pre>
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public final class UserRestrictionsUtilsTest {

    // List of restrictions applied to all methods
    private static final List<Pair<String, Boolean>> BASELINE_RESTRICTIONS = Arrays.asList(
            Pair.create(UserManager.DISALLOW_RECORD_AUDIO, false),
            Pair.create(UserManager.DISALLOW_WALLPAPER, false),
            Pair.create(UserManager.DISALLOW_ADJUST_VOLUME, true));

    private static final int TEST_USER_ID = 10;
    private static final int DEVICE_OWNER_USER_ID = 11;
    private static final String DISALLOW_DEBUGGING_FEATURES =
            UserManager.DISALLOW_DEBUGGING_FEATURES;

    @Rule
    public final Expect expect = Expect.create();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private UserManagerInternal mUserManagerInternal;
    @Mock
    private DevicePolicyManagerInternal mDevicePolicyManagerInternal;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Mock LocalServices
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mUserManagerInternal);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class, mDevicePolicyManagerInternal);

        // Mock Context to return UserManager. The test environment can sometimes confuse
        // getSystemService with getSystemServiceName, so we mock both to be safe.
        when(mContext.getSystemServiceName(UserManager.class)).thenReturn(Context.USER_SERVICE);
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
    }

    @Test
    public void testNonNull() {
        Bundle out = UserRestrictionsUtils.nonNull(null);
        expect.that(out).isNotNull();
        out.putBoolean("a", true); // Should not be Bundle.EMPTY.

        Bundle in = new Bundle();
        expect.that(UserRestrictionsUtils.nonNull(in)).isSameInstanceAs(in);
    }

    @Test
    public void testNetworkRestrictionMatchesOutgoingCallsPolicy() {
        expect.that(UserRestrictionsUtils.isValidRestriction(UserManager.DISALLOW_NETWORK))
                .isTrue();
        expect.that(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_NETWORK))
                .isTrue();
        for (boolean mainUser : new boolean[] {false, true}) {
            for (boolean organizationOwned : new boolean[] {false, true}) {
                expect.that(UserRestrictionsUtils.canProfileOwnerChange(
                        UserManager.DISALLOW_NETWORK, mainUser, organizationOwned)).isEqualTo(
                                UserRestrictionsUtils.canProfileOwnerChange(
                                        UserManager.DISALLOW_OUTGOING_CALLS,
                                        mainUser, organizationOwned));
            }
        }
        expect.that(UserRestrictionsUtils.isGlobal(UserManagerInternal.OWNER_TYPE_DEVICE_OWNER,
                UserManager.DISALLOW_NETWORK)).isFalse();
        expect.that(UserRestrictionsUtils.canFinancedDeviceOwnerChange(UserManager.DISALLOW_NETWORK))
                .isEqualTo(UserRestrictionsUtils.canFinancedDeviceOwnerChange(
                        UserManager.DISALLOW_OUTGOING_CALLS));
        expect.that(UserRestrictionsUtils.canParentOfProfileOwnerOfOrganizationOwnedDeviceChange(
                UserManager.DISALLOW_NETWORK)).isEqualTo(
                        UserRestrictionsUtils.canParentOfProfileOwnerOfOrganizationOwnedDeviceChange(
                                UserManager.DISALLOW_OUTGOING_CALLS));
    }

    @Test
    public void testMerge() {
        Bundle a = newRestrictions("a", "d");
        Bundle b = newRestrictions("b", "d", "e");

        UserRestrictionsUtils.merge(a, b);

        assertRestrictions(newRestrictions("a", "b", "d", "e"), a);

        UserRestrictionsUtils.merge(a, null);

        assertRestrictions(newRestrictions("a", "b", "d", "e"), a);

        assertThrows(IllegalArgumentException.class, () -> UserRestrictionsUtils.merge(a, a));
    }

    private static final List<Pair<String, Boolean>> CAN_DO_CHANGE =
            new ImmutableList.Builder<Pair<String, Boolean>>()
            .addAll(BASELINE_RESTRICTIONS)
            .add(Pair.create(UserManager.DISALLOW_ADD_USER, true))
            .add(Pair.create(UserManager.DISALLOW_USER_SWITCH, true))
            .build();

    @Test
    public void testCanDeviceOwnerChange() {
        for (var pair : CAN_DO_CHANGE) {
            var key = pair.first;
            var expectedResult = pair.second;
            var result = UserRestrictionsUtils.canDeviceOwnerChange(key);
            expect.withMessage("canDeviceOwnerChange(%s)", key)
                    .that(result)
                    .isEqualTo(expectedResult);
        }
    }

    private static final List<Pair<String, Boolean>> CAN_PO_CHANGE__MAIN_USER =
            new ImmutableList.Builder<Pair<String, Boolean>>()
            .addAll(BASELINE_RESTRICTIONS)
            .add(Pair.create(UserManager.DISALLOW_ADD_USER, true))
            .add(Pair.create(UserManager.DISALLOW_USER_SWITCH, false))
            .build();

    @Test
    public void testCanProfileOwnerChange_mainUser() {
        for (var pair : CAN_PO_CHANGE__MAIN_USER) {
            var key = pair.first;
            var expectedResult = pair.second;
            var result = UserRestrictionsUtils.canProfileOwnerChange(key,
                    /* isMainUser= */ true,
                    /* isProfileOwnerOnOrgOwnedDevice= */ false);
            expect.withMessage("canProfileOwnerChange(%s)", key)
                    .that(result)
                    .isEqualTo(expectedResult);
        }
    }

    private static final List<Pair<String, Boolean>> CAN_PO_CHANGE__NOT_MAIN_USER =
            new ImmutableList.Builder<Pair<String, Boolean>>()
            .addAll(BASELINE_RESTRICTIONS)
            .add(Pair.create(UserManager.DISALLOW_ADD_USER, false))
            .add(Pair.create(UserManager.DISALLOW_USER_SWITCH, false))
            .build();

    @Test
    public void testCanProfileOwnerChange_notMainUser() {
        for (var pair : CAN_PO_CHANGE__NOT_MAIN_USER) {
            var key = pair.first;
            var expectedResult = pair.second;
            var result = UserRestrictionsUtils.canProfileOwnerChange(key,
                    /* isMainUser= */ false,
                    /* isProfileOwnerOnOrgOwnedDevice= */ false);
            expect.withMessage("canProfileOwnerChange(%s)", key)
                    .that(result)
                    .isEqualTo(expectedResult);
        }
    }

    // These restrictions are only allowed when isProfileOwnerOnOrgOwnedDevice is true, regardless
    // of the other arguments
    private static final String[] CAN_PO_CHANGE__ALWAYS_REQUIRES_ORG_OWNER = {
            UserManager.DISALLOW_SIM_GLOBALLY,
    };

    @Test
    public void testCanProfileOwnerChange_restrictionRequiresOrgOwnedDevice_orgOwned() {
        for (String key: CAN_PO_CHANGE__ALWAYS_REQUIRES_ORG_OWNER) {
            expect.withMessage("canProfileOwnerChange(%s, notMainUser, orgOwned)", key)
                    .that(UserRestrictionsUtils.canProfileOwnerChange(key,
                            /* isMainUser= */ false,
                            /* isProfileOwnerOnOrgOwnedDevice= */ true))
                    .isTrue();
            expect.withMessage("canProfileOwnerChange(%s, mainUser, orgOwned)", key)
                    .that(UserRestrictionsUtils.canProfileOwnerChange(key,
                            /* isMainUser= */ true,
                            /* isProfileOwnerOnOrgOwnedDevice= */ true))
                    .isTrue();
        }
    }

    @Test
    public void testCanProfileOwnerChange_restrictionRequiresOrgOwnedDevice_notOrgOwned() {
        for (String key: CAN_PO_CHANGE__ALWAYS_REQUIRES_ORG_OWNER) {
            expect.withMessage("canProfileOwnerChange(%s, notMainUser, notOrgOwned)", key)
                    .that(UserRestrictionsUtils.canProfileOwnerChange(key,
                            /* isMainUser= */ false,
                            /* isProfileOwnerOnOrgOwnedDevice= */ false))
                    .isFalse();
            expect.withMessage("canProfileOwnerChange(%s, mainUser, notOrgOwned)", key)
                    .that(UserRestrictionsUtils.canProfileOwnerChange(key,
                            /* isMainUser= */ true,
                            /* isProfileOwnerOnOrgOwnedDevice= */ false))
                    .isFalse();
        }
    }

    // These restrictions are allowed regardless of the arguments
    private static final String[] CAN_PO_CHANGE__DONT_REQUIRES_ORG_OWNER = {
            UserManager.DISALLOW_ADJUST_VOLUME,
    };

    @Test
    public void testCanProfileOwnerChange_restrictionNotRequiresOrgOwnedDevice_orgOwned() {
        for (String key: CAN_PO_CHANGE__DONT_REQUIRES_ORG_OWNER) {
            expect.withMessage("canProfileOwnerChange(%s, notMainUser, orgOwned)", key)
                    .that(UserRestrictionsUtils.canProfileOwnerChange(key,
                            /* isMainUser= */ false,
                            /* isProfileOwnerOnOrgOwnedDevice= */ true))
                    .isTrue();
            expect.withMessage("canProfileOwnerChange(%s, mainUser, orgOwned)", key)
                    .that(UserRestrictionsUtils.canProfileOwnerChange(key,
                            /* isMainUser= */ true,
                            /* isProfileOwnerOnOrgOwnedDevice= */ true))
                    .isTrue();
        }
    }

    @Test
    public void testCanProfileOwnerChange_restrictionNotRequiresOrgOwnedDevice_notOrgOwned() {
        for (String key: CAN_PO_CHANGE__DONT_REQUIRES_ORG_OWNER) {
            expect.withMessage("canProfileOwnerChange(%s, notMainUser, notOrgOwned)", key)
                    .that(UserRestrictionsUtils.canProfileOwnerChange(key,
                            /* isMainUser= */ false,
                            /* isProfileOwnerOnOrgOwnedDevice= */ false))
                    .isTrue();
            expect.withMessage("canProfileOwnerChange(%s, mainUser, notOrgOwned)", key)
                    .that(UserRestrictionsUtils.canProfileOwnerChange(key,
                            /* isMainUser= */ true,
                            /* isProfileOwnerOnOrgOwnedDevice= */ false))
                    .isTrue();
        }
    }

    @Test
    public void testMoveRestriction() {
        SparseArray<RestrictionsSet> localRestrictions = new SparseArray<>();
        RestrictionsSet globalRestrictions = new RestrictionsSet();

        // User 0 has only local restrictions, nothing should change.
        localRestrictions.put(0, newRestrictions(0, UserManager.DISALLOW_ADJUST_VOLUME));
        // User 1 has a local restriction to be moved to global and some global already. Local
        // restrictions should be removed for this user.
        localRestrictions.put(1, newRestrictions(1, UserManager.ENSURE_VERIFY_APPS));
        globalRestrictions.updateRestrictions(1,
                newRestrictions(UserManager.DISALLOW_ADD_USER));
        // User 2 has a local restriction to be moved and one to leave local.
        localRestrictions.put(2, newRestrictions(2,
                UserManager.ENSURE_VERIFY_APPS, UserManager.DISALLOW_CONFIG_VPN));

        UserRestrictionsUtils.moveRestriction(
                UserManager.ENSURE_VERIFY_APPS, localRestrictions, globalRestrictions);

        // Check user 0.
        assertRestrictions(
                newRestrictions(0, UserManager.DISALLOW_ADJUST_VOLUME),
                localRestrictions.get(0));
        expect.that(globalRestrictions.getRestrictions(0)).isNull();

        // Check user 1.
        expect.that(localRestrictions.get(1).isEmpty()).isTrue();
        assertRestrictions(
                newRestrictions(UserManager.ENSURE_VERIFY_APPS, UserManager.DISALLOW_ADD_USER),
                globalRestrictions.getRestrictions(1));

        // Check user 2.
        assertRestrictions(
                newRestrictions(2, UserManager.DISALLOW_CONFIG_VPN),
                localRestrictions.get(2));
        assertRestrictions(
                newRestrictions(UserManager.ENSURE_VERIFY_APPS),
                globalRestrictions.getRestrictions(2));
    }

    @Test
    public void testAreEqual() {
        expect.that(UserRestrictionsUtils.areEqual(
                null,
                null)).isTrue();

        expect.that(UserRestrictionsUtils.areEqual(
                null,
                Bundle.EMPTY)).isTrue();

        expect.that(UserRestrictionsUtils.areEqual(
                Bundle.EMPTY,
                null)).isTrue();

        expect.that(UserRestrictionsUtils.areEqual(
                Bundle.EMPTY,
                Bundle.EMPTY)).isTrue();

        expect.that(UserRestrictionsUtils.areEqual(
                new Bundle(),
                Bundle.EMPTY)).isTrue();

        expect.that(UserRestrictionsUtils.areEqual(
                null,
                newRestrictions("a"))).isFalse();

        expect.that(UserRestrictionsUtils.areEqual(
                newRestrictions("a"),
                null)).isFalse();

        expect.that(UserRestrictionsUtils.areEqual(
                newRestrictions("a"),
                newRestrictions("a"))).isTrue();

        expect.that(UserRestrictionsUtils.areEqual(
                newRestrictions("a"),
                newRestrictions("a", "b"))).isFalse();

        expect.that(UserRestrictionsUtils.areEqual(
                newRestrictions("a", "b"),
                newRestrictions("a"))).isFalse();

        expect.that(UserRestrictionsUtils.areEqual(
                newRestrictions("b", "a"),
                newRestrictions("a", "a"))).isFalse();

        // Make sure false restrictions are handled correctly.
        Bundle a = newRestrictions("a");
        a.putBoolean("b", true);

        Bundle b = newRestrictions("a");
        b.putBoolean("b", false);

        expect.that(UserRestrictionsUtils.areEqual(a, b)).isFalse();
        expect.that(UserRestrictionsUtils.areEqual(b, a)).isFalse();
    }

    @Test
    public void isSettingRestrictedForUser_adbEnabled_restrictedBySystemUser() {
        // GIVEN the DISALLOW_DEBUGGING_FEATURES restriction is set on the system user
        when(mUserManagerInternal.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(UserHandle.USER_SYSTEM))).thenReturn(true);

        // WHEN checking if the ADB_ENABLED setting is restricted
        boolean isRestricted = UserRestrictionsUtils.isSettingRestrictedForUser(mContext,
                Settings.Global.ADB_ENABLED, TEST_USER_ID, "1", Process.SYSTEM_UID,
                UserHandle.USER_NULL);

        // THEN the setting should be restricted
        expect.that(isRestricted).isTrue();
    }

    @Test
    public void isSettingRestrictedForUser_adbEnabled_restrictedByDeviceOwner() {
        // GIVEN the DISALLOW_DEBUGGING_FEATURES restriction is set on the device owner
        when(mUserManagerInternal.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(UserHandle.USER_SYSTEM))).thenReturn(false);
        when(mUserManagerInternal.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(DEVICE_OWNER_USER_ID))).thenReturn(true);

        // WHEN checking if the ADB_ENABLED setting is restricted
        boolean isRestricted = UserRestrictionsUtils.isSettingRestrictedForUser(mContext,
                Settings.Global.ADB_ENABLED, TEST_USER_ID, "1", Process.SYSTEM_UID,
                DEVICE_OWNER_USER_ID);

        // THEN the setting should be restricted
        expect.that(isRestricted).isTrue();
    }

    @Test
    public void isSettingRestrictedForUser_adbEnabled_notRestrictedGlobally() {
        // GIVEN no global restrictions for DISALLOW_DEBUGGING_FEATURES are set
        when(mUserManagerInternal.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(UserHandle.USER_SYSTEM))).thenReturn(false);
        // AND the restriction is not set for the current user
        when(mUserManager.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), any(UserHandle.class))).thenReturn(false);

        // WHEN checking if the ADB_ENABLED setting is restricted
        boolean isRestricted = UserRestrictionsUtils.isSettingRestrictedForUser(mContext,
                Settings.Global.ADB_ENABLED, TEST_USER_ID, "1", Process.SYSTEM_UID,
                UserHandle.USER_NULL);

        // THEN the setting should not be restricted
        expect.that(isRestricted).isFalse();
    }

    @Test
    public void isSettingRestrictedForUser_adbEnabled_restrictedForCurrentUser() {
        // GIVEN no global restrictions for DISALLOW_DEBUGGING_FEATURES are set
        when(mUserManagerInternal.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(UserHandle.USER_SYSTEM))).thenReturn(false);
        // BUT the restriction is set for the current user
        when(mUserManager.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(UserHandle.of(TEST_USER_ID)))).thenReturn(true);

        // WHEN checking if the ADB_ENABLED setting is restricted
        boolean isRestricted = UserRestrictionsUtils.isSettingRestrictedForUser(mContext,
                Settings.Global.ADB_ENABLED, TEST_USER_ID, "1", Process.SYSTEM_UID,
                UserHandle.USER_NULL);

        // THEN the setting should be restricted
        expect.that(isRestricted).isTrue();
    }

    @Test
    public void isSettingRestrictedForUser_adbEnabled_restrictedButValueIsZero() {
        // GIVEN the DISALLOW_DEBUGGING_FEATURES restriction is set globally
        when(mUserManagerInternal.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(UserHandle.USER_SYSTEM))).thenReturn(true);

        // WHEN checking if the ADB_ENABLED setting is restricted for a value of "0" (disabled)
        boolean isRestricted = UserRestrictionsUtils.isSettingRestrictedForUser(mContext,
                Settings.Global.ADB_ENABLED, TEST_USER_ID, "0", Process.SYSTEM_UID,
                UserHandle.USER_NULL);

        // THEN the setting should NOT be restricted, as disabling is always allowed
        expect.that(isRestricted).isFalse();
    }

    @Test
    public void isSettingRestrictedForUser_adbEnabled_deviceOwnerIsSystemUser() {
        // GIVEN the device owner is the system user and has the restriction
        when(mUserManagerInternal.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(UserHandle.USER_SYSTEM))).thenReturn(true);

        // WHEN checking if the ADB_ENABLED setting is restricted
        boolean isRestricted = UserRestrictionsUtils.isSettingRestrictedForUser(mContext,
                Settings.Global.ADB_ENABLED, TEST_USER_ID, "1", Process.SYSTEM_UID,
                UserHandle.USER_SYSTEM);

        // THEN the setting should be restricted (and checked only once)
        expect.that(isRestricted).isTrue();
    }

    @Test
    public void isSettingRestrictedForUser_adbEnabled_deviceOwnerIsSystemUser_noRestriction() {
        // GIVEN the device owner is the system user but does NOT have the restriction
        when(mUserManagerInternal.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), eq(UserHandle.USER_SYSTEM))).thenReturn(false);
        when(mUserManager.hasUserRestriction(
                eq(DISALLOW_DEBUGGING_FEATURES), any(UserHandle.class))).thenReturn(false);

        // WHEN checking if the ADB_ENABLED setting is restricted
        boolean isRestricted = UserRestrictionsUtils.isSettingRestrictedForUser(mContext,
                Settings.Global.ADB_ENABLED, TEST_USER_ID, "1", Process.SYSTEM_UID,
                UserHandle.USER_SYSTEM);

        // THEN the setting should not be restricted
        expect.that(isRestricted).isFalse();
    }
}
