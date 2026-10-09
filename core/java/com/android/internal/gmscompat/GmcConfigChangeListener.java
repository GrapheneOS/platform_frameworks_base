package com.android.internal.gmscompat;

import android.app.compat.gms.GmsCompat;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.gmscompat.flags.GmsFlagOverrides;

import static com.android.internal.gmscompat.GmsHooks.inMainGmsCoreProcess;
import static com.android.internal.gmscompat.GmsInfo.PACKAGE_GMS_CORE;

class GmcConfigChangeListener extends BroadcastReceiver {
    private static final String TAG = "GmcConfigChangeListener";

    static void register(Context context) {
        var receiver = new GmcConfigChangeListener();
        var filter = new IntentFilter(GmsCompatApp.CONFIG_UPDATED_BROADCAST);
        context.registerReceiver(receiver, filter, GmsCompatApp.SELF_PERMISSION,
                GmsHooks.getBackgroundHandler(), Context.RECEIVER_EXPORTED);
    }

    private GmcConfigChangeListener() {}

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "onReceive: " + intent);
        GmsCompatConfig newConfig = GmsCompatApp.call(i -> i.getConfig());
        GmsHooks.setConfig(newConfig);
        onConfigUpdated(newConfig);
    }

    private static void onConfigUpdated(GmsCompatConfig newConfig) {
        if (!inMainGmsCoreProcess) {
            return;
        }

        SQLiteOpenHelper phenotypeDb = GmsHooks.getPhenotypeDb();

        if (phenotypeDb == null) {
            // shouldn't happen in practice, phenotype db is opened on startup
            Log.e(TAG, "phenotypeDb is null");
        } else {
            notifyForceDefaultFlagsChanged(phenotypeDb, newConfig);
        }

        GmsFlagOverrides.applyOverrides();
    }

    private static void notifyForceDefaultFlagsChanged(SQLiteOpenHelper phenotypeDb, GmsCompatConfig newConfig) {
        SQLiteDatabase db = phenotypeDb.getReadableDatabase();
        String[] columns = { "androidPackageName" };
        String selection = "packageName = ?";

        ArraySet<String> configPackageNames = new ArraySet<>(newConfig.forceDefaultFlagsMap.keySet());

        for (String configPackageName : configPackageNames) {
            String[] selectionArgs = { configPackageName };
            String packageName = null;
            try (Cursor c = db.query("Packages", columns, selection, selectionArgs, null, null, null, "1")) {
                if (c.moveToFirst()) {
                    packageName = c.getString(0);
                }
            }

            if (packageName == null) {
                Log.d(TAG, "unknown configPackageName " + configPackageName);
                continue;
            }

            Intent i = new Intent(PACKAGE_GMS_CORE + ".phenotype.UPDATE");
            i.putExtra(PACKAGE_GMS_CORE + ".phenotype.PACKAGE_NAME", configPackageName);
            i.setPackage(packageName);
            GmsCompat.appContext().sendBroadcast(i);
        }
    }
}
