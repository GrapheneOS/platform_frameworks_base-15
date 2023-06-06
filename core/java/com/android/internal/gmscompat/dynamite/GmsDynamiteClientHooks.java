/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.gmscompat.dynamite;

import android.app.compat.gms.GmsCompat;
import android.content.res.ApkAssets;
import android.content.res.loader.AssetsProvider;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.gmscompat.GmsCompatApp;
import com.android.internal.gmscompat.GmsInfo;
import com.android.internal.gmscompat.dynamite.server.IFileProxyService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Pattern;

import dalvik.system.DelegateLastClassLoader;

import static android.system.OsConstants.F_DUPFD_CLOEXEC;

public final class GmsDynamiteClientHooks {
    static final String TAG = "GmsCompat/DynamiteClient";
    private static final boolean DEBUG = false;

    // written last in the init sequence, "volatile" to publish all the preceding writes
    private static volatile boolean enabled;
    private static String gmsCoreDataPrefix;
    private static ArrayMap<String, ParcelFileDescriptor> pfdCache;
    private static ArrayList<ParcelFileDescriptor> pastCachedFds;

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
            // faster than ctx.createPackageContext().createDeviceProtectedStorageContext().getDataDir()
            int userId = GmsCompat.appContext().getUserId();
            String deDataDirectory = Environment.getDataUserDeDirectory(null, userId).getPath();
            gmsCoreDataPrefix = deDataDirectory + '/' + GmsInfo.PACKAGE_GMS_CORE + '/';
            pfdCache = new ArrayMap<>(20);
            pastCachedFds = new ArrayList<>();

            File.lastModifiedHook = GmsDynamiteClientHooks::getFileLastModified;
            DelegateLastClassLoader.modifyClassLoaderPathHook = GmsDynamiteClientHooks::maybeModifyClassLoaderPath;
            enabled = true;
        }
    }

    // ApkAssets#loadFromPath(String, int, AssetsProvider)
    public static ApkAssets loadAssetsFromPath(String path, int flags, AssetsProvider assets) throws IOException {
        if (!path.startsWith(gmsCoreDataPrefix)) {
            return null;
        }
        FileDescriptor fd = modulePathToFd(path);
        // no need to dup the fd, ApkAssets does it itself
        return ApkAssets.loadFromFd(fd, path, flags, assets);
    }

    // To fix false-positive "Module APK has been modified" check
    // File#lastModified()
    public static long getFileLastModified(File file) {
        final String path = file.getPath();

        if (enabled && path.startsWith(gmsCoreDataPrefix)) {
            String fdPath = "/proc/self/fd/" + modulePathToFd(path).getInt$();
            return new File(fdPath).lastModified();
        }
        return 0L;
    }

    public static FileDescriptor openFileDescriptor(String path) {
        if (!path.startsWith(gmsCoreDataPrefix)) {
            return null;
        }

        FileDescriptor fd = modulePathToFd(path);
        int dupFd;
        try {
            dupFd = Os.fcntlInt(fd, F_DUPFD_CLOEXEC, 0);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }

        var dupJfd = new FileDescriptor();
        dupJfd.setInt$(dupFd);
        return dupJfd;
    }

    // Replaces file paths of Dynamite modules with "/proc/self/fd" file descriptor references
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
            if (!pathPart.startsWith(gmsCoreDataPrefix)) {
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
            String fdFilePath = "/gmscompat_fd_" + modulePathToFd(filePath).getInt$();

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

    // Returned file descriptor should never be closed, because it may be dup()-ed at any time by the native code
    private static FileDescriptor modulePathToFd(String path) {
        if (DEBUG) {
            Log.d(TAG, "path " + path, new Throwable());
        }
        try {
            ArrayMap<String, ParcelFileDescriptor> cache = pfdCache;
            // this lock isn't contended, favor simplicity, not making the critical section shorter
            synchronized (cache) {
                ParcelFileDescriptor pfd = cache.get(path);
                if (pfd == null) {
                    pfd = getFileProxyService().openFile(path);
                    if (pfd == null) {
                        throw new IllegalStateException("unable to open " + path);
                    }
                    // ParcelFileDescriptor owns the underlying file descriptor
                    cache.put(path, pfd);
                }
                return pfd.getFileDescriptor();
            }
        } catch (RemoteException e) {
            // FileProxyService never forwards exceptions to minimize the information leaks,
            // this is a very rare "binder died" exception
            throw e.rethrowAsRuntimeException();
        }
    }

    private static volatile IFileProxyService fileProxyService;

    @GuardedBy("pfdCache")
    private static IFileProxyService getFileProxyService() {
        IFileProxyService cache = fileProxyService;
        if (cache != null) {
            return cache;
        }
        try {
            IFileProxyService service = GmsCompatApp.iClientOfGmsCore2Gca().getDynamiteFileProxyService();
            fileProxyService = service;
            IBinder.DeathRecipient serviceDeathCallback = () -> {
                fileProxyService = null;
                Log.d(TAG, "FileProxyService died");
                synchronized (pfdCache) {
                    // It's not safe to close cached file descriptors, they might still be in use
                    // at this point. Simply clearing the cache would make cached ParcelFileDescriptors
                    // collectable by GC, which would close the underlying file descriptors via
                    // ParcelFileDescriptor#finalize()
                    //
                    // pastCachedFds list is effectively a file descriptor leak, but it's small and
                    // rare. File descriptor count limit (RLIMIT_NOFILE) is set to 32768 as of Android 15.
                    pastCachedFds.addAll(pfdCache.values());
                    pfdCache.clear();
                }
            };
            service.asBinder().linkToDeath(serviceDeathCallback, 0);
            return service;
        } catch (RemoteException e) {
            Log.e(TAG, "unable to obtain FileProxyService", e);
            throw e.rethrowAsRuntimeException();
        }
    }

    private GmsDynamiteClientHooks() {}
}
