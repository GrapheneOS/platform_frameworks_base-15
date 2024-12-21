/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.locksettings;

import static android.Manifest.permission.CONFIGURE_FACTORY_RESET_PROTECTION;
import static android.security.Flags.FLAG_CLEAR_STRONG_AUTH_ON_ADD_PRIMARY_CREDENTIAL;
import static android.security.Flags.FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS;
import static com.android.internal.widget.LockDomain.Primary;
import static com.android.internal.widget.LockDomain.Secondary;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.PASSWORD_HISTORY_KEY;
import static com.android.internal.widget.LockPatternUtils.PIN_LENGTH_UNAVAILABLE;
import static com.android.internal.widget.LockPatternUtils.USER_FRP;
import static com.android.server.locksettings.SyntheticPasswordManager.NULL_PROTECTOR_ID;
import static com.android.server.testutils.TestUtils.assertExpectException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PropertyInvalidatedCache;
import android.app.admin.PasswordMetrics;
import android.content.Intent;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.gatekeeper.GateKeeperResponse;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockDomain;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternUtils.SecondaryForCredSharableUserException;
import com.android.internal.widget.LockPatternUtils.SecondaryForSpecialUserException;
import com.android.internal.widget.LockSettingsStateListener;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

/**
 * atest FrameworksServicesTests:LockSettingsServiceTests
 */
@SmallTest
@Presubmit
@RunWith(JUnitParamsRunner.class)
public class LockSettingsServiceTests extends BaseLockSettingsServiceTests {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        PropertyInvalidatedCache.disableForTestMode();
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testSetPasswordPrimaryUser() throws RemoteException {
        setAndVerifyCredential(PRIMARY_USER_ID, newPassword("password"), newPin("123456"));
    }

    @Test
    public void testSetPasswordFailsWithoutLockScreen() throws RemoteException {
        testSetCredentialFailsWithoutLockScreen(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetTooShortPatternFails() throws RemoteException {
        mService.setLockCredential(newPattern("123"), nonePassword(), PRIMARY_USER_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetTooShortPinFails() throws RemoteException {
        mService.setLockCredential(newPin("123"), nonePassword(), PRIMARY_USER_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetTooShortPassword() throws RemoteException {
        mService.setLockCredential(newPassword("123"), nonePassword(), PRIMARY_USER_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetPasswordWithInvalidChars() throws RemoteException {
        mService.setLockCredential(newPassword("§µ¿¶¥£"), nonePassword(), PRIMARY_USER_ID);
    }

    @Test
    public void testSetPatternPrimaryUser() throws RemoteException {
        setAndVerifyCredential(PRIMARY_USER_ID, newPattern("123456789"));
    }

    @Test
    public void testSetPatternFailsWithoutLockScreen() throws RemoteException {
        testSetCredentialFailsWithoutLockScreen(PRIMARY_USER_ID, newPattern("123456789"));
    }

    @Test
    public void testChangePasswordPrimaryUser() throws RemoteException {
        testChangeCredential(PRIMARY_USER_ID, newPattern("78963214"), newPassword("asdfghjk"));
    }

    @Test
    public void testChangeSecondaryPinPrimaryUser() throws RemoteException {
        // Configure.
        LockscreenCredential primaryPassword = newPassword("asdfghjk");
        LockscreenCredential secondaryPin0 = newPin("123456");
        LockscreenCredential secondaryPin1 = newPin("1234567");
        setCredential(PRIMARY_USER_ID, primaryPassword);
        setCredential(PRIMARY_USER_ID, secondaryPin0, primaryPassword, Secondary);
        assertVerifyCredential(PRIMARY_USER_ID, secondaryPin0, Secondary);

        // Change.
        setCredential(PRIMARY_USER_ID, secondaryPin1, primaryPassword, Secondary);
        assertVerifyCredential(PRIMARY_USER_ID, secondaryPin1, Secondary);
    }

    @Test
    public void testChangePatternPrimaryUser() throws RemoteException {
        testChangeCredential(PRIMARY_USER_ID, newPassword("password"), newPattern("1596321"));
    }

    @Test
    public void testChangePasswordFailPrimaryUser() throws RemoteException {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        assertFalse(mService.setLockCredential(newPassword("newpwd"), newPassword("badpwd"),
                    PRIMARY_USER_ID));
        assertVerifyCredential(PRIMARY_USER_ID, newPassword("password"), Primary);
    }

    @Test
    public void testChangeSecondaryPinFailPrimaryUser() throws RemoteException {
        // Configure.
        int userId = PRIMARY_USER_ID;
        LockscreenCredential primaryPassword = newPassword("password");
        LockscreenCredential badPrimaryPassword = newPassword("badpassword");
        LockscreenCredential secondaryPin0 = newPin("123456");
        LockscreenCredential secondaryPin1 = newPin("1234567");
        setCredential(userId, primaryPassword);

        // Change from None.
        assertFalse(mService.setLockCredential(secondaryPin0, badPrimaryPassword, Secondary,
                userId));
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId, Secondary));

        // Change from PIN.
        setCredential(userId, secondaryPin0, primaryPassword, Secondary);
        assertFalse(mService.setLockCredential(secondaryPin1, badPrimaryPassword, Secondary,
                userId));
        assertVerifyCredential(PRIMARY_USER_ID, secondaryPin0, Secondary);
    }

    @Test
    public void testClearPasswordPrimaryUser() throws RemoteException {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        clearCredential(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test
    public void testManagedProfileUnifiedChallenge() throws RemoteException {
        mService.initializeSyntheticPassword(TURNED_OFF_PROFILE_USER_ID);

        final LockscreenCredential firstUnifiedPassword = newPassword("pwd-1");
        final LockscreenCredential secondUnifiedPassword = newPassword("pwd-2");
        setCredential(PRIMARY_USER_ID, firstUnifiedPassword);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        final long primarySid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        final long profileSid = mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID);
        final long turnedOffProfileSid =
                mGateKeeperService.getSecureUserId(TURNED_OFF_PROFILE_USER_ID);
        assertTrue(primarySid != 0);
        assertTrue(profileSid != 0);
        assertTrue(profileSid != primarySid);
        assertTrue(turnedOffProfileSid != 0);
        assertTrue(turnedOffProfileSid != primarySid);
        assertTrue(turnedOffProfileSid != profileSid);

        // clear auth token and wait for verify challenge from primary user to re-generate it.
        mGateKeeperService.clearAuthToken(MANAGED_PROFILE_USER_ID);
        mGateKeeperService.clearAuthToken(TURNED_OFF_PROFILE_USER_ID);
        // verify credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                firstUnifiedPassword, PRIMARY_USER_ID, 0 /* flags */)
                .getResponseCode());

        // Verify that we have a new auth token for the profile
        assertNotNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));

        // Verify that profile which aren't running (e.g. turn off work) don't get unlocked
        assertNull(mGateKeeperService.getAuthToken(TURNED_OFF_PROFILE_USER_ID));

        // Change primary password and verify that profile SID remains
        setCredential(PRIMARY_USER_ID, secondUnifiedPassword, firstUnifiedPassword);
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertNull(mGateKeeperService.getAuthToken(TURNED_OFF_PROFILE_USER_ID));

        // Clear unified challenge
        clearCredential(PRIMARY_USER_ID, secondUnifiedPassword);
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(TURNED_OFF_PROFILE_USER_ID));
    }

    @Test
    public void testManagedProfileSeparateChallenge() throws RemoteException {
        final LockscreenCredential primaryPassword = newPassword("primary");
        final LockscreenCredential profilePassword = newPassword("profile");
        setCredential(PRIMARY_USER_ID, primaryPassword);
        setCredential(MANAGED_PROFILE_USER_ID, profilePassword);

        final long primarySid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        final long profileSid = mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID);
        assertTrue(primarySid != 0);
        assertTrue(profileSid != 0);
        assertTrue(profileSid != primarySid);

        // clear auth token and make sure verify challenge from primary user does not regenerate it.
        mGateKeeperService.clearAuthToken(MANAGED_PROFILE_USER_ID);
        // verify primary credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                primaryPassword, PRIMARY_USER_ID, 0 /* flags */)
                .getResponseCode());
        assertNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));

