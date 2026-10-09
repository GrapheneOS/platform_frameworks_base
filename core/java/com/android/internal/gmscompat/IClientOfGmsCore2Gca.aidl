package com.android.internal.gmscompat;

import android.os.BinderDef;

// calls from clients of GMS Core to GmsCompatApp
interface IClientOfGmsCore2Gca {
    @nullable BinderDef maybeGetBinderDef(String callerPkg, int processState, String ifaceName);

    oneway void showMissingAppNotification(String pkgName);

    oneway void showPlayIntegrityNotification(String pkgName, boolean isBlocked);

    oneway void onGoogleIdCredentialOptionInit();
}
