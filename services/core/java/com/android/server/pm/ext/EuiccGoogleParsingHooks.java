package com.android.server.pm.ext;

import android.content.pm.PackageManager;

import com.android.internal.pm.pkg.parsing.PackageParsingHooks;

class EuiccGoogleParsingHooks extends PackageParsingHooks {

    @Override
    public int overrideDefaultPackageEnabledState() {
        return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}
