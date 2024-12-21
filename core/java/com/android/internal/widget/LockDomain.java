package com.android.internal.widget;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Using this type as an argument for methods that require secondary handling is safer and more
 * future proof given that we rebase on top of upstream.
 *
 * Consider that if we add secondary handling to a method by overloading with a boolean
 * isPrimary, then upstream could add a conflicting overload.
 *
 * Also consider that if we have foo(boolean) and its overload foo(boolean, boolean), adding an
 * additional boolean argument, isPrimary, to both could result in future callers silently calling
 * the wrong method.
 */
public enum LockDomain implements Parcelable {
    Primary, Secondary;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@androidx.annotation.NonNull Parcel dest, int flags) {
        dest.writeInt(ordinal());
    }

    public static final Parcelable.Creator<LockDomain> CREATOR
            = new Parcelable.Creator<>() {
        public LockDomain createFromParcel(Parcel in) {
            return LockDomain.values()[in.readInt()];
        }

        public LockDomain[] newArray(int size) {
            return new LockDomain[size];
        }
    };
}