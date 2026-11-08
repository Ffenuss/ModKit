package io.github.ffenuss.modkit

import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ElfImage
import io.github.ffenuss.modkit.analysis.NativeScalarExportDiscovery
import io.github.ffenuss.modkit.analysis.NativeScalarExportCandidate
import io.github.ffenuss.modkit.analysis.NativeScalarExportPatch
import java.io.File
import java.util.zip.ZipFile

/**
 * Owned-fixture-only adapter from the generic, nonselectable ELF candidate to
 * a proposed exact-byte patch. The role of the exported symbol is known ONLY
 * to this device test, not to the generic scanner or the production menu.
 */
internal object NativeFixtureScalarDiscoveryProof {
    data class Discovery(
        val candidate: NativeScalarExportCandidate,
        val patch: NativeScalarExportPatch,
    )

    fun discover(
        apk: File,
        abi: String,
        cacheDir: File,
        cancellation: CancellationSignal,
    ): Discovery {
        require(abi == "x86_64" || abi == "arm64-v8a")
        val temp = File.createTempFile("modkit-game-native-", ".so", cacheDir)
        try {
            ZipFile(apk).use { zip ->
                val entry = requireNotNull(zip.getEntry(
                    "lib/$abi/libmodkit_native_fixture.so"
                )) { "Owned native game ELF missing for $abi" }
                require(entry.size in 64..2_000_000)
                zip.getInputStream(entry).use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            ElfImage.open(temp, cancellation).use { elf ->
                val candidates = NativeScalarExportDiscovery.scan(elf, cancellation)
                val candidate = candidates.single {
                    it.symbol == "native_fixture_step" && it.abi == abi
                }
                require(candidate.value == 2) {
                    "Game fixture native step changed; no automatic replacement"
                }
                require(!candidate.selectable) {
                    "Structural candidates must not claim gameplay proof"
                }
                val proposed = NativeScalarExportDiscovery.propose(candidate, 7)
                require(proposed.expectedHex == candidate.expectedPatchHex)
                require(proposed.patchVirtualAddress > 0)
                return Discovery(candidate, proposed)
            }
        } finally {
            temp.delete()
        }
    }
}
