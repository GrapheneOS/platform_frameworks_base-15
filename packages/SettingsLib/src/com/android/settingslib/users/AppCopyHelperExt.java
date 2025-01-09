package com.android.settingslib.users;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.ext.KnownSystemPackages;

import androidx.annotation.NonNull;

import com.android.internal.R;

final class AppCopyHelperExt {
    static AppCopyHelper.SelectableAppInfoExt getSelectableAppInfoExt(
            @NonNull Context ctx, @NonNull ApplicationInfo appInfo) {
        String pkgName = appInfo.packageName;
        final boolean neededForPrivateProfile = !"com.android.deskclock".equals(pkgName)
                && !"com.android.stk".equals(pkgName)
                && !ctx.getString(R.string.config_defaultDialer).equals(pkgName)
                && !ctx.getString(R.string.config_defaultSms).equals(pkgName)
                ;
        final boolean systemApp = appInfo.isSystemApp() || appInfo.isUpdatedSystemApp();
        return new AppCopyHelper.SelectableAppInfoExt(neededForPrivateProfile, systemApp);
    }
}
