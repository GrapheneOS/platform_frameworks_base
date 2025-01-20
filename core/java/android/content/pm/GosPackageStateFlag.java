package android.content.pm;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
@SystemApi
public interface GosPackageStateFlag {
    /* SysApi */ int STORAGE_SCOPES_ENABLED = 0;
    /** @hide */ int ALLOW_ACCESS_TO_OBB_DIRECTORY = 1;
    // flags 2, 3, 4 were used previously, do not reuse them
    /* SysApi */ int CONTACT_SCOPES_ENABLED = 5;
    /** @hide */ int BLOCK_NATIVE_DEBUGGING_NON_DEFAULT = 6;
    /** @hide */ int BLOCK_NATIVE_DEBUGGING = 7;
    /** @hide */ int BLOCK_NATIVE_DEBUGGING_SUPPRESS_NOTIF = 8;
    /** @hide */ int RESTRICT_MEMORY_DYN_CODE_LOADING_NON_DEFAULT = 9;
    /** @hide */ int RESTRICT_MEMORY_DYN_CODE_LOADING = 10;
    /** @hide */ int RESTRICT_MEMORY_DYN_CODE_LOADING_SUPPRESS_NOTIF = 11;
    /** @hide */ int RESTRICT_STORAGE_DYN_CODE_LOADING_NON_DEFAULT = 12;
    /** @hide */ int RESTRICT_STORAGE_DYN_CODE_LOADING = 13;
    /** @hide */ int RESTRICT_STORAGE_DYN_CODE_LOADING_SUPPRESS_NOTIF = 14;
    /** @hide */ int RESTRICT_WEBVIEW_DYN_CODE_LOADING_NON_DEFAULT = 15;
    /** @hide */ int RESTRICT_WEBVIEW_DYN_CODE_LOADING = 16;
    /** @hide */ int USE_HARDENED_MALLOC_NON_DEFAULT = 17;
    /** @hide */ int USE_HARDENED_MALLOC = 18;
    /** @hide */ int USE_EXTENDED_VA_SPACE_NON_DEFAULT = 19;
    /** @hide */ int USE_EXTENDED_VA_SPACE = 20;
    /** @hide */ int FORCE_MEMTAG_NON_DEFAULT = 21;
    /** @hide */ int FORCE_MEMTAG = 22;
    /** @hide */ int FORCE_MEMTAG_SUPPRESS_NOTIF = 23;
    /** @hide */ int ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE = 24;
    // flag 25 was used previously, do not reuse it

    /** @hide */
    @IntDef(value = {
            STORAGE_SCOPES_ENABLED,
            ALLOW_ACCESS_TO_OBB_DIRECTORY,
            CONTACT_SCOPES_ENABLED,
            BLOCK_NATIVE_DEBUGGING_NON_DEFAULT,
            BLOCK_NATIVE_DEBUGGING,
            BLOCK_NATIVE_DEBUGGING_SUPPRESS_NOTIF,
            RESTRICT_MEMORY_DYN_CODE_LOADING_NON_DEFAULT,
            RESTRICT_MEMORY_DYN_CODE_LOADING,
            RESTRICT_MEMORY_DYN_CODE_LOADING_SUPPRESS_NOTIF,
            RESTRICT_STORAGE_DYN_CODE_LOADING_NON_DEFAULT,
            RESTRICT_STORAGE_DYN_CODE_LOADING,
            RESTRICT_STORAGE_DYN_CODE_LOADING_SUPPRESS_NOTIF,
            RESTRICT_WEBVIEW_DYN_CODE_LOADING_NON_DEFAULT,
            RESTRICT_WEBVIEW_DYN_CODE_LOADING,
            USE_HARDENED_MALLOC_NON_DEFAULT,
            USE_HARDENED_MALLOC,
            USE_EXTENDED_VA_SPACE_NON_DEFAULT,
            USE_EXTENDED_VA_SPACE,
            FORCE_MEMTAG_NON_DEFAULT,
            FORCE_MEMTAG,
            FORCE_MEMTAG_SUPPRESS_NOTIF,
            ENABLE_EXPLOIT_PROTECTION_COMPAT_MODE,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Enum {}
}
