package com.android.server.permission.test

import android.os.UserManager
import com.android.server.pm.UserManagerInternal
import com.android.server.pm.UserRestrictionsUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserNetworkRestrictionPolicyTest {
    private val network = UserManager.DISALLOW_NETWORK
    private val calls = UserManager.DISALLOW_OUTGOING_CALLS

    @Test fun deviceAndProfileOwnerAuthorizationMatchesOutgoingCalls() {
        assertTrue(UserRestrictionsUtils.isValidRestriction(network))
        assertEquals(UserRestrictionsUtils.canDeviceOwnerChange(calls),
            UserRestrictionsUtils.canDeviceOwnerChange(network))
        for (mainUser in listOf(false, true)) {
            for (organizationOwned in listOf(false, true)) {
                assertEquals(UserRestrictionsUtils.canProfileOwnerChange(
                    calls, mainUser, organizationOwned),
                    UserRestrictionsUtils.canProfileOwnerChange(
                        network, mainUser, organizationOwned))
            }
        }
    }

    @Test fun specialOwnerGroupsMatchOutgoingCalls() {
        assertEquals(UserRestrictionsUtils.canFinancedDeviceOwnerChange(calls),
            UserRestrictionsUtils.canFinancedDeviceOwnerChange(network))
        assertEquals(UserRestrictionsUtils.canParentOfProfileOwnerOfOrganizationOwnedDeviceChange(
            calls), UserRestrictionsUtils.canParentOfProfileOwnerOfOrganizationOwnedDeviceChange(
                network))
    }

    @Test fun scopeClassificationMatchesOutgoingCallsForEveryOwnerType() {
        for (ownerType in listOf(UserManagerInternal.OWNER_TYPE_DEVICE_OWNER,
            UserManagerInternal.OWNER_TYPE_PROFILE_OWNER,
            UserManagerInternal.OWNER_TYPE_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE,
            UserManagerInternal.OWNER_TYPE_NO_OWNER)) {
            assertEquals(UserRestrictionsUtils.isGlobal(ownerType, calls),
                UserRestrictionsUtils.isGlobal(ownerType, network))
            assertEquals(UserRestrictionsUtils.isLocal(ownerType, calls),
                UserRestrictionsUtils.isLocal(ownerType, network))
        }
    }
}
