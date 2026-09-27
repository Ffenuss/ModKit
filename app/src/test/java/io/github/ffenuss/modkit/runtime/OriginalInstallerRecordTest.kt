package io.github.ffenuss.modkit.runtime

import org.junit.Assert.*
import org.junit.Test

class OriginalInstallerRecordTest {
    @Test fun recordIsBoundToExactPackageInstallerAndArtifact() {
        val record = OriginalInstallerRecord("dev.fixture", "dev.original.store", "a".repeat(64))
        assertEquals("modkit-installer/1\ndev.fixture\ndev.original.store\n${"a".repeat(64)}\n", record.encode().toString(Charsets.UTF_8))
    }
    @Test fun invalidNamesAndArtifactCannotBecomeARecord() {
        for (installer in listOf("", "dev.store\nother", "dev/store", "a".repeat(255))) {
            assertThrows(IllegalArgumentException::class.java) { OriginalInstallerRecord("dev.fixture", installer, "a".repeat(64)) }
        }
        assertThrows(IllegalArgumentException::class.java) { OriginalInstallerRecord("dev.fixture", "dev.store", "stale") }
    }
}
