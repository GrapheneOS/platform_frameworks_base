package com.android.server.pm.ext;

import android.Manifest;
import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.app.GmsCompatServiceHooks;
import android.app.compat.gms.GmsCompat;
import android.ext.PackageId;
import android.os.Bundle;

import com.android.internal.gmscompat.GmsCompatApp;
import com.android.internal.gmscompat.client.GmsCompatClientService;
import com.android.internal.pm.pkg.component.ParsedProcess;
import com.android.internal.pm.pkg.component.ParsedProcessImpl;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.component.ParsedServiceImpl;
import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl;
import com.android.internal.pm.pkg.parsing.PackageParsingHooks;
import com.android.internal.pm.pkg.parsing.ParsingPackage;

import java.util.ArrayList;
import java.util.List;

public class GmsCompatPkgParsingHooks extends PackageParsingHooks {

    @Nullable
    public static PackageParsingHooks maybeGet(String pkgName) {
        if (!GmsCompat.canBeEnabledFor(pkgName)) {
            return null;
        }

        return switch (pkgName) {
            case PackageId.GMS_CORE_NAME -> new GmsCoreHooks.ParsingHooks();
            case PackageId.PLAY_STORE_NAME -> new PlayStoreHooks.ParsingHooks();
            case PackageId.G_CARRIER_SETTINGS_NAME -> new GCarrierSettingsHooks.ParsingHooks();
            case PackageId.ANDROID_AUTO_NAME -> new AndroidAutoHooks.ParsingHooks();
            default -> new GmsCompatPkgParsingHooks();
        };
    }

    public static ParsedService maybeCreateClientService(ParsingPackage pkg) {
        Bundle metadata = pkg.getMetaData();
        boolean isGmsCoreClient = metadata != null && metadata.containsKey("com.google.android.gms.version");

        if (!isGmsCoreClient) {
            return null;
        }

        ParsedServiceImpl s = createService(pkg, GmsCompatClientService.class.getName());
        s.setPermission(GmsCompatApp.SELF_PERMISSION);

        String pkgName = pkg.getPackageName();
        if (GmsCompat.canBeEnabledFor(pkgName)) {
            // Use a separate process to avoid deadlocks in early process init. Deadlocks were
            // caused by the app asking GmsCompat app to bind to itself synchronously from the main
            // thread, which can never complete since service init happens on the main thread too.
            // Binding has to be synchronous when it's required for starting a service, since
            // otherwise the app would crash.
            s.setProcessName(GmsCompat.gmsCompatProcessNameForPackage(pkgName));
        }

        return s;
    }

    @CallSuper
    @Override
    public List<ParsedProcess> addProcesses(ParsingPackage pkg) {
        var p = new ParsedProcessImpl();
        p.setName(GmsCompat.gmsCompatProcessNameForPackage(pkg.getPackageName()));
        var res = new ArrayList<ParsedProcess>();
        res.add(p);
        return res;
    }

    @CallSuper
    @Override
    public List<ParsedUsesPermissionImpl> addUsesPermissions() {
        return createUsesPerms(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE
                , GmsCompatApp.CLIENT_PERMISSION
        );
    }

    @CallSuper
    @Override
    public void amendParsedService(ParsedServiceImpl s) {
        GmsCompatServiceHooks.amendParsedService(s);
    }
}
