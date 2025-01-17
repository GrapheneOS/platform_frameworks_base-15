package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.ext.DerivedPackageFlag;
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
public final class GosPackageState implements Parcelable {
    public final long flagStorage1;
    // flags that have package-specific meaning
    public final long packageFlagStorage;
    @Nullable
    public final byte[] storageScopes;
    @Nullable
    public final byte[] contactScopes;
    /**
     * These flags are lazily derived from persistent state. They are intentionally skipped from
     * equals() and hashCode(). derivedFlags are stored here for performance reasons, to avoid
     * performing separate IPC to fetch them.
     * <p>
     * Note that calculation of derived flags is skipped unless a GosPackageState flag is set that
     * depends on derived flags, @see {@link com.android.server.pm.GosPackageStatePmHooks#maybeDeriveFlags}
     * <p>
     * If package is part of sharedUid, then its derivedFlags are calculated across all
     * sharedUid member packages. See GosPackageState javadoc for reasoning.
     * @hide
     */
    @DerivedPackageFlag.Enum public int derivedFlags;

    /** @hide */ public static final GosPackageState DEFAULT = createEmpty();

    /** @hide */
    public GosPackageState(long flagStorage1, long packageFlagStorage,
                           @Nullable byte[] storageScopes, @Nullable byte[] contactScopes) {
        this.flagStorage1 = flagStorage1;
        this.packageFlagStorage = packageFlagStorage;
        this.storageScopes = storageScopes;
        this.contactScopes = contactScopes;
    }

    private static GosPackageState createEmpty() {
        return new GosPackageState(0L, 0L, null, null);
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
        dest.writeLong(this.flagStorage1);
        dest.writeLong(this.packageFlagStorage);
        dest.writeByteArray(storageScopes);
        dest.writeByteArray(contactScopes);
        dest.writeInt(derivedFlags);
    }

    @NonNull
    public static final Creator<GosPackageState> CREATOR = new Creator<>() {
        @Override
        public GosPackageState createFromParcel(Parcel in) {
            var res = new GosPackageState(in.readLong(), in.readLong(),
                    in.createByteArray(), in.createByteArray());
            res.derivedFlags = in.readInt();
            return res;
        }

        @Override
        public GosPackageState[] newArray(int size) {
            return new GosPackageState[size];
        }
    };

    @Override
    public int hashCode() {
        return Long.hashCode(flagStorage1) + Arrays.hashCode(storageScopes) + Arrays.hashCode(contactScopes) + Long.hashCode(packageFlagStorage);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof GosPackageState o)) {
            return false;
        }
        if (this == o) {
            return true;
        }
        if (flagStorage1 != o.flagStorage1) {
            return false;
        }
        if (!Arrays.equals(storageScopes, o.storageScopes)) {
            return false;
        }
        if (!Arrays.equals(contactScopes, o.contactScopes)) {
            return false;
        }
        if (packageFlagStorage != o.packageFlagStorage) {
            return false;
        }
        return true;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public boolean hasFlag(@GosPackageStateFlag.Enum int flag) {
        return (this.flagStorage1 & (1L << flag)) != 0;
    }

    public boolean hasPackageFlag(int packageFlag) {
        return (this.packageFlagStorage & (1L << packageFlag)) != 0;
    }

    public boolean hasDerivedFlag(@DerivedPackageFlag.Enum int flag) {
        return (derivedFlags & flag) != 0;
    }

    public boolean hasDerivedFlags(@DerivedPackageFlag.Enum int flags) {
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
        private long flagStorage1;
        private long packageFlagStorage;
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
            this.flagStorage1 = s.flagStorage1;
            this.packageFlagStorage = s.packageFlagStorage;
            this.storageScopes = s.storageScopes;
            this.contactScopes = s.contactScopes;
        }

        @NonNull
        public Editor setFlagState(@GosPackageStateFlag.Enum int flag, boolean state) {
            if (state) {
                addFlag(flag);
            } else {
                clearFlag(flag);
            }
            return this;
        }

        @NonNull
        public Editor addFlag(@GosPackageStateFlag.Enum int flag) {
            this.flagStorage1 |= (1L << flag);
            return this;
        }

        @NonNull
        public Editor clearFlag(@GosPackageStateFlag.Enum int flag) {
            this.flagStorage1 &= ~(1L << flag);
            return this;
        }

        @NonNull
        public Editor addPackageFlag(int flag) {
            this.packageFlagStorage |= (1L << flag);
            return this;
        }

        @NonNull
        public Editor clearPackageFlag(int flag) {
            this.packageFlagStorage &= ~(1L << flag);
            return this;
        }

        @NonNull
        public Editor setPackageFlagState(int flag, boolean state) {
            if (state) {
                addPackageFlag(flag);
            } else {
                clearPackageFlag(flag);
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
                        new GosPackageState(flagStorage1, packageFlagStorage, storageScopes, contactScopes),
                        editorFlags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
