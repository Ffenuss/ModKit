package io.github.ffenuss.modkit.patch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexGameplayContextTest {
    @Test
    fun ordinaryAppsAreNotPresentedAsGamesForGenericNames() {
        val nonGameCases = listOf(
            "Lcom/example/logging/Logger;" to "getLevel",
            "Lcom/example/media/AudioCodec;" to "getLevel",
            "Lcom/example/hr/ApplicantProfile;" to "getExperience",
            "Lcom/example/education/LearningProfile;" to "getCurrentLevel",
            "Lcom/example/app/ExperienceManager;" to "getXP",
            "Lcom/example/app/Account;" to "getSkillPoints",
        )
        nonGameCases.forEach { (owner, method) ->
            assertTrue(DexGameplayContext.isProgressionGetter(method))
            assertFalse(
                "$owner.$method is not evidence of a video game",
                DexGameplayContext.acceptProgressionGetter(owner, method),
            )
        }
    }

    @Test
    fun gameOwnedStateGettersRemainVisible() {
        val cases = listOf(
            "Lcom/example/android/game/PlayerStats;" to "getXP",
            "Lcom/example/game/Stats;" to "getLevel",
            "Lcom/example/rpg/CharacterProgression;" to "getExperience",
            "Lcom/example/app/PlayerStats;" to "getPlayerLevel",
        )
        cases.forEach { (owner, method) ->
            assertTrue(
                "Expected narrow game-context match for $owner.$method",
                DexGameplayContext.acceptProgressionGetter(owner, method),
            )
        }
    }

    @Test
    fun gameHudAndLoggingAreStillExcluded() {
        assertFalse(
            DexGameplayContext.acceptProgressionGetter(
                "Lcom/example/game/HudLevelView;",
                "getLevel",
            ),
        )
        assertFalse(
            DexGameplayContext.acceptProgressionGetter(
                "Lcom/example/game/Logger;",
                "getLevel",
            ),
        )
    }

    @Test
    fun nonProgressionNamesAreNotAccidentallyAffected() {
        assertFalse(DexGameplayContext.isProgressionGetter("getMaxHealth"))
        assertFalse(
            DexGameplayContext.acceptProgressionGetter(
                "Lcom/example/game/Player;",
                "getMaxHealth",
            ),
        )
    }
}
