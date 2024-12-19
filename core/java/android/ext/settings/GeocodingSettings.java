package android.ext.settings;

import android.provider.Settings;

/** @hide */
public class GeocodingSettings {

    public static final int GEOCODING_DISABLED = 0;
    public static final int GEOCODING_SERVER_NOMINATIM = 1;
    public static final int GEOCODING_SERVER_GRAPHENEOS_PROXY = 2;

    public static final IntSetting GEOCODING_SETTINGS = new IntSetting(
            Setting.Scope.GLOBAL, //scope
            Settings.Global.GEOCODING_PROVIDER, //key
            GEOCODING_DISABLED,
            GEOCODING_DISABLED,
            GEOCODING_SERVER_NOMINATIM,
            GEOCODING_SERVER_GRAPHENEOS_PROXY
    );

}
