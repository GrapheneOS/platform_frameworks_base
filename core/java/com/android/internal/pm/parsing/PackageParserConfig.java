package com.android.internal.pm.parsing;

import android.annotation.Nullable;
import android.os.Build;
import android.util.Log;
import android.util.Slog;

import com.android.internal.pm.parsing.nano.ApkParserConfig;
import com.android.internal.pm.parsing.nano.ApcPackageConfig;
import com.android.internal.pm.parsing.nano.PackageInstallRequirements;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedPermissionGroup;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.pm.pkg.parsing.ParsingPackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

public class PackageParserConfig {
    public static final String TAG = "PackageParserConfig";

    private ApkParserConfig config;
    private HashSet<String> nonInstallablePackages;

    private static volatile PackageParserConfig instance;

    public static PackageParserConfig get() {
        PackageParserConfig res = instance;
        if (res == null) {
            throw new RuntimeException("PackageParserConfig was not initialized");
        }
        return res;
    }

    public static void init() {
        Log.d(TAG, "init");

        ApkParserConfig res;
        try {
            byte[] protobuf = Files.readAllBytes(Path.of("/product/etc/apk-parser-config.pb"));
            res = ApkParserConfig.parseFrom(protobuf);
        } catch (IOException e) {
            if (Build.IS_DEBUGGABLE || Build.IS_EMULATOR) {
                if (!Build.IS_EMULATOR) {
                    Slog.e(TAG, "", e);
                }
                res = new ApkParserConfig();
            } else {
                // apk-parser-config.pb is a trusted part of the OS
                throw new SecurityException(e);
            }
        }
        if (res.permissionOwners == null) {
            res.permissionOwners = Map.of();
        }
        if (res.permissionGroupOwners == null) {
            res.permissionGroupOwners = Map.of();
        }
        if (res.contentProviderAuthorityOwners == null) {
            res.contentProviderAuthorityOwners = Map.of();
        }
        if (res.parsingConfigs == null) {
            res.parsingConfigs = Map.of();
        }
        if (res.installablePackages == null) {
            res.installablePackages = Map.of();
        }

        var ppc = new PackageParserConfig();
        ppc.config = res;
        ppc.nonInstallablePackages = new HashSet<>(Arrays.asList(res.nonInstallablePackages));

        HashSet<String> packages = new HashSet<>(ppc.nonInstallablePackages);
        for (String pkg : res.installablePackages.keySet()) {
            packages.add(pkg);
        }
        for (String pkg : res.permissionOwners.values()) {
            if (!packages.contains(pkg)) {
                throw new SecurityException(pkg);
            }
        }
        for (String pkg : res.permissionGroupOwners.values()) {
            if (!packages.contains(pkg)) {
                throw new SecurityException(pkg);
            }
        }
        for (String pkg : res.contentProviderAuthorityOwners.values()) {
            if (!packages.contains(pkg)) {
                throw new SecurityException(pkg);
            }
        }
        for (String pkg : res.parsingConfigs.keySet()) {
            if (!packages.contains(pkg)) {
                throw new SecurityException(pkg);
            }
        }

        instance = ppc;
    }

    public boolean isInstallationBlocked(String pkgName) {
        return nonInstallablePackages.contains(pkgName);
    }

    public void checkPermissionOwnership(ParsingPackage pkg, ParsedPermission permission) {
        Map<String, String> ownershipMap = config.permissionOwners;
        String name = permission.getName();
        {
            String ownerPkgName = ownershipMap.get(name);
            if (ownerPkgName != null) {
                String pkgName = pkg.getPackageName();
                if (!ownerPkgName.equals(pkgName)) {
                    pkg.recordIdOwnershipViolation("permission " + name + " is owned by " + ownerPkgName);
                }
            }
        }
        if (permission.isTree()) {
            String prefix = name + '.';
            for (String permName : ownershipMap.keySet()) {
                if (!permName.startsWith(prefix)) {
                    continue;
                }
                String ownerPkgName = ownershipMap.get(permName);
                String pkgName = pkg.getPackageName();
                if (!ownerPkgName.equals(pkgName)) {
                    pkg.recordIdOwnershipViolation("permission-tree " + name + " conflicts with " + permName + " which is owned by " + ownerPkgName);
                }
            }
        }
    }

    public void checkPermissionGroupOwnership(ParsingPackage pkg, ParsedPermissionGroup permissionGroup) {
        Map<String, String> ownershipMap = config.permissionGroupOwners;
        String name = permissionGroup.getName();
        String ownerPkgName = ownershipMap.get(name);
        if (ownerPkgName != null) {
            String pkgName = pkg.getPackageName();
            if (!ownerPkgName.equals(pkgName)) {
                pkg.recordIdOwnershipViolation("permission-group " + name + " is owned by " + ownerPkgName);
            }
        }
    }

    public void checkContentProviderAuthorityOwnership(ParsingPackage pkg, ParsedProvider provider) {
        String authority = provider.getAuthority();
        if (authority == null) {
            return;
        }
        Map<String, String> ownershipMap = config.contentProviderAuthorityOwners;
        String pkgName = pkg.getPackageName();

        for (String auth : authority.split(";")) {
            String authOwnerPkgName = ownershipMap.get(auth);
            if (authOwnerPkgName == null) {
                continue;
            }
            if (!authOwnerPkgName.equals(pkgName)) {
                pkg.recordIdOwnershipViolation("provider " + provider.getName() + " has authority " + auth + " which is owned by " + authOwnerPkgName);
            }
        }
    }

    @Nullable
    public ApcPackageConfig getApcPackageConfig(String pkgName) {
        return config.parsingConfigs.get(pkgName);
    }

    @Nullable
    public PackageInstallRequirements getPackageInstallRequirements(String pkgName) {
        return config.installablePackages.get(pkgName);
    }

    public static boolean hasApcFlag(ApcPackageConfig config, int flag) {
        return (config.flags & (1L << flag)) != 0;
    }
}
