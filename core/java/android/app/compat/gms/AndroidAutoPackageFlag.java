package android.app.compat.gms;

import android.annotation.SystemApi;

/** @hide */
@SystemApi
public interface AndroidAutoPackageFlag {
    int GRANT_PERMS_FOR_WIRED_ANDROID_AUTO = 0;
    int GRANT_PERMS_FOR_WIRELESS_ANDROID_AUTO = 1;
    int GRANT_AUDIO_ROUTING_PERM = 2;
    int GRANT_PERMS_FOR_ANDROID_AUTO_PHONE_CALLS = 3;
}
