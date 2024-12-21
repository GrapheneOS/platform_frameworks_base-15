package com.android.internal.widget;

import static com.android.internal.widget.LockDomain.Primary;
import static com.android.internal.widget.LockDomain.Secondary;

import android.content.Context;

public class WrappedLockPatternUtils {
    private final LockPatternUtils mInner;
    private final LockDomain mLockDomain;

    public WrappedLockPatternUtils(LockPatternUtils inner, LockDomain lockDomain) {
        mInner = inner;
        mLockDomain = lockDomain;
    }

    public WrappedLockPatternUtils(Context context, LockDomain lockDomain) {
        mInner = new LockPatternUtils(context);
        mLockDomain = lockDomain;
    }

    public LockPatternUtils getInner() {
        return mInner;
    }

    public LockDomain getLockDomain() {
        return mLockDomain;
    }

    public @LockPatternUtils.CredentialType int getCredentialTypeForUser(int userHandle) {
        return mInner.getCredentialTypeForUser(userHandle, mLockDomain);
    }

    public void setPinEnhancedPrivacyEnabled(boolean enabled, int userId) {
        mInner.setPinEnhancedPrivacyEnabled(enabled, userId, mLockDomain);
    }

    public boolean isPinEnhancedPrivacyEnabled(int userId) {
        return mInner.isPinEnhancedPrivacyEnabled(userId, mLockDomain);
    }

    public int getPinLength(int userId) {
        // This technique allows us to avoid modifying upstream tests in cases where we can mock
        // the inner LPU but not the WLPU.
        if (mLockDomain == Primary) {
            return mInner.getPinLength(userId);
        }
        return mInner.getPinLength(userId, Secondary);
    }

    public int getCurrentFailedPasswordAttempts(int userId) {
        if (mLockDomain == Primary) {
            return mInner.getCurrentFailedPasswordAttempts(userId);
        }
        return mInner.getCurrentFailedPasswordAttempts(userId, Secondary);
    }

    public boolean isAutoPinConfirmEnabled(int userId) {
        if (mLockDomain == Primary) {
            return mInner.isAutoPinConfirmEnabled(userId);
        }
        return mInner.isAutoPinConfirmEnabled(userId, Secondary);
    }
}
