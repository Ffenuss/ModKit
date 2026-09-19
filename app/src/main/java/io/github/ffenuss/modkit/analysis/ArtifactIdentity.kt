package io.github.ffenuss.modkit.analysis

import java.security.MessageDigest

/**
 * Canonical content identity for a single target or APK split set.
 *
 * A single source is identified by its own SHA-256. A multi-source set is
 * ordered deterministically and binds display name, content SHA and size.
 */
object ArtifactIdentity {
    fun combine(sources: List<ArtifactSource>): String {
        require(sources.isNotEmpty()) { "Artifact identity requires at least one source" }
        if (sources.size == 1) return sources.single().sha256.lowercase()

        val digest = MessageDigest.getInstance("SHA-256")
        sources.sortedWith(
            compareBy<ArtifactSource> { it.displayName }
                .thenBy { it.sha256.lowercase() }
                .thenBy { it.size },
        ).forEach { source ->
            digest.update(source.displayName.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(source.sha256.lowercase().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(source.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
