package android.ext;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
@SystemApi
public interface KnownSystemPackage {
    int SETTINGS = 0;
    int SHELL = 1;
    int SYSTEM_UI = 2;
    int SETUP_WIZARD = 3;
    int GMS_COMPAT = 4;

    /** @hide */
    @IntDef(value = {
            SETTINGS,
            SHELL,
            SYSTEM_UI,
            SETUP_WIZARD,
            GMS_COMPAT,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Enum {}
}
