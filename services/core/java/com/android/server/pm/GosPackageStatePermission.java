package com.android.server.pm;

import android.annotation.AppIdInt;
import android.annotation.IntDef;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.os.Build;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.pm.pkg.PackageStateInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.android.internal.util.Preconditions.checkState;

class GosPackageStatePermission {
    static final String TAG = "GosPackageStatePermission";

    // bitmask of flags that can be read/written
    private final long readFlagStorage1;
    private final long writeFlagStorage1;

    static final int FIELD_STORAGE_SCOPES = 0;
    static final int FIELD_CONTACT_SCOPES = 1;
    static final int FIELD_PACKAGE_FLAGS = 2;

    @IntDef(prefix = "FIELD_", value = {
            FIELD_STORAGE_SCOPES, FIELD_CONTACT_SCOPES, FIELD_PACKAGE_FLAGS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Field {}

    // bitmask of fields that can be read/written
    private final int readFields;
    private final int writeFields;

    private final int crossUserPermissions;

    static final int ALLOW_CROSS_USER_PROFILE_READS = 0;
    static final int ALLOW_CROSS_USER_PROFILE_WRITES = 1;
    static final int ALLOW_CROSS_ANY_USER_READS = 2;
    static final int ALLOW_CROSS_ANY_USER_WRITES = 3;

    @IntDef(prefix = "ALLOW_CROSS_", value = {
            ALLOW_CROSS_USER_PROFILE_READS, ALLOW_CROSS_USER_PROFILE_WRITES,
            ALLOW_CROSS_ANY_USER_READS, ALLOW_CROSS_ANY_USER_WRITES,
    })
    @interface CrossUserPermission {}

    private GosPackageStatePermission(long readFlagStorage1, long writeFlagStorage1,
                                      int readFields, int writeFields,
                                      int crossUserPermissions) {
        this.readFlagStorage1 = readFlagStorage1;
        this.writeFlagStorage1 = writeFlagStorage1;
        this.readFields = readFields;
        this.writeFields = writeFields;
        this.crossUserPermissions = crossUserPermissions;
    }

    static GosPackageStatePermission createFull() {
        return new GosPackageStatePermission(-1, -1, -1, -1, -1);
    }

    boolean canReadField(@Field int field) {
        return (readFields & (1 << field)) != 0;
    }

    boolean canWriteField(@Field int field) {
        return (writeFields & (1 << field)) != 0;
    }

    boolean hasCrossUserPermission(@CrossUserPermission int perm) {
        return (crossUserPermissions & (1 << perm)) != 0;
    }

    static class Builder {
        private long readFlagStorage1;
        private long writeFlagStorage1;

        private int readFields;
        private int writeFields;

        private int crossUserPerms;

        Builder readFlag(@GosPackageStateFlag.Enum int flag) {
            readFlagStorage1 |= (1L << flag);
            return this;
        }

        Builder readFlags(@GosPackageStateFlag.Enum int... flags) {
            for (int flag : flags) {
                readFlagStorage1 |= (1L << flag);
            }
            return this;
        }

        Builder readWriteFlag(@GosPackageStateFlag.Enum int flag) {
            readFlagStorage1 |= (1L << flag);
            writeFlagStorage1 |= (1L << flag);
            return this;
        }

        Builder readWriteFlags(@GosPackageStateFlag.Enum int... flags) {
            for (int flag : flags) {
                readFlagStorage1 |= (1L << flag);
                writeFlagStorage1 |= (1L << flag);
            }
            return this;
        }

        Builder readField(@Field int field) {
            readFields |= (1 << field);
            return this;
        }

        Builder readFields(@Field int... fields) {
            for (int field : fields) {
                readFields |= (1 << field);
            }
            return this;
        }

        Builder readWriteField(@Field int field) {
            readFields |= (1 << field);
            writeFields |= (1 << field);
            return this;
        }

        Builder readWriteFields(@Field int... fields) {
            for (int field : fields) {
                readFields |= (1 << field);
                writeFields |= (1 << field);
            }
            return this;
        }

        Builder crossUserPermission(@CrossUserPermission int perm) {
            crossUserPerms |= (1 << perm);
            return this;
        }

        Builder crossUserPermissions(@CrossUserPermission int... perms) {
            for (int perm : perms) {
                crossUserPerms |= (1 << perm);
            }
            return this;
        }

        GosPackageStatePermission create() {
            return new GosPackageStatePermission(readFlagStorage1, writeFlagStorage1, readFields, writeFields,
                    crossUserPerms);
        }

        void apply(String pkgName, Computer computer) {
            PackageStateInternal psi = computer.getPackageStateInternal(pkgName);
            if (psi == null || !psi.isSystem()) {
                String msg = pkgName + " is not a system package";
                Slog.d(TAG, msg);
                if (Build.IS_DEBUGGABLE) {
                    throw new IllegalStateException(msg);
                }
                return;
            }
            apply(psi.getAppId());
        }

        void apply(@AppIdInt int appId) {
            if (Build.IS_DEBUGGABLE) {
                checkState(GosPackageStatePermissions.grantedPermissions.get(appId) == null);
            }
            GosPackageStatePermissions.grantedPermissions.put(appId, create());
        }
    }

    boolean canWrite() {
        return writeFlagStorage1 != 0L || writeFields != 0L;
    }

    boolean checkCrossUserPermissions(int callingUid, int targetUserId, boolean forWrite) {
        int callingUserId = UserHandle.getUserId(callingUid);

        if (targetUserId == callingUserId) {
            // caller and target are in the same userId
            return true;
        }

        final int crossAnyUserFlag = forWrite?
                ALLOW_CROSS_ANY_USER_WRITES : ALLOW_CROSS_ANY_USER_READS;

        if (hasCrossUserPermission(crossAnyUserFlag)) {
            // caller is allowed to access any user
            return true;
        }

        final int crossProfileFlag = forWrite?
                ALLOW_CROSS_USER_PROFILE_WRITES : ALLOW_CROSS_USER_PROFILE_READS;

        if (hasCrossUserPermission(crossProfileFlag)) {
            if (GosPackageStatePermissions.userManager.getProfileParentId(targetUserId) == callingUserId) {
                // caller is allowed to access its child profile
                return true;
            }
        }

        Slog.d(TAG, "not allowed to access userId " + targetUserId + " from uid " + callingUid);
        return false;
    }

    GosPackageState filterRead(GosPackageState ps) {
        var default_ = GosPackageState.DEFAULT;
        var res = new GosPackageState(ps.flagStorage1 & readFlagStorage1
                , canReadField(FIELD_PACKAGE_FLAGS) ? ps.packageFlagStorage : default_.packageFlagStorage
                , canReadField(FIELD_STORAGE_SCOPES) ? ps.storageScopes : default_.storageScopes
                , canReadField(FIELD_CONTACT_SCOPES) ? ps.contactScopes : default_.contactScopes
        );
        if (default_.equals(res)) {
            return default_;
        }
        // derivedFlags are intentionally not filtered, see its javadoc
        res.derivedFlags = ps.derivedFlags;
        return res;
    }

    GosPackageState filterWrite(GosPackageState current, GosPackageState update) {
        long flagStorage1 = (current.flagStorage1 & ~writeFlagStorage1) | (update.flagStorage1 & writeFlagStorage1);

        // flags that can't be unset
        long oneWayFlags1 = (1L << GosPackageStateFlag.PLAY_INTEGRITY_API_USED_AT_LEAST_ONCE);
        flagStorage1 |= current.flagStorage1 & oneWayFlags1;

        var res = new GosPackageState(
                flagStorage1
                , canWriteField(FIELD_PACKAGE_FLAGS) ? update.packageFlagStorage : current.packageFlagStorage
                , canWriteField(FIELD_STORAGE_SCOPES) ? update.storageScopes : current.storageScopes
                , canWriteField(FIELD_CONTACT_SCOPES) ? update.contactScopes : current.contactScopes
        );
        var default_ = GosPackageState.DEFAULT;
        if (default_.equals(res)) {
            return default_;
        }
        return res;
    }
}
