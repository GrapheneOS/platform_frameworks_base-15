package com.android.server;

import android.Manifest;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.ext.settings.ExtSettings;
import android.ext.settings.UsbPortSecurity;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.oemlock.OemLockManager;

import com.android.server.pm.UserManagerInternal;

public class SecurityStateManagerServiceExt {

    // Copy the keys for apps fetching the extra security state values.
    private static final String SECURITY_STATE_EXT_KEY = "android.ext.SECURITY_STATE_EXT";

    private static final String AUTO_REBOOT_TIMEOUT_KEY = "android.ext.AUTO_REBOOT_TIMEOUT";
    private static final String USB_PORT_SECURITY_MODE_KEY = "android.ext.USB_PORT_SECURITY_MODE";
    private static final String OEM_UNLOCK_ALLOWED_KEY = "android.ext.OEM_UNLOCK_ALLOWED";
    private static final String USER_COUNT_KEY = "android.ext.USER_COUNT";

    static void appendSecurityStateExt(@NonNull Context ctx, @NonNull Bundle securityState) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (ctx.checkCallingPermission(
                Manifest.permission.READ_ADDITIONAL_SECURITY_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Bundle securityStateExt = new Bundle();

        {
            // For future reference where autoreboot is unsupported on certain build configurations,
            // such as microdroid builds.
            final boolean isAutoRebootTimeoutSupported = true;
            if (isAutoRebootTimeoutSupported) {
                int autoRebootTimeoutMillis = ExtSettings.AUTO_REBOOT_TIMEOUT.get(ctx);
                securityStateExt.putInt(AUTO_REBOOT_TIMEOUT_KEY, autoRebootTimeoutMillis);
            }
        }

        {
            boolean isUsbPortSecuritySupported = ctx.getResources().getBoolean(
                    com.android.internal.R.bool.config_usbPortSecuritySupported);
            if (isUsbPortSecuritySupported) {
                int usbSecurityStateMode = UsbPortSecurity.MODE_SETTING.get(ctx);
                securityStateExt.putInt(USB_PORT_SECURITY_MODE_KEY, usbSecurityStateMode);
            }
        }

        {
            UserManagerInternal userManagerInternal = LocalServices.getService(UserManagerInternal.class);
            if (userManagerInternal != null) {
                var users = userManagerInternal.getUsers(true, true, true);
                int userCount = users.size();
                securityStateExt.putInt(USER_COUNT_KEY, userCount);
            }
        }

        {
            long token = Binder.clearCallingIdentity();
            try {
                OemLockManager oemLockManager = ctx.getSystemService(OemLockManager.class);
                if (oemLockManager != null) {
                    boolean isOemUnlockAllowed = oemLockManager.isOemUnlockAllowedByUser();
                    securityStateExt.putBoolean(OEM_UNLOCK_ALLOWED_KEY, isOemUnlockAllowed);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        securityState.putBundle(SECURITY_STATE_EXT_KEY, securityStateExt);
    }
}
