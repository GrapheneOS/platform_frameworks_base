package com.android.server.permission.test

import com.android.server.permission.access.permission.PermissionFlags as F
import com.android.server.permission.access.permission.UserNetworkPermissionPolicy as Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserNetworkPermissionPolicyTest {
    @Test fun grantedPermissionIsActuallyRevokedAndRestored() {
        val original = F.RUNTIME_GRANTED or F.USER_SET or F.USER_FIXED
        val blocked = Policy.update(original, original, true)
        assertFalse(F.isPermissionGranted(blocked))
        assertEquals(original, Policy.update(blocked, blocked, false))
    }

    @Test fun deniedPermissionStaysDeniedAfterRestore() {
        val original = F.USER_SET or F.USER_FIXED
        val blocked = Policy.update(original, original, true)
        assertFalse(F.isPermissionGranted(blocked))
        assertEquals(original, Policy.update(blocked, blocked, false))
    }

    @Test fun repeatedBlockingPreservesTheOriginalGrant() {
        val original = F.RUNTIME_GRANTED or F.USER_SET
        val first = Policy.update(original, original, true)
        val second = Policy.update(first, first, true)
        assertEquals(first, second)
        assertEquals(original, Policy.update(second, second, false))
    }

    @Test fun grantOnNewInstallIsSavedWithoutEnablingAccess() {
        val denied = Policy.update(0, F.USER_SET, true)
        val installed = Policy.update(denied, denied or F.RUNTIME_GRANTED, true,
            initializeInstallGrant = true)
        assertFalse(F.isPermissionGranted(installed))
        assertEquals(F.USER_SET or F.RUNTIME_GRANTED,
            Policy.update(installed, installed, false))
    }

    @Test fun laterGrantCannotOverwriteAnExistingDeniedBackup() {
        val original = F.USER_SET or F.USER_FIXED
        val blocked = Policy.update(original, original, true)
        val changed = Policy.update(blocked, blocked or F.RUNTIME_GRANTED, true)
        assertFalse(F.isPermissionGranted(changed))
        assertEquals(original, Policy.update(changed, changed, false))
    }

    @Test fun laterDenialCannotOverwriteAnExistingGrantedBackup() {
        val original = F.RUNTIME_GRANTED or F.USER_SET
        val blocked = Policy.update(original, original, true)
        val changed = Policy.update(blocked, blocked and F.RUNTIME_GRANTED.inv(), true)
        assertEquals(original, Policy.update(changed, changed, false))
    }

    @Test fun newInstallationCanInitializeADeniedDecision() {
        val provisional = Policy.update(F.RUNTIME_GRANTED, F.RUNTIME_GRANTED, true)
        val installed = Policy.update(provisional, provisional, true,
            initializeInstallGrant = true)
        assertFalse(F.isPermissionGranted(installed))
        assertEquals(0, Policy.update(installed, installed, false))
    }

    @Test fun reconciliationRetainsBackupEvenWhenCallerDropsInternalFlags() {
        val blocked = Policy.update(F.RUNTIME_GRANTED, F.RUNTIME_GRANTED, true)
        val reset = Policy.update(blocked, F.USER_SET, true)
        assertFalse(F.isPermissionGranted(reset))
        assertEquals(F.RUNTIME_GRANTED or F.USER_SET, Policy.update(reset, reset, false))
    }

    @Test fun unblockedPermissionChangesAreUnmodified() {
        assertEquals(F.RUNTIME_GRANTED, Policy.update(0, F.RUNTIME_GRANTED, false))
        assertEquals(0, Policy.update(F.RUNTIME_GRANTED, 0, false))
    }

    @Test(expected = IllegalStateException::class)
    fun unsupportedGrantCannotBeSilentlyLost() {
        Policy.update(F.INSTALL_GRANTED, F.INSTALL_GRANTED, true)
    }
}
