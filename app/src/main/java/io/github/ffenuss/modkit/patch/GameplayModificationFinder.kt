package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.Il2CppMethodBinaryBinding
import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind

enum class GameplayModificationCategory(
    val title: String,
    val priority: Int,
) {
    SURVIVABILITY("Здоровье / бессмертие", 0),
    DAMAGE("Урон / защита", 1),
    MOVEMENT("Скорость / движение", 2),
    COLLISION("Noclip / коллизии", 3),
    STAMINA("Стамина / энергия", 4),
    COOLDOWN("Кулдауны / таймеры", 5),
    ATTACK_SPEED("Скорость атаки", 6),
    REGENERATION("Регенерация / лечение", 7),
    PROGRESSION("Уровень / опыт / навыки", 8),
    INVENTORY("Инвентарь / предметы", 9),
    DROPS("Дроп / награды", 10),
    DIFFICULTY("Сложность / параметры врагов", 11),
    WORLD("Прыжок / гравитация / время", 12),
    CAMERA("Камера / FOV", 13),
}

enum class GameplayMutationAction(
    val presetId: String?,
) {
    SKIP_METHOD("arm64-return-void"),
    FORCE_FALSE("arm64-return-zero"),
    FORCE_TRUE("arm64-return-one"),
    FORCE_ZERO("arm64-return-zero"),
    DISCOVERY_ONLY(null),
}

data class GameplayModificationOpportunity(
    val id: String,
    val category: GameplayModificationCategory,
    val title: String,
    val targetId: String,
    val targetDisplayName: String,
    val action: GameplayMutationAction,
    val replacementHex: String?,
    val selectable: Boolean,
    val blocker: String?,
    val evidenceSummary: String,
)

/**
 * Discovers user-facing gameplay modification opportunities from already
 * proven IL2CPP executable targets.
 *
 * A metadata name is only semantic discovery evidence. It never proves the
 * gameplay effect. Selectable rows therefore describe the exact mutation
 * ("skip this exact method" / "force this exact bool return") rather than
 * claiming a higher-level effect such as "god mode".
 */
object GameplayModificationFinder {
    private data class Rule(
        val category: GameplayModificationCategory,
        val terms: Set<String>,
    )

