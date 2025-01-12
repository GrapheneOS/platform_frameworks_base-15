/*
 * Copyright (C) 2022 GrapheneOS
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

package com.android.internal.app;

import android.Manifest;
import android.annotation.AnyThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.DerivedPackageFlag;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

public class StorageScopesAppHooks {
    private static final String TAG = "StorageScopesAppHooks";

    private static volatile boolean isEnabled;
    private static int gosPsDerivedFlags;

    @AnyThread
    public static void maybeEnable(GosPackageState ps) {
        if (isEnabled) {
            return;
        }

        if (ps.hasFlag(GosPackageStateFlag.STORAGE_SCOPES_ENABLED)) {
            gosPsDerivedFlags = ps.derivedFlags;
            isEnabled = true;
        }
    }

    public static boolean isEnabled() {
        return isEnabled;
    }

    public static boolean shouldSkipPermissionCheckSpoof(int gosPsDflags, int permDerivedFlag) {
        if ((gosPsDflags & DerivedPackageFlag.HAS_READ_MEDIA_VISUAL_USER_SELECTED_DECLARATION) != 0) {
            switch (permDerivedFlag) {
                case DerivedPackageFlag.HAS_READ_MEDIA_AUDIO_DECLARATION:
                case DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION:
                    // see https://developer.android.com/about/versions/14/changes/partial-photo-video-access
                    return false;
            }
        }

        return false;
    }

    // call only if isEnabled == true
    private static boolean shouldSpoofSelfPermissionCheckInner(int permDerivedFlag) {
        if (permDerivedFlag == 0) {
            return false;
        }

        if (shouldSkipPermissionCheckSpoof(gosPsDerivedFlags, permDerivedFlag)) {
            return false;
        }

        return (gosPsDerivedFlags & permDerivedFlag) != 0;
    }

    public static boolean shouldSpoofSelfPermissionCheck(String permName) {
        if (!isEnabled) {
            return false;
        }

        return shouldSpoofSelfPermissionCheckInner(getSpoofablePermissionDflag(permName));
    }

    public static boolean shouldSpoofSelfAppOpCheck(int op) {
        if (!isEnabled) {
            return false;
        }

        return shouldSpoofSelfPermissionCheckInner(getSpoofableAppOpPermissionDflag(op));
    }

    // Instrumentation#execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)
    public static void maybeModifyActivityIntent(Context ctx, Intent i) {
        String action = i.getAction();
        if (action == null) {
            return;
        }

        int op;
        switch (action) {
            case Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION:
                op = AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE;
                break;
            case Settings.ACTION_REQUEST_MANAGE_MEDIA:
                op = AppOpsManager.OP_MANAGE_MEDIA;
                break;
            default:
                return;
        }

        Uri uri = i.getData();
        if (uri == null || !"package".equals(uri.getScheme())) {
            return;
        }

        String pkgName = uri.getSchemeSpecificPart();

        if (pkgName == null) {
            return;
        }

        if (!pkgName.equals(ctx.getPackageName())) {
            return;
        }

        boolean shouldModify = false;

        if (shouldSpoofSelfAppOpCheck(op)) {
            // in case a buggy app launches intent again despite pseudo-having the permission
            shouldModify = true;
        } else {
            if (op == AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE) {
                shouldModify = !Environment.isExternalStorageManager();
            } else if (op == AppOpsManager.OP_MANAGE_MEDIA) {
                shouldModify = !MediaStore.canManageMedia(ctx);
            }
        }

        if (shouldModify) {
            i.setAction(action + "_PROMPT");
        }
    }

    public static int getSpoofablePermissionDflag(String permName) {
        switch (permName) {
            case Manifest.permission.READ_EXTERNAL_STORAGE:
                return DerivedPackageFlag.HAS_READ_EXTERNAL_STORAGE_DECLARATION;

            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                return DerivedPackageFlag.HAS_WRITE_EXTERNAL_STORAGE_DECLARATION;

            case Manifest.permission.ACCESS_MEDIA_LOCATION:
                return DerivedPackageFlag.HAS_ACCESS_MEDIA_LOCATION_DECLARATION;

            case Manifest.permission.READ_MEDIA_AUDIO:
                return DerivedPackageFlag.HAS_READ_MEDIA_AUDIO_DECLARATION;

            case Manifest.permission.READ_MEDIA_IMAGES:
                return DerivedPackageFlag.HAS_READ_MEDIA_IMAGES_DECLARATION;

            case Manifest.permission.READ_MEDIA_VIDEO:
                return DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION;

            case Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED:
                return DerivedPackageFlag.HAS_READ_MEDIA_VISUAL_USER_SELECTED_DECLARATION;

            default:
                return 0;
        }
    }

    private static int getSpoofableAppOpPermissionDflag(int op) {
        switch (op) {
            case AppOpsManager.OP_READ_EXTERNAL_STORAGE:
                return DerivedPackageFlag.HAS_READ_EXTERNAL_STORAGE_DECLARATION;

            case AppOpsManager.OP_WRITE_EXTERNAL_STORAGE:
                return DerivedPackageFlag.HAS_WRITE_EXTERNAL_STORAGE_DECLARATION;

            case AppOpsManager.OP_READ_MEDIA_AUDIO:
                return DerivedPackageFlag.HAS_READ_MEDIA_AUDIO_DECLARATION;

            case AppOpsManager.OP_READ_MEDIA_IMAGES:
                return DerivedPackageFlag.HAS_READ_MEDIA_IMAGES_DECLARATION;

            case AppOpsManager.OP_READ_MEDIA_VIDEO:
                return DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION;

            case AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE:
                return DerivedPackageFlag.HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION;

            case AppOpsManager.OP_MANAGE_MEDIA:
                return DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION;

            case AppOpsManager.OP_ACCESS_MEDIA_LOCATION:
                return DerivedPackageFlag.HAS_ACCESS_MEDIA_LOCATION_DECLARATION;

            case AppOpsManager.OP_READ_MEDIA_VISUAL_USER_SELECTED:
                return DerivedPackageFlag.HAS_READ_MEDIA_VISUAL_USER_SELECTED_DECLARATION;

            default:
                return 0;
        }
    }

    private StorageScopesAppHooks() {}
}
