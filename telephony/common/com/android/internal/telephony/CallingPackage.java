package com.android.internal.telephony;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;

/** {@hide} */
public record CallingPackage(int uid, String packageName) {

    public static CallingPackage get(Context ctx, @Nullable String unverifiedCallingPackageName) {
        int callingUid = Binder.getCallingUid();
        PackageManager pm = ctx.getPackageManager();
        CallingPackage result = null;
        if (unverifiedCallingPackageName != null) {
            int uid;
            try {
                uid = pm.getPackageUidAsUser(unverifiedCallingPackageName, UserHandle.getUserId(callingUid));
                if (uid == callingUid) {
                    result = new CallingPackage(callingUid, unverifiedCallingPackageName);
                }
            } catch (PackageManager.NameNotFoundException ignored) {}
        } else {
            String[] packages = pm.getPackagesForUid(callingUid);
            if (packages != null) {
                result = new CallingPackage(callingUid, packages[0]);
            }
        }
        if (result == null) {
            // avoid revealing which call failed to prevent leaks
            throw new SecurityException();
        }
        return result;
    }

    private static final char SEPARATOR = '|';

    public String serialize() {
        var b = new StringBuilder(packageName.length() + 12);
        b.append(uid);
        b.append(SEPARATOR);
        b.append(packageName);
        return b.toString();
    }

    public static CallingPackage parse(String s) {
        int i = s.indexOf(SEPARATOR);
        if (i <= 0 || i >= s.length() - 1) {
            throw new IllegalArgumentException(s);
        }
        int uid = Integer.parseInt(s.substring(0, i));
        String packageName = s.substring(i + 1);
        return new CallingPackage(uid, packageName);
    }

    public UserHandle userHandle() {
        return UserHandle.of(userId());
    }

    public int userId() {
        return UserHandle.getUserId(uid);
    }
}
