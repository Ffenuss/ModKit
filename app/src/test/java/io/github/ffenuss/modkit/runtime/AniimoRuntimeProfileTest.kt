package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniimoRuntimeProfileTest {
    @Test
    fun confirmsAniimoUnityIl2CppProfile() {
        val result =
            AniimoRuntimeProfile.inspect(
                packageName =
                    AniimoRuntimeProfile.PACKAGE_NAME,
                loadedModules =
                    listOf(
                        "/data/app/lib/arm64/libunity.so",
                        "/data/app/lib/arm64/libil2cpp.so",
                        "/data/app/lib/arm64/libxlua.so",
                    ),
            )

        assertTrue(result.packageMatched)
        assertTrue(result.unityDetected)
        assertTrue(result.il2CppDetected)
        assertTrue(result.xLuaDetected)
        assertTrue(result.eligibleForRuntimeOverlay)
        assertTrue(result.blockers.isEmpty())
    }

    @Test
    fun metadataEntryCanConfirmIl2CppBeforeLaunch() {
        val result =
            AniimoRuntimeProfile.inspect(
                packageName =
                    AniimoRuntimeProfile.PACKAGE_NAME,
                archiveEntries =
                    listOf(
                        "lib/arm64-v8a/libunity.so",
                        "assets/bin/Data/Managed/Metadata/global-metadata.dat",
                    ),
            )

        assertTrue(result.unityDetected)
        assertTrue(result.il2CppDetected)
        assertTrue(result.eligibleForRuntimeOverlay)
    }

    @Test
    fun acceptsRussianAniimoPackageVariant() {
        val result =
            AniimoRuntimeProfile.inspect(
                packageName =
                    AniimoRuntimeProfile.RU_PACKAGE_NAME,
                loadedModules =
                    listOf(
                        "libunity.so",
                        "libil2cpp.so",
                    ),
            )

        assertTrue(result.packageMatched)
        assertTrue(result.eligibleForRuntimeOverlay)
    }

    @Test
    fun refusesToTreatAnotherPackageAsAniimo() {
        val result =
            AniimoRuntimeProfile.inspect(
                packageName = "com.example.other",
                loadedModules =
                    listOf(
                        "libunity.so",
                        "libil2cpp.so",
                    ),
            )

        assertFalse(result.packageMatched)
        assertFalse(result.eligibleForRuntimeOverlay)
        assertTrue(result.blockers.isNotEmpty())
    }

    @Test
    fun luaSignalIsOptionalForBaseOverlayEligibility() {
        val result =
            AniimoRuntimeProfile.inspect(
                packageName =
                    AniimoRuntimeProfile.PACKAGE_NAME,
                loadedModules =
                    listOf(
                        "libunity.so",
                        "libil2cpp.so",
                    ),
            )

        assertFalse(result.xLuaDetected)
        assertTrue(result.eligibleForRuntimeOverlay)
    }
}
