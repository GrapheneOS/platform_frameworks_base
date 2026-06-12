package com.android.internal.os;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.ext.settings.app.AswUseExecSpawning;
import android.ext.settings.app.AswUseExtendedVaSpace;
import android.ext.settings.app.AswUseHardenedMalloc;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ZygoteProcess;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HexFormat;

public class ZygoteExtraArgs implements Parcelable {
    private long selinuxFlags;
    private int flags;
    @Nullable private byte[] nativeAppLaunchCommand;

    public static final String ARG_PREFIX = "--gos-extra-args=";
    // checked by isSimpleForkCommand() in core/jni/com_android_internal_os_ZygoteCommandBuffer.cpp
    public static final String ARG_COMPLEX_COMMAND_MARKER = "--is-complex-zygote-command";

    public static final ZygoteExtraArgs DEFAULT = new ZygoteExtraArgs();

    private ZygoteExtraArgs() {}

    // keep in sync with ExtraArgsFlag in core/jni/com_android_internal_os_Zygote.cpp
    public interface Flag {
        // hardened_malloc is always disabled when PREFER_COMPAT_ZYGOTE is set
        int DISABLE_HARDENED_MALLOC = 1;
        // 39-bit is available only on arm64 and is always enabled when PREFER_COMPAT_ZYGOTE is set
        int ENABLE_COMPAT_VA_39_BIT = 1 << 1;
        int FORCIBLY_ENABLE_MEMORY_TAGGING = 1 << 2;
        int USE_ZYGOTE_SPAWNING = 1 << 3;
        int PREFER_COMPAT_ZYGOTE = 1 << 4;
        int MANUALLY_RUN_ZYGOTE_PRELOAD = 1 << 5;

        @IntDef(flag = true, value = {
                DISABLE_HARDENED_MALLOC,
                ENABLE_COMPAT_VA_39_BIT,
                FORCIBLY_ENABLE_MEMORY_TAGGING,
                PREFER_COMPAT_ZYGOTE,
                MANUALLY_RUN_ZYGOTE_PRELOAD,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface Enum {}
    }

    public static ZygoteExtraArgs create(Context ctx, int userId, ApplicationInfo appInfo,
                                         boolean shouldForciblyEnableMemoryTagging,
                                         GosPackageState ps,
                                         boolean isIsolatedProcess) {
        var res = new ZygoteExtraArgs();
        res.selinuxFlags = SELinuxFlags.get(ctx, userId, appInfo, ps, isIsolatedProcess);
        boolean useZygoteSpawning = !AswUseExecSpawning.I.get(ctx, userId, appInfo, ps);
        if (useZygoteSpawning) {
            res.setFlag(Flag.USE_ZYGOTE_SPAWNING, true);
            if (!AswUseHardenedMalloc.I.get(ctx, userId, appInfo, ps)) {
                res.setFlag(Flag.PREFER_COMPAT_ZYGOTE, true);
            }
        } else {
            res.setFlag(Flag.DISABLE_HARDENED_MALLOC, !AswUseHardenedMalloc.I.get(ctx, userId, appInfo, ps));
            res.setFlag(Flag.ENABLE_COMPAT_VA_39_BIT, !AswUseExtendedVaSpace.I.get(ctx, userId, appInfo, ps));
        }
        res.setFlag(Flag.FORCIBLY_ENABLE_MEMORY_TAGGING, shouldForciblyEnableMemoryTagging);
        return res;
    }

    public static ZygoteExtraArgs createForWebviewZygote() {
        return DEFAULT;
    }

    public static ZygoteExtraArgs createForWebviewProcess(Context ctx, int userId,
                                                          ApplicationInfo callerAppInfo, GosPackageState callerPs) {
        var res = new ZygoteExtraArgs();
        res.selinuxFlags = SELinuxFlags.getForWebViewProcess(ctx, userId, callerAppInfo, callerPs);
        res.setFlag(Flag.USE_ZYGOTE_SPAWNING, !AswUseExecSpawning.I.get(ctx, userId, callerAppInfo, callerPs));
        return res;
    }

    public boolean hasFlag(@Flag.Enum int flag) {
        return (this.flags & flag) == flag;
    }

    public void setFlag(int flag, boolean value) {
        if (value) {
            this.flags |= flag;
        } else {
            this.flags &= ~flag;
        }
    }

    public long getSelinuxFlags() {
        return selinuxFlags;
    }

    @Nullable
    public byte[] getNativeAppLaunchCommand() {
        return nativeAppLaunchCommand;
    }

    public void setNativeAppLaunchCommand(byte[] value) {
        nativeAppLaunchCommand = value;
    }

    public boolean shouldUseExecSpawning() {
        return !hasFlag(Flag.USE_ZYGOTE_SPAWNING);
    }

    static ZygoteExtraArgs parse(String argValue) {
        byte[] serialized = HexFormat.of().parseHex(argValue);
        Parcel p = Parcel.obtain();
        try {
            p.unmarshall(serialized, 0, serialized.length);
            p.setDataPosition(0);
            return new ZygoteExtraArgs(p);
        } finally {
            p.recycle();
        }
    }

    public void toZygoteArgList(ArrayList<String> argsForZygote) {
        if (shouldUseExecSpawning()) {
            argsForZygote.add(ARG_COMPLEX_COMMAND_MARKER);
        }
        byte[] serialized;
        Parcel p = Parcel.obtain();
        try {
            writeToParcel(p, 0);
            serialized = p.marshall();
        } finally {
            p.recycle();
        }
        argsForZygote.add(ARG_PREFIX + HexFormat.of().formatHex(serialized));
    }

    // this field is intentionally not serialized, it's stored here only for passing its value
    // throughout the system_server codepath
    private ApplicationInfo preloadAppInfo;

    public void setPreloadAppInfo(ApplicationInfo appInfo) {
        preloadAppInfo = appInfo;
    }

    public ApplicationInfo getPreloadAppInfo() {
        return preloadAppInfo;
    }

    // keep in sync with ExtraArgs struct in core/jni/com_android_internal_os_Zygote.cpp
    private static final int IDX_SELINUX_FLAGS = 0;
    private static final int IDX_FLAGS = 1;
    private static final int ARR_LEN = 2;

    public long[] makeJniLongArray() {
        long[] res = new long[ARR_LEN];
        res[IDX_SELINUX_FLAGS] = selinuxFlags;
        res[IDX_FLAGS] = flags;
        return res;
    }

    @Override
    public void writeToParcel(Parcel p, int parcelFlags) {
        p.writeLong(this.selinuxFlags);
        p.writeInt(this.flags);
        p.writeByteArray(this.nativeAppLaunchCommand);
    }

    ZygoteExtraArgs(Parcel p) {
        selinuxFlags = p.readLong();
        flags = p.readInt();
        nativeAppLaunchCommand = p.createByteArray();
    }

    public static final Parcelable.Creator<ZygoteExtraArgs> CREATOR = new Creator<>() {
        @Override
        public ZygoteExtraArgs createFromParcel(Parcel p) {
            return new ZygoteExtraArgs(p);
        }

        @Override
        public ZygoteExtraArgs[] newArray(int size) {
            return new ZygoteExtraArgs[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }
}
