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

package com.android.internal.gmscompat;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.ext.PackageId;
import android.os.RemoteException;
import android.util.Log;

/**
 * Synthesizes {@link PackageId#GSF_NAME} package presence for third-party apps when
 * Play Services is installed but GSF itself is absent (fresh GrapheneOS installs).
 * <p>
 * Some apps (e.g. Hikvision Hik-Connect / Guarding Vision) gate FCM on
 * {@code getPackageInfo("com.google.android.gsf")} even though FCM works through GmsCore.
 * This helper answers that presence probe only; it does not allow installing real GSF.
 * <p>
 * The GmsCore presence check is subject to the caller's package visibility. That matches
 * when FCM is viable for the app.
 *
 * @hide
 */
public final class GsfPresenceCompat {
    private static final String TAG = "GsfPresenceCompat";

    private GsfPresenceCompat() {}

    /**
     * @param flags unused; kept so call sites can pass the original query flags unchanged
     * @return a synthetic {@link PackageInfo} for GSF, or null if this request should
     *         still throw {@link PackageManager.NameNotFoundException}
     */
    @Nullable
    public static PackageInfo maybeSynthesizePackageInfo(String packageName, long flags,
            int userId) {
        if (!PackageId.GSF_NAME.equals(packageName)) {
            return null;
        }
        if (!isVerifiedGmsCoreInstalled(userId)) {
            return null;
        }
        // Minimal presence-only object. Do not copy GmsCore fields (version, signatures,
        // sharedUserId, etc.) under the GSF name.
        PackageInfo pi = new PackageInfo();
        pi.packageName = PackageId.GSF_NAME;
        pi.applicationInfo = synthesizeApplicationInfo();
        Log.d(TAG, "synthesized PackageInfo for " + PackageId.GSF_NAME
                + " (GmsCore present, userId=" + userId + ")");
        return pi;
    }

    /**
     * @param flags unused; kept so call sites can pass the original query flags unchanged
     * @return a synthetic {@link ApplicationInfo} for GSF, or null if this request should
     *         still throw {@link PackageManager.NameNotFoundException}
     */
    @Nullable
    public static ApplicationInfo maybeSynthesizeApplicationInfo(String packageName, long flags,
            int userId) {
        if (!PackageId.GSF_NAME.equals(packageName)) {
            return null;
        }
        if (!isVerifiedGmsCoreInstalled(userId)) {
            return null;
        }
        ApplicationInfo ai = synthesizeApplicationInfo();
        Log.d(TAG, "synthesized ApplicationInfo for " + PackageId.GSF_NAME
                + " (GmsCore present, userId=" + userId + ")");
        return ai;
    }

    private static ApplicationInfo synthesizeApplicationInfo() {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = PackageId.GSF_NAME;
        ai.enabled = true;
        return ai;
    }

    /**
     * True if verified Play Services is installed and visible to the caller for {@code userId}.
     */
    private static boolean isVerifiedGmsCoreInstalled(int userId) {
        IPackageManager pm = ActivityThread.getPackageManager();
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(PackageId.GMS_CORE_NAME, /* flags */ 0L, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (ai == null || !ai.enabled) {
            return false;
        }
        // Reject lookalikes (wrong signing key / no GrapheneOS PackageId assignment).
        return ai.ext().getPackageId() == PackageId.GMS_CORE;
    }
}
