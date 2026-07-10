package com.android.server.pm;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.KnownSystemPackages;
import android.ext.DerivedPackageFlag;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.pm.parsing.pkg.AndroidPackageInternal;
import com.android.internal.pm.pkg.component.ParsedUsesPermission;
import com.android.server.LocalServices;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.utils.Slogf;

import java.util.List;

import static android.content.pm.GosPackageState.*;
import static com.android.server.pm.GosPackageStateUtils.parseFlag;

public class GosPackageStatePmHooks {
    private static final String TAG = "GosPackageStatePmHooks";

    private final PackageManagerService pkgManager;

    GosPackageStatePmHooks(PackageManagerService pkgManager) {
        this.pkgManager = pkgManager;
    }

    void init() {
        GosPackageStatePermissions.init(pkgManager);
    }

    @NonNull
    GosPackageState getUnfiltered(String packageName, int userId) {
        return getUnfiltered(pkgManager.snapshotComputer(), packageName, userId);
    }

    @NonNull
    private static GosPackageState getUnfiltered(Computer snapshot, String packageName, int userId) {
        PackageStateInternal psi = snapshot.getPackageStates().get(packageName);
        if (psi == null) {
            return NONE;
        }
        return psi.getUserStateOrDefault(userId).getGosPackageState();
    }

    @NonNull
    GosPackageState getFiltered(int callingUid, int callingPid, String packageName, int userId) {
        Computer pmComputer = pkgManager.snapshotComputer();
        PackageStateInternal packageState = pmComputer.getPackageStates().get(packageName);
        if (packageState == null) {
            // the package was likely racily uninstalled
            return NONE;
        }
        return getFiltered(pmComputer, packageState,
                packageState.getUserStateOrDefault(userId).getGosPackageState(),
                callingUid, callingPid, userId);
    }

    @NonNull
    public static GosPackageState getFiltered(Computer pmComputer,
                                      PackageStateInternal packageState, GosPackageState gosPs,
                                      int callingUid, int callingPid, int userId) {
        final int appId = packageState.getAppId();

        GosPackageStatePermission permission = GosPackageStatePermissions.get(callingUid, callingPid, appId, userId, false);
        if (permission == null) {
            return NONE;
        }

        maybeDeriveFlags(pmComputer, gosPs, packageState);
        return permission.filterRead(gosPs);
    }

