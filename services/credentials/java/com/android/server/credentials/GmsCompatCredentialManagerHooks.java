package com.android.server.credentials;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.compat.gms.GmsCompat;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.ext.PackageId;
import android.os.Binder;

final class GmsCompatCredentialManagerHooks {
    static final ComponentName GMS_CORE_REMOTE_CREDENTIAL_SERVICE = new ComponentName(
            PackageId.GMS_CORE_NAME,
            "com.google.android.gms.auth.api.credentials.credman.service.RemoteService");

    private GmsCompatCredentialManagerHooks() {}

    static boolean shouldUseRemoteEntryCompatValidation(
            @NonNull ComponentName providerComponent, boolean isProviderEnabled,
            @NonNull String configuredHybridService) {
        if (!configuredHybridService.isEmpty()) {
            return false;
        }

        return isProviderEnabled
                && GMS_CORE_REMOTE_CREDENTIAL_SERVICE.equals(providerComponent);
    }

    static boolean isGmsCoreRemoteCredentialService(@NonNull Context context,
            @UserIdInt int userId, @NonNull ComponentName componentName) {
        if (!GMS_CORE_REMOTE_CREDENTIAL_SERVICE.equals(componentName)) {
            return false;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfoAsUser(
                    componentName.getPackageName(), PackageManager.ApplicationInfoFlags.of(0),
                    userId);
            return isGmsCoreRemoteCredentialService(componentName, appInfo);
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static boolean isGmsCoreRemoteCredentialService(@NonNull ComponentName componentName,
            @NonNull ApplicationInfo appInfo) {
        return GMS_CORE_REMOTE_CREDENTIAL_SERVICE.equals(componentName)
                && PackageId.GMS_CORE_NAME.equals(appInfo.packageName)
                && appInfo.ext().getPackageId() == PackageId.GMS_CORE
                && !appInfo.isPrivilegedApp()
                && GmsCompat.isEnabledFor(appInfo, false);
    }
}
