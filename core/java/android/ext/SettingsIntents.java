package android.ext;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** @hide */
@SystemApi
public class SettingsIntents {

    public static final String APP_NATIVE_DEBUGGING = "android.settings.OPEN_APP_NATIVE_DEBUGGING_SETTINGS";
    public static final String APP_MEMTAG = "android.settings.OPEN_APP_MEMTAG_SETTINGS";
    public static final String APP_HARDENED_MALLOC = "android.settings.OPEN_APP_HARDENED_MALLOC_SETTINGS";
    public static final String APP_MEMORY_DYN_CODE_LOADING = "android.settings.OPEN_APP_MEMORY_DYN_CODE_LOADING_SETTINGS";
    public static final String APP_STORAGE_DYN_CODE_LOADING = "android.settings.OPEN_APP_STORAGE_DYN_CODE_LOADING_SETTINGS";
    public static final String APP_MANAGE_PLAY_INTEGRITY_API = "android.settings.OPEN_APP_MANAGE_PLAY_INTEGRITY_API_SETTINGS";
    public static final String APP_STRICT_LEAK_BLOCKING = "android.settings.OPEN_APP_STRICT_LEAK_BLOCKING";

    // This constructor keeps the linter happy.
    private SettingsIntents() {}

    public static @NonNull Intent createAppIntent(@NonNull Context ctx, @NonNull String action,
            @NonNull String pkgName, boolean newTask) {
        var i = new Intent(action);
        i.setData(Uri.fromParts("package", pkgName, null));
        i.setPackage(KnownSystemPackages.get(ctx).settings);
        if (newTask) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        return i;
    }
}
