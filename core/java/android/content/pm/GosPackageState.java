/*
 * Copyright (C) 2022 GrapheneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.permission.PermissionManager;

import java.util.Objects;

/**
 * GrapheneOS-specific persistent package state, stored in per-user PackageUserState.
 * <p>
 * GosPackageState has a special handling for sharedUid packages. All packages in a given sharedUid
 * share the same GosPackageState. This was done because in some cases (e.g. when an app accesses
 * MediaProvider via FUSE) there's no way to retrieve the package name, only UID is available.
 * Manually merging GosPackageStates of sharedUid members would be too complex.
 *
 * @hide
 */
@SystemApi
public final class GosPackageState extends GosPackageStateBase implements Parcelable {
    public final int derivedFlags; // derived from persistent state, but not persisted themselves

    // to distinguish between the case when no dflags are set and the case when dflags weren't calculated yet
    public static final int DFLAGS_SET = 1;

    public static final int DFLAG_EXPECTS_ALL_FILES_ACCESS = 1 << 1;
    public static final int DFLAG_EXPECTS_ACCESS_TO_MEDIA_FILES_ONLY = 1 << 2;
    public static final int DFLAG_EXPECTS_STORAGE_WRITE_ACCESS = 1 << 3;
    public static final int DFLAG_HAS_READ_EXTERNAL_STORAGE_DECLARATION = 1 << 4;
    public static final int DFLAG_HAS_WRITE_EXTERNAL_STORAGE_DECLARATION = 1 << 5;
    public static final int DFLAG_HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION = 1 << 6;
    public static final int DFLAG_HAS_MANAGE_MEDIA_DECLARATION = 1 << 7;
    public static final int DFLAG_HAS_ACCESS_MEDIA_LOCATION_DECLARATION = 1 << 8;
    public static final int DFLAG_HAS_READ_MEDIA_AUDIO_DECLARATION = 1 << 9;
    public static final int DFLAG_HAS_READ_MEDIA_IMAGES_DECLARATION = 1 << 10;
    public static final int DFLAG_HAS_READ_MEDIA_VIDEO_DECLARATION = 1 << 11;
    public static final int DFLAG_HAS_READ_MEDIA_VISUAL_USER_SELECTED_DECLARATION = 1 << 12;
    public static final int DFLAG_EXPECTS_LEGACY_EXTERNAL_STORAGE = 1 << 13;

    public static final int DFLAG_HAS_READ_CONTACTS_DECLARATION = 1 << 20;
    public static final int DFLAG_HAS_WRITE_CONTACTS_DECLARATION = 1 << 21;
    public static final int DFLAG_HAS_GET_ACCOUNTS_DECLARATION = 1 << 22;

    /** @hide */
    public GosPackageState(int flags, long packageFlags,
                           @Nullable byte[] storageScopes, @Nullable byte[] contactScopes,
                           int derivedFlags) {
        super(flags, packageFlags, storageScopes, contactScopes);
        this.derivedFlags = derivedFlags;
    }

    @Nullable
    public static GosPackageState getForSelf(@NonNull Context context) {
        return get(context.getPackageName(), context.getUserId());
    }

    @Nullable
    @SuppressLint("UserHandleName")
    public static GosPackageState get(@NonNull String packageName, @NonNull UserHandle user) {
        return get(packageName, user.getIdentifier());
    }

    @Nullable
    public static GosPackageState get(@NonNull String packageName, @UserIdInt int userId) {
        var query = new CacheQuery(packageName, userId);
        if (sCache.query(query) instanceof GosPackageState res) {
            return res;
        }
        return null;
    }

    @NonNull
    @SuppressLint("UserHandleName")
    public static GosPackageState getOrDefault(@NonNull String packageName, @NonNull UserHandle user) {
        return getOrDefault(packageName, user.getIdentifier());
    }

    @NonNull
    public static GosPackageState getOrDefault(@NonNull String packageName, int userId) {
        var s = get(packageName, userId);
        if (s == null) {
            s = DEFAULT;
        }
        return s;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(this.flags);
        dest.writeLong(this.packageFlags);
        dest.writeByteArray(storageScopes);
        dest.writeByteArray(contactScopes);
        dest.writeInt(derivedFlags);
    }

