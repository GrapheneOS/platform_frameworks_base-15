package com.android.server.am;

import android.content.pm.PackageManagerInternal;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.pm.ext.PackageExt;
import com.android.server.pm.pkg.AndroidPackage;

class ActiveServicesHooks {
    static final String TAG = ActiveServicesHooks.class.getSimpleName();

    static boolean shouldAllowFgsWhileInUsePermission(ActiveServices activeServices, int uid) {
        PackageManagerInternal pm = activeServices.mAm.getPackageManagerInternal();
        AndroidPackage pkg = pm.getPackage(uid);
        if (pkg == null) {
            return false;
        }
        boolean res = PackageExt.get(pkg).hooks().shouldAllowFgsWhileInUsePermission(pm, UserHandle.getUserId(uid));
        if (res) {
            Slog.d(TAG, "shouldAllowFgsWhileInUsePermission for " + pkg.getPackageName() + " returned true");
        }
        return res;
    }
}
