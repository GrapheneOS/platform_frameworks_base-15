package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.UserIdInt;

final class PackageManagerServiceHooks {

    static void systemReady(@NonNull PackageManagerService pm) {
        GosPackageStatePmHooks.init(pm);
    }

    static void onClearApplicationUserData(
            @NonNull PackageManagerService pm, @NonNull String pkgName, @UserIdInt int userId) {
        GosPackageStatePmHooks.onClearApplicationUserData(pm, pkgName, userId);
    }
}
