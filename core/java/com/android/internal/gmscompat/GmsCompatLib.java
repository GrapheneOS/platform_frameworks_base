package com.android.internal.gmscompat;

import android.content.Context;
import android.content.pm.PackageManager;

import com.android.internal.util.Preconditions;

public class GmsCompatLib {
    private static IGmsCompatLib instance;

    public static IGmsCompatLib get() {
        return instance;
    }

    public static void init(Context appContext, String processName) {
        Preconditions.checkState(instance == null);

        Context libCtx;
        try {
            int flags = Context.CONTEXT_INCLUDE_CODE
                    // GmsCompatLib is part of system image
                    | Context.CONTEXT_IGNORE_SECURITY;
            libCtx = appContext.createPackageContext("app.grapheneos.gmscompat.lib", flags);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }

        IGmsCompatLib lib;
        try {
            Class cls = libCtx.getClassLoader().loadClass("app.grapheneos.gmscompat.lib.GmsCompatLibImpl");
            lib = (IGmsCompatLib) cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        instance = lib;
        lib.init(appContext, processName);
    }
}
