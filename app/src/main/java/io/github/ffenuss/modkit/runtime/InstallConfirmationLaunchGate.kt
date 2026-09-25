package io.github.ffenuss.modkit.runtime

/**
 * A PackageInstaller confirmation Intent is a one-shot handoff for its session.
 * The receiver and the foreground UI can both try to launch it; only one may win.
 * A failed startActivity is retryable, but an accepted launch requires a new
 * install session if the user later dismisses the system dialog.
 */
internal class InstallConfirmationLaunchGate {
    private var sessionId: Int? = null
    private var launched = false

    @Synchronized
    fun remember(id: Int?): Boolean {
        if (id == null) return false
        if (sessionId != id) {
            sessionId = id
            launched = false
        }
        // Repeated callbacks must not rearm a confirmation already launched.
        return !launched
    }

    @Synchronized
    fun claim(id: Int): Boolean {
        if (sessionId != id || launched) return false
        launched = true
        return true
    }

    @Synchronized
    fun releaseOnLaunchFailure(id: Int) {
        if (sessionId == id) launched = false
    }

    @Synchronized
    fun clear(id: Int?): Boolean {
        if (id == null || sessionId != id) return false
        sessionId = null
        launched = false
        return true
    }

    @Synchronized
    fun availableFor(id: Int?): Boolean =
        id != null && sessionId == id && !launched
}
