package app.grapheneos.hardeningtest;

import android.annotation.NonNull;
import android.app.ZygotePreload;
import android.content.pm.ApplicationInfo;
import android.system.Os;
import android.util.Log;

public class ZygotePreloadImpl implements ZygotePreload {
    private static final String TAG = ZygotePreloadImpl.class.getSimpleName();

    @Override
    public void doPreload(@NonNull ApplicationInfo appInfo) {
        String expected = Os.getenv("IS_EXEC_SPAWNED_APP_PROCESS") != null ?
                "u:r:isolated_app:s0:c" :
                "u:r:app_zygote:s0:c";
        // check that app zygote is not allowed to change SELinux flags
        Utils.checkSELinuxContextAndFlags(expected, appInfo.targetSdkVersion);
        Log.d(TAG, "doPreload completed");
    }
}
