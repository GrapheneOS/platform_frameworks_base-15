package com.android.server.pm;

import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

import java.lang.reflect.Field;
import java.util.BitSet;

class GosPackageStateUtils {
    private static volatile BitSet knownFlagsCache;

    static @GosPackageStateFlag.Enum int parseFlag(String s) {
        if (Character.isDigit(s.charAt(0))) {
            int value = Integer.parseInt(s);
            BitSet knownFlags = knownFlagsCache;
            if (knownFlags == null) {
                knownFlags = new BitSet();
                for (Field f : GosPackageState.class.getDeclaredFields()) {
                    try {
                        knownFlags.set(f.getInt(null));
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }
                knownFlagsCache = knownFlags;
            }
            if (!knownFlags.get(value)) {
                throw new IllegalArgumentException(s);
            }
            return value;
        }

        try {
            return GosPackageStateFlag.class.getDeclaredField(s).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
