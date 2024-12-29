package android.ext.power;

import android.content.Context;
import android.ext.settings.BoolSetting;
import android.ext.settings.Setting;
import android.os.Build;
import android.provider.Settings;

/** @hide */
public class BatteryChargeLimit {
    public static final int CHARGE_LEVEL = 80;

    private static final BoolSetting SETTING = new BoolSetting(Setting.Scope.GLOBAL,
            Settings.Global.BATTERY_CHARGE_LIMIT, false);

    public static BoolSetting getSetting() {
        return SETTING;
    }

    public static boolean isChargeLimitEnabled(Context context) {
        if (!isGoogleDevice()) {
            return false;
        }
        return SETTING.get(context);
    }

    public static boolean isGoogleDevice() {
        return "Google".equals(Build.MANUFACTURER);
    }
}
