package io.hyperaudio.bridge

import org.junit.Assert.*
import org.junit.Test

class SessionRecoveryTest {
    @Test fun retryBudgetIsBounded() {
        assertEquals(listOf(1500L, 3000L, 4500L, 6000L, 7500L), (0..4).map { SessionRecovery.delayMillis(it) })
        assertNull(SessionRecovery.delayMillis(5)); assertNull(SessionRecovery.delayMillis(-1))
    }
    @Test fun staleSessionCannotRevive() {
        assertFalse(SessionRecovery.canRecover("pc", 999, 1000))
        assertFalse(SessionRecovery.canRecover("pc", 100000, 1000))
        assertFalse(SessionRecovery.canRecover(null, 1100, 1000))
        assertFalse(SessionRecovery.canRecover("", 1100, 1000))
    }
    @Test fun recentRequestedSessionCanRecover() { assertTrue(SessionRecovery.canRecover("pc", 91000, 1000)) }
}
