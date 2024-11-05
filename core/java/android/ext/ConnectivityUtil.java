package android.ext;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.Settings;

/** @hide */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class ConnectivityUtil {
    private ConnectivityUtil() {}
    /**
     * Return true if this uid is a core system component, or if the uid is known to PackageManager
     * and at least one of the packages that use it is a system app.
     */
    public static boolean isSystem(@NonNull Context context, int uid) {
        if (UserHandle.isCore(uid)) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        String[] packageNames = pm.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }
        for (String packageName : packageNames) {
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                if (appInfo.isSystemApp()) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return false;
    }

    public static boolean isRegularAppWithLockdownVpnEnabled(@NonNull Context context, int uid) {
        if (isSystem(context, uid)) {
            return false;
        }

        ContentResolver cr = context.createContextAsUser(UserHandle.getUserHandleForUid(
                uid), 0).getContentResolver();
        return Settings.Secure.getInt(cr, Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN, 0) == 1;
    }
}
