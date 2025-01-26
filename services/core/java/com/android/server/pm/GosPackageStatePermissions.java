package com.android.server.pm;

import android.annotation.Nullable;
import android.app.PropertyInvalidatedCache;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.GosPackageStateFlag;
import android.ext.KnownSystemPackages;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.EmptyArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.gmscompat.GmsCompatApp;
import com.android.server.LocalServices;

import java.util.Objects;

import static android.content.pm.GosPackageStateFlag.ALLOW_ACCESS_TO_OBB_DIRECTORY;
import static android.content.pm.GosPackageStateFlag.BLOCK_NATIVE_DEBUGGING;
import static android.content.pm.GosPackageStateFlag.BLOCK_NATIVE_DEBUGGING_NON_DEFAULT;
import static android.content.pm.GosPackageStateFlag.BLOCK_NATIVE_DEBUGGING_SUPPRESS_NOTIF;
import static android.content.pm.GosPackageStateFlag.BLOCK_PLAY_INTEGRITY_API;
import static android.content.pm.GosPackageStateFlag.CONTACT_SCOPES_ENABLED;
import static android.content.pm.GosPackageStateFlag.ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE;
import static android.content.pm.GosPackageStateFlag.FORCE_MEMTAG;
import static android.content.pm.GosPackageStateFlag.FORCE_MEMTAG_NON_DEFAULT;
import static android.content.pm.GosPackageStateFlag.FORCE_MEMTAG_SUPPRESS_NOTIF;
import static android.content.pm.GosPackageStateFlag.PLAY_INTEGRITY_API_USED_AT_LEAST_ONCE;
import static android.content.pm.GosPackageStateFlag.RESTRICT_MEMORY_DYN_CODE_LOADING;
import static android.content.pm.GosPackageStateFlag.RESTRICT_MEMORY_DYN_CODE_LOADING_NON_DEFAULT;
import static android.content.pm.GosPackageStateFlag.RESTRICT_MEMORY_DYN_CODE_LOADING_SUPPRESS_NOTIF;
import static android.content.pm.GosPackageStateFlag.RESTRICT_STORAGE_DYN_CODE_LOADING;
import static android.content.pm.GosPackageStateFlag.RESTRICT_STORAGE_DYN_CODE_LOADING_NON_DEFAULT;
import static android.content.pm.GosPackageStateFlag.RESTRICT_STORAGE_DYN_CODE_LOADING_SUPPRESS_NOTIF;
import static android.content.pm.GosPackageStateFlag.RESTRICT_WEBVIEW_DYN_CODE_LOADING;
import static android.content.pm.GosPackageStateFlag.RESTRICT_WEBVIEW_DYN_CODE_LOADING_NON_DEFAULT;
import static android.content.pm.GosPackageStateFlag.STORAGE_SCOPES_ENABLED;
import static android.content.pm.GosPackageStateFlag.SUPPRESS_PLAY_INTEGRITY_API_NOTIF;
import static android.content.pm.GosPackageStateFlag.USE_EXTENDED_VA_SPACE;
import static android.content.pm.GosPackageStateFlag.USE_EXTENDED_VA_SPACE_NON_DEFAULT;
import static android.content.pm.GosPackageStateFlag.USE_HARDENED_MALLOC;
import static android.content.pm.GosPackageStateFlag.USE_HARDENED_MALLOC_NON_DEFAULT;
import static com.android.server.pm.GosPackageStatePermission.ALLOW_CROSS_USER_PROFILE_READS;
import static com.android.server.pm.GosPackageStatePermission.FIELD_CONTACT_SCOPES;
import static com.android.server.pm.GosPackageStatePermission.FIELD_PACKAGE_FLAGS;
import static com.android.server.pm.GosPackageStatePermission.FIELD_STORAGE_SCOPES;

class GosPackageStatePermissions {
    private static final String TAG = "GosPackageStatePermissions";
    // Permission that each package has for accessing its own GosPackageState
    private static GosPackageStatePermission selfAccessPermission;
    private static GosPackageStatePermission fullPermission;
    private static int myPid;
    // Maps app's appId to its permission.
    // Written only during PackageManager init, no need to synchronize reads
    static SparseArray<GosPackageStatePermission> grantedPermissions;
    static UserManagerInternal userManager;

    static final int UNKNOWN_CALLING_PID = 0;