    fun find(
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan,
        projectCodeOnly: Boolean = true,
        limit: Int = 64,
    ): List<GameplayModificationOpportunity> {
        require(limit in 1..256) { "Gameplay modification result limit is out of bounds." }

        val bindingByArtifactToken =
            result.il2cppBinaryBinding
                ?.evidence
                .orEmpty()
                .asSequence()
                .flatMap { item ->
                    item.bindings.asSequence().map { binding ->
                        bindingKey(item.libraryEntry, binding.metadataToken) to binding
                    }
                }
                .groupBy({ it.first }, { it.second })

        val eligible = preparation.targets
            .asSequence()
            .filter(::isEligible)
            .filter { prepared ->
                !projectCodeOnly ||
                    Il2CppPatchTargetBrowser.isAssemblyCSharp(prepared.target)
            }
            .toList()

        val sharedBodyCounts = eligible
            .asSequence()
            .mapNotNull { prepared ->
                val artifact = prepared.target.artifact ?: return@mapNotNull null
                val offset = prepared.target.fileOffset ?: return@mapNotNull null
                bodyKey(artifact, offset)
            }
            .groupingBy { it }
            .eachCount()

        return eligible
            .asSequence()
            .mapNotNull { prepared ->
                val target = prepared.target
                val artifact = target.artifact ?: return@mapNotNull null
                val token = target.metadataToken ?: return@mapNotNull null
                val abi = target.abi ?: return@mapNotNull null
                val offset = target.fileOffset ?: return@mapNotNull null
                val identity = (
                    target.declaringType.orEmpty() + "." +
                        target.memberName.orEmpty()
                    )
                val compact = compact(identity)

                if (forbiddenTerms.any { it in compact }) {
                    return@mapNotNull null
                }

                val rule = rules.firstOrNull { candidate ->
                    candidate.terms.any { it in compact }
                } ?: return@mapNotNull null

                val binding =
                    bindingByArtifactToken[
                        bindingKey(artifact, token)
                    ]
                        ?.singleOrNull()
                val action =
                    resolveAction(
                        category = rule.category,
                        compactIdentity = compact,
                        memberName = target.memberName.orEmpty(),
                        returnKind =
                            binding?.returnKind
                                ?: Il2CppNativeReturnKind.UNKNOWN,
                    )
                val preset =
                    action.presetId?.let { presetId ->
                        NativePatchPresetCatalog.find(
                            abi = abi,
                            id = presetId,
                        )
                    }
                val sharedCount =
                    sharedBodyCounts[
                        bodyKey(artifact, offset)
                    ] ?: 0
                val blocker = when {
                    sharedCount != 1 ->
                        "Native body общий для " + sharedCount +
                            " metadata-методов; одиночный patch заблокирован."
                    binding == null ->
                        "Binary binding не содержит доказанной сигнатуры return type."
                    action == GameplayMutationAction.DISCOVERY_ONLY ->
                        numericBlocker(rule.category, binding.returnKind)
                    preset == null ->
                        "Для ABI/return type пока нет безопасного готового mutation preset."
                    else -> null
                }
                val selectable =
                    blocker == null &&
                        preset != null &&
                        action != GameplayMutationAction.DISCOVERY_ONLY

                GameplayModificationOpportunity(
                    id =
                        rule.category.name.lowercase() + ":" +
                            target.id + ":" + action.name.lowercase(),
                    category = rule.category,
                    title =
                        actionTitle(
                            category = rule.category,
                            action = action,
                            methodName =
                                target.memberName
                                    ?: target.displayName,
                        ),
                    targetId = target.id,
                    targetDisplayName = target.displayName,
                    action = action,
                    replacementHex = preset?.replacementHex,
                    selectable = selectable,
                    blocker = blocker,
                    evidenceSummary =
                        "EXACT_BINARY · token 0x" +
                            token.toString(16) +
                            " · offset 0x" +
                            offset.toString(16) +
                            " · semantic match по metadata identity; игровой эффект требует теста.",
                )
            }
            .distinctBy { it.targetId }
            .sortedWith(
                compareByDescending<GameplayModificationOpportunity> {
                    it.selectable
                }
                    .thenBy { it.category.priority }
                    .thenBy { it.targetDisplayName.lowercase() },
            )
            .take(limit)
            .toList()
    }

    private fun isEligible(
        prepared: PreparedTarget,
    ): Boolean =
        (
            prepared.status == PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ||
                prepared.status == PreparationTargetStatus.READY
            ) &&
            prepared.target.runtimeId == "unity_il2cpp" &&
            prepared.target.fileOffset != null &&
            prepared.target.abi != null &&
            prepared.target.metadataToken != null

    private fun resolveAction(
        category: GameplayModificationCategory,
        compactIdentity: String,
        memberName: String,
        returnKind: Il2CppNativeReturnKind,
    ): GameplayMutationAction {
        if (returnKind == Il2CppNativeReturnKind.BOOLEAN) {
            if (forceFalseTerms.any { it in compactIdentity }) {
                return GameplayMutationAction.FORCE_FALSE
            }
            if (forceTrueTerms.any { it in compactIdentity }) {
                return GameplayMutationAction.FORCE_TRUE
            }
        }

        if (returnKind == Il2CppNativeReturnKind.VOID) {
            val skippable =
                when (category) {
                    GameplayModificationCategory.SURVIVABILITY,
                    GameplayModificationCategory.DAMAGE ->
                        voidDamageTerms.any { it in compactIdentity }
                    GameplayModificationCategory.STAMINA ->
                        voidStaminaTerms.any { it in compactIdentity }
                    GameplayModificationCategory.COOLDOWN ->
                        voidCooldownTerms.any { it in compactIdentity }
                    GameplayModificationCategory.MOVEMENT ->
                        voidMovementLimitTerms.any { it in compactIdentity }
                    GameplayModificationCategory.COLLISION ->
                        voidCollisionTerms.any { it in compactIdentity }
                    else -> false
                }
            if (skippable) return GameplayMutationAction.SKIP_METHOD
        }

        if (
            returnKind == Il2CppNativeReturnKind.INTEGER &&
            category == GameplayModificationCategory.COOLDOWN &&
            memberName.startsWith("get_", ignoreCase = true)
        ) {
            return GameplayMutationAction.FORCE_ZERO
        }

        return GameplayMutationAction.DISCOVERY_ONLY
    }

