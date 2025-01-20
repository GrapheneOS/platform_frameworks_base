package android.ext;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
@SystemApi
public interface DerivedPackageFlag {
    // to distinguish between the case when no dflags are set and the case when dflags weren't calculated yet
    int DFLAGS_SET = 1;
    int EXPECTS_ALL_FILES_ACCESS = 1 << 1;
    int EXPECTS_ACCESS_TO_MEDIA_FILES_ONLY = 1 << 2;
    int EXPECTS_STORAGE_WRITE_ACCESS = 1 << 3;
    int HAS_READ_EXTERNAL_STORAGE_DECLARATION = 1 << 4;
    int HAS_WRITE_EXTERNAL_STORAGE_DECLARATION = 1 << 5;
    int HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION = 1 << 6;
    int HAS_MANAGE_MEDIA_DECLARATION = 1 << 7;
    int HAS_ACCESS_MEDIA_LOCATION_DECLARATION = 1 << 8;
    int HAS_READ_MEDIA_AUDIO_DECLARATION = 1 << 9;
    int HAS_READ_MEDIA_IMAGES_DECLARATION = 1 << 10;
    int HAS_READ_MEDIA_VIDEO_DECLARATION = 1 << 11;
    int HAS_READ_MEDIA_VISUAL_USER_SELECTED_DECLARATION = 1 << 12;
    int EXPECTS_LEGACY_EXTERNAL_STORAGE = 1 << 13;
    int HAS_READ_CONTACTS_DECLARATION = 1 << 20;
    int HAS_WRITE_CONTACTS_DECLARATION = 1 << 21;
    int HAS_GET_ACCOUNTS_DECLARATION = 1 << 22;

    /** @hide */
    @IntDef(flag = true, value = {
            DFLAGS_SET,
            EXPECTS_ALL_FILES_ACCESS,
            EXPECTS_ACCESS_TO_MEDIA_FILES_ONLY,
            EXPECTS_STORAGE_WRITE_ACCESS,
            HAS_READ_EXTERNAL_STORAGE_DECLARATION,
            HAS_WRITE_EXTERNAL_STORAGE_DECLARATION,
            HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION,
            HAS_MANAGE_MEDIA_DECLARATION,
            HAS_ACCESS_MEDIA_LOCATION_DECLARATION,
            HAS_READ_MEDIA_AUDIO_DECLARATION,
            HAS_READ_MEDIA_IMAGES_DECLARATION,
            HAS_READ_MEDIA_VIDEO_DECLARATION,
            HAS_READ_MEDIA_VISUAL_USER_SELECTED_DECLARATION,
            EXPECTS_LEGACY_EXTERNAL_STORAGE,
            HAS_READ_CONTACTS_DECLARATION,
            HAS_WRITE_CONTACTS_DECLARATION,
            HAS_GET_ACCOUNTS_DECLARATION,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Enum {}
}
