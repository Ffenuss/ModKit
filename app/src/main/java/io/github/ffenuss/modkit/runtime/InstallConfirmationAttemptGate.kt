package io.github.ffenuss.modkit.runtime

/**
 * One user-initiated PackageInstaller confirmation launch per pending session.
 *
 * The system may send repeated pending-action callbacks. They must not reopen
 * an already committed / finished temporary APK after the first confirmation.
 * A launch that throws synchronously may be retried; the next session starts
 * with a fresh claim. This contains no Android dependency so it can be tested
 * on the JVM.
 */
internal class InstallConfirmationAttemptGate {
    private var currentSession: Int? = null
    private var claimed = false

    @Synchronized
    fun remember(sessionId: Int?) {
        if (currentSession != sessionId) {
            currentSession = sessionId
            claimed = false
        }
    }

    @Synchronized
    fun claim(sessionId: Int): Boolean {
        if (currentSession != sessionId || claimed) return false
        claimed = true
        return true
    }

    @Synchronized
    fun releaseAfterFailedLaunch(sessionId: Int) {
        if (currentSession == sessionId) claimed = false
    }

    @Synchronized
    fun clear(sessionId: Int?) {
        if (currentSession == sessionId) {
            currentSession = null
            claimed = false
        }
    }
}
