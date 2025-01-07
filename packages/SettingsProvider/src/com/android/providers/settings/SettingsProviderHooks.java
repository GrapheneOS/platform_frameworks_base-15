package com.android.providers.settings;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.ext.KnownSystemPackages;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import java.util.function.IntFunction;
import java.util.function.Supplier;

class SettingsProviderHooks {

    static void onSettingsStateInit(final SettingsProvider.SettingsRegistry registry, final int userId) {
        if (userId == UserHandle.USER_SYSTEM) {
            SettingsState globalSettings = registry.getSettingsLocked(SettingsProvider.SETTINGS_TYPE_GLOBAL, userId);
            insertSetting(globalSettings, Settings.Global.ADD_USERS_WHEN_LOCKED, "0" /* disabled value */);
            insertSetting(globalSettings, Settings.Global.ENABLE_EPHEMERAL_FEATURE, "0" /* disabled value */);
        }
        SettingsState secureSettings = registry.getSettingsLocked(SettingsProvider.SETTINGS_TYPE_SECURE, userId);
    }

    /**
     * Unlike UpgradeController#upgradeIfNeededLocked settings migration, this runs every time a user is initialized.
     * Insert or modify setting upon SettingState initialization for any user, or in case of system user, upon boot.
     */
    private static void insertSetting(SettingsState state, String key, String value) {
        state.insertSettingLocked(key, value, null /* tag */, false /* makeDefault */, SettingsState.SYSTEM_PACKAGE_NAME);
    }

    static boolean isImmutableSetting(SettingsState settingsState, Context ctx, int type, String name, int userId) {
        if (name == null) {
            return false;
        }

        return switch (type) {
            case SettingsProvider.SETTINGS_TYPE_GLOBAL -> switch (name) {
                case Settings.Global.ADD_USERS_WHEN_LOCKED,
                     Settings.Global.ENABLE_EPHEMERAL_FEATURE
                    -> true;
                default -> false;
            };

            default -> false;
        };
    }

    static SettingsState.Setting getImmutableSetting(SettingsState settingsState, Context ctx, int type, String name, int userId) {
        if (name == null) {
            return null;
        }

        return switch (type) {
            case SettingsProvider.SETTINGS_TYPE_GLOBAL -> switch (name) {
                case Settings.Global.ADD_USERS_WHEN_LOCKED,
                     Settings.Global.ENABLE_EPHEMERAL_FEATURE
                        -> settingsState.new Setting(
                                null, null, false, null, null) {
                    @Override
                    public String getValue() {
                        return "0";
                    }

                    @Override
                    public boolean update(
                            String value, boolean setDefault, String packageName,
                            String tag, boolean forceNonSystemPackage,
                            boolean overrideableByRestore) {
                        return false;
                    }

                    @Override
                    public boolean reset() {
                        return false;
                    }

                    @Override
                    public boolean isTransient() {
                        return true;
                    }
                };

                default -> null;
            };

            default -> null;
        };
    }

    static final int OPR_UNKNOWN = 0;
    static final int OPR_READ_SETTING = 1;
    static final int OPR_MUTATE_SETTING_INSERT = 2;
    static final int OPR_MUTATE_SETTING_DELETE = 3;
    static final int OPR_MUTATE_SETTING_UPDATE = 4;
    static final int OPR_MUTATE_SETTING_RESET = 5;

    static void maybeEnforceMorePermissions(
            SettingsProvider settingsProvider, String name, String value, int settingsType,
            int settingOperation, int callingUserId, int owningUserId,
            Supplier<PackageInfo> callingPkgInfoSupplier) {
        final int callingUid = Binder.getCallingUid();
        final int callingAppId = UserHandle.getAppId(callingUid);

        PackageInfo callingPkgInfo = callingPkgInfoSupplier.get();
        ApplicationInfo callingAppInfo;
        if (callingPkgInfo == null) {
            if (callingUid < Process.SYSTEM_UID || Settings.isInSystemServer()) {
                return;
            }

            callingAppInfo = null;
        } else {
            callingAppInfo = callingPkgInfo.applicationInfo;
        }

        if (callingAppInfo == null) {
            throw new SecurityException();
        }

        KnownSystemPackages ksp = KnownSystemPackages.get(settingsProvider.requireContext());

        String callingPkgName = callingAppInfo.packageName;
        switch (settingsType) {
            case SettingsProvider.SETTINGS_TYPE_GLOBAL -> {
                switch (name) {
                    case Settings.Global.AUTO_REBOOT_TIMEOUT,
                         Settings.Global.WIFI_AUTO_OFF,
                         Settings.Global.BLUETOOTH_AUTO_OFF,
                         Settings.Global.ALLOW_DISABLING_HARDENING_VIA_APP_COMPAT_CONFIG,
                         Settings.Global.RESTRICT_MEMORY_DYN_CODE_LOADING_BY_DEFAULT,
                         Settings.Global.RESTRICT_STORAGE_DYN_CODE_LOADING_BY_DEFAULT,
                         Settings.Global.RESTRICT_WEBVIEW_DYN_CODE_LOADING_BY_DEFAULT,
                         Settings.Global.FORCE_APP_MEMTAG_BY_DEFAULT,
                         Settings.Global.SHOW_SYSTEM_PROCESS_CRASH_NOTIFICATIONS
                            -> {
                    }

                    default -> {
                        return;
                    }
                }
            }

            case SettingsProvider.SETTINGS_TYPE_SECURE -> {
                switch (name) {
                    case Settings.Secure.AUTO_GRANT_OTHER_SENSORS_PERMISSION,
                         Settings.Secure.CROSS_PROFILE_CLIPBOARD_IMPORT_ACCESS
                            -> {
                    }
                    case Settings.Secure.SCREENSHOT_TIMESTAMP_EXIF,
                         Settings.Secure.SCRAMBLE_PIN_LAYOUT_PRIMARY,
                         Settings.Secure.SCRAMBLE_PIN_LAYOUT_SECONDARY,
                         Settings.Secure.SCRAMBLE_SIM_PIN_LAYOUT
                            -> {
                        switch (settingOperation) {
                            case OPR_READ_SETTING -> {
                                if (ksp.systemUi.equals(callingPkgName)
                                        && callingAppInfo.isSystemApp()) {
                                    return;
                                }
                            }
                            default -> {
                            }
                        }
                    }

                    default -> {
                        return;
                    }
                }
            }

            default -> {
                return;
            }
        }

        if (UserHandle.getAppId(callingAppInfo.uid) == Process.SYSTEM_UID) {
            if (SettingsState.SYSTEM_PACKAGE_NAME.equals(callingPkgName)
                    || UserHandle.getUserId(callingAppInfo.uid) == UserHandle.USER_SYSTEM
                    || ksp.settings.equals(callingPkgName)) {
                return;
            } else {
                throw new SecurityException();
            }
        }

        if (callingAppId == Process.SHELL_UID
                || (Build.IS_DEBUGGABLE && callingAppId == Process.ROOT_UID)) {
            return;
        }

        throw new SecurityException("Not allowed to access additional write settings");
    }
}
