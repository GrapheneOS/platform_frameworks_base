package com.android.internal.os;

import android.os.Parcel;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Objects;

import static com.android.internal.util.Preconditions.checkState;

// Records zygote commands that have to be replayed in exec-spawned zygote as part of exec spawning
class ZygoteCommandRecorder {
    private static final String TAG = "ZygoteCommandRecorder";
    private static boolean isEnabled;
    private static boolean is64bit;

    enum CommandType {
        BootCompleted,
        ApiDenylistExemptions,
        HiddenApiAccessLogSampleRate,
    }

    // intentionally using an insertion-ordered map
    private static LinkedHashMap<CommandType, ZygoteArguments> capturedCommands;

    static void enable(boolean is64bit) {
        Log.d(TAG, "enabled, is64bit: " + is64bit);
        checkState(!isEnabled);
        isEnabled = true;
        ZygoteCommandRecorder.is64bit = is64bit;
        capturedCommands = new LinkedHashMap<>();
    }

    static boolean is64bit() {
        return is64bit;
    }

    static void maybeAddReplayCommand(CommandType type, ZygoteArguments cmd) {
        if (!isEnabled) {
            return;
        }
        Objects.requireNonNull(cmd);

        if (ExecSpawning.isExecSpawnedProcess()) {
            return;
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "maybeAddReplayCommand: added " + type + ": " + cmd, new Throwable());
        }

        capturedCommands.put(type, cmd);
    }

    static byte[] addFinalCommandAndSerialize(ZygoteArguments finalCmd) {
        checkState(isEnabled);
        int numReplayCmds = capturedCommands.size();

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "addFinalCommandAndSerialize: " + finalCmd);
        }

        var p = Parcel.obtain();
        byte[] serializedCommands;
        try {
            p.writeInt(numReplayCmds);
            for (ZygoteArguments cmd : capturedCommands.values()) {
                cmd.writeToParcel(p, 0);
            }
            finalCmd.writeToParcel(p, 0);
            serializedCommands = p.marshall();
        } finally {
            p.recycle();
        }
        return serializedCommands;
    }

    static ZygoteArguments[] deserializeCommands(byte[] data) {
        Parcel p = Parcel.obtain();
        try {
            p.unmarshall(data, 0, data.length);
            p.setDataPosition(0);
            int numReplayCmds = p.readInt();
            ZygoteArguments[] commands = new ZygoteArguments[numReplayCmds + 1];
            for (int i = 0; i < numReplayCmds; ++i) {
                commands[i] = ZygoteArguments.CREATOR.createFromParcel(p);
            }
            commands[numReplayCmds] = ZygoteArguments.CREATOR.createFromParcel(p);
            return commands;
        } finally {
            p.recycle();
        }
    }
}