    boolean set(final int callingUid, final int callingPid,
                       String packageName, int userId,
                       GosPackageState update, int editorFlags) {
        final int appId;

        synchronized (pm.mLock) {
            PackageSetting packageSetting = pm.mSettings.getPackageLPr(packageName);
            if (packageSetting == null) {
                Slogf.d(TAG, "set: no packageSetting for %s", packageName);
                return false;
            }

            appId = packageSetting.getAppId();

            // Packages with this appId use the "android.uid.system" sharedUserId, which is expensive
            // to deal with due to the large number of packages that it includes (see GosPackageState
            // doc). These packages have no need for GosPackageState.
            if (appId == Process.SYSTEM_UID) {
                Slogf.d(TAG, "set: appId of %s == SYSTEM_UID", packageName);
                return false;
            }

            GosPackageStatePermission permission = GosPackageStatePermissions.get(
                    callingUid, callingPid, appId, userId, true);

            if (permission == null) {
                Slog.d(TAG, "no write permission");
                return false;
            }

            PackageUserStateInternal userState = packageSetting.getUserStates().get(userId);
            if (userState == null) {
                Slog.d(TAG, "no user state");
                return false;
            }

            GosPackageState currentGosPs = userState.getGosPackageState();
            GosPackageState updatedGosPs = permission.filterWrite(currentGosPs, update);

            SharedUserSetting sharedUser = pm.mSettings.getSharedUserSettingLPr(packageSetting);

            if (sharedUser != null) {
                List<AndroidPackage> sharedPkgs = sharedUser.getPackages();

                // see GosPackageState doc
                for (AndroidPackage sharedPkg : sharedPkgs) {
                    PackageSetting sharedPkgSetting = pm.mSettings.getPackageLPr(sharedPkg.getPackageName());
                    if (sharedPkgSetting != null) {
                        sharedPkgSetting.setGosPackageState(userId, updatedGosPs);
                    }
                }
            } else {
                packageSetting.setGosPackageState(userId, updatedGosPs);
            }

            // will invalidate app-side caches (GosPackageState.sCache)
            pm.scheduleWritePackageRestrictions(userId);
        }

        if ((editorFlags & EDITOR_FLAG_KILL_UID_AFTER_APPLY) != 0) {
            final long token = Binder.clearCallingIdentity();
            try {
                // important to call outside the 'synchronized (pm.mLock)' section, may deadlock otherwise
                ActivityManager.getService().killUid(appId, userId, "GosPackageState");
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        if ((editorFlags & EDITOR_FLAG_NOTIFY_UID_AFTER_APPLY) != 0) {
            int uid = UserHandle.getUid(userId, appId);

            final long token = Binder.clearCallingIdentity();
            try {
                var am = LocalServices.getService(ActivityManagerInternal.class);
                // New GosPackageState is intentionally not passed since the GosPackageState change
                // callback is delivered to the app process via a oneway binder call. Oneway binder
                // calls to frozen processes are queued until the process is unfrozen. Not passing
                // the GosPackageState also removes the need to maintain ordering between
                // concurrent GosPackageState changes.
                am.dispatchGosPackageStateCallbacks(uid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        return true;
    }

    private static void maybeDeriveFlags(Computer snapshot, GosPackageState gosPs, PackageStateInternal pkgState) {
        if ((gosPs.derivedFlags & DerivedPackageFlag.DFLAGS_SET) != 0) {
            return;
        }

        if (!gosPs.hasFlag(GosPackageStateFlag.STORAGE_SCOPES_ENABLED) && !gosPs.hasFlag(GosPackageStateFlag.CONTACT_SCOPES_ENABLED)) {
            return;
        }

        AndroidPackageInternal pkg = pkgState.getPkg();
        if (pkg == null) {
            // see AndroidPackage.pkg javadoc for an explanation
            return;
        }

        SharedUserApi sharedUser = null;
        if (pkgState.hasSharedUser()) {
            sharedUser = snapshot.getSharedUser(pkgState.getSharedUserAppId());
        }

        int flags;
        if (sharedUser != null) {
            flags = 0;
            for (AndroidPackage sharedPkg : sharedUser.getPackages()) {
                // see GosPackageState doc
                flags = deriveFlags(flags, sharedPkg);
            }
        } else {
            flags = deriveFlags(0, pkg);
        }
        gosPs.derivedFlags = flags | DerivedPackageFlag.DFLAGS_SET;
    }

    private static int deriveFlags(int flags, AndroidPackage pkg) {
        for (ParsedUsesPermission perm : pkg.getUsesPermissionMapping().values()) {
            String name = perm.getName();
            switch (name) {
                case Manifest.permission.READ_EXTERNAL_STORAGE:
                case Manifest.permission.WRITE_EXTERNAL_STORAGE: {
                    boolean writePerm = name.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    flags |= writePerm ?
                            DerivedPackageFlag.HAS_WRITE_EXTERNAL_STORAGE_DECLARATION :
                            DerivedPackageFlag.HAS_READ_EXTERNAL_STORAGE_DECLARATION;

                    int targetSdk = pkg.getTargetSdkVersion();

                    boolean legacy = targetSdk < 29
                                || (targetSdk == 29 && pkg.isRequestLegacyExternalStorage());

                    if (writePerm && legacy) {
                        // when app doesn't have "legacy external storage", WRITE_EXTERNAL_STORAGE
                        // doesn't grant write access
                        flags |= DerivedPackageFlag.EXPECTS_STORAGE_WRITE_ACCESS;
                    }

                    if ((flags & DerivedPackageFlag.EXPECTS_ALL_FILES_ACCESS) == 0) {
                        if (legacy) {
                            flags |= (DerivedPackageFlag.EXPECTS_ALL_FILES_ACCESS
                                    | DerivedPackageFlag.EXPECTS_LEGACY_EXTERNAL_STORAGE);
                        } else {
                            flags |= DerivedPackageFlag.EXPECTS_ACCESS_TO_MEDIA_FILES_ONLY;
                        }
                    }
                    continue;
                }

                case Manifest.permission.MANAGE_EXTERNAL_STORAGE:
                    flags &= ~DerivedPackageFlag.EXPECTS_ACCESS_TO_MEDIA_FILES_ONLY;
                    flags |= DerivedPackageFlag.EXPECTS_ALL_FILES_ACCESS
                            | DerivedPackageFlag.EXPECTS_STORAGE_WRITE_ACCESS
                            | DerivedPackageFlag.HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION;
                    continue;

                case Manifest.permission.MANAGE_MEDIA:
                    flags |= DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION;
                    continue;

                case Manifest.permission.ACCESS_MEDIA_LOCATION:
                    flags |= DerivedPackageFlag.HAS_ACCESS_MEDIA_LOCATION_DECLARATION;
                    continue;

                case Manifest.permission.READ_MEDIA_AUDIO:
                    flags |= DerivedPackageFlag.HAS_READ_MEDIA_AUDIO_DECLARATION;
                    continue;

                case Manifest.permission.READ_MEDIA_IMAGES:
                    flags |= DerivedPackageFlag.HAS_READ_MEDIA_IMAGES_DECLARATION;
                    continue;

                case Manifest.permission.READ_MEDIA_VIDEO:
                    flags |= DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION;
                    continue;

                case Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED:
                    flags |= DerivedPackageFlag.HAS_READ_MEDIA_VISUAL_USER_SELECTED_DECLARATION;
                    continue;

                case Manifest.permission.READ_CONTACTS:
                    flags |= DerivedPackageFlag.HAS_READ_CONTACTS_DECLARATION;
                    continue;

                case Manifest.permission.WRITE_CONTACTS:
                    flags |= DerivedPackageFlag.HAS_WRITE_CONTACTS_DECLARATION;
                    continue;

                case Manifest.permission.GET_ACCOUNTS:
                    flags |= DerivedPackageFlag.HAS_GET_ACCOUNTS_DECLARATION;
                    continue;
            }
        }

        if ((flags & DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION) != 0) {
            if ((flags & (DerivedPackageFlag.HAS_READ_EXTERNAL_STORAGE_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_AUDIO_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_IMAGES_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION
                    | DerivedPackageFlag.HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION)) == 0)
            {
                flags &= ~DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION;
            }
        }

        if ((flags & DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION) != 0) {
            flags |= DerivedPackageFlag.EXPECTS_STORAGE_WRITE_ACCESS;
        }

        if ((flags & DerivedPackageFlag.HAS_ACCESS_MEDIA_LOCATION_DECLARATION) != 0) {
            if ((flags & (DerivedPackageFlag.HAS_READ_EXTERNAL_STORAGE_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_IMAGES_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION
                    | DerivedPackageFlag.HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION)) == 0)
            {
                flags &= ~DerivedPackageFlag.HAS_ACCESS_MEDIA_LOCATION_DECLARATION;
            }
        }

        return flags;
    }

    /** @see PackageManagerService.IPackageManagerImpl#clearApplicationUserData */
    public static void onClearApplicationUserData(PackageManagerService pm, String packageName, int userId) {
        if (packageName.equals(KnownSystemPackages.get(pm.getContext()).contactsProvider)) {
            // discard IDs that refer to entries in the contacts provider database
            clearContactScopesStorage(pm, userId);
        }
    }

    private static void clearContactScopesStorage(PackageManagerService pm, int userId) {
        for (PackageStateInternal ps : pm.snapshotComputer().getPackageStates().values()) {
            PackageUserStateInternal us = ps.getUserStateOrDefault(userId);
            GosPackageState gosPs = us.getGosPackageState();
            if (gosPs.contactScopes != null) {
                gosPs.createEditor(ps.getPackageName(), userId)
                        .setContactScopes(null)
                        .apply();
            }
        }
    }

    static int runShellCommand(PackageManagerShellCommand cmd) {
        String packageName = cmd.getNextArgRequired();
        int userId = Integer.parseInt(cmd.getNextArgRequired());

        GosPackageState.Editor ed = GosPackageState.edit(packageName, userId);
        boolean updatePermissionState = false;

        for (;;) {
            String arg = cmd.getNextArg();
            if (arg == null) {
                if (!ed.apply()) {
                    return 1;
                }
                if (updatePermissionState) {
                    cmd.mPermissionManager.updatePermissionStateAndInvalidateCache(
                            packageName, userId);
                }
                return 0;
            }
            switch (arg) {
                case "add-flag", "clear-flag" ->
                    ed.setFlagState(parseFlag(cmd.getNextArgRequired()), "add-flag".equals(arg));
                case "add-package-flag", "clear-package-flag" ->
                    ed.setPackageFlagState(Integer.parseInt(cmd.getNextArgRequired()),
                            "add-package-flag".equals(arg));
                case "set-storage-scopes" ->
                    ed.setStorageScopes(getByteArrArg(cmd));
                case "set-contact-scopes" ->
                    ed.setContactScopes(getByteArrArg(cmd));
                case "set-kill-uid-after-apply" ->
                    ed.setKillUidAfterApply(Boolean.parseBoolean(cmd.getNextArgRequired()));
                case "set-notify-uid-after-apply" ->
                    ed.setNotifyUidAfterApply(Boolean.parseBoolean(cmd.getNextArgRequired()));
                case "update-permission-state" ->
                    updatePermissionState = true;
                default ->
                    throw new IllegalArgumentException(arg);
            }
        }
    }

    @Nullable
    private static byte[] getByteArrArg(ShellCommand cmd) {
        String s = cmd.getNextArgRequired();
        return "null".equals(s) ? null : libcore.util.HexEncoding.decode(s);
    }
}
