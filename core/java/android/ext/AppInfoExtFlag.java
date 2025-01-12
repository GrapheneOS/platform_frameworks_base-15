package android.ext;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
@SystemApi
public interface AppInfoExtFlag {
    /* SysApi */ int HAS_GMSCORE_CLIENT_LIBRARY = 0;

    /** @hide */
    @IntDef(value = {
              HAS_GMSCORE_CLIENT_LIBRARY
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Enum {}
}
