package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallConfirmationAttemptGateTest {
    @Test
    fun duplicateCallbacksAndTapsCannotReopenTheSameSession() {
        val gate = InstallConfirmationAttemptGate()
        gate.remember(42)
        assertTrue(gate.available(42))
        assertTrue(gate.claim(42))
        assertFalse(gate.available(42))
        assertFalse(gate.claim(42))
        // Android is allowed to resend PENDING_USER_ACTION for the same session.
        gate.remember(42)
        assertFalse(gate.claim(42))
    }

    @Test
    fun aNewSessionCanBeConfirmedIndependently() {
        val gate = InstallConfirmationAttemptGate()
        gate.remember(42)
        assertTrue(gate.claim(42))
        gate.remember(43)
        assertFalse(gate.claim(42))
        assertTrue(gate.claim(43))
        gate.clear(42) // stale callback must not clear the new session
        assertFalse(gate.available(43))
        gate.clear(43)
        gate.remember(44)
        assertTrue(gate.claim(44))
    }

    @Test
    fun synchronousLaunchFailureAllowsExplicitRetry() {
        val gate = InstallConfirmationAttemptGate()
        gate.remember(42)
        assertTrue(gate.claim(42))
        gate.releaseAfterFailedLaunch(42)
        assertTrue(gate.available(42))
        assertTrue(gate.claim(42))
    }

    @Test
    fun noPendingSessionMeansNoLaunch() {
        val gate = InstallConfirmationAttemptGate()
        assertFalse(gate.claim(42))
        gate.remember(null)
        assertFalse(gate.available(42))
        gate.remember(43)
        assertFalse(gate.claim(42))
        gate.clear(43)
        assertFalse(gate.claim(43))
    }
}
