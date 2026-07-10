/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.local;

import android.Manifest;
import android.annotation.CallSuper;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.Flags;
import android.content.pm.SigningDetails;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.net.VpnConfig;
import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.server.art.model.DexoptParams;
import com.android.server.art.model.DexoptResult;
import com.android.server.ext.BgDexoptUi;
import com.android.server.pm.Computer;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.snapshot.PackageDataSnapshot;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** @hide */
public class PackageManagerLocalImpl implements PackageManagerLocal {

    private final PackageManagerService mService;

    public PackageManagerLocalImpl(PackageManagerService service) {
        mService = service;
    }

    @Override
    public void addGosPackageStateChangeCallback(GosPackageStateChangeCallback callback) {
        mService.gosPackageStatePmHooks.addChangeCallback(callback);
    }

    @Override
    public boolean removeGosPackageStateChangeCallback(GosPackageStateChangeCallback callback) {
        return mService.gosPackageStatePmHooks.removeChangeCallback(callback);
    }

    @Override
    public void reconcileSdkData(@Nullable String volumeUuid, @NonNull String packageName,
            @NonNull List<String> subDirNames, int userId, int appId, int previousAppId,
            @NonNull String seInfo, int flags) throws IOException {
        mService.reconcileSdkData(volumeUuid, packageName, subDirNames, userId, appId,
                previousAppId, seInfo, flags);
    }

    @NonNull
    @Override
    public UnfilteredSnapshotImpl withUnfilteredSnapshot() {
        return new UnfilteredSnapshotImpl(mService.snapshotComputer(false /*allowLiveComputer*/));
    }

    @NonNull
    @Override
    public FilteredSnapshotImpl withFilteredSnapshot() {
        return withFilteredSnapshot(Binder.getCallingUid(), Binder.getCallingUserHandle());
    }

    @NonNull
    @Override
    public FilteredSnapshotImpl withFilteredSnapshot(int callingUid, @NonNull UserHandle user) {
        return withFilteredSnapshot(callingUid, user, /* uncommittedPs= */ null);
    }

    /**
     * Creates a {@link FilteredSnapshot} with a uncommitted {@link PackageState} that is used for
     * dexopt in the art service to get the correct package state before the package is committed.
     */
    @NonNull
    public static FilteredSnapshotImpl withFilteredSnapshot(PackageManagerLocal pm,
            @NonNull PackageState uncommittedPs) {
        return ((PackageManagerLocalImpl) pm).withFilteredSnapshot(Binder.getCallingUid(),
                Binder.getCallingUserHandle(), uncommittedPs);
    }

    @NonNull
    private FilteredSnapshotImpl withFilteredSnapshot(int callingUid, @NonNull UserHandle user,
            @Nullable PackageState uncommittedPs) {
        return new FilteredSnapshotImpl(callingUid, user,
                mService.snapshotComputer(/* allowLiveComputer= */ false),
                /* parentSnapshot= */ null, uncommittedPs, /* unowned= */ false);
    }

    @NonNull
    @Override
    public FilteredSnapshot withUnownedFilteredSnapshot(@NonNull PackageDataSnapshot computer) {
        if (Flags.alternativeForDexoptCleanup()) {
            return new FilteredSnapshotImpl(Binder.getCallingUid(), Binder.getCallingUserHandle(),
                    computer,
                    /* parentSnapshot= */ null, /* uncommittedPs= */ null, /* unowned= */ true);
        } else {
            return withFilteredSnapshot();
        }
    }

    @Override
    public void addOverrideSigningDetails(@NonNull SigningDetails oldSigningDetails,
            @NonNull SigningDetails newSigningDetails) {
        if (!Build.isDebuggable()) {
            throw new SecurityException("This test API is only available on debuggable builds");
        }
        ApkSignatureVerifier.addOverrideSigningDetails(oldSigningDetails, newSigningDetails);
    }

    @Override
    public void removeOverrideSigningDetails(@NonNull SigningDetails oldSigningDetails) {
        if (!Build.isDebuggable()) {
            throw new SecurityException("This test API is only available on debuggable builds");
        }
        ApkSignatureVerifier.removeOverrideSigningDetails(oldSigningDetails);
    }

