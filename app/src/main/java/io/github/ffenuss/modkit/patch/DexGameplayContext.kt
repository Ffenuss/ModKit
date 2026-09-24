package io.github.ffenuss.modkit.patch

/**
 * DEX has no reliable "this is an RPG" bit. Methods such as getLevel() and
 * getExperience() are common in logging, audio, education and business apps.
 * Only display a gameplay-progression suggestion when both the method name
 * and its owning class/package supply a narrow gameplay context.
 *
 * This is still semantic evidence, NOT runtime proof that a patch changes
 * the player's actual level or XP.
 */
object DexGameplayContext {
    private val progressionGetterNames = setOf(
        "getexp", "getxp", "getexperience",
        "getcurrentxp", "getcurrentexperience",
        "getskillpoints", "getabilitypoints",
        "getlevel", "getplayerlevel",
        "getcharacterlevel", "getcurrentlevel",
        "getskilllevel",
    )

    private val excludedOwnerMarkers = listOf(
        "logger", "logging", "loglevel",
        "audio", "video", "codec", "media",
        "screen", "widget", "view", "dialog", "hud", "display",
        "analytics", "telemetry", "http", "network", "webview",
        "career", "resume", "recruit", "jobprofile",
        "course", "education", "school", "lesson",
        "accesslevel", "permission", "authorization",
    )

    private val explicitlyGameplayOwners = listOf(
        "player", "character", "hero", "avatar",
        "gamestate", "playerstats", "characterstats",
        "skilltree", "experiencecontroller",
        "xpmanager", "progressioncontroller",
    )

    private val gameplayPackageSegments = setOf(
        "game", "games", "gameplay", "rpg",
        "roguelike", "combat", "battle",
    )

    fun isProgressionGetter(methodName: String): Boolean =
        normalize(methodName) in progressionGetterNames

    fun isPlausibleProgressionOwner(
        definingClass: String,
    ): Boolean {
        val path = definingClass.removePrefix("L")
            .removeSuffix(";").lowercase()
        val owner = path.substringAfterLast('/')
            .substringBefore('$')
        if (excludedOwnerMarkers.any { it in owner }) return false
        val segments = path.split('/')
        val gameNamespace = segments.any { it in gameplayPackageSegments }
        val gameOwner = explicitlyGameplayOwners.any { it in owner }
        return gameNamespace || gameOwner
    }

    fun acceptProgressionGetter(
        definingClass: String,
        methodName: String,
    ): Boolean =
        isProgressionGetter(methodName) &&
            isPlausibleProgressionOwner(definingClass)

    private fun normalize(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)
}
