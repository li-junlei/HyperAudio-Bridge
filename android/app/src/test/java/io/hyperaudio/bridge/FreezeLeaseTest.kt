package io.hyperaudio.bridge

import org.junit.Assert.*
import org.junit.Test

class FreezeLeaseTest {
    @Test fun onlyOwnerIsProtectedDuringLease() {
        val lease = FreezeLease()
        assertFalse(lease.protects(10312, 100))
        lease.update(10312, true, 100)
        assertTrue(lease.protects(10312, 101))
        assertFalse(lease.protects(10313, 101))
        assertFalse(lease.protects(1000, 101))
        assertFalse(lease.protects(10312, 45100))
    }
    @Test fun binderFreezeIsScopedAndThawAlwaysAllowed() {
        val lease = FreezeLease()
        lease.update(10312, true, 100)
        assertTrue(lease.blocksBinder(10312, true, 101))
        assertFalse(lease.blocksBinder(10312, false, 101))
        assertFalse(lease.blocksBinder(10313, true, 101))
        assertFalse(lease.blocksBinder(10312, true, 45100))
        lease.update(10312, false, 102)
        assertFalse(lease.blocksBinder(10312, true, 103))
    }
    @Test fun renewalAndStop() {
        val lease = FreezeLease()
        lease.update(10312, true, 100)
        lease.update(10312, true, 30100)
        assertTrue(lease.protects(10312, 45100))
        lease.update(10312, false, 45101)
        assertFalse(lease.protects(10312, 45101))
    }
}
