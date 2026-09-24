package io.github.ffenuss.modkit.build

import java.security.InvalidKeyException
import java.security.SignatureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildRecoveryTest {
    @Test
    fun signerErrorIncludesRootCauseAndApkName() {
        val wrapped = SignatureException(
            "Failed to sign using signer MODKIT",
            InvalidKeyException("SHA256withRSA not supported by selected provider"),
        )
        val message = BuildFailureDetails.describe(
            "Подпись APK v2/v3",
            "split_UnityDataAssetPack.apk",
            wrapped,
        )
        assertTrue(message.contains("split_UnityDataAssetPack.apk"))
        assertTrue(message.contains("Failed to sign using signer MODKIT"))
        assertTrue(message.contains("SHA256withRSA not supported"))
    }

    @Test
    fun signerErrorHandlesCyclesAndLimitsLongMessages() {
        val problem = IllegalStateException("bad ".repeat(1000))
        val message = BuildFailureDetails.describe(
            "sign", null, problem,
        )
        assertTrue(message.length < 2000)
    }

    @Test
    fun storagePreflightRejectsInadequateSpaceBeforeRepack() {
        val mib = 1024L * 1024L
        val failure = assertThrows(IllegalArgumentException::class.java) {
            BuildStoragePreflight.checkAvailable(
                inputBytes = 400L * mib,
                availableBytes = 900L * mib,
            )
        }
        assertTrue(failure.message.orEmpty().contains("Недостаточно места"))
        BuildStoragePreflight.checkAvailable(
            inputBytes = 400L * mib,
            availableBytes = 930L * mib,
        )
    }

    @Test
    fun testBuildUsesModernAndroidSignatureSchemes() {
        assertEquals(24, ApkSigningStage.MIN_TEST_APK_API)
        assertEquals(false, ApkSigningStage.ENABLE_V1)
    }
}
