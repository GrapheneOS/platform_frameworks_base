package com.android.server.permission.test

import android.content.Context
import android.os.Bundle
import android.os.UserManager
import com.android.server.LocalServices
import com.android.server.pm.UserRestrictionsUtils
import com.android.server.pm.permission.PermissionManagerServiceInterface
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class UserNetworkRestrictionApplicationTest {
    private val permissions = mock(PermissionManagerServiceInterface::class.java)
    private val context = mock(Context::class.java)
    private var previousService: PermissionManagerServiceInterface? = null

    @Before fun setUp() {
        previousService = LocalServices.getService(PermissionManagerServiceInterface::class.java)
        LocalServices.removeServiceForTest(PermissionManagerServiceInterface::class.java)
        LocalServices.addService(PermissionManagerServiceInterface::class.java, permissions)
    }

    @After fun tearDown() {
        LocalServices.removeServiceForTest(PermissionManagerServiceInterface::class.java)
        previousService?.let {
            LocalServices.addService(PermissionManagerServiceInterface::class.java, it)
        }
    }

    @Test fun enablingReconcilesTargetUser() {
        apply(true, false)
        verify(permissions).updateUserNetworkPermissions(10)
        verifyNoMoreInteractions(permissions)
    }

    @Test fun clearingReconcilesTargetUser() {
        apply(false, true)
        verify(permissions).updateUserNetworkPermissions(10)
        verifyNoMoreInteractions(permissions)
    }

    @Test fun unchangedRestrictionDoesNotReconcile() {
        apply(false, false)
        apply(true, true)
        verifyNoMoreInteractions(permissions)
    }

    @Test fun unrelatedRestrictionDoesNotReconcile() {
        val current = Bundle().apply { putBoolean(UserManager.DISALLOW_SMS, true) }
        UserRestrictionsUtils.applyUserRestrictions(context, 10, current, Bundle(), 0)
        verifyNoMoreInteractions(permissions)
    }

    private fun apply(current: Boolean, previous: Boolean) {
        val restrictions = Bundle().apply { putBoolean(UserManager.DISALLOW_NETWORK, current) }
        val oldRestrictions = Bundle().apply { putBoolean(UserManager.DISALLOW_NETWORK, previous) }
        UserRestrictionsUtils.applyUserRestrictions(context, 10, restrictions, oldRestrictions, 0)
    }
}
