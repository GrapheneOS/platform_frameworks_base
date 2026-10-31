package com.android.server.servicewatcher;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Process;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class CurrentUserServiceSupplierTest {

    private static final String ACTION = "test.action.POPULATION_DENSITY";
    private static final String PACKAGE_NAME = "test.provider";
    private static final String CALLER_PERMISSION = "test.permission.BIND_PROVIDER";
    private static final String WRONG_CALLER_PERMISSION = "test.permission.WRONG_BIND_PROVIDER";
    private static final String SERVICE_PERMISSION = "test.permission.PROVIDE_DENSITY";
    private static final int ENABLE_OVERLAY_RES_ID = 1;
    private static final int PACKAGE_NAME_RES_ID = 2;
    private static final int USER_ID = 10;
    private static final int PROVIDER_UID = 100_000;

    private ActivityManagerInternal mOriginalActivityManager;
    private ActivityManagerInternal mActivityManager;
    private Context mContext;
    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        mOriginalActivityManager = LocalServices.getService(ActivityManagerInternal.class);
        if (mOriginalActivityManager != null) {
            LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        }
        mActivityManager = mock(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mActivityManager);

        mContext = mock(Context.class);
        mPackageManager = mock(PackageManager.class);
        Resources resources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(resources);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(resources.getBoolean(ENABLE_OVERLAY_RES_ID)).thenReturn(true);
        when(mActivityManager.getCurrentUserId()).thenReturn(USER_ID);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        if (mOriginalActivityManager != null) {
            LocalServices.addService(ActivityManagerInternal.class, mOriginalActivityManager);
        }
    }

    @Test
    public void createFromConfig_forwardsPermissionRequirements() {
        ResolveInfo resolveInfo = createResolveInfo(CALLER_PERMISSION);
        when(mPackageManager.queryIntentServicesAsUser(any(Intent.class), anyInt(), eq(USER_ID)))
                .thenReturn(List.of(resolveInfo));
        when(mContext.checkPermission(SERVICE_PERMISSION, Process.INVALID_PID, PROVIDER_UID))
                .thenReturn(PERMISSION_GRANTED);

        CurrentUserServiceSupplier supplier =
                CurrentUserServiceSupplier.createFromConfig(
                        mContext,
                        ACTION,
                        ENABLE_OVERLAY_RES_ID,
                        PACKAGE_NAME_RES_ID,
                        CALLER_PERMISSION,
                        SERVICE_PERMISSION);

        assertThat(supplier.getServiceInfo()).isNotNull();
        verify(mContext).checkPermission(SERVICE_PERMISSION, Process.INVALID_PID, PROVIDER_UID);
    }

    @Test
    public void createFromConfig_rejectsWrongCallerPermission() {
        ResolveInfo resolveInfo = createResolveInfo(WRONG_CALLER_PERMISSION);
        when(mPackageManager.queryIntentServicesAsUser(any(Intent.class), anyInt(), eq(USER_ID)))
                .thenReturn(List.of(resolveInfo));
        when(mContext.checkPermission(SERVICE_PERMISSION, Process.INVALID_PID, PROVIDER_UID))
                .thenReturn(PERMISSION_GRANTED);

        CurrentUserServiceSupplier supplier =
                CurrentUserServiceSupplier.createFromConfig(
                        mContext,
                        ACTION,
                        ENABLE_OVERLAY_RES_ID,
                        PACKAGE_NAME_RES_ID,
                        CALLER_PERMISSION,
                        SERVICE_PERMISSION);

        assertThat(supplier.getServiceInfo()).isNull();
    }

    @Test
    public void createFromConfig_rejectsDeniedServicePermission() {
        ResolveInfo resolveInfo = createResolveInfo(CALLER_PERMISSION);
        when(mPackageManager.queryIntentServicesAsUser(any(Intent.class), anyInt(), eq(USER_ID)))
                .thenReturn(List.of(resolveInfo));
        when(mContext.checkPermission(SERVICE_PERMISSION, Process.INVALID_PID, PROVIDER_UID))
                .thenReturn(PERMISSION_DENIED);

        CurrentUserServiceSupplier supplier =
                CurrentUserServiceSupplier.createFromConfig(
                        mContext,
                        ACTION,
                        ENABLE_OVERLAY_RES_ID,
                        PACKAGE_NAME_RES_ID,
                        CALLER_PERMISSION,
                        SERVICE_PERMISSION);

        assertThat(supplier.getServiceInfo()).isNull();
        verify(mContext).checkPermission(SERVICE_PERMISSION, Process.INVALID_PID, PROVIDER_UID);
    }

    private static ResolveInfo createResolveInfo(String callerPermission) {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.packageName = PACKAGE_NAME;
        serviceInfo.name = PACKAGE_NAME + ".PopulationDensityProvider";
        serviceInfo.permission = callerPermission;
        serviceInfo.applicationInfo = new ApplicationInfo();
        serviceInfo.applicationInfo.uid = PROVIDER_UID;
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        return resolveInfo;
    }
}
