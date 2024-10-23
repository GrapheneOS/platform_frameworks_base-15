package com.android.server.permission.access.permission

import android.Manifest
import android.content.pm.PackageManager
import android.util.Slog
import com.android.server.LocalServices
import com.android.server.permission.access.MutableAppIdPermissionFlags
import com.android.server.permission.access.immutable.set
import com.android.server.permission.access.util.andInv
import com.android.server.permission.access.util.hasBits
import com.android.server.pm.permission.PermissionMigrationHelper

object SpecialRuntimePermissionFixup {
    const val TAG = "SpecialRtPermsFixup"

    // There was a bug in auto-grants of special runtime permissions (INTERNET and OTHER_SENSORS)
    // in the first few Android 15 releases, which led to auto-grants being performed after updates
    // of user apps in some cases, instead of only doing that as part of initial install.
    //
    // That bug is now fixed. This one-time task resets the state of granted special runtime
    // permissions to their last known good state from the previous implementation of the
    // PermissionManagerService, which was used until Android 15.
    fun maybeRun(appIdPermissionFlags: MutableAppIdPermissionFlags, userId: Int): Boolean {
        val permissionMigrationHelper = LocalServices.getService(PermissionMigrationHelper::class.java) ?: return false

        Slog.d(TAG, "running for userId $userId")

        if (!permissionMigrationHelper.hasLegacyPermissionState(userId)) {
            Slog.d(TAG, "no legacy permission state")
            return false
        }

        val legacyAppIdPermissionStates = permissionMigrationHelper
            .getLegacyPermissionStates(userId, /* excludeSystemPackages */ true)

        // SpecialRuntimePermUtils.getAll() is not used intentionally, its return value may change
        // in the future
        val specialPerms = arrayOf(Manifest.permission.INTERNET, Manifest.permission.OTHER_SENSORS)

        var isStateModified = false

        legacyAppIdPermissionStates.forEach { appId: Int, legacyStates: Map<String, PermissionMigrationHelper.LegacyPermissionState> ->
            Slog.d(TAG, "processing appId $appId")

            val currentAppIdState = appIdPermissionFlags.mutate(appId)
            if (currentAppIdState == null) {
                Slog.d(TAG, "no current permission state")
                return@forEach
            }

            for (specialPermissionName in specialPerms) {
                val legacyState = legacyStates.get(specialPermissionName) ?: continue
                Slog.d(TAG, "processing " + specialPermissionName
                        + "; legacy state: isGranted: " + legacyState.isGranted
                        + ", flags: " + Integer.toHexString(legacyState.flags))
                if (legacyState.isGranted) {
                    continue
                }
                if ((legacyState.flags and PackageManager.FLAG_PERMISSION_USER_SET) != 0) {
                    // permissions with USER_SET flag were not affect by the bug
                    continue
                }

                val currentPermissionFlags: Int = currentAppIdState.get(specialPermissionName) ?: continue
                Slog.d(TAG, "current flags: " + Integer.toHexString(currentPermissionFlags))
                if (!currentPermissionFlags.hasBits(PermissionFlags.RUNTIME_GRANTED)) {
                    continue
                }

                val newFlags = currentPermissionFlags andInv PermissionFlags.RUNTIME_GRANTED
                currentAppIdState.set(specialPermissionName, newFlags)
                Slog.d(TAG, "revoked $specialPermissionName from $appId")
                isStateModified = true
            }
        }
        return isStateModified
    }
}
