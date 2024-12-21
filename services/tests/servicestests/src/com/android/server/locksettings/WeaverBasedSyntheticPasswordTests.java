package com.android.server.locksettings;

import static com.android.internal.widget.LockDomain.Primary;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockDomain;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import com.google.android.collect.Sets;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

@SmallTest
@Presubmit
@RunWith(JUnitParamsRunner.class)
public class WeaverBasedSyntheticPasswordTests extends SyntheticPasswordTests {

    @Before
    public void enableWeaver() throws Exception {
        mSpManager.enableWeaver();
    }

    // Tests that if the device is not yet provisioned and the FRP credential uses Weaver, then the
    // Weaver slot of the FRP credential is not reused.  Assumes that Weaver slots are allocated
    // sequentially, starting at slot 0.
    @Test
    public void testFrpWeaverSlotNotReused() {
        final int userId = SECONDARY_USER_ID;
        final int frpWeaverSlot = 0;

        setDeviceProvisioned(false);
        assertEquals(Sets.newHashSet(), mPasswordSlotManager.getUsedSlots());
        mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_WEAVER, frpWeaverSlot, 0,
                new byte[1]);
        mService.initializeSyntheticPassword(userId); // This should allocate a Weaver slot.
        assertEquals(Sets.newHashSet(1), mPasswordSlotManager.getUsedSlots());
    }

    @Test
    public void testFrpWeaverSlotNotReusedWhenUsingWeaverForSecondFactor() {
        mSpManager.setUseWeaverForSecondaryProtectors(true);

        final int userId = SECONDARY_USER_ID;
        final int frpWeaverSlot = 0;

        setDeviceProvisioned(false);
        assertEquals(Sets.newHashSet(), mPasswordSlotManager.getUsedSlots());
        mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_WEAVER, frpWeaverSlot, 0,
                new byte[1]);
        mService.initializeSyntheticPassword(userId); // This should allocate 2 Weaver slots.
        assertEquals(Sets.newHashSet(1, 2), mPasswordSlotManager.getUsedSlots());
    }

    // Tests that if the device is already provisioned and the FRP credential uses Weaver, then the
    // Weaver slot of the FRP credential is reused.  This is not a very interesting test by itself;
    // it's here as a control for testFrpWeaverSlotNotReused().
    @Test
    public void testFrpWeaverSlotReused() {
        final int userId = SECONDARY_USER_ID;
        final int frpWeaverSlot = 0;

        setDeviceProvisioned(true);
        assertEquals(Sets.newHashSet(), mPasswordSlotManager.getUsedSlots());
        mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_WEAVER, frpWeaverSlot, 0,
                new byte[1]);
        mService.initializeSyntheticPassword(userId); // This should allocate a Weaver slot.
        assertEquals(Sets.newHashSet(0), mPasswordSlotManager.getUsedSlots());
    }

    @Test
    public void testFrpWeaverSlotReusedWhenUsingWeaverForSecondFactor() {
        mSpManager.setUseWeaverForSecondaryProtectors(true);

        final int userId = SECONDARY_USER_ID;
        final int frpWeaverSlot = 0;

        setDeviceProvisioned(true);
        assertEquals(Sets.newHashSet(), mPasswordSlotManager.getUsedSlots());
        mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_WEAVER, frpWeaverSlot, 0,
                new byte[1]);
        mService.initializeSyntheticPassword(userId); // This should allocate 2 Weaver slots.
        assertEquals(Sets.newHashSet(0, 1), mPasswordSlotManager.getUsedSlots());
    }

    @Test
    @Parameters({"Primary", "Secondary"})
    public void createAndUnlockLskfBasedProtector_nonNone(LockDomain lockDomain) {
        mSpManager.setUseWeaverForSecondaryProtectors(true);

        final int userId = PRIMARY_USER_ID;
        final LockscreenCredential pin = newPin("123456");
        final LockscreenCredential badPin = newPin("654321");

        // Create

        assertEquals(PersistentData.NONE, mStorage.readPersistentDataBlock());
        assertEquals(0, mPasswordSlotManager.getUsedSlots().size());

        SyntheticPasswordManager.SyntheticPassword sp = mSpManager.newSyntheticPassword(userId,
                lockDomain);
        long protectorId = mSpManager.createLskfBasedProtector(mGateKeeperService,
                pin, lockDomain, sp, userId);

        assertEquals(1, mPasswordSlotManager.getUsedSlots().size());
        if (lockDomain == Primary) {
            Assert.assertNotEquals(PersistentData.NONE, mStorage.readPersistentDataBlock());
        } else {
            assertEquals(PersistentData.NONE, mStorage.readPersistentDataBlock());
        }
        assertTrue(mSpManager.hasPasswordData(protectorId, userId));
        assertTrue(mSpManager.hasPasswordMetrics(protectorId, userId));

        // Unlock

        mSpManager.newSidForUser(mGateKeeperService, sp, userId);
        SyntheticPasswordManager.AuthenticationResult result = mSpManager.unlockLskfBasedProtector(
                mGateKeeperService, protectorId, pin, lockDomain, userId,
                null);
        assertArrayEquals(result.syntheticPassword.deriveKeyStorePassword(),
                sp.deriveKeyStorePassword());
        if (lockDomain == Primary) {
            assertEquals(VerifyCredentialResponse.RESPONSE_OK, result.gkResponse.getResponseCode());
            assertNotNull(result.gkResponse.getGatekeeperHAT());
        } else {
            assertEquals(VerifyCredentialResponse.RESPONSE_OK, result.gkResponse.getResponseCode());
            assertNull(result.gkResponse.getGatekeeperHAT());
        }

        result = mSpManager.unlockLskfBasedProtector(mGateKeeperService, protectorId, badPin,
                lockDomain, userId, null);
        assertNull(result.syntheticPassword);
        assertEquals(VerifyCredentialResponse.ERROR, result.gkResponse);
    }
}
