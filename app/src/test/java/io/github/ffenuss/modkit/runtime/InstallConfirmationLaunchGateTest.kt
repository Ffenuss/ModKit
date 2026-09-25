package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallConfirmationLaunchGateTest {
    @Test fun receiverAndUiCannotLaunchTheSameConfirmationTwice() {
        val gate = InstallConfirmationLaunchGate()
        assertTrue(gate.remember(29))
        assertTrue(gate.claim(29))
        assertFalse(gate.availableFor(29))
        assertFalse(gate.claim(29))
        assertFalse("A duplicate PackageInstaller callback cannot rearm the intent", gate.remember(29))
        assertFalse(gate.claim(29))
    }

    @Test fun startActivityFailureLeavesManualConfirmationAvailable() {
        val gate = InstallConfirmationLaunchGate()
        assertTrue(gate.remember(29))
        assertTrue(gate.claim(29))
        gate.releaseOnLaunchFailure(29)
        assertTrue(gate.availableFor(29))
        assertTrue(gate.claim(29))
        assertFalse(gate.claim(29))
    }

    @Test fun aNewSessionCanConfirmAfterThePreviousOneWasLaunched() {
        val gate = InstallConfirmationLaunchGate()
        assertTrue(gate.remember(29))
        assertTrue(gate.claim(29))
        assertTrue(gate.remember(35))
        assertFalse(gate.claim(29))
        assertTrue(gate.claim(35))
        assertFalse(gate.availableFor(35))
    }

    @Test fun completedOrUnknownSessionsCannotBeReopened() {
        val gate = InstallConfirmationLaunchGate()
        assertFalse(gate.remember(null))
        assertFalse(gate.claim(1))
        assertTrue(gate.remember(29))
        assertFalse(gate.clear(35))
        assertTrue(gate.availableFor(29))
        assertTrue(gate.clear(29))
        assertFalse(gate.availableFor(29))
        assertFalse(gate.claim(29))
    }
}
