package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class VerifiedApkTransferTest {
    private val bytes = ByteArray(400_000) { (it % 251).toByte() }
    private val active = object : CancellationSignal { override fun isCancelled() = false }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun transfersExactBytesWithVisibleProgress() {
        val target = ByteArrayOutputStream()
        val updates = mutableListOf<Long>()
        assertEquals(bytes.size.toLong(), VerifiedApkTransfer.copy(bytes.inputStream(), target,
            bytes.size.toLong(), hash(bytes), active, updates::add))
        assertArrayEquals(bytes, target.toByteArray())
        assertEquals(0L, updates.first())
        assertEquals(bytes.size.toLong(), updates.last())
    }

    @Test fun rejectsSameSizeMutationAfterThePlanWasMade() {
        val changed = bytes.clone().apply { this[150_000] = 99 }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedApkTransfer.copy(changed.inputStream(), ByteArrayOutputStream(),
                bytes.size.toLong(), hash(bytes), active)
        }
    }

    @Test fun rejectsTruncatedOrGrowingInput() {
        for (size in listOf(bytes.size - 1, bytes.size + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedApkTransfer.copy(bytes.inputStream(), ByteArrayOutputStream(),
                    size.toLong(), hash(bytes), active)
            }
        }
    }

    @Test fun cancellationNeverReturnsACompletedTransfer() {
        var cancelled = false
        val signal = object : CancellationSignal { override fun isCancelled() = cancelled }
        assertThrows(AnalysisCancelledException::class.java) {
            VerifiedApkTransfer.copy(bytes.inputStream(), ByteArrayOutputStream(),
                bytes.size.toLong(), hash(bytes), signal) { if (it > 0) cancelled = true }
        }
    }
}
