package io.github.ffenuss.modkit.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SandboxProfileParserTest {
    @Test
    fun parsesConfirmedGameplayProfile() {
        val profile =
            SandboxProfileParser.parse(
                """
                {
                  "schema": "modkit/root-mod-profile/1",
                  "createdAtEpochMs": 1,
                  "packageName": "com.example.game",
                  "label": "Game",
                  "versionName": "1.0",
                  "versionCode": 7,
                  "artifactSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "modifications": [
                    {
                      "id": "speed",
                      "title": "Speed",
                      "category": "SPEED",
                      "moduleName": "libgame.so",
                      "fileOffset": 4096,
                      "replacementHex": "c0035fd6",
                      "abi": "arm64-v8a",
                      "runtimeId": "native"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(
            "com.example.game",
            profile.packageName,
        )
        assertEquals(
            1,
            profile.modifications.size,
        )
    }

    @Test
    fun rejectsSensitiveSurface() {
        try {
            SandboxProfileParser.parse(
                """
                {
                  "schema": "modkit/root-mod-profile/1",
                  "packageName": "com.example.game",
                  "versionCode": 7,
                  "artifactSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "modifications": [
                    {
                      "id": "billing",
                      "category": "SENSITIVE_SURFACE",
                      "moduleName": "libgame.so",
                      "fileOffset": 4096,
                      "replacementHex": "c0035fd6"
                    }
                  ]
                }
                """.trimIndent(),
            )
            fail("Sensitive surface must be rejected.")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
