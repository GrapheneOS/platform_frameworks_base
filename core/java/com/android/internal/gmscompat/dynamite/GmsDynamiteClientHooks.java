package com.android.internal.gmscompat.dynamite;

import android.app.compat.gms.GmsCompat;
import android.content.res.ApkAssets;
import android.content.res.loader.AssetsProvider;

import com.android.internal.gmscompat.fileservice.GmsCoreFileServerClientHooks;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.regex.Pattern;

import dalvik.system.DelegateLastClassLoader;

public final class GmsDynamiteClientHooks {
    static final String TAG = "GmsCompat/DynamiteClient";

    // written last in the init sequence, "volatile" to publish all the preceding writes
    private static volatile boolean enabled;

    public static boolean enabled() {
        return enabled;
    }

    // ContentResolver#acquireProvider(Uri)
    public static void maybeInit(String auth) {
        if (!"com.google.android.gms.chimera".equals(auth)) {
            return;
        }
        synchronized (GmsDynamiteClientHooks.class) {
            if (enabled()) {
                return;
            }
            if (!GmsCompat.isClientOfGmsCore()) {
                return;
            }

            DelegateLastClassLoader.modifyClassLoaderPathHook = GmsDynamiteClientHooks::maybeModifyClassLoaderPath;
            enabled = true;
        }
    }

    // ApkAssets#loadFromPath(String, int, AssetsProvider)
    public static ApkAssets loadAssetsFromPath(String path, int flags, AssetsProvider assets) throws IOException {
        if (!GmsCoreFileServerClientHooks.isInGmsCoreDeDataDirAndShouldCacheFds(path)) {
            return null;
        }
        FileDescriptor fd = GmsCoreFileServerClientHooks.openCachedFile(path);
        if (fd == null) {
            throw new IOException("unable to open " + path);
        }
        // no need to dup the fd, ApkAssets does it itself
        return ApkAssets.loadFromFd(fd, path, flags, assets);
    }

    // Replaces file paths of Dynamite modules with "/gmscompat_fd" file descriptor references
    // DelegateLastClassLoader#maybeModifyClassLoaderPath(String, Boolean)
    public static String maybeModifyClassLoaderPath(String path, Boolean nativeLibsPathB) {
        if (path == null) {
            return null;
        }
        if (!enabled) { // libcore code doesn't have access to this field
            return path;
        }
        boolean nativeLibsPath = nativeLibsPathB.booleanValue();
        String[] pathParts = path.split(Pattern.quote(File.pathSeparator));
        boolean modified = false;

        for (int i = 0; i < pathParts.length; ++i) {
            String pathPart = pathParts[i];
            if (!GmsCoreFileServerClientHooks.isInGmsCoreDeDataDirAndShouldCacheFds(pathPart)) {
                continue;
            }
            // defined in bionic/linker/linker_utils.cpp kZipFileSeparator
            final String zipFileSeparator = "!/";

            String filePath;
            String nativeLibRelPath;
            if (nativeLibsPath) {
                int idx = pathPart.indexOf(zipFileSeparator);
                filePath = pathPart.substring(0, idx);
                nativeLibRelPath = pathPart.substring(idx + zipFileSeparator.length());
            } else {
                filePath = pathPart;
                nativeLibRelPath = null;
            }
            FileDescriptor fd = GmsCoreFileServerClientHooks.openCachedFile(filePath);
            if (fd == null) {
                throw new IllegalStateException("unable to open " + filePath);
            }
            String fdFilePath = "/gmscompat_fd_" + fd.getInt$();

            pathParts[i] = nativeLibsPath ?
                fdFilePath + zipFileSeparator + nativeLibRelPath :
                fdFilePath;

            modified = true;
        }
        if (!modified) {
            return path;
        }
        return String.join(File.pathSeparator, pathParts);
    }

    private GmsDynamiteClientHooks() {}
}
