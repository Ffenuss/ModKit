package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItem
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItemMode
import java.io.File
import java.security.MessageDigest

/**
 * The simple UI must never bake a selected native change into the APK: a
 * selected, proven recipe becomes an initially OFF and reversible runtime
 * switch, otherwise the build fails with the exact unsupported recipe.
 */
object RuntimeRecipeSelectionPolicy {
    private val allowedCategories = setOf(
        GameplayModificationCategory.SURVIVABILITY,
        GameplayModificationCategory.DAMAGE,
        GameplayModificationCategory.MOVEMENT,
        GameplayModificationCategory.COLLISION,
        GameplayModificationCategory.STAMINA,
        GameplayModificationCategory.COOLDOWN,
        GameplayModificationCategory.CONTROL,
        GameplayModificationCategory.ATTACK_SPEED,
        GameplayModificationCategory.REGENERATION,
        GameplayModificationCategory.PROGRESSION,
        GameplayModificationCategory.INVENTORY,
        GameplayModificationCategory.DROPS,
        GameplayModificationCategory.DIFFICULTY,
        GameplayModificationCategory.WORLD,
        GameplayModificationCategory.CAMERA,
    )

    fun supports(recipe: AutoModRecipe): Boolean =
        recipe.selectable && recipe.dex.isEmpty &&
            recipe.native?.let {
                it.category in allowedCategories && !it.replacementHex.isNullOrBlank()
            } == true
}

object SelectedRuntimeMenuBuilder {
    private const val MAX_SWITCHES = 24
    private const val MAX_PATCH_BYTES = 64

    fun build(
        result: FastAnalysisResult,
        selected: List<AutoModRecipe>,
        analysisResultsRoot: File,
        cancellation: CancellationSignal,
    ): RuntimeGameplayTestMenuSpec {
        require(selected.isNotEmpty()) { "Выберите хотя бы один мод для меню." }
        require(selected.size <= MAX_SWITCHES) {
            "Runtime-меню поддерживает не более $MAX_SWITCHES переключателей."
        }
        require(selected.map { it.id }.distinct().size == selected.size) {
            "Один мод нельзя добавить в меню дважды."
        }

        val targets = result.evidenceGraph?.targets.orEmpty().associateBy { it.id }
        val libraries = result.il2cppBinaryBinding?.evidence.orEmpty().associateBy { it.libraryEntry }
        val verifiedIndices = mutableMapOf<String, Boolean>()
        val coveredRanges = mutableMapOf<String, MutableList<LongRange>>()

        val items = selected.map { recipe ->
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            require(RuntimeRecipeSelectionPolicy.supports(recipe)) {
                "«${recipe.title}» нельзя выключать во время игры: готового runtime-рецепта нет."
            }
            val native = requireNotNull(recipe.native)
            val target = requireNotNull(targets[native.targetId]) {
                "У рецепта «${recipe.title}» отсутствует точная IL2CPP-привязка."
            }
            require(target.abi == "arm64-v8a") {
                "Переключатели нативных модов сейчас поддерживают только ARM64."
            }
            val libraryName = requireNotNull(target.artifact)
            val library = requireNotNull(libraries[libraryName]) {
                "Не найдено подтверждение исходной нативной библиотеки."
            }
            val index = requireNotNull(library.functionIndex) {
                "Нет проверенного индекса адресов для runtime-переключателя."
            }
            require(verifiedIndices.getOrPut(libraryName) { index.complete && index.verify() }) {
                "Индекс адресов неполон или его контрольная сумма изменилась."
            }
            val offset = requireNotNull(target.fileOffset)
            val span = requireNotNull(index.lookup(offset)) {
                "Адрес метода отсутствует в проверенном индексе."
            }
            require(span.references == 1) {
                "Метод имеет общее тело: восстановление байтов затронет другие рецепты."
            }
            val binding = requireNotNull(Il2CppPatchTargetBrowser.bindingFor(result, target)) {
                "Нет единственной доказанной привязки метода к машинному коду."
            }
            require(binding.functionFileOffset == offset) {
                "Адрес рецепта расходится с точной binary-привязкой."
            }
            val replacement = Il2CppNativeMutationDraftBuilder.parseHex(
                requireNotNull(native.replacementHex),
            )
            require(replacement.size in 4..MAX_PATCH_BYTES && replacement.size % 4 == 0) {
                "Неверная длина runtime-патча для «${recipe.title}»."
            }
            val code = Il2CppNativeMutationDraftBuilder.readCodeWindow(
                result, target.id, analysisResultsRoot, MAX_PATCH_BYTES,
            )
            require(code.byteLength >= replacement.size) {
                "Граница метода не вмещает переключаемый патч."
            }
            val original = Il2CppNativeMutationDraftBuilder.parseHex(code.originalHex)
                .copyOf(replacement.size)
            require(!original.contentEquals(replacement)) {
                "Метод уже содержит выбранные байты; переключатель не нужен."
            }
            require(span.nextOffset == null || offset + replacement.size <= span.nextOffset) {
                "Патч пересекает начало следующего метода."
            }

            val range = offset..(offset + replacement.size - 1)
            val used = coveredRanges.getOrPut(libraryName) { mutableListOf() }
            require(used.none { range.first <= it.last && it.first <= range.last }) {
                "Выбранные моды пересекаются по адресам. Выберите один из них."
            }
            used.add(range)

            val module = libraryName.substringAfterLast(':').substringAfterLast('/')
            require(module.endsWith(".so") && '/' !in module && '\\' !in module) {
                "Неподдерживаемое имя нативного модуля."
            }
            val idDigest = MessageDigest.getInstance("SHA-256")
                .digest(recipe.id.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            RepackedRuntimeTestMenuItem(
                id = "mod:$idDigest",
                label = recipe.title.take(180),
                detail = (recipe.targetLabel + " · эффекты и безопасность проверяйте в приложении").take(320),
                mode = RepackedRuntimeTestMenuItemMode.PATCH,
                moduleName = module,
                binaryVirtualAddress = binding.functionVirtualAddress,
                originalHex = original.joinToString(" ") { "%02X".format(it.toInt() and 255) },
                replacementHex = replacement.joinToString(" ") { "%02X".format(it.toInt() and 255) },
            )
        }
        return RuntimeGameplayTestMenuSpec(items)
    }
}
