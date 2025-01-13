package com.android.server.pm;

import android.annotation.Nullable;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.util.Slog;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.pm.pkg.PackageUserStateInternal;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class GosPackageStatePersistence {
    private static final String TAG = "GosPackageStatePersistence";

    public static final String TAG_GOS_PACKAGE_STATE = "GrapheneOS-package-state";
    private static final String ATTR_FLAG_STORAGE_1 = "flags-1";
    private static final String ATTR_PACKAGE_FLAG_STORAGE = "package-flags";
    private static final String ATTR_STORAGE_SCOPES = "storage-scopes";
    private static final String ATTR_CONTACT_SCOPES = "contact-scopes";

    /** @see Settings#writePackageRestrictions */
    static void serialize(PackageUserStateInternal packageUserState, TypedXmlSerializer serializer) throws IOException {
        GosPackageState ps = packageUserState.getGosPackageState();
        if (GosPackageState.DEFAULT.equals(ps)) {
            return;
        }
        serializer.startTag(null, TAG_GOS_PACKAGE_STATE);
        serializeInner(ps, serializer);
        serializer.endTag(null, TAG_GOS_PACKAGE_STATE);
    }

    private static void serializeInner(GosPackageState ps, TypedXmlSerializer serializer) throws IOException {
        long flagStorage1 = ps.flagStorage1;
        if (flagStorage1 != 0L) {
            serializer.attributeLong(null, ATTR_FLAG_STORAGE_1, flagStorage1);
        }
        if (ps.hasFlag(GosPackageStateFlag.STORAGE_SCOPES_ENABLED)) {
            byte[] s = ps.storageScopes;
            if (s != null) {
                serializer.attributeBytesHex(null, ATTR_STORAGE_SCOPES, s);
            }
        }
        if (ps.hasFlag(GosPackageStateFlag.CONTACT_SCOPES_ENABLED)) {
            byte[] s = ps.contactScopes;
            if (s != null) {
                serializer.attributeBytesHex(null, ATTR_CONTACT_SCOPES, s);
            }
        }
        long packageFlagStorage = ps.packageFlagStorage;
        if (packageFlagStorage != 0L) {
            serializer.attributeLong(null, ATTR_PACKAGE_FLAG_STORAGE, ps.packageFlagStorage);
        }
    }

    static GosPackageState deserialize(TypedXmlPullParser parser) throws XmlPullParserException {
        long flagStorage1 = 0L;
        long packageFlagStorage = 0L;
        byte[] storageScopes = null;
        byte[] contactScopes = null;

        for (int i = 0, numAttr = parser.getAttributeCount(); i < numAttr; ++i) {
            String attr = parser.getAttributeName(i);
            switch (attr) {
                case ATTR_FLAG_STORAGE_1 ->
                    flagStorage1 = parser.getAttributeLong(i);
                case ATTR_PACKAGE_FLAG_STORAGE ->
                    packageFlagStorage = parser.getAttributeLong(i);
                case ATTR_STORAGE_SCOPES ->
                    storageScopes = parser.getAttributeBytesHex(i);
                case ATTR_CONTACT_SCOPES ->
                    contactScopes = parser.getAttributeBytesHex(i);
                default ->
                    Slog.e(TAG, "deserialize: unknown attribute " + attr);
            }
        }
        return new GosPackageState(flagStorage1, packageFlagStorage, storageScopes, contactScopes);
    }

    // Compatibility with legacy serialized GosPackageState.
    @Nullable
    static GosPackageState maybeDeserializeLegacy(TypedXmlPullParser parser) {
        int legacyFlags = parser.getAttributeInt(null, "GrapheneOS-flags", 0);
        if (legacyFlags == 0) {
            return null;
        }
        long flagStorage1 = migrateLegacyFlags(legacyFlags);
        long packageFlagStorage = parser.getAttributeLong(null, "GrapheneOS-package-flags", 0L);
        byte[] storageScopes = parser.getAttributeBytesHex(null, "GrapheneOS-storage-scopes", null);
        byte[] contactScopes = parser.getAttributeBytesHex(null, "GrapheneOS-contact-scopes", null);
        return new GosPackageState(flagStorage1, packageFlagStorage, storageScopes, contactScopes);
    }

    private static long migrateLegacyFlags(int flags) {
        final int FLAG_DISABLE_HARDENED_MALLOC = 1 << 2;
        if ((flags & FLAG_DISABLE_HARDENED_MALLOC) != 0) {
            flags &= ~(1 << GosPackageStateFlag.USE_HARDENED_MALLOC);
            flags |= (1 << GosPackageStateFlag.USE_HARDENED_MALLOC_NON_DEFAULT);
        }

        final int FLAG_ENABLE_COMPAT_VA_39_BIT = 1 << 3;
        if ((flags & FLAG_ENABLE_COMPAT_VA_39_BIT) != 0) {
            flags &= ~(1 << GosPackageStateFlag.USE_EXTENDED_VA_SPACE);
            flags |= (1 << GosPackageStateFlag.USE_EXTENDED_VA_SPACE_NON_DEFAULT);
        }

        final int FLAG_DO_NOT_SHOW_RELAX_APP_HARDENING_NOTIFICATION = 1 << 4;
        final int FLAG_HAS_PACKAGE_FLAGS = 1 << 25;

        final int unusedFlags = FLAG_DISABLE_HARDENED_MALLOC
                | FLAG_ENABLE_COMPAT_VA_39_BIT
                | FLAG_DO_NOT_SHOW_RELAX_APP_HARDENING_NOTIFICATION
                | FLAG_HAS_PACKAGE_FLAGS;

        // clear unused flags
        return flags & ~unusedFlags;
    }
}