        // verify profile credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, MANAGED_PROFILE_USER_ID, 0 /* flags */)
                .getResponseCode());
        assertNotNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));

        setCredential(PRIMARY_USER_ID, newPassword("password"), primaryPassword);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, MANAGED_PROFILE_USER_ID, 0 /* flags */)
                .getResponseCode());
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testManagedProfileChallengeUnification_parentUserNoPassword() throws Exception {
        // Start with a profile with unified challenge, parent user has not password
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(MANAGED_PROFILE_USER_ID));

        // Set a separate challenge on the profile
        setCredential(MANAGED_PROFILE_USER_ID, newPassword("12345678"));
        assertNotEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(MANAGED_PROFILE_USER_ID));

        // Now unify again, profile should become passwordless again
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false,
                newPassword("12345678"));
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testSetLockCredential_forPrimaryUser_sendsFrpNotification() throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        checkRecordedFrpNotificationIntent(true);
    }

    @Test
    public void testSetLockCredential_secondaryPin_doesNotSendFrpNotification() throws Exception {
        LockscreenCredential primaryPassword = newPassword("password");
        LockscreenCredential secondaryPin = newPin("123456");
        setCredential(PRIMARY_USER_ID, primaryPassword);

        mService.clearRecordedFrpNotificationData();
        setCredential(PRIMARY_USER_ID, secondaryPin, primaryPassword, Secondary);
        checkRecordedFrpNotificationIntent(false);
    }

    @Test
    public void testSetLockCredential_forPrimaryUser_sendsCredentials() throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, "password".getBytes(),
                        PRIMARY_USER_ID);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_CLEAR_STRONG_AUTH_ON_ADD_PRIMARY_CREDENTIAL)
    public void setLockCredential_forPrimaryUser_clearsStrongAuthWhenFlagIsOn()
            throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));

        verify(mStrongAuth).reportUnlock(PRIMARY_USER_ID);
    }

    @Test
    @RequiresFlagsDisabled(FLAG_CLEAR_STRONG_AUTH_ON_ADD_PRIMARY_CREDENTIAL)
    public void setLockCredential_forPrimaryUser_leavesStrongAuthWhenFlagIsOff()
            throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));

        verify(mStrongAuth, never()).reportUnlock(anyInt());
    }

    @Test
    public void setLockCredential_forPrimaryUserWithCredential_leavesStrongAuth() throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        reset(mStrongAuth);

        setCredential(PRIMARY_USER_ID, newPassword("password2"), newPassword("password"));

        verify(mStrongAuth, never()).reportUnlock(anyInt());
    }

    public void testSetLockCredential_secondaryPin_doesNotSendCredentials() throws Exception {
        LockscreenCredential primaryPassword = newPassword("password");
        LockscreenCredential secondaryPin = newPin("123456");
        setCredential(PRIMARY_USER_ID, primaryPassword);

        reset(mRecoverableKeyStoreManager);

        setCredential(PRIMARY_USER_ID, secondaryPin, primaryPassword, Secondary);
        verify(mRecoverableKeyStoreManager, never())
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, "password".getBytes(),
                        PRIMARY_USER_ID);
    }

    @Test
    public void testSetLockCredential_forProfileWithSeparateChallenge_sendsCredentials()
            throws Exception {
        setCredential(MANAGED_PROFILE_USER_ID, newPattern("12345"));
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PATTERN, "12345".getBytes(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testSetLockCredential_forProfileWithSeparateChallenge_updatesCredentials()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, true, null);
        setCredential(MANAGED_PROFILE_USER_ID, newPattern("12345"));
        setCredential(MANAGED_PROFILE_USER_ID, newPassword("newPassword"), newPattern("12345"));
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, "newPassword".getBytes(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_CLEAR_STRONG_AUTH_ON_ADD_PRIMARY_CREDENTIAL)
    public void setLockCredential_profileWithNewSeparateChallenge_clearsStrongAuthWhenFlagIsOn()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, true, null);

        setCredential(MANAGED_PROFILE_USER_ID, newPattern("12345"));

        verify(mStrongAuth).reportUnlock(MANAGED_PROFILE_USER_ID);
    }

    @Test
    @RequiresFlagsDisabled(FLAG_CLEAR_STRONG_AUTH_ON_ADD_PRIMARY_CREDENTIAL)
    public void setLockCredential_profileWithNewSeparateChallenge_leavesStrongAuthWhenFlagIsOff()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, true, null);

        setCredential(MANAGED_PROFILE_USER_ID, newPattern("12345"));

        verify(mStrongAuth, never()).reportUnlock(anyInt());
    }

    @Test
    public void testSetLockCredential_forProfileWithUnifiedChallenge_doesNotSendRandomCredential()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        setCredential(PRIMARY_USER_ID, newPattern("12345"));
        verify(mRecoverableKeyStoreManager, never())
                .lockScreenSecretChanged(
                        eq(CREDENTIAL_TYPE_PASSWORD), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void
            testSetLockCredential_forPrimaryUserWithUnifiedChallengeProfile_updatesBothCredentials()
                    throws Exception {
        final LockscreenCredential oldCredential = newPassword("oldPassword");
        final LockscreenCredential newCredential = newPassword("newPassword");
        setCredential(PRIMARY_USER_ID, oldCredential);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        setCredential(PRIMARY_USER_ID, newCredential, oldCredential);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, newCredential.getCredential(),
                        PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, newCredential.getCredential(),
                        MANAGED_PROFILE_USER_ID);
    }


    @Test
    public void setLockCredential_primaryWithUnifiedProfileAndCredential_leavesStrongAuthForBoth()
            throws Exception {
        final LockscreenCredential oldCredential = newPassword("oldPassword");
        final LockscreenCredential newCredential = newPassword("newPassword");
        setCredential(PRIMARY_USER_ID, oldCredential);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        reset(mStrongAuth);

        setCredential(PRIMARY_USER_ID, newCredential, oldCredential);

        verify(mStrongAuth, never()).reportUnlock(anyInt());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_CLEAR_STRONG_AUTH_ON_ADD_PRIMARY_CREDENTIAL)
    public void setLockCredential_primaryWithUnifiedProfile_clearsStrongAuthForBothWhenFlagIsOn()
            throws Exception {
        final LockscreenCredential credential = newPassword("oldPassword");
        setCredential(PRIMARY_USER_ID, credential);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        clearCredential(PRIMARY_USER_ID, credential);
        reset(mStrongAuth);

        setCredential(PRIMARY_USER_ID, credential);

        verify(mStrongAuth).reportUnlock(PRIMARY_USER_ID);
        verify(mStrongAuth).reportUnlock(MANAGED_PROFILE_USER_ID);
    }

    @Test
    @RequiresFlagsDisabled(FLAG_CLEAR_STRONG_AUTH_ON_ADD_PRIMARY_CREDENTIAL)
    public void setLockCredential_primaryWithUnifiedProfile_leavesStrongAuthForBothWhenFlagIsOff()
            throws Exception {
        final LockscreenCredential credential = newPassword("oldPassword");
        setCredential(PRIMARY_USER_ID, credential);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        clearCredential(PRIMARY_USER_ID, credential);
        reset(mStrongAuth);

        setCredential(PRIMARY_USER_ID, credential);

        verify(mStrongAuth, never()).reportUnlock(anyInt());
    }


    @Test
    public void setLockCredential_primaryWithUnifiedProfileWithCredential_leavesStrongAuthForBoth()
            throws Exception {
        final LockscreenCredential oldCredential = newPassword("oldPassword");
        final LockscreenCredential newCredential = newPassword("newPassword");
        setCredential(PRIMARY_USER_ID, oldCredential);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        reset(mStrongAuth);

        setCredential(PRIMARY_USER_ID, newCredential, oldCredential);

        verify(mStrongAuth, never()).reportUnlock(anyInt());
    }

    @Test
    public void
            testSetLockCredential_forPrimaryUserWithUnifiedChallengeProfile_removesBothCredentials()
                    throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("oldPassword"));
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        clearCredential(PRIMARY_USER_ID, newPassword("oldPassword"));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_NONE, null, PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_NONE, null, MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testClearLockCredential_removesBiometrics() throws RemoteException {
        setCredential(PRIMARY_USER_ID, newPattern("123654"));
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        clearCredential(PRIMARY_USER_ID, newPattern("123654"));

        // Verify fingerprint is removed
        verify(mFingerprintManager).removeAll(eq(PRIMARY_USER_ID), any());
        verify(mFaceManager).removeAll(eq(PRIMARY_USER_ID), any());

        verify(mFingerprintManager).removeAll(eq(MANAGED_PROFILE_USER_ID), any());
        verify(mFaceManager).removeAll(eq(MANAGED_PROFILE_USER_ID), any());
    }

    @Test
    public void testClearLockCredential_sendsFrpNotification() throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        checkRecordedFrpNotificationIntent(true);
        mService.clearRecordedFrpNotificationData();
        clearCredential(PRIMARY_USER_ID, newPassword("password"));
        checkRecordedFrpNotificationIntent(true);
    }

    @Test
    public void testClearSecondaryLockCredential_DoesNotSendFrpNotification() throws Exception {
        LockscreenCredential primaryPassword = newPassword("password");
        LockscreenCredential secondaryPin = newPin("123456");
        setCredential(PRIMARY_USER_ID, primaryPassword);
        setCredential(PRIMARY_USER_ID, secondaryPin, primaryPassword, Secondary);
        mService.clearRecordedFrpNotificationData();
        clearCredential(PRIMARY_USER_ID, primaryPassword, Secondary);
        checkRecordedFrpNotificationIntent(false);
    }

    @Test
    public void clearLockCredential_primaryWithUnifiedProfile_leavesStrongAuthForBoth()
            throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        reset(mStrongAuth);

        clearCredential(PRIMARY_USER_ID, newPassword("password"));

        verify(mStrongAuth, never()).reportUnlock(anyInt());
    }

    @Test
    public void testSetLockCredential_forUnifiedToSeparateChallengeProfile_sendsNewCredentials()
            throws Exception {
        final LockscreenCredential parentPassword = newPassword("parentPassword");
        final LockscreenCredential profilePassword = newPassword("profilePassword");
        setCredential(PRIMARY_USER_ID, parentPassword);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        setCredential(MANAGED_PROFILE_USER_ID, profilePassword);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, profilePassword.getCredential(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void
            testSetLockCredential_forSeparateToUnifiedChallengeProfile_doesNotSendRandomCredential()
                    throws Exception {
        final LockscreenCredential parentPassword = newPassword("parentPassword");
        final LockscreenCredential profilePassword = newPattern("12345");
        mService.setSeparateProfileChallengeEnabled(
                MANAGED_PROFILE_USER_ID, true, profilePassword);
        setCredential(PRIMARY_USER_ID, parentPassword);
        setAndVerifyCredential(MANAGED_PROFILE_USER_ID, profilePassword);

        mService.setSeparateProfileChallengeEnabled(
                MANAGED_PROFILE_USER_ID, false, profilePassword);

        // Called once for setting the initial separate profile credentials and not again during
        // unification.
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(anyInt(), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testVerifyCredential_forPrimaryUser_sendsCredentials() throws Exception {
        final LockscreenCredential password = newPassword("password");
        setCredential(PRIMARY_USER_ID, password);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PASSWORD, password.getCredential(), PRIMARY_USER_ID);
    }

    @Test
    public void testVerifyCredential_secondaryPin_DoesNotSendCredentials() throws Exception {
        int userId = PRIMARY_USER_ID;
        LockscreenCredential primaryPassword = newPassword("password");
        LockscreenCredential secondaryPin = newPin("123456");
        setAndVerifyCredential(userId, primaryPassword, secondaryPin);

        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(secondaryPin, PRIMARY_USER_ID, 0 /* flags */);

        verify(mRecoverableKeyStoreManager, never())
                .lockScreenSecretAvailable(
                        anyInt(), any(byte[].class), anyInt());
    }

    @Test
    public void testVerifyCredential_forProfileWithSeparateChallenge_sendsCredentials()
            throws Exception {
        final LockscreenCredential pattern = newPattern("12345");
        setCredential(MANAGED_PROFILE_USER_ID, pattern);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(pattern, MANAGED_PROFILE_USER_ID, 0 /* flags */);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getCredential(), MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void verifyCredential_forPrimaryUserWithUnifiedChallengeProfile_sendsCredentialsForBoth()
                    throws Exception {
        final LockscreenCredential pattern = newPattern("12345");
        setCredential(PRIMARY_USER_ID, pattern);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(pattern, PRIMARY_USER_ID, 0 /* flags */);

        // Parent sends its credentials for both the parent and profile.
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getCredential(), PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getCredential(), MANAGED_PROFILE_USER_ID);
        // Profile doesn't send its own random credentials.
        verify(mRecoverableKeyStoreManager, never())
                .lockScreenSecretAvailable(
                        eq(CREDENTIAL_TYPE_PASSWORD), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testVerifyCredential_notifyLockSettingsStateListeners_whenGoodPassword()
            throws Exception {
        mSetFlagsRule.enableFlags(FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS);
        final LockscreenCredential password = newPassword("password");
        setCredential(PRIMARY_USER_ID, password);
        final LockSettingsStateListener listener = mock(LockSettingsStateListener.class);
        mLocalService.registerLockSettingsStateListener(listener);

        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                        .getResponseCode());

        verify(listener).onAuthenticationSucceeded(PRIMARY_USER_ID, Primary);
    }

    @Test
    public void testVerifyCredential_notifyLockSettingsStateListeners_whenBadPassword()
            throws Exception {
        mSetFlagsRule.enableFlags(FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS);
        final LockscreenCredential password = newPassword("password");
        setCredential(PRIMARY_USER_ID, password);
        final LockscreenCredential badPassword = newPassword("badPassword");
        final LockSettingsStateListener listener = mock(LockSettingsStateListener.class);
        mLocalService.registerLockSettingsStateListener(listener);

        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR,
                mService.verifyCredential(badPassword, PRIMARY_USER_ID, 0 /* flags */)
                        .getResponseCode());

        verify(listener).onAuthenticationFailed(PRIMARY_USER_ID, Primary);
    }

    @Test
    public void testLockSettingsStateListener_registeredThenUnregistered() throws Exception {
        mSetFlagsRule.enableFlags(FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS);
        final LockscreenCredential password = newPassword("password");
        setCredential(PRIMARY_USER_ID, password);
        final LockscreenCredential badPassword = newPassword("badPassword");
        final LockSettingsStateListener listener = mock(LockSettingsStateListener.class);

        mLocalService.registerLockSettingsStateListener(listener);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                        .getResponseCode());
        verify(listener).onAuthenticationSucceeded(PRIMARY_USER_ID, Primary);

        mLocalService.unregisterLockSettingsStateListener(listener);
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR,
                mService.verifyCredential(badPassword, PRIMARY_USER_ID, 0 /* flags */)
                        .getResponseCode());
        verify(listener, never()).onAuthenticationFailed(PRIMARY_USER_ID, Primary);
    }

    @Test
    public void testSetCredentialNotPossibleInSecureFrpModeDuringSuw() {
        setUserSetupComplete(false);
        setSecureFrpMode(true);
        try {
            mService.setLockCredential(newPassword("1234"), nonePassword(), PRIMARY_USER_ID);
            fail("Password shouldn't be changeable while FRP is active");
        } catch (SecurityException e) { }
    }

    @Test
    @DisableFlags(android.security.Flags.FLAG_FRP_ENFORCEMENT)
    public void testSetCredentialPossibleInSecureFrpModeAfterSuw_FlagOff() throws RemoteException {
        setUserSetupComplete(true);
        setSecureFrpMode(true);
        setCredential(PRIMARY_USER_ID, newPassword("1234"));
    }

    @Test
    @EnableFlags(android.security.Flags.FLAG_FRP_ENFORCEMENT)
    public void testSetCredentialNotPossibleInSecureFrpModeAfterSuw_FlagOn()
            throws RemoteException {
        setUserSetupComplete(true);
        setSecureFrpMode(true);
        try {
            mService.setLockCredential(newPassword("1234"), nonePassword(), PRIMARY_USER_ID);
            fail("Password shouldn't be changeable after SUW while FRP is active");
        } catch (SecurityException e) { }
    }

    @Test
    public void testPasswordHistoryDisabledByDefault() throws Exception {
        final int userId = PRIMARY_USER_ID;
        checkPasswordHistoryLength(userId, 0);
        setCredential(userId, newPassword("1234"));
        checkPasswordHistoryLength(userId, 0);
    }

    @Test
    public void testPasswordHistoryLengthHonored() throws Exception {
        final int userId = PRIMARY_USER_ID;
        when(mDevicePolicyManager.getPasswordHistoryLength(any(), eq(userId)))
                .thenReturn(3);
        checkPasswordHistoryLength(userId, 0);

        setCredential(userId, newPassword("pass1"));
        checkPasswordHistoryLength(userId, 1);

        setCredential(userId, newPassword("pass2"), newPassword("pass1"));
        checkPasswordHistoryLength(userId, 2);

        setCredential(userId, newPassword("pass3"), newPassword("pass2"));
        checkPasswordHistoryLength(userId, 3);

        // maximum length should have been reached
        setCredential(userId, newPassword("pass4"), newPassword("pass3"));
        checkPasswordHistoryLength(userId, 3);
    }

    @Test(expected=NullPointerException.class)
    public void testSetBooleanRejectsNullKey() {
        mService.setBoolean(null, false, 0);
    }

    @Test(expected=NullPointerException.class)
    public void testSetLongRejectsNullKey() {
        mService.setLong(null, 0, 0);
    }

    @Test(expected=NullPointerException.class)
    public void testSetStringRejectsNullKey() {
        mService.setString(null, "value", 0);
    }

    private void checkRecordedFrpNotificationIntent(boolean expected) {
        if (android.security.Flags.frpEnforcement()) {
            Intent savedNotificationIntent = mService.getSavedFrpNotificationIntent();
            if (!expected) {
                assertNull(savedNotificationIntent);
                return;
            }
            assertNotNull(savedNotificationIntent);
            UserHandle userHandle = mService.getSavedFrpNotificationUserHandle();
            assertEquals(userHandle,
                    UserHandle.of(mInjector.getUserManagerInternal().getMainUserId()));

            String permission = mService.getSavedFrpNotificationPermission();
            assertEquals(CONFIGURE_FACTORY_RESET_PROTECTION, permission);
        } else {
            assertNull(mService.getSavedFrpNotificationIntent());
            assertNull(mService.getSavedFrpNotificationUserHandle());
            assertNull(mService.getSavedFrpNotificationPermission());
        }
    }

    @Test
    public void onUserStopped_removesPasswordMetrics() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential primaryPassword = newPassword("primaryPassword");
        setCredential(userId, primaryPassword);
        assertNotNull(mService.getUserPasswordMetrics(userId));
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId));

        final LockscreenCredential secondaryPin = newPin("1111");
        setCredential(userId, secondaryPin, primaryPassword, Secondary);
        assertNotNull(mService.getUserPasswordMetrics(userId, Secondary));
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId, Secondary));
    }

    @Test
    public void getPinLength_secondaryForManagedProfile_throwsException() {
        assertExpectException(
                SecondaryForCredSharableUserException.class,
                null,
                () -> mService.getPinLength(MANAGED_PROFILE_USER_ID, Secondary));
    }

    @Test
    public void getPinLength_secondaryForSpecialUser_throwsException() {
        assertExpectException(
                SecondaryForSpecialUserException.class,
                null,
                () -> mService.getPinLength(USER_FRP, Secondary));
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void getPinLength_notExistingUser_returnsUnavailable(LockDomain lockDomain) {
        assertEquals(PIN_LENGTH_UNAVAILABLE, mService.getPinLength(DOES_NOT_EXIST_USER_ID,
                lockDomain));
    }

    @Test
    public void getPinLength_withCachedMetrics_returnsLength() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential primaryPin = newPin("123456");
        setCredential(userId, primaryPin);
        assertNotNull(mService.getUserPasswordMetrics(userId));
        assertEquals(6, mService.getPinLength(userId, Primary));

        final LockscreenCredential secondaryPin = newPin("1234");
        setCredential(userId, secondaryPin, primaryPin, Secondary);
        assertNotNull(mService.getUserPasswordMetrics(userId, Secondary));
        assertEquals(4, mService.getPinLength(userId, Secondary));
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void getPinLength_withNullProtector_returnsUnavailable(LockDomain lockDomain) {
        int userId = PRIMARY_USER_ID;
        mService.setCurrentLskfBasedProtectorId(NULL_PROTECTOR_ID, userId, lockDomain);

        PasswordMetrics pm = mService.getUserPasswordMetrics(userId, lockDomain);
        assertEquals(CREDENTIAL_TYPE_NONE, pm.credType);

        long protectorId = mService.getCurrentLskfBasedProtectorId(userId, lockDomain);
        assertEquals(NULL_PROTECTOR_ID, protectorId);

        int pinLength = mService.getPinLength(userId, lockDomain);
        assertEquals(PIN_LENGTH_UNAVAILABLE, pinLength);
    }

    @Test
    public void getPinLength_noCachedMetricsAndNotSavedToDisk_returnsUnavailable()
            throws Exception {
        int userId = PRIMARY_USER_ID;

        setAutoPinConfirm(userId, Primary, false);
        final LockscreenCredential primaryPin = newPin("123456");
        setCredential(userId, primaryPin);
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId));
        assertEquals(PIN_LENGTH_UNAVAILABLE, mService.getPinLength(userId, Primary));

        setAutoPinConfirm(userId, Secondary, false);
        final LockscreenCredential secondaryPin = newPin("654321");
        setCredential(userId, secondaryPin, primaryPin, Secondary);
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId, Secondary));
        assertEquals(PIN_LENGTH_UNAVAILABLE, mService.getPinLength(userId, Secondary));
    }

    @Test
    public void getPinLength_noCachedMetricsAndSavedToDisk_returnsLength() throws Exception {
        int userId = PRIMARY_USER_ID;

        setAutoPinConfirm(userId, Primary, true);
        final LockscreenCredential primaryPin = newPin("123456");
        setCredential(userId, primaryPin);
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId));
        assertEquals(6, mService.getPinLength(userId, Primary));

        setAutoPinConfirm(userId, Secondary, true);
        final LockscreenCredential secondaryPin = newPin("654321");
        setCredential(userId, secondaryPin, primaryPin, Secondary);
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId, Secondary));
        assertEquals(6, mService.getPinLength(userId, Secondary));
    }

    @Test
    public void refreshStoredPinLength_secondaryForManagedProfile_throwsException() {
        assertExpectException(
                SecondaryForCredSharableUserException.class,
                null,
                () -> mService.refreshStoredPinLength(MANAGED_PROFILE_USER_ID, Secondary));
    }

    @Test
    public void refreshStoredPinLength_secondaryForSpecialUser_throwsException() {
        assertExpectException(
                SecondaryForSpecialUserException.class,
                null,
                () -> mService.refreshStoredPinLength(USER_FRP, Secondary));
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void refreshStoredPinLength_notExistingUser_returnsFalse(LockDomain lockDomain) {
        assertFalse(mService.refreshStoredPinLength(DOES_NOT_EXIST_USER_ID, lockDomain));
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void refreshStoredPinLength_withMetricsCached_savesToDisk(LockDomain lockDomain)
            throws Exception {
        int userId = PRIMARY_USER_ID;

        // Use same pin for primary and secondary.
        LockscreenCredential pin = newPin("123456");

        // Start with auto confirm false so that PIN length is not saved to disk.
        setAutoPinConfirm(userId, lockDomain, false);

        setCredential(userId, pin);
        if (lockDomain == Secondary) {
            setCredential(userId, pin, pin, Secondary);
        }

        // Verify not already stored on disk.
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId));
        assertEquals(PIN_LENGTH_UNAVAILABLE, mService.getPinLength(userId, lockDomain));

        // Save credential to disk.
        assertVerifyCredential(userId, pin, lockDomain);
        setAutoPinConfirm(userId, lockDomain, true);
        assertTrue(mService.refreshStoredPinLength(userId, lockDomain));

        // Verify credential was saved to disk.
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId, lockDomain));
        assertEquals(6, mService.getPinLength(userId, lockDomain));
    }

    @Test
    public void getCredentialType_secondaryForSpecialUser_throwsException() {
        assertThrows(
                SecondaryForSpecialUserException.class,
                () -> mService.getCredentialType(USER_FRP, Secondary)
        );
    }

    @Test
    public void getCredentialType_secondaryForManagedProfile_throwsException() {
        assertThrows(
                SecondaryForCredSharableUserException.class,
                () -> mService.getCredentialType(MANAGED_PROFILE_USER_ID, Secondary)
        );
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void getCredentialType_notExistingUser_returnsNone(LockDomain lockDomain) {
        assertEquals(CREDENTIAL_TYPE_NONE,
                mService.getCredentialType(DOES_NOT_EXIST_USER_ID, lockDomain));
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void getCredentialType_withNullProtector_returnsNone(LockDomain lockDomain) {
        int userId = PRIMARY_USER_ID;
        mService.setCurrentLskfBasedProtectorId(NULL_PROTECTOR_ID, userId, lockDomain);
        long protectorId = mService.getCurrentLskfBasedProtectorId(userId, lockDomain);
        assertEquals(NULL_PROTECTOR_ID, protectorId);

        int credentialType = mService.getCredentialType(userId, lockDomain);
        assertEquals(CREDENTIAL_TYPE_NONE, credentialType);
    }

    @Test
    public void getCredentialType_withPin_returnsPin() throws Exception {
        int userId = PRIMARY_USER_ID;

        // Use same PIN for primary and secondary.
        final LockscreenCredential pin = newPin("123456");
        setCredential(userId, pin);
        int credentialType = mService.getCredentialType(userId);
        assertEquals(CREDENTIAL_TYPE_PIN, credentialType);

        setCredential(userId, pin, pin, Secondary);
        credentialType = mService.getCredentialType(userId, Secondary);
        assertEquals(CREDENTIAL_TYPE_PIN, credentialType);
    }

    @Test
    public void getCredentialType_primaryAndSecondaryDifferent_returnsDifferent() throws Exception {
        int userId = PRIMARY_USER_ID;

        // Use same PIN for primary and secondary.
        final LockscreenCredential password = newPassword("validpassword");
        setCredential(userId, password);
        int credentialType = mService.getCredentialType(userId);
        assertEquals(CREDENTIAL_TYPE_PASSWORD, credentialType);

        final LockscreenCredential pin = newPin("123456");
        setCredential(userId, pin, password, Secondary);
        credentialType = mService.getCredentialType(userId, Secondary);
        assertEquals(CREDENTIAL_TYPE_PIN, credentialType);
    }

    @Test
    public void setLockCredential_secondaryForManagedProfile_doesNotVerifyPrimaryAndThrowsException()
            throws Exception {
        final LockscreenCredential parentPrimaryPin = newPin("123456");
        setCredential(PRIMARY_USER_ID, parentPrimaryPin);

        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        assertTrue(mService.isProfileWithUnifiedLock(MANAGED_PROFILE_USER_ID));

        final LockscreenCredential profileSecondaryPin = newPin("654321");
        assertThrows(
                SecondaryForCredSharableUserException.class,
                () -> mService.setLockCredential(profileSecondaryPin, parentPrimaryPin, Secondary,
                        MANAGED_PROFILE_USER_ID));
        LockscreenCredential zeroizedPin = newPin("0");
        zeroizedPin.zeroize();
        Assert.assertNotEquals(zeroizedPin, parentPrimaryPin);
    }

    @Test
    public void setLockCredential_secondaryForSpecialUser_throwsException() {
        final LockscreenCredential pin = newPin("123456");

        assertThrows(
                SecondaryForSpecialUserException.class,
                () -> mService.setLockCredential(pin, pin, Secondary,
                        USER_FRP));
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void setLockCredential_notExistingUser_returnsFalse(LockDomain lockDomain) {
        LockscreenCredential credential = newPin("123456");
        LockscreenCredential savedCredential = newPin("654321");
        assertFalse(mService.setLockCredential(credential, savedCredential, lockDomain,
                DOES_NOT_EXIST_USER_ID));
    }

    @Test
    public void setLockCredential_secondaryNotPinOrNone_throwsException() throws Exception {
        int userId = PRIMARY_USER_ID;

        // Do this so that the test won't fail if the order of exception checks gets changed.
        final LockscreenCredential primaryPin = newPin("123456");
        setCredential(userId, primaryPin);

        final LockscreenCredential secondaryPassword = newPassword("valid-password");
        assertExpectException(IllegalArgumentException.class,
                "Biometric second factor must be PIN or None",
                () -> mService.setLockCredential(secondaryPassword, primaryPin, Secondary,
                        userId));
    }

    @Test
    public void setLockCredential_secondaryWithoutPrimary_returnsFalse() {
        int userId = PRIMARY_USER_ID;

        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId));
        final LockscreenCredential secondaryPin = newPin("123456");
        assertFalse(mService.setLockCredential(secondaryPin, nonePassword(), Secondary, userId));
    }

    @Test
    public void setLockCredential_secondaryWithIncorrectPrimary_returnsFalse() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential primaryPin = newPin("123456");
        setCredential(userId, primaryPin);
        final LockscreenCredential secondaryPin = newPin("654321");
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId, Secondary));
        assertFalse(mService.setLockCredential(secondaryPin, nonePassword(), Secondary, userId));
        setCredential(userId, secondaryPin, primaryPin, Secondary);
        assertFalse(mService.setLockCredential(secondaryPin, secondaryPin, Secondary, userId));
    }

    @Test
    public void setLockCredential_clearPrimaryWithSecondary_clearsSecondary() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential primaryPin = newPin("123456");
        setCredential(userId, primaryPin);
        final LockscreenCredential secondaryPin = newPin("654321");
        setCredential(userId, secondaryPin, primaryPin, Secondary);

        long secondaryProtector = mService.getCurrentLskfBasedProtectorId(userId, Secondary);
        SyntheticPasswordManager.SyntheticPassword secondarySp0 =
                mSpManager.unlockLskfBasedProtector(mGateKeeperService, secondaryProtector,
                        secondaryPin, Secondary, userId, null).syntheticPassword;
        assertNotNull(secondarySp0);

        assertTrue(mService.setLockCredential(nonePassword(), primaryPin, userId));

        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId, Secondary));
        secondaryProtector = mService.getCurrentLskfBasedProtectorId(userId, Secondary);
        SyntheticPasswordManager.SyntheticPassword secondarySp1 =
                mSpManager.unlockLskfBasedProtector(mGateKeeperService, secondaryProtector,
                        nonePassword(), Secondary, userId, null).syntheticPassword;
        assertNotNull(secondarySp1);
        Assert.assertNotEquals(secondarySp0, secondarySp1);

        PasswordMetrics pm = PasswordMetrics.computeForCredential(LockscreenCredential.createNone());
        assertEquals(pm, mService.getUserPasswordMetrics(userId, Secondary));
        verify(mDevicePolicyManager, times(1)).reportPasswordChanged(pm, userId,
                Secondary);
    }

    @Test
    public void setLockCredential_setSecondaryPin_success() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential primaryPin = newPin("123456");
        setCredential(userId, primaryPin);
        final LockscreenCredential secondaryPin = newPin("654321");
        PasswordMetrics secondaryPinMetrics = PasswordMetrics.computeForCredential(secondaryPin);

        long primaryProtector = mService.getCurrentLskfBasedProtectorId(userId);
        SyntheticPasswordManager.SyntheticPassword primarySp =
                mSpManager.unlockLskfBasedProtector(mGateKeeperService, primaryProtector,
                        primaryPin, userId, null).syntheticPassword;
        assertNotNull(primarySp);

        long secondaryProtector = mService.getCurrentLskfBasedProtectorId(userId, Secondary);
        SyntheticPasswordManager.SyntheticPassword secondarySp0 =
                mSpManager.unlockLskfBasedProtector(mGateKeeperService, secondaryProtector,
                        nonePassword(), Secondary, userId, null).syntheticPassword;
        assertNotNull(secondarySp0);

        assertEquals(CREDENTIAL_TYPE_NONE,
                mService.getUserPasswordMetrics(userId, Secondary).credType);

        // Set secondary PIN.
        assertTrue(mService.setLockCredential(secondaryPin, primaryPin, Secondary, userId));

        assertEquals(secondaryPinMetrics, mService.getUserPasswordMetrics(userId, Secondary));
        assertVerifyCredential(userId, secondaryPin, Secondary);
        secondaryProtector = mService.getCurrentLskfBasedProtectorId(userId, Secondary);
        SyntheticPasswordManager.SyntheticPassword secondarySp1 =
                mSpManager.unlockLskfBasedProtector(mGateKeeperService, secondaryProtector,
                        secondaryPin, Secondary, userId, null).syntheticPassword;
        assertNotNull(secondarySp1);
        Assert.assertNotEquals(secondarySp0, secondarySp1);
        Assert.assertNotEquals(secondarySp1, primarySp);

        verify(mDevicePolicyManager, times(1)).reportPasswordChanged(
                secondaryPinMetrics, userId, Secondary);
    }

    @Test
    public void setLockCredential_clearSecondary_returnsTrue() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential primaryPin = newPin("123456");
        setCredential(userId, primaryPin);
        final LockscreenCredential secondaryPin = newPin("654321");
        setCredential(userId, secondaryPin, primaryPin, Secondary);
        clearCredential(userId, primaryPin, Secondary);
    }
    @Test
    public void setLockCredential_onSuccess_addsPasswordMetrics() {
        assertEquals(CREDENTIAL_TYPE_NONE,
                mService.getUserPasswordMetrics(PRIMARY_USER_ID).credType);
        final LockscreenCredential primaryPassword = newPassword("primaryPassword");
        final PasswordMetrics primaryMetrics = PasswordMetrics.computeForCredential(
                primaryPassword);
        assertTrue(mService.setLockCredential(primaryPassword, nonePassword(), PRIMARY_USER_ID));
        assertEquals(primaryMetrics, mService.getUserPasswordMetrics(PRIMARY_USER_ID));

        assertEquals(CREDENTIAL_TYPE_NONE,
                mService.getUserPasswordMetrics(PRIMARY_USER_ID, Secondary).credType);
        final LockscreenCredential secondaryPin = newPin("1111");
        final PasswordMetrics secondaryMetrics = PasswordMetrics.computeForCredential(
                secondaryPin);
        assertTrue(mService.setLockCredential(secondaryPin, primaryPassword, Secondary,
                PRIMARY_USER_ID));
        assertEquals(secondaryMetrics, mService.getUserPasswordMetrics(PRIMARY_USER_ID, Secondary));
    }

    @Test
    public void verifyCredential_secondaryWithFlags_throwsException() {
        LockscreenCredential credentialToVerify = newPin("123456");

        assertExpectException(IllegalArgumentException.class,
                "Invalid flags for biometric second factor",
                () -> mService.verifyCredential(credentialToVerify, Secondary,
                        PRIMARY_USER_ID, 1));
    }

    @Test
    public void verifyCredential_secondaryForSpecialUser_throwsException() {
        LockscreenCredential credentialToVerify = newPin("123456");

        assertThrows(
                SecondaryForSpecialUserException.class,
                () -> mService.verifyCredential(credentialToVerify, Secondary,
                        USER_FRP, 0));
    }

    @Test
    public void verifyCredential_secondaryForManagedProfile_throwsException() {
        LockscreenCredential credentialToVerify = newPin("123456");

        assertThrows(SecondaryForCredSharableUserException.class,
                () -> mService.verifyCredential(credentialToVerify, Secondary,
                        MANAGED_PROFILE_USER_ID, 0));
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void verifyCredential_notExistingUser_returnsError(LockDomain lockDomain) {
        LockscreenCredential credentialToVerify = newPin("123456");

        assertEquals(VerifyCredentialResponse.ERROR, mService.verifyCredential(credentialToVerify,
                lockDomain, DOES_NOT_EXIST_USER_ID, 0));
    }

    @Test
    public void verifyCredential_correctCredential_returnsOk() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential primaryPassword = newPassword("primaryPassword");
        setCredential(userId, primaryPassword);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                primaryPassword, userId, 0).getResponseCode());

        final LockscreenCredential secondaryPin = newPin("1111");
        setCredential(userId, secondaryPin, primaryPassword, Secondary);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                secondaryPin, Secondary, userId, 0).getResponseCode());
    }

    @Test
    public void verifyCredential_incorrectCredential_returnsError() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential badPassword = newPassword("badPassword");
        final LockscreenCredential primaryPassword = newPassword("primaryPassword");
        setCredential(userId, primaryPassword);
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR, mService.verifyCredential(
                badPassword, userId, 0).getResponseCode());

        final LockscreenCredential secondaryPin = newPin("1111");
        setCredential(userId, secondaryPin, primaryPassword, Secondary);
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR, mService.verifyCredential(
                badPassword, Secondary, userId, 0).getResponseCode());
    }

    @Test
    public void verifyCredential_correctCredential_addsPasswordMetrics() throws Exception {
        int userId = PRIMARY_USER_ID;

        final LockscreenCredential primaryPassword = newPassword("primaryPassword");
        final PasswordMetrics primaryMetrics = PasswordMetrics.computeForCredential(
                primaryPassword);
        setCredential(userId, primaryPassword);
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId));
        mService.verifyCredential(primaryPassword, userId, 0);
        assertEquals(primaryMetrics, mService.getUserPasswordMetrics(userId));

        final LockscreenCredential secondaryPin = newPin("1111");
        final PasswordMetrics secondaryMetrics = PasswordMetrics.computeForCredential(
                secondaryPin);
        setCredential(userId, secondaryPin, primaryPassword, Secondary);
        mService.onUserStopped(userId);
        assertNull(mService.getUserPasswordMetrics(userId, Secondary));
        mService.verifyCredential(secondaryPin, Secondary, userId, 0);
        assertEquals(secondaryMetrics, mService.getUserPasswordMetrics(userId, Secondary));
    }

    private void checkPasswordHistoryLength(int userId, int expectedLen) {
        String history = mService.getString(PASSWORD_HISTORY_KEY, "", userId);
        String[] hashes = TextUtils.split(history, LockPatternUtils.PASSWORD_HISTORY_DELIMITER);
        assertEquals(expectedLen, hashes.length);
    }

    private void testSetCredentialFailsWithoutLockScreen(
            int userId, LockscreenCredential credential) throws RemoteException {
        mService.mHasSecureLockScreen = false;
        try {
            mService.setLockCredential(credential, nonePassword(), userId);
            fail("An exception should have been thrown.");
        } catch (UnsupportedOperationException e) {
            // Success - the exception was expected.
        }

        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId));
    }

    private void testChangeCredential(int userId, LockscreenCredential newCredential,
            LockscreenCredential oldCredential) throws RemoteException {
        setCredential(userId, oldCredential);
        setCredential(userId, newCredential, oldCredential);
        assertVerifyCredential(userId, newCredential, Primary);
    }

    private void assertVerifyCredential(int userId, LockscreenCredential credential,
            LockDomain lockDomain) {
        VerifyCredentialResponse response = mService.verifyCredential(credential,
                lockDomain, userId, 0 /* flags */);

        assertEquals(GateKeeperResponse.RESPONSE_OK, response.getResponseCode());
        if (credential.isPassword()) {
            assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(userId, lockDomain));
        } else if (credential.isPin()) {
            assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(userId, lockDomain));
        } else if (credential.isPattern()) {
            assertEquals(CREDENTIAL_TYPE_PATTERN, mService.getCredentialType(userId, lockDomain));
        } else {
            assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId, lockDomain));
        }
        // check for bad credential
        final LockscreenCredential badCredential;
        if (!credential.isNone()) {
            badCredential = credential.duplicate();
            badCredential.getCredential()[0] ^= 1;
        } else {
            badCredential = LockscreenCredential.createPin("0");
        }
        assertEquals(GateKeeperResponse.RESPONSE_ERROR, mService.verifyCredential(
                badCredential, lockDomain, userId, 0 /* flags */).getResponseCode());
    }

    private void setAndVerifyCredential(int userId, LockscreenCredential newCredential)
            throws RemoteException {
        setAndVerifyCredential(userId, newCredential, null);
    }

    private void setAndVerifyCredential(int userId, LockscreenCredential newPrimaryCredential,
            LockscreenCredential newSecondaryCredential) throws RemoteException {
        setCredential(userId, newPrimaryCredential);
        assertVerifyCredential(userId, newPrimaryCredential, Primary);
        if (newSecondaryCredential != null) {
            setCredential(userId, newSecondaryCredential, newPrimaryCredential, Secondary);
            assertVerifyCredential(userId, newSecondaryCredential, Secondary);
        }
    }

    private void setCredential(int userId, LockscreenCredential newCredential)
            throws RemoteException {
        setCredential(userId, newCredential, nonePassword());
    }

    private void clearCredential(int userId, LockscreenCredential currentPrimaryCredential)
            throws RemoteException {
        clearCredential(userId, currentPrimaryCredential, Primary);
    }

    private void clearCredential(int userId, LockscreenCredential currentPrimaryCredential,
            LockDomain lockDomain) throws RemoteException {
        setCredential(userId, nonePassword(), currentPrimaryCredential, lockDomain);
    }

    private void setCredential(int userId, LockscreenCredential newCredential,
            LockscreenCredential currentPrimaryCredential) throws RemoteException {
        setCredential(userId, newCredential, currentPrimaryCredential, Primary);
    }

    private void setCredential(int userId, LockscreenCredential newCredential,
            LockscreenCredential currentPrimaryCredential, LockDomain lockDomain)
            throws RemoteException {
        assertTrue(mService.setLockCredential(newCredential, currentPrimaryCredential, lockDomain,
                userId));
        assertEquals(newCredential.getType(), mService.getCredentialType(userId, lockDomain));
        if (lockDomain == Primary) {
            if (newCredential.isNone()) {
                assertEquals(0, mGateKeeperService.getSecureUserId(userId));
            } else {
                assertNotEquals(0, mGateKeeperService.getSecureUserId(userId));
            }
        }
    }
}