    @Override
    public void clearOverrideSigningDetails() {
        if (!Build.isDebuggable()) {
            throw new SecurityException("This test API is only available on debuggable builds");
        }
        ApkSignatureVerifier.clearOverrideSigningDetails();
    }

    private abstract static class BaseSnapshotImpl implements AutoCloseable {

        private boolean mClosed;

        @NonNull
        protected Computer mSnapshot;

        // True if this object does not own the computer ({@link #mSnapshot}) and is not responsible
        // for releasing the resources that the computer holds (if any).
        private final boolean mUnowned;

        private BaseSnapshotImpl(@NonNull PackageDataSnapshot snapshot, boolean unowned) {
            mSnapshot = (Computer) snapshot;
            mUnowned = unowned;
        }

        @CallSuper
        @Override
        public void close() {
            mClosed = true;
            if (mUnowned) {
                // Short-circuit this method in the unowned case. At the time of writing, it is
                // actually fine to execute this method to the end because it doesn't change the
                // state of the computer but only drops the reference to it, but we aggressively
                // early return just in case someone in the future adds some code below to release
                // resources by changing the state of the computer and forgets to handle the unowned
                // case.
                return;
            }
            mSnapshot = null;
            // TODO: Recycle snapshots?
        }

        @CallSuper
        protected void checkClosed() {
            if (mClosed) {
                throw new IllegalStateException("Snapshot already closed");
            }
        }
    }

    private static class UnfilteredSnapshotImpl extends BaseSnapshotImpl implements
            UnfilteredSnapshot {

        @Nullable
        private Map<String, PackageState> mCachedUnmodifiablePackageStates;

        @Nullable
        private Map<String, SharedUserApi> mCachedUnmodifiableSharedUsers;

        @Nullable
        private Map<String, PackageState> mCachedUnmodifiableDisabledSystemPackageStates;

        private UnfilteredSnapshotImpl(@NonNull PackageDataSnapshot snapshot) {
            super(snapshot, /* unowned= */ false);
        }

        @Override
        public FilteredSnapshot filtered(int callingUid, @NonNull UserHandle user) {
            return new FilteredSnapshotImpl(callingUid, user, mSnapshot, this,
                    /* uncommittedPs= */ null, /* unowned= */ true);
        }

        @SuppressWarnings("RedundantSuppression")
        @NonNull
        @Override
        public Map<String, PackageState> getPackageStates() {
            checkClosed();

            if (mCachedUnmodifiablePackageStates == null) {
                mCachedUnmodifiablePackageStates =
                        Collections.unmodifiableMap(mSnapshot.getPackageStates());
            }
            return mCachedUnmodifiablePackageStates;
        }

        @SuppressWarnings("RedundantSuppression")
        @NonNull
        @Override
        public Map<String, SharedUserApi> getSharedUsers() {
            checkClosed();

            if (mCachedUnmodifiableSharedUsers == null) {
                mCachedUnmodifiableSharedUsers =
                        Collections.unmodifiableMap(mSnapshot.getSharedUsers());
            }
            return mCachedUnmodifiableSharedUsers;
        }

        @SuppressWarnings("RedundantSuppression")
        @NonNull
        @Override
        public Map<String, PackageState> getDisabledSystemPackageStates() {
            checkClosed();

            if (mCachedUnmodifiableDisabledSystemPackageStates == null) {
                mCachedUnmodifiableDisabledSystemPackageStates =
                        Collections.unmodifiableMap(mSnapshot.getDisabledSystemPackageStates());
            }
            return mCachedUnmodifiableDisabledSystemPackageStates;
        }

        @Override
        public void close() {
            super.close();
            mCachedUnmodifiablePackageStates = null;
            mCachedUnmodifiableDisabledSystemPackageStates = null;
        }
    }