    private fun actionTitle(
        category: GameplayModificationCategory,
        action: GameplayMutationAction,
        methodName: String,
    ): String = when (action) {
        GameplayMutationAction.SKIP_METHOD ->
            category.title + ": пропустить " + methodName + "()"
        GameplayMutationAction.FORCE_FALSE ->
            category.title + ": " + methodName + "() всегда false"
        GameplayMutationAction.FORCE_TRUE ->
            category.title + ": " + methodName + "() всегда true"
        GameplayMutationAction.FORCE_ZERO ->
            category.title + ": " + methodName + "() всегда 0"
        GameplayMutationAction.DISCOVERY_ONLY ->
            category.title + ": найден кандидат " + methodName + "()"
    }

    private fun numericBlocker(
        category: GameplayModificationCategory,
        returnKind: Il2CppNativeReturnKind,
    ): String =
        when (returnKind) {
            Il2CppNativeReturnKind.FLOATING_POINT ->
                "Найден числовой кандидат для «" + category.title +
                    "», но float/double constant/multiplier executor ещё не реализован."
            Il2CppNativeReturnKind.INTEGER ->
                "Найден числовой кандидат для «" + category.title +
                    "», но безопасная величина изменения не доказана автоматически."
            Il2CppNativeReturnKind.VALUE_TYPE ->
                "Возвращается value type; простой X0/S0 patch ABI-некорректен."
            Il2CppNativeReturnKind.POINTER_OR_REFERENCE ->
                "Ссылочный результат найден, но null не является автоматически корректной модификацией."
            Il2CppNativeReturnKind.UNKNOWN ->
                "Return type ещё не доказан."
            Il2CppNativeReturnKind.BOOLEAN,
            Il2CppNativeReturnKind.VOID ->
                "Семантика метода недостаточна для автоматического действия."
        }

    private fun bindingKey(
        artifact: String,
        token: Long,
    ): String = artifact + "#" + token.toString(16)

    private fun bodyKey(
        artifact: String,
        offset: Long,
    ): String = artifact + "@" + offset

