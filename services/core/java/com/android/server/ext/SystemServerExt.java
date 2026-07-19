package com.android.server.ext;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SELinuxFlags;
import com.android.server.pm.PackageManagerService;

import dalvik.system.VMRuntime;

public final class SystemServerExt {

    public final Context context;
    public final Handler bgHandler;
    public final PackageManagerService packageManager;

    private SystemServerExt(Context systemContext, PackageManagerService pm) {
        context = systemContext;
        bgHandler = BackgroundThread.getHandler();
        packageManager = pm;
    }

    /*
     Called after system server has completed its initialization,
     but before any of the apps are started.

     Call from com.android.server.SystemServer#startOtherServices(), at the end of lambda
     that is passed into mActivityManagerService.systemReady()
     */
    public static void init(Context systemContext, PackageManagerService pm) {
        SystemServerExt sse = new SystemServerExt(systemContext, pm);
        sse.bgHandler.post(sse::initBgThread);

        AppCompatConf.init(systemContext);
    }

    void initBgThread() {
        WifiAutoOff.maybeInit(this);
        BluetoothAutoOff.maybeInit(this);

        if (android.os.Flags.isDevBuild()) {
            if (!SELinuxFlags.kernelSupportsSELinuxFlags()) {
                String title = "Kernel doesn't support SELinux flags";
                String msg = "App hardening features that use SELinux flags, such as DCL and ptrace restrictions, do not work.";
                new SystemErrorNotification("missing hardening", title, msg).show(context);
            }

            String[] abis = Build.SUPPORTED_64_BIT_ABIS;
            if (abis.length > 0 && "arm64".equals(VMRuntime.getInstructionSet(abis[0]))) {
                try {
                    long size = 1L << 40;
                    long addr = Os.mmap(0, size, OsConstants.PROT_NONE,
                            OsConstants.MAP_PRIVATE | OsConstants.MAP_ANONYMOUS, null, 0);
                    Os.munmap(addr, size);
                } catch (ErrnoException e) {
                    Slog.e("ARM_VA_CHECK", "", e);
                    String title = "scudo is used instead of hardened_malloc: no kernel support for 48-bit VA";
                    String msg = Log.getStackTraceString(e);
                    new SystemErrorNotification("missing hardening", title, msg).show(context);
                }
            }
        }
    }
}
