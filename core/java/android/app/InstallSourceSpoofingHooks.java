package android.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.SigningInfo;
import android.ext.PackageId;
import android.ext.settings.BoolSetting;
import android.ext.settings.Setting;
import android.provider.Settings;
import android.util.Log;

import java.util.Objects;

/** @hide */
public class InstallSourceSpoofingHooks {
    static final String TAG = "InstallSourceSpoofingHooks";

    private static final BoolSetting setting = new BoolSetting(Setting.Scope.PER_USER,
            Settings.Secure.SPOOF_INSTALL_SOURCE_FOR_PLAY_STORE_APPS, true);

    // returns Play Store signing info if spoofing should be performed
    static SigningInfo shouldSpoof(Context context, PackageManager pm, String pkgName) {
        SigningInfo res = shouldSpoofInner(context, pm, pkgName);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "shouldSpoof result for " + pkgName + ": " + res);
        }
        return res;
    }

    private static SigningInfo shouldSpoofInner(Context context, PackageManager pm, String pkgName) {
        ApplicationInfo selfAppInfo = context.getApplicationInfo();
        if (selfAppInfo.isSystemApp()) {
            return null;
        }
        switch (selfAppInfo.ext().getPackageId()) {
            case PackageId.GMS_CORE:
            case PackageId.PLAY_STORE:
                return null;
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "shouldSpoof called for " + pkgName, new Throwable());
        }
        if (!setting.get(context)) {
            return null;
        }
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkgName,
                    ApplicationInfoFlags.of(PackageManager.GET_PLAY_STORE_SOURCE_STAMP_STATE));
            if (appInfo.isSystemApp() || (!appInfo.hasPlayStoreSourceStamp() && !appInfo.ext().hasCompatChange(
                    com.android.server.os.nano.AppCompatProtos.SPOOF_INSTALLER_CHECKS))) {
                return null;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        PackageInfo playStorePkgInfo;
        try {
            playStorePkgInfo = pm.getPackageInfo(PackageId.PLAY_STORE_NAME,
                    PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        ApplicationInfo playStoreAppInfo = playStorePkgInfo.applicationInfo;
        if (playStoreAppInfo == null) {
            return null;
        }
        if (playStoreAppInfo.ext().getPackageId() != PackageId.PLAY_STORE) {
            return null;
        }
        return Objects.requireNonNull(playStorePkgInfo.signingInfo);
    }

    static InstallSourceInfo getPlayStoreInstallSourceInfo(SigningInfo playStoreSigningInfo) {
        return new InstallSourceInfo(
                PackageId.PLAY_STORE_NAME, // initiatingPackageName
                playStoreSigningInfo, // initiatingPackageSigningInfo
                null, // originatingPackageName, which is hidden from packages without the
                // privileged INSTALL_PACKAGES permission. Spoofing of installer checks can be
                // enabled only for third-party apps, which are always unprivileged.
                PackageId.PLAY_STORE_NAME, // installingPackageName
                null, // updateOwnerPackageName
                // Play Store doesn't set the package source value
                PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);
    }
}