    static void init(PackageManagerService pm) {
        myPid = Process.myPid();

        @GosPackageStateFlag.Enum int[] playIntegrityFlags = {
                PLAY_INTEGRITY_API_USED_AT_LEAST_ONCE,
                BLOCK_PLAY_INTEGRITY_API,
                SUPPRESS_PLAY_INTEGRITY_API_NOTIF,
        };

        selfAccessPermission = builder()
                .readFlags(STORAGE_SCOPES_ENABLED, ALLOW_ACCESS_TO_OBB_DIRECTORY,
                        CONTACT_SCOPES_ENABLED)
                .readFlags(playIntegrityFlags)
                .readWriteFlag(PLAY_INTEGRITY_API_USED_AT_LEAST_ONCE)
                .create();

        grantedPermissions = new SparseArray<>();

        GosPackageStatePermission full = GosPackageStatePermission.createFull();
        fullPermission = full;

        grantedPermissions.put(Process.SHELL_UID, full);
        if (Build.isDebuggable()) {
            // for root adb
            grantedPermissions.put(Process.ROOT_UID, full);
        }
        Computer computer = pm.snapshotComputer();

        KnownSystemPackages ksp = KnownSystemPackages.get(pm.getContext());
        builder()
                .readFlag(STORAGE_SCOPES_ENABLED)
                .readField(FIELD_STORAGE_SCOPES)
                .apply(ksp.mediaProvider, computer);
        builder()
                .readFlag(CONTACT_SCOPES_ENABLED)
                .readField(FIELD_CONTACT_SCOPES)
                .apply(ksp.contactsProvider, computer);
        builder()
                .readFlags(STORAGE_SCOPES_ENABLED, CONTACT_SCOPES_ENABLED)
                // work profile is handled by the launcher in profile's parent
                .crossUserPermission(ALLOW_CROSS_USER_PROFILE_READS)
                .apply(ksp.launcher, computer);
        builder()
                .readWriteFlags(STORAGE_SCOPES_ENABLED, CONTACT_SCOPES_ENABLED)
                .readWriteFields(FIELD_STORAGE_SCOPES, FIELD_CONTACT_SCOPES,
                        FIELD_PACKAGE_FLAGS)
                .apply(ksp.permissionController, computer);
        builder()
                .readFlags(playIntegrityFlags)
                .readWriteFlag(SUPPRESS_PLAY_INTEGRITY_API_NOTIF)
                .apply(GmsCompatApp.PKG_NAME, computer);

        @GosPackageStateFlag.Enum int[] settingsReadWriteFlags = {
                ALLOW_ACCESS_TO_OBB_DIRECTORY,
                BLOCK_NATIVE_DEBUGGING_NON_DEFAULT,
                BLOCK_NATIVE_DEBUGGING,
                BLOCK_NATIVE_DEBUGGING_SUPPRESS_NOTIF,
                RESTRICT_MEMORY_DYN_CODE_LOADING_NON_DEFAULT,
                RESTRICT_MEMORY_DYN_CODE_LOADING,
                RESTRICT_MEMORY_DYN_CODE_LOADING_SUPPRESS_NOTIF,
                RESTRICT_STORAGE_DYN_CODE_LOADING_NON_DEFAULT,
                RESTRICT_STORAGE_DYN_CODE_LOADING,
                RESTRICT_STORAGE_DYN_CODE_LOADING_SUPPRESS_NOTIF,
                RESTRICT_WEBVIEW_DYN_CODE_LOADING_NON_DEFAULT,
                RESTRICT_WEBVIEW_DYN_CODE_LOADING,
                USE_HARDENED_MALLOC_NON_DEFAULT,
                USE_HARDENED_MALLOC,
                USE_EXTENDED_VA_SPACE_NON_DEFAULT,
                USE_EXTENDED_VA_SPACE,
                FORCE_MEMTAG_NON_DEFAULT,
                FORCE_MEMTAG,
                FORCE_MEMTAG_SUPPRESS_NOTIF,
                ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE,
        };
        builder()
                .readWriteFlags(settingsReadWriteFlags)
                .readWriteFlags(playIntegrityFlags)
                .readFlags(STORAGE_SCOPES_ENABLED, CONTACT_SCOPES_ENABLED)
                .readFields(FIELD_PACKAGE_FLAGS)
                // note that this applies to all packages that run in the android.uid.system sharedUserId,
                // not just the Settings app.
                .apply(ksp.settings, computer);

        userManager = Objects.requireNonNull(LocalServices.getService(UserManagerInternal.class));

        registerDevModeReceiver(pm);
    }

    @Nullable
    static GosPackageStatePermission get(int callingUid, int callingPid, int targetAppId, int targetUserId, boolean forWrite) {
        if (callingUid == android.os.Process.SYSTEM_UID && callingPid == myPid) {
            return fullPermission;
        }
        int callingAppId = UserHandle.getAppId(callingUid);
        GosPackageStatePermission permission = grantedPermissions.get(callingAppId);

        if (permission == null) {
            if (targetAppId == callingAppId) {
                permission = selfAccessPermission;
            } else {
                Slog.d(TAG, "uid " + callingUid + " doesn't have permission to " +
                        "access GosPackageState of other packages");
                return null;
            }
        }
        if (forWrite && !permission.canWrite()) {
            return null;
        }
        if (!permission.checkCrossUserPermissions(callingUid, targetUserId, forWrite)) {
            return null;
        }
        return permission;
    }

    private static void registerDevModeReceiver(PackageManagerService pm) {
        if (!Build.IS_DEBUGGABLE) {
            return;
        }

        var receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                builder()
                        .readFlags(flagsArr(i, "readFlags"))
                        .readWriteFlags(flagsArr(i, "readWriteFlags"))
                        .readFields(intArr(i, "readFields"))
                        .readWriteFields(intArr(i, "readWriteFields"))
                        .crossUserPermissions(intArr(i, "crossUserPermissions"))
                        .apply(i.getStringExtra("pkgName"), pm.snapshotComputer());

                PropertyInvalidatedCache.invalidateCache(PermissionManager.CACHE_KEY_PACKAGE_INFO);
                Slog.d(TAG, "granted permission " + i.getExtras());
            }

            private static int[] flagsArr(Intent intent, String name) {
                String[] strings = intent.getStringArrayExtra(name);
                if (strings == null) {
                    return EmptyArray.INT;
                }

                int[] res = new int[strings.length];
                for (int i = 0; i < strings.length; ++i) {
                    res[i] = GosPackageStateUtils.parseFlag(strings[i]);
                }
                return res;
            }

            private static int[] intArr(Intent i, String name) {
                int[] res = i.getIntArrayExtra(name);
                return res != null ? res : EmptyArray.INT;
            }
        };
        pm.getContext().registerReceiver(receiver, new IntentFilter("GosPackageState.grant_permission"),
                Context.RECEIVER_EXPORTED);
    }

    private static GosPackageStatePermission.Builder builder() {
        return new GosPackageStatePermission.Builder();
    }
}
