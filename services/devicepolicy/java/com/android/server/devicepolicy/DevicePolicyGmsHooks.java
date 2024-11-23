package com.android.server.devicepolicy;

import static android.app.AppOpsManager.MODE_ALLOWED;


import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.ext.PackageId;
import android.os.RemoteException;

import com.android.server.utils.Slogf;

import java.util.Arrays;
import java.util.List;

public class DevicePolicyGmsHooks {
    protected static final String LOG_TAG = "DevicePolicyGmsHooks";
    final IPackageManager mIPackageManager;
    final AppOpsManager mIAppOpsManager;

    public DevicePolicyGmsHooks(IPackageManager _mIPackageManager, AppOpsManager _mIAppOpsManager) {
        mIPackageManager = _mIPackageManager;
        mIAppOpsManager = _mIAppOpsManager;
    }

    /**
     * Check if app requires play services
     */
    private boolean requiresPlay(String pkg, int callerUserId) throws RemoteException {
        return true;
        /*ApplicationInfo ai = mIPackageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA, callerUserId);
        if (ai.metaData != null) {
            int playVersion = ai.metaData.getInt("com.google.android.gms.version", -1);
            return playVersion != -1;
        }

        return false;*/
    }

    /**
     * GrapheneOS Handler to check if any app such as role owner in a profile
     * requires play services and install them
     */
    public void maybeInstallPlay(int targetUserId, int callerUserId, String[] pkgNames) {
        boolean shouldInstall = false;

        for (String pkgName : pkgNames) {
            try {
                if (requiresPlay(pkgName, callerUserId)) {
                    Slogf.i(LOG_TAG, "Detected " + pkgName + " needs play services");
                    shouldInstall = true;
                }
            } catch (RemoteException e) {
                // Does not happen, same process
            }
        }

        if (shouldInstall) {
            installPlay(targetUserId, callerUserId);
        }
    }
    /**
     * GrapheneOS Handler to install sandboxed play into managed user profile
     * in order to allow DPC apps that require play services to work normally
     */
    private void installPlay(int targetUserId, int callerUserId) {
        // TODO: possibly copy permissions from existing install in managing user?
        Slogf.i(LOG_TAG, "Installing play for user " + targetUserId);

        // NOTE: we never need to copy GSF over, since it counts as a fresh install, so existence of GSF in owner is irrelevant.
        List<String> playPkgList = Arrays.asList(PackageId.GMS_CORE_NAME, PackageId.PLAY_STORE_NAME);

        boolean playAllAvailableOnSystem = true;

        try {
            for (final String playPkg : playPkgList) {
                if (mIPackageManager.getApplicationInfo(playPkg, 0, callerUserId) == null) {
                    playAllAvailableOnSystem = false;
                    Slogf.w(LOG_TAG, "Play package missing: " + playPkg);
                }
            }
            if (playAllAvailableOnSystem) {
                for (final String playPkg : playPkgList) {
                    if (mIPackageManager.isPackageAvailable(playPkg, targetUserId)) {
                        Slogf.d(LOG_TAG, "The play package "
                                + playPkg + " is already installed in "
                                + "user " + targetUserId);
                        continue;
                    }
                    Slogf.d(LOG_TAG, "Installing play package "
                            + playPkg + " in user " + targetUserId);
                    mIPackageManager.installExistingPackageAsUser(
                            playPkg,
                            targetUserId,
                            /* installFlags= */ 0,
                            PackageManager.INSTALL_REASON_POLICY,
                            /* whiteListedPermissions= */ null);
                }
            } else {
                // TODO: intent to app store to install play packages?
                Slogf.w(LOG_TAG, "Play Services not installed, yet requested for profile!");
                return;
            }

            Slogf.d(LOG_TAG, "Granting REQUEST_INSTALL_PACKAGES to Play Store");

            // We need to grant Play Store "Allow from source" / REQUEST_INSTALL_PACKAGES,
            // as this is not possible later if changing that setting is blocked by device policy
            // It will appear as "set by admin"

            final int storeUid = mIPackageManager.getPackageUid(
                    PackageId.PLAY_STORE_NAME, /* flags= */ 0, targetUserId);
            mIAppOpsManager.setMode(AppOpsManager.OP_REQUEST_INSTALL_PACKAGES, storeUid,
                    PackageId.PLAY_STORE_NAME, MODE_ALLOWED);

            Slogf.d(LOG_TAG, "Granting USER_TRUSTED_SOURCE to Play Store");

            mIAppOpsManager.setMode(AppOpsManager.OP_USER_TRUSTED_SOURCE, storeUid,
                    PackageId.PLAY_STORE_NAME, MODE_ALLOWED);
        } catch (RemoteException e) {
            // Does not happen, same process
        }
    }
}
