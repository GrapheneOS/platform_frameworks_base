/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.Nullable;
import android.os.Binder;
import android.os.Handler;
import android.os.IMediaDrmIdAccessService;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.pm.pkg.PackageStateInternal;

/** Trusted policy service for MediaDrm device unique ID access. */
final class MediaDrmIdAccessService extends IMediaDrmIdAccessService.Stub {
    private static final String TAG = "MediaDrmIdAccess";

    // DRM HAL implementations use one of these dedicated Android identities.
    private static final int MEDIA_DRM_UID = 1031;

    private final ActivityManagerService mService;
    private final Handler mCallbackHandler;

    MediaDrmIdAccessService(ActivityManagerService service) {
        this(service, BackgroundThread.getHandler());
    }

    @VisibleForTesting
    MediaDrmIdAccessService(ActivityManagerService service, Handler callbackHandler) {
        mService = service;
        mCallbackHandler = callbackHandler;
    }

    @Override
    public boolean isAllowed() {
        return checkAccess(Binder.getCallingUid(), Binder.getCallingPid());
    }

    @Override
    public boolean isAllowedFromDrmHal(int uid, int pid) {
        return isAllowedFromDrmHalTransport(uid, pid, Binder.getCallingUid());
    }

    @VisibleForTesting
    boolean isAllowedFromDrmHalTransport(int uid, int pid, int transportUid) {
        if (transportUid != Process.MEDIA_UID && transportUid != Process.DRM_UID
                && transportUid != MEDIA_DRM_UID) {
            Slog.w(TAG, "rejecting request from untrusted transport uid " + transportUid);
            return false;
        }
        return checkAccess(uid, pid);
    }

    @VisibleForTesting
    boolean checkAccess(int uid, int pid) {
        if (Process.isCoreUid(uid)) {
            return true;
        }

        final boolean isolated = Process.isIsolatedUid(uid);
        final boolean sdkSandbox = Process.isSdkSandboxUid(uid);
        final ProcessRecord process;
        synchronized (mService.mPidsSelfLocked) {
            process = mService.mPidsSelfLocked.get(pid);
        }

        String packageName = null;
        int appUid = sdkSandbox ? Process.getAppUidForSdkSandboxUid(uid) : uid;
        if (process != null && process.uid == uid) {
            if (sdkSandbox) {
                packageName = process.sdkSandboxClientAppPackage;
            } else {
                packageName = process.info.packageName;
                if (isolated) {
                    appUid = process.info.uid;
                }
            }
        }

        PackageStateInternal packageState = null;
        final int userId = UserHandle.getUserId(appUid);
        if (!TextUtils.isEmpty(packageName)) {
            packageState = mService.getPackageManagerInternal()
                    .getPackageStateInternal(packageName);
            if (packageState == null
                    || packageState.getAppId() != UserHandle.getAppId(appUid)
                    || !packageState.getUserStateOrDefault(userId).isInstalled()) {
                packageName = null;
                packageState = null;
            }
        }

        if (!isolated && !sdkSandbox && packageState != null && packageState.isSystem()) {
            return true;
        }

        final String blockedPackage = packageName;
        final int blockedAppUid = appUid;
        mCallbackHandler.post(() -> onAccessBlocked(
                uid, pid, blockedAppUid, userId, blockedPackage));
        return false;
    }

    @VisibleForTesting
    void onAccessBlocked(int uid, int pid, int appUid, int userId,
            @Nullable String packageName) {
        Slog.w(TAG, "blocked deviceUniqueId access: uid=" + uid + ", pid=" + pid
                + ", appUid=" + appUid + ", userId=" + userId + ", package=" + packageName);
    }
}
