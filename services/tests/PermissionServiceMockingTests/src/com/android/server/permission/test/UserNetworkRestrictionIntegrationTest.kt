package com.android.server.permission.test

import android.Manifest
import com.android.server.permission.access.permission.PermissionFlags as F
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.android.server.permission.access.permission.AppIdPermissionPolicy
import com.android.server.testutils.mock
import org.mockito.Mockito.verify
import com.android.server.pm.pkg.PackageUserState
import com.android.server.testutils.whenever

class UserNetworkRestrictionIntegrationTest : BasePermissionPolicyTest() {
    private fun requester(requestsInternet: Boolean = true) = mockPackageState(
        APP_ID_1, mockAndroidPackage(PACKAGE_NAME_1,
            requestedPermissions = if (requestsInternet) setOf(Manifest.permission.INTERNET)
                else emptySet()))

    @Test fun uninitializedSecondaryUserAllowsOrdinaryInternetGrant() {
        createUserState(USER_ID_NEW)
        addPackageState(requester())
        assertFalse(appIdPermissionPolicy.isUserNetworkRestricted(USER_ID_NEW))
        mutateState {
            with(appIdPermissionPolicy) {
                setPermissionFlags(APP_ID_1, USER_ID_NEW, Manifest.permission.INTERNET,
                    F.RUNTIME_GRANTED)
            }
        }
        assertEquals(F.RUNTIME_GRANTED,
            getPermissionFlags(APP_ID_1, USER_ID_NEW, Manifest.permission.INTERNET))
        oldState = newState
        mutateState {
            with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_NEW, true) }
        }
        assertFalse(F.isPermissionGranted(
            getPermissionFlags(APP_ID_1, USER_ID_NEW, Manifest.permission.INTERNET)))
        oldState = newState
        mutateState {
            with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_NEW, false) }
        }
        assertEquals(F.RUNTIME_GRANTED,
            getPermissionFlags(APP_ID_1, USER_ID_NEW, Manifest.permission.INTERNET))
    }

    @Test fun transitionNotifiesTheExistingPermissionChangeListener() {
        addPackageState(requester())
        val original = F.RUNTIME_GRANTED or F.USER_SET
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        val listener = mock<AppIdPermissionPolicy.OnPermissionFlagsChangedListener>()
        appIdPermissionPolicy.addOnPermissionFlagsChangedListener(listener)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, true) } }
        val blocked = getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET)
        verify(listener).onPermissionFlagsChanged(
            APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original, blocked)
        oldState = newState
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        verify(listener).onPermissionFlagsChanged(
            APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, blocked, original)
    }

    @Test fun transitionChangesActualGrantAndRestoresIt() {
        addPackageState(requester())
        val original = F.RUNTIME_GRANTED or F.USER_SET
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, true) } }
        assertFalse(F.isPermissionGranted(getPermissionFlags(
            APP_ID_1, USER_ID_0, Manifest.permission.INTERNET)))
        oldState = newState
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun installGrantIsInterceptedBeforeStateIsPublished() {
        addPackageState(requester())
        mutateState {
            with(appIdPermissionPolicy) {
                setUserNetworkRestricted(USER_ID_0, true)
                setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET,
                    F.RUNTIME_GRANTED)
            }
        }
        assertFalse(F.isPermissionGranted(getPermissionFlags(
            APP_ID_1, USER_ID_0, Manifest.permission.INTERNET)))
        oldState = newState
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        assertEquals(F.RUNTIME_GRANTED,
            getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun lastRequesterRemovalDiscardsSavedGrant() {
        addPackageState(requester())
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, F.RUNTIME_GRANTED)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, true) } }
        oldState = newState
        addPackageState(requester(false))
        mutateState {
            with(appIdPermissionPolicy) {
                setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, 0)
                setUserNetworkRestricted(USER_ID_0, false)
            }
        }
        assertEquals(0, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun unavailablePackageRetainsItsUnrestrictedGrant() {
        addPackageState(mockPackageState(APP_ID_1, PACKAGE_NAME_1))
        val original = F.RUNTIME_GRANTED or F.USER_SET
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun unavailableDeniedPackageRetainsItsUserDecision() {
        addPackageState(mockPackageState(APP_ID_1, PACKAGE_NAME_1))
        val original = F.USER_SET or F.USER_FIXED
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun unavailablePackageRetainsItsBackupUntilRestoration() {
        addPackageState(requester())
        val original = F.RUNTIME_GRANTED or F.USER_SET
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, true) } }
        val blocked = getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET)
        oldState = newState
        addPackageState(mockPackageState(APP_ID_1, PACKAGE_NAME_1))
        mutateState {
            with(appIdPermissionPolicy) {
                setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, 0)
            }
        }
        assertEquals(blocked, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
        oldState = newState
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
        oldState = newState
        addPackageState(requester())
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun unknownSharedPackageIsNotProofThatLastRequesterWasRemoved() {
        addPackageState(requester(false))
        addPackageState(mockPackageState(APP_ID_1, PACKAGE_NAME_2))
        val original = F.RUNTIME_GRANTED or F.USER_SET
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, true) } }
        assertFalse(F.isPermissionGranted(getPermissionFlags(
            APP_ID_1, USER_ID_0, Manifest.permission.INTERNET)))
        oldState = newState
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun updateGrantCannotOverwriteExistingDeniedChoice() {
        addPackageState(requester())
        val original = F.USER_SET or F.USER_FIXED
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, true) } }
        oldState = newState
        mutateState {
            with(appIdPermissionPolicy) {
                val blocked = getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET)
                setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET,
                    blocked or F.RUNTIME_GRANTED)
                setUserNetworkRestricted(USER_ID_0, false)
            }
        }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun newInstallInitializesGrantAfterProvisionalDenial() {
        val packageState = requester()
        addPackageState(packageState)
        mutateState {
            with(appIdPermissionPolicy) {
                setUserNetworkRestricted(USER_ID_0, true)
                setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, F.USER_SET)
                initializeNetworkPermissionForInstall(packageState, USER_ID_0,
                    F.USER_SET or F.RUNTIME_GRANTED)
            }
        }
        assertFalse(F.isPermissionGranted(getPermissionFlags(
            APP_ID_1, USER_ID_0, Manifest.permission.INTERNET)))
        oldState = newState
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, false) } }
        assertEquals(F.USER_SET or F.RUNTIME_GRANTED,
            getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun newSharedPackageCannotOverwriteIncumbentChoice() {
        val incumbent = requester()
        val installed = mock<PackageUserState> { whenever(isInstalled).thenReturn(true) }
        whenever(incumbent.getUserStateOrDefault(USER_ID_0)).thenReturn(installed)
        addPackageState(incumbent)
        val added = mockPackageState(APP_ID_1, mockAndroidPackage(PACKAGE_NAME_2,
            requestedPermissions = setOf(Manifest.permission.INTERNET)))
        addPackageState(added)
        val original = F.USER_SET
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        mutateState {
            with(appIdPermissionPolicy) {
                setUserNetworkRestricted(USER_ID_0, true)
                initializeNetworkPermissionForInstall(added, USER_ID_0,
                    original or F.RUNTIME_GRANTED)
                setUserNetworkRestricted(USER_ID_0, false)
            }
        }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun unavailableIncumbentProtectsTheSharedBackup() {
        val incumbent = mockPackageState(APP_ID_1, PACKAGE_NAME_1)
        val installed = mock<PackageUserState> { whenever(isInstalled).thenReturn(true) }
        whenever(incumbent.getUserStateOrDefault(USER_ID_0)).thenReturn(installed)
        addPackageState(incumbent)
        val added = mockPackageState(APP_ID_1, mockAndroidPackage(PACKAGE_NAME_2,
            requestedPermissions = setOf(Manifest.permission.INTERNET)))
        addPackageState(added)
        val original = F.USER_SET
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, original)
        mutateState {
            with(appIdPermissionPolicy) {
                setUserNetworkRestricted(USER_ID_0, true)
                initializeNetworkPermissionForInstall(added, USER_ID_0,
                    original or F.RUNTIME_GRANTED)
                setUserNetworkRestricted(USER_ID_0, false)
            }
        }
        assertEquals(original, getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun packageInstalledOnlyElsewhereDoesNotPreventNewUserGrantInitialization() {
        val elsewhere = requester()
        val notInstalled = mock<PackageUserState> { whenever(isInstalled).thenReturn(false) }
        whenever(elsewhere.getUserStateOrDefault(USER_ID_0)).thenReturn(notInstalled)
        addPackageState(elsewhere)
        val added = mockPackageState(APP_ID_1, mockAndroidPackage(PACKAGE_NAME_2,
            requestedPermissions = setOf(Manifest.permission.INTERNET)))
        addPackageState(added)
        mutateState {
            with(appIdPermissionPolicy) {
                setUserNetworkRestricted(USER_ID_0, true)
                setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, F.USER_SET)
                initializeNetworkPermissionForInstall(added, USER_ID_0,
                    F.USER_SET or F.RUNTIME_GRANTED)
                setUserNetworkRestricted(USER_ID_0, false)
            }
        }
        assertEquals(F.USER_SET or F.RUNTIME_GRANTED,
            getPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET))
    }

    @Test fun anotherUserKeepsItsGrant() {
        createUserState(USER_ID_NEW)
        addPackageState(requester())
        setPermissionFlags(APP_ID_1, USER_ID_0, Manifest.permission.INTERNET, F.RUNTIME_GRANTED)
        setPermissionFlags(APP_ID_1, USER_ID_NEW, Manifest.permission.INTERNET, F.RUNTIME_GRANTED)
        mutateState { with(appIdPermissionPolicy) { setUserNetworkRestricted(USER_ID_0, true) } }
        assertEquals(F.RUNTIME_GRANTED,
            getPermissionFlags(APP_ID_1, USER_ID_NEW, Manifest.permission.INTERNET))
    }
}
