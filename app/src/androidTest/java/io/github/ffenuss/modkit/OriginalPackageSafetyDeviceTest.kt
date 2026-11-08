package io.github.ffenuss.modkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ffenuss.modkit.analysis.ArtifactIdentity
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.space.OriginalInstalledPackageLauncher
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual installed ModKit is the owned fixture for the no-repack launch path. */
@RunWith(AndroidJUnit4::class)
class OriginalPackageSafetyDeviceTest {
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Test fun originalInstalledAppLaunchRequiresExactSourceAndDoesNotChangeApks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageName = context.packageName
        val installed = requireNotNull(InstalledAppRepository(context).find(packageName))
        assertTrue(installed.hasLauncherActivity)
        val sourceBefore = installed.apkFiles.map { file ->
            ArtifactSource(file.name, file.length(), hash(file))
        }
        val combined = ArtifactIdentity.combine(sourceBefore)
        val cancellation = AtomicCancellationSignal()
        val missing = OriginalInstalledPackageLauncher.inspect(
            context, packageName, "0".repeat(64), cancellation,
        )
        assertFalse("Changed bytes must not launch with stale analysis", missing.ready)
        assertFalse(missing.sourceMatches)

        val ready = OriginalInstalledPackageLauncher.inspect(
            context, packageName, combined, cancellation,
        )
        assertTrue("Original package must remain launchable: " + ready.reason, ready.ready)
        assertEquals(packageName, ready.packageName)
        OriginalInstalledPackageLauncher.launch(
            context, packageName, combined, cancellation,
        )
        val after = requireNotNull(InstalledAppRepository(context).find(packageName))
        assertEquals(
            "Preserve installed APK/split hashes; no signed replacement",
            sourceBefore.map { it.sha256 },
            after.apkFiles.map(::hash),
        )
        assertEquals(sourceBefore.size, after.apkFiles.size)
    }
}
