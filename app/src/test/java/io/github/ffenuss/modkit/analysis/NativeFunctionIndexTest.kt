package io.github.ffenuss.modkit.analysis

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NativeFunctionIndexTest {
    private val active = object : CancellationSignal { override fun isCancelled() = false }

    @Test fun findsAliasesAndBoundariesAcrossSortedDiskRuns() {
        val root = Files.createTempDirectory("native-index-").toFile()
        try {
            val index = NativeFunctionIndexWriter(root.resolve("index"), active, 3).use {
                listOf(88L, 24L, 40L, 24L, 80L, 24L, 120L).forEach(it::add)
                it.finish(7, true)
            }
            assertTrue(index.verify())
            assertEquals(5, index.records)
            assertEquals(NativeFunctionSpan(24, 3, 40), index.lookup(24))
            assertEquals(NativeFunctionSpan(120, 1, null), index.lookup(120))
            assertNull(index.lookup(28))
            assertEquals(listOf("index"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun coversFunctionsBeyondTheRichBindingLimit() {
        val root = Files.createTempDirectory("native-large-index-").toFile()
        try {
            val index = NativeFunctionIndexWriter(root.resolve("index"), active).use { writer ->
                for (i in 0 until 40_000) writer.add(i.toLong() * 8)
                writer.add(8)
                writer.finish(40_001, true)
            }
            assertEquals(40_000, index.records)
            assertEquals(2, index.lookup(8)!!.references)
            assertEquals(320_000L - 8, index.lookup(320_000L - 16)!!.nextOffset)
        } finally { root.deleteRecursively() }
    }

    @Test fun incompleteOrCorruptIndexCannotProveAnAddress() {
        val root = Files.createTempDirectory("native-incomplete-index-").toFile()
        try {
            val index = NativeFunctionIndexWriter(root.resolve("index"), active).use {
                it.add(8); it.finish(2, false)
            }
            assertNull(index.lookup(8))
            root.resolve("index").appendBytes(byteArrayOf(1))
            assertFalse(index.verify())
        } finally { root.deleteRecursively() }
    }
}
