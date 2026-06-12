package app.grapheneos.goscompat.securespawn.shared;

import app.grapheneos.goscompat.securespawn.SecureSpawnCheck;

public final class SecureSpawnDumpableCheck {
    private SecureSpawnDumpableCheck() {
    }

    public static DumpableState run(boolean execSpawned) {
        return new DumpableState(execSpawned, SecureSpawnCheck.dumpable());
    }

    public record DumpableState(boolean execSpawned, int dumpable) {
        public boolean isDumpable() {
            return dumpable() == 1;
        }

        @Override
        public String toString() {
            return "execSpawned=" + execSpawned()
                    + "\ndumpable=" + dumpable()
                    + "\nisDumpable=" + isDumpable();
        }
    }
}