    private static class FilteredSnapshotImpl extends BaseSnapshotImpl implements
            FilteredSnapshot {

        private final int mCallingUid;

        @UserIdInt
        private final int mUserId;

        @Nullable
        private Map<String, PackageState> mFilteredPackageStates;

        @Nullable
        private final UnfilteredSnapshotImpl mParentSnapshot;

        @Nullable
        private final PackageState mUncommitPackageState;

        private FilteredSnapshotImpl(int callingUid, @NonNull UserHandle user,
                @NonNull PackageDataSnapshot snapshot,
                @Nullable UnfilteredSnapshotImpl parentSnapshot,
                @Nullable PackageState uncommittedPs, boolean unowned) {
            super(snapshot, unowned);
            mCallingUid = callingUid;
            mUserId = user.getIdentifier();
            mParentSnapshot = parentSnapshot;
            mUncommitPackageState = uncommittedPs;
        }

        @Override
        protected void checkClosed() {
            if (mParentSnapshot != null) {
                mParentSnapshot.checkClosed();
            }

            super.checkClosed();
        }

        @Override
        public void close() {
            super.close();
            mFilteredPackageStates = null;
        }

        @Nullable
        @Override
        public PackageState getPackageState(@NonNull String packageName) {
            checkClosed();
            if (mUncommitPackageState != null
                    && packageName.equals(mUncommitPackageState.getPackageName())) {
                return mUncommitPackageState;
            }
            return mSnapshot.getPackageStateFiltered(packageName, mCallingUid, mUserId);
        }

        @NonNull
        @Override
        public Map<String, PackageState> getPackageStates() {
            checkClosed();

            if (mFilteredPackageStates == null) {
                var packageStates = mSnapshot.getPackageStates();
                var filteredPackageStates = new ArrayMap<String, PackageState>();
                for (int index = 0, size = packageStates.size(); index < size; index++) {
                    var packageState = packageStates.valueAt(index);
                    if (mUncommitPackageState != null
                            && packageState.getPackageName().equals(
                            mUncommitPackageState.getPackageName())) {
                        packageState = (PackageStateInternal) mUncommitPackageState;
                    }
                    if (!mSnapshot.shouldFilterApplication(packageState, mCallingUid, mUserId)) {
                        filteredPackageStates.put(packageStates.keyAt(index), packageState);
                    }
                }
                mFilteredPackageStates = Collections.unmodifiableMap(filteredPackageStates);
            }

            return mFilteredPackageStates;
        }
    }

    @Override
    public void onBgDexoptProgressUpdate(@ElapsedRealtimeLong long start, int percentage, int current, int total) {
        BgDexoptUi.onBgDexoptProgressUpdate(mService, start, percentage, current, total);
    }

    @Override
    public void onBgDexoptCompleted(@Nullable Object dexOptResultByPass, long durationMs) {
        // DexoptResult can't be used as a parameter in PackageManagerLocal interface, it's declared
        // in libartservice
        BgDexoptUi.onBgDexoptCompleted(mService, (Map<Integer, DexoptResult>) dexOptResultByPass, durationMs);
    }

    @Nullable
    @Override
    public String maybeOverrideCompilerFilter(@NonNull String origFilter, @NonNull AndroidPackage pkg,
                                              @NonNull Object dexoptParamsR) {
        final String TAG = "maybeOverrideCompilerFilter";
        final String speedFilter = "speed";

        if (speedFilter.equals(origFilter)) {
            return null;
        }

        var dexoptParams = (DexoptParams) dexoptParamsR;

        if (isVpnServiceHost(pkg)) {
            Slog.d(TAG, pkg.getPackageName() + " is a VPN service host, using " + speedFilter +
                ", reason: " + dexoptParams.getReason());
            return speedFilter;
        }

        return null;
    }

    private static boolean isVpnServiceHost(AndroidPackage pkg) {
        for (ParsedService s : pkg.getServices()) {
            if (!Manifest.permission.BIND_VPN_SERVICE.equals(s.getPermission())) {
                continue;
            }
            for (ParsedIntentInfo i : s.getIntents()) {
                if (i.getIntentFilter().hasAction(VpnConfig.SERVICE_INTERFACE)) {
                    return true;
                }
            }
        }
        return false;
    }
}
