package com.android.internal.os;

import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.util.Arrays;

import static com.android.internal.util.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class ExecSpawning {
    static final String TAG = "ExecSpawning";

    // Zygote argument for passing commands from zygote to exec-spawned app process
    static final String COMMAND_FD_ARG = "--command-fd=";

    private static boolean isExecSpawnedProcess;
    private static ZygoteArguments[] commandsToReplay;

    public static boolean isExecSpawnedProcess() {
        return isExecSpawnedProcess;
    }

    public static boolean isReplayingZygoteCommands() {
        return commandsToReplay != null;
    }

    static void init(String[] argv) {
        boolean logv = Log.isLoggable(TAG, Log.VERBOSE);
        if (logv) Log.v(TAG, "argv: " + Arrays.toString(argv));

        String arg = argv[argv.length - 1];
        if (!arg.startsWith(COMMAND_FD_ARG)) {
            if (arg.equals("--socket-name=zygote")) {
                // we're in the primary 64-bit zygote
                ZygoteCommandRecorder.enable(true);
            } else if (arg.equals("--enable-lazy-preload") && "--socket-name=zygote_secondary".equals(argv[argv.length - 2])) {
                // we're in the 32-bit zygote
                ZygoteCommandRecorder.enable(false);
            }
            return;
        }

        if (logv) Log.v(TAG, "init");

        isExecSpawnedProcess = true;

        byte[] serializedCmds;
        {
            int separator = arg.indexOf('_', COMMAND_FD_ARG.length());
            int fd = Integer.parseInt(arg, COMMAND_FD_ARG.length(), separator, 10);
            int fdSize = Integer.parseInt(arg, separator + 1, arg.length(), 10);
            try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))) {
                serializedCmds = stream.readNBytes(fdSize);
            } catch (IOException e) {
                Log.e(TAG, "", e);
                // fd is a memfd, using it should never fail
                throw new IllegalStateException(e);
            }
            checkState(serializedCmds.length == fdSize);
        }
        checkState(commandsToReplay == null);
        commandsToReplay = ZygoteCommandRecorder.deserializeCommands(serializedCmds);
    }

    static Runnable replayCommands(ZygoteServer zygoteServer) {
        ZygoteArguments[] commandsToReplay = ExecSpawning.commandsToReplay;
        requireNonNull(commandsToReplay);

        ZygoteConnection pseudoConnection;
        try {
            pseudoConnection = new ZygoteConnection(null, null);
        } catch (IOException e) {
            // pseudo ZygoteConnection doesn't perform any IO
            throw new IllegalStateException(e);
        }

        boolean logv = Log.isLoggable(TAG, Log.VERBOSE);

        for (int i = 0; i < commandsToReplay.length; ++i) {
            ZygoteArguments cmd = commandsToReplay[i];
            if (logv) Log.v(TAG, "replaying cmd " + (i + 1) + " / " + commandsToReplay.length + ": " + cmd);
            Runnable r = pseudoConnection.processCommand(zygoteServer, false, cmd);
            if (i == commandsToReplay.length - 1) {
                checkState(r != null);
                ExecSpawning.commandsToReplay = null;
                return r;
            }
            if (r != null) { // some commands run in-place, without a Runnable
                r.run();
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static boolean hasPendingZygotePreload;

    public static void addPendingZygotePreload() {
        Preconditions.checkState(!hasPendingZygotePreload);
        hasPendingZygotePreload = true;
    }

    public static void maybeRunPendingZygotePreload(ApplicationInfo appInfo) {
        if (hasPendingZygotePreload) {
            hasPendingZygotePreload = false;
            Log.d(TAG, "running manual ZygotePreload");
            // Note that the process is in the isolated_app SELinux domain at this point. When zygote
            // spawning is used, app zygote is running in the app_zygote SELinux domain when it performs
            // preloading. This shouldn't cause issues in practice since app_zygote and isolated_app
            // domains have similar level of isolation.
            AppZygoteInit.AppZygoteConnection.handlePreloadAppStatic(null, appInfo);
        }
    }

    public static boolean shouldSkipZygotePreload(ApplicationInfo appInfo, boolean isNative) {
        if (isNative) {
            String zygotePreloadNativeLib = appInfo.zygotePreloadNativeLib;
            switch (zygotePreloadNativeLib) {
                case "libmonochrome_64.so":
                    Log.i(TAG, "skipping zygotePreloadNativeLib: " + zygotePreloadNativeLib);
                    return true;
                default:
                    Log.w(TAG, "keeping unknown zygotePreloadNativeLib: " + zygotePreloadNativeLib);
                    return false;
            }
        } else {
            String zygotePreloadName = appInfo.zygotePreloadName;
            switch (zygotePreloadName) {
                case "org.chromium.chrome.app.TrichromeZygotePreload":
                case "org.chromium.content_public.app.ZygotePreload":
                case "org.mozilla.gecko.process.ZygotePreload":
                // VosZygotePreload is used by https://www.v-key.com/products/v-os-mobile-app-protection/
                // Its checks don't pass for an unknown reason when exec spawning is used, but
                // skipping them entirely works at least for some apps (e.g. for com.dbs.sg.dbsmbanking app)
                case "vkey.android.vos.VosZygotePreload":
                    Log.i(TAG, "skipping Java ZygotePreload: " + zygotePreloadName);
                    return true;
                default:
                    Log.w(TAG, "keeping unknown Java ZygotePreload: " + zygotePreloadName);
                    return false;
            }
        }
    }
}