    private fun compact(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private val forbiddenTerms =
        setOf(
            "purchase",
            "inapppurchase",
            "iap",
            "billing",
            "payment",
            "receipt",
            "checkout",
            "subscription",
            "entitlement",
            "anticheat",
            "integritycheck",
            "serverauth",
            "authentication",
            "login",
        )

    private val forceTrueTerms =
        setOf(
            "invincible",
            "invulnerable",
            "immortal",
            "godmode",
            "isalive",
            "canmove",
            "canrun",
            "cansprint",
            "canjump",
            "candash",
            "canfly",
            "canattack",
            "cancast",
            "canuse",
            "hasstamina",
            "hasenergy",
            "ignorecollision",
            "canpassthrough",
            "noclip",
        )

    private val forceFalseTerms =
        setOf(
            "isdead",
            "isdying",
            "deadstate",
            "oncooldown",
            "isoncooldown",
            "cooldownactive",
            "islocked",
            "movementblocked",
            "collisionenabled",
        )

    private val voidDamageTerms =
        setOf(
            "takedamage",
            "receivedamage",
            "applydamage",
            "ondamage",
            "hurt",
            "applyhurt",
            "die",
            "ondeath",
            "killplayer",
        )

    private val voidStaminaTerms =
        setOf(
            "consumestamina",
            "spendstamina",
            "drainstamina",
            "consumeenergy",
            "spendenergy",
            "drainenergy",
        )

    private val voidCooldownTerms =
        setOf(
            "startcooldown",
            "begincooldown",
            "applycooldown",
            "setcooldown",
        )

    private val voidMovementLimitTerms =
        setOf(
            "applyspeedlimit",
            "limitspeed",
            "clampspeed",
            "blockmovement",
        )

    private val voidCollisionTerms =
        setOf(
            "applycollision",
            "resolvecollision",
            "blockbycollision",
        )

    private val rules =
        listOf(
            Rule(
                GameplayModificationCategory.COLLISION,
                setOf(
                    "noclip",
                    "collision",
                    "collider",
                    "passthrough",
                    "throughwall",
                    "obstacle",
                ),
            ),
            Rule(
                GameplayModificationCategory.STAMINA,
                setOf(
                    "stamina",
                    "fatigue",
                    "sprintenergy",
                    "runenergy",
                ),
            ),
            Rule(
                GameplayModificationCategory.COOLDOWN,
                setOf(
                    "cooldown",
                    "recharge",
                    "skilldelay",
                    "abilitydelay",
                ),
            ),
            Rule(
                GameplayModificationCategory.ATTACK_SPEED,
                setOf(
                    "attackspeed",
                    "attackrate",
                    "firerate",
                    "shotspeed",
                ),
            ),
            Rule(
                GameplayModificationCategory.REGENERATION,
                setOf(
                    "regeneration",
                    "regen",
                    "healing",
                    "healrate",
                ),
            ),
            Rule(
                GameplayModificationCategory.MOVEMENT,
                setOf(
                    "movespeed",
                    "movementspeed",
                    "walkspeed",
                    "runspeed",
                    "sprintspeed",
                    "movement",
                    "locomotion",
                    "jumpheight",
                    "jump",
                    "dash",
                    "gravity",
                ),
            ),
            Rule(
                GameplayModificationCategory.SURVIVABILITY,
                setOf(
                    "invincible",
                    "invulnerable",
                    "immortal",
                    "godmode",
                    "isdead",
                    "isdying",
                    "isalive",
                    "health",
                    "hitpoints",
                    "playerhp",
                    "ondeath",
                    "die",
                ),
            ),
            Rule(
                GameplayModificationCategory.DAMAGE,
                setOf(
                    "takedamage",
                    "receivedamage",
                    "applydamage",
                    "ondamage",
                    "damage",
                    "hurt",
                    "attackpower",
                    "damagebonus",
                    "critical",
                    "critchance",
                ),
            ),
            Rule(
                GameplayModificationCategory.PROGRESSION,
                setOf(
                    "experience",
                    "experiencepoint",
                    "xpgain",
                    "playerxp",
                    "levelup",
                    "playerlevel",
                    "skillpoint",
                    "talentpoint",
                    "rank",
                ),
            ),
            Rule(
                GameplayModificationCategory.INVENTORY,
                setOf(
                    "inventory",
                    "itemcount",
                    "itemcapacity",
                    "carryweight",
                    "backpack",
                    "ammo",
                ),
            ),
            Rule(
                GameplayModificationCategory.DROPS,
                setOf(
                    "droprate",
                    "dropchance",
                    "lootchance",
                    "loot",
                    "reward",
                ),
            ),
            Rule(
                GameplayModificationCategory.DIFFICULTY,
                setOf(
                    "difficulty",
                    "enemyhealth",
                    "enemydamage",
                    "enemyspeed",
                ),
            ),
            Rule(
                GameplayModificationCategory.WORLD,
                setOf(
                    "timescale",
                    "gametime",
                    "gravityscale",
                    "jumpforce",
                ),
            ),
            Rule(
                GameplayModificationCategory.CAMERA,
                setOf(
                    "fieldofview",
                    "camerafov",
                    "zoom",
                ),
            ),
        )
}