    @NonNull
    public static final Creator<GosPackageState> CREATOR = new Creator<>() {
        @Override
        public GosPackageState createFromParcel(Parcel in) {
            return new GosPackageState(in.readInt(), in.readLong(),
                    in.createByteArray(), in.createByteArray(),
                    in.readInt());
        }

        @Override
        public GosPackageState[] newArray(int size) {
            return new GosPackageState[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public boolean hasDerivedFlag(int flag) {
        return (derivedFlags & flag) != 0;
    }

    public boolean hasDerivedFlags(int flags) {
        return (derivedFlags & flags) == flags;
    }

    /** @hide */
    public static boolean attachableToPackage(int appId) {
        // Packages with this appId use the "android.uid.system" sharedUserId, which is expensive
        // to deal with due to the large number of packages that it includes (see GosPackageStatePm
        // doc). These packages have no need for GosPackageState.
        return appId != Process.SYSTEM_UID;
    }

    public static boolean attachableToPackage(@NonNull String pkg) {
        Context ctx = ActivityThread.currentApplication();
        if (ctx == null) {
            return false;
        }

        ApplicationInfo ai;
        try {
            ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        return attachableToPackage(UserHandle.getAppId(ai.uid));
    }

    private record CacheQuery(String packageName, int userId) {}

    // invalidated by PackageManager#invalidatePackageInfoCache() (e.g. when
    // PackageManagerService#setGosPackageState succeeds)
    @Nullable private static volatile PropertyInvalidatedCache<CacheQuery, Object> sCache =
            new PropertyInvalidatedCache<>(256, PermissionManager.CACHE_KEY_PACKAGE_INFO,
                "getGosPackageStateOtherUsers") {
        @Override
        public Object recompute(CacheQuery query) {
            return getUncached(query.packageName, query.userId);
        }
    };

    static Object getUncached(String packageName, int userId) {
        try {
            GosPackageState s = ActivityThread.getPackageManager().getGosPackageState(packageName, userId);
            if (s != null) {
                return s;
            }
            // return non-null to cache null results, see javadoc for PropertyInvalidatedCache#recompute()
            return GosPackageState.class;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    @SuppressLint("UserHandleName")
    public Editor createEditor(@NonNull String packageName, @NonNull UserHandle user) {
        return createEditor(packageName, user.getIdentifier());
    }

    @NonNull
    public Editor createEditor(@NonNull String packageName, @UserIdInt int userId) {
        return new Editor(this, packageName, userId);
    }

    @NonNull
    @SuppressLint("UserHandleName")
    public static Editor edit(@NonNull String packageName, @NonNull UserHandle user) {
        return edit(packageName, user.getIdentifier());
    }

    @NonNull
    public static Editor edit(@NonNull String packageName, @UserIdInt int userId) {
        GosPackageState s = GosPackageState.get(packageName, userId);
        if (s != null) {
            return s.createEditor(packageName, userId);
        }
        return new Editor(packageName, userId);
    }

    public static final int EDITOR_FLAG_KILL_UID_AFTER_APPLY = 1;
    public static final int EDITOR_FLAG_NOTIFY_UID_AFTER_APPLY = 1 << 1;

    public static class Editor {
        private final String packageName;
        private final int userId;
        private int flags;
        private long packageFlags;
        private byte[] storageScopes;
        private byte[] contactScopes;
        private int editorFlags;

        /**
         * Don't call directly, use GosPackageState#edit or GosPackageStatePm#getEditor
         *
         * @hide
         */
        public Editor(String packageName, int userId) {
            this(DEFAULT, packageName, userId);
        }

        /** @hide */
        public Editor(GosPackageState s, String packageName, int userId) {
            this.packageName = packageName;
            this.userId = userId;
            this.flags = s.flags;
            this.packageFlags = s.packageFlags;
            this.storageScopes = s.storageScopes;
            this.contactScopes = s.contactScopes;
        }

        @NonNull
        public Editor setFlagsState(int flags, boolean state) {
            if (state) {
                addFlags(flags);
            } else {
                clearFlags(flags);
            }
            return this;
        }

        @NonNull
        public Editor addFlags(int flags) {
            this.flags |= flags;
            return this;
        }

        @NonNull
        public Editor clearFlags(int flags) {
            this.flags &= ~flags;
            return this;
        }

        @NonNull
        public Editor addPackageFlags(long flags) {
            this.packageFlags |= flags;
            return this;
        }

        @NonNull
        public Editor clearPackageFlags(long flags) {
            this.packageFlags &= ~flags;
            return this;
        }

        @NonNull
        public Editor setPackageFlagState(long flags, boolean state) {
            if (state) {
                addPackageFlags(flags);
            } else {
                clearPackageFlags(flags);
            }

            return this;
        }

        @NonNull
        public Editor setStorageScopes(@Nullable byte[] storageScopes) {
            this.storageScopes = storageScopes;
            return this;
        }

        @NonNull
        public Editor setContactScopes(@Nullable byte[] contactScopes) {
            this.contactScopes = contactScopes;
            return this;
        }

        @NonNull
        public Editor killUidAfterApply() {
            return setKillUidAfterApply(true);
        }

        @NonNull
        public Editor setKillUidAfterApply(boolean v) {
            if (v) {
                this.editorFlags |= EDITOR_FLAG_KILL_UID_AFTER_APPLY;
            } else {
                this.editorFlags &= ~EDITOR_FLAG_KILL_UID_AFTER_APPLY;
            }
            return this;
        }

        @NonNull
        public Editor setNotifyUidAfterApply(boolean v) {
            if (v) {
                this.editorFlags |= EDITOR_FLAG_NOTIFY_UID_AFTER_APPLY;
            } else {
                this.editorFlags &= ~EDITOR_FLAG_NOTIFY_UID_AFTER_APPLY;
            }
            return this;
        }

        // Returns true if the update was successfully applied and is scheduled to be written back
        // to storage. Actual writeback is performed asynchronously.
        public boolean apply() {
            setFlagsState(GosPackageState.FLAG_HAS_PACKAGE_FLAGS, packageFlags != 0);

            try {
                return ActivityThread.getPackageManager().setGosPackageState(packageName, userId,
                        new GosPackageState(flags, packageFlags, storageScopes, contactScopes, 0),
                        editorFlags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
