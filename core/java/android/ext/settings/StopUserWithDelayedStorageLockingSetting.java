package android.ext.settings;

import android.content.Context;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ServiceManager;

/** @hide */
public final class StopUserWithDelayedStorageLockingSetting {

    private static IUserManager userManager;

    private static IUserManager userManager() {
        IUserManager res = userManager;
        if (res == null) {
            IBinder binder = ServiceManager.getService(Context.USER_SERVICE);
            res = IUserManager.Stub.asInterface(binder);
            userManager = res;
        }

        return res;
    }

    /** @hide */
    public static boolean get(int userId) {
        try {
            return userManager().getAllowStopUserWithDelayedStorageLocking(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static boolean set(int userId, boolean allow) {
        try {
            return userManager().setAllowStopUserWithDelayedStorageLocking(userId, allow);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static boolean reset(int userId) {
        try {
            return userManager().resetAllowStopUserWithDelayedStorageLocking(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private StopUserWithDelayedStorageLockingSetting() {
    }
}
