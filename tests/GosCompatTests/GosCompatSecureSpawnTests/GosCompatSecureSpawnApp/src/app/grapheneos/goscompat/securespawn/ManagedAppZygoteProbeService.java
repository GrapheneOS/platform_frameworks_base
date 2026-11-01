package app.grapheneos.goscompat.securespawn;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

public final class ManagedAppZygoteProbeService extends Service {
    private final IManagedProcessProbe.Stub mBinder = new IManagedProcessProbe.Stub() {
        @Override
        public int getPid() {
            return Process.myPid();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
