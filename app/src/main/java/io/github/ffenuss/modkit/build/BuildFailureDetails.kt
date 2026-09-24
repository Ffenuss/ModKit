package io.github.ffenuss.modkit.build

/**
 * Android apksig wraps the actionable JCA failure in one or more generic
 * "Failed to sign using signer" exceptions. Never show just that wrapper.
 *
 * The bounded cause chain is safe for an on-screen error dialog or the
 * explicit diagnostic report. No keys, passwords or raw stack traces.
 */
object BuildFailureDetails {
    fun describe(
        stage: String,
        artifactName: String?,
        failure: Throwable,
    ): String {
        val messages = linkedSetOf<String>()
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>(),
        )
        var cursor: Throwable? = failure
        var depth = 0
        while (cursor != null && depth < 8 && seen.add(cursor)) {
            val message = cursor.message?.trim().orEmpty()
            val meaningful = message.ifBlank { cursor.javaClass.simpleName }
            messages += meaningful.take(500)
            cursor = cursor.cause
            depth++
        }
        val path = artifactName?.takeIf(String::isNotBlank)
            ?.let { " · " + it.substringAfterLast('/') }
            .orEmpty()
        return stage + path + ": " +
            messages.joinToString(" → ").take(1800)
    }
}
