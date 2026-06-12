package app.grapheneos.goscompat.securespawn;

import android.os.Process;

import app.grapheneos.goscompat.securespawn.shared.SecureSpawnHiddenApiCheck;
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnReflectiveDumpCheck;
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnSmapsCheck;
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnTestApiCompatCheck;

public final class SecureSpawnCheck {
    static {
        System.loadLibrary("goscompat_secure_spawn_jni");
    }

    private SecureSpawnCheck() {
    }

    public static Result run() {
        ProcessState processState = processState();
        return new Result(
                secureAppSpawningSetting(),
                processState,
                androidRuntimeSmaps(),
                SecureSpawnHiddenApiCheck.run(processState.execSpawned()),
                SecureSpawnTestApiCompatCheck.run(processState.execSpawned()),
                SecureSpawnReflectiveDumpCheck.run(processState.execSpawned()));
    }

    public static SecureAppSpawningSetting secureAppSpawningSetting() {
        return new SecureAppSpawningSetting(System.getenv("IS_EXEC_SPAWNED_APP_PROCESS") != null);
    }

    public static ProcessState processState() {
        return new ProcessState(
                System.getenv("IS_EXEC_SPAWNED_APP_PROCESS") != null,
                System.getenv("DISABLE_HARDENED_MALLOC") != null,
                Process.myPid(),
                Process.myTid());
    }

    public static SecureSpawnSmapsCheck.AndroidRuntimeSmaps androidRuntimeSmaps() {
        return SecureSpawnSmapsCheck.run();
    }

    public static SecureSpawnHiddenApiCheck.HiddenApiEnforcement hiddenApiEnforcement() {
        return SecureSpawnHiddenApiCheck.run(processState().execSpawned());
    }

    public static SecureSpawnTestApiCompatCheck.TestApiCompat testApiCompatDefault() {
        return SecureSpawnTestApiCompatCheck.run(processState().execSpawned());
    }

    public static SecureSpawnReflectiveDumpCheck.AcyclicReflectiveDump
            acyclicReflectiveDump() {
        return SecureSpawnReflectiveDumpCheck.run(processState().execSpawned());
    }

    public static int dumpable() {
        return nativeDumpable();
    }

    private static native String nativeSystemProperty(String key);
    private static native int nativeDumpable();

    public record Result(
            SecureAppSpawningSetting secureAppSpawningSetting,
            ProcessState processState,
            SecureSpawnSmapsCheck.AndroidRuntimeSmaps androidRuntimeSmaps,
            SecureSpawnHiddenApiCheck.HiddenApiEnforcement hiddenApiEnforcement,
            SecureSpawnTestApiCompatCheck.TestApiCompat testApiCompatDefault,
            SecureSpawnReflectiveDumpCheck.AcyclicReflectiveDump acyclicReflectiveDump) {}

    public record SecureAppSpawningSetting(boolean enabled) {}

    public record ProcessState(
            boolean execSpawned,
            boolean hardenedMallocDisabled,
            int pid,
            int tid) {
        @Override
        public String toString() {
            return "execSpawned=" + execSpawned()
                    + "\nhardenedMallocDisabled=" + hardenedMallocDisabled()
                    + "\npid=" + pid()
                    + "\ntid=" + tid();
        }
    }
}
