package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31i
import org.jf.dexlib2.writer.pool.DexPool

/**
 * DEX method discovery and exact rewriting for local game/test-build state.
 * No heuristic byte replacement: dexlib2 resolves method identity, validates
 * the return type, rebuilds DEX, and the patched method is parsed again.
 * Purchase receipts, billing clients, authentication and server state are
 * never considered a locally writable gameplay flag.
 */
enum class DexLocalCategory(val label: String, val rank: Int) {
    FULL_VERSION("Full / Premium (тест собственной игры)", 0),
    HEALTH("Здоровье / бессмертие", 1),
    STAMINA("Энергия / выносливость", 2),
    AMMO("Боезапас", 3),
    MOVEMENT("Скорость / движение", 4),
    COOLDOWN("Ограничения / таймеры", 5),
    EXPERIENCE("Опыт / уровень", 6),
    INVENTORY("Размер инвентаря", 7),
    DEBUG_UI("Отладочный интерфейс своей программы", 8),
}

enum class DexLocalAction(val label: String) {
    TRUE("Возвращать true"),
    FALSE("Возвращать false"),
    INT_9999("Возвращать 9999"),
    INT_99("Возвращать 99"),
    FLOAT_2("Возвращать 2.0f"),
}

data class DexLocalOpportunity(
    val id: String,
    val apkIndex: Int,
    val dexEntry: String,
    val className: String,
    val methodName: String,
    val signature: String,
    val originalDexSha256: String,
    val category: DexLocalCategory,
    val action: DexLocalAction,
    val selectable: Boolean,
    val reason: String,
    val bodyKind: DexMethodBodyKind = DexMethodBodyKind.UNSUPPORTED,
    val fieldIdentity: String? = null,
    val matchedByField: Boolean = false,
    val runtimeBlocker: String? = null,
) {
    val displayName: String
        get() = className.removePrefix("L").removeSuffix(";")
            .replace('/', '.') + "." + methodName + signature
}

data class DexLocalScan(
    val opportunities: List<DexLocalOpportunity>,
    val warnings: List<String>,
    val dexFilesExamined: Int,
    val methodsExamined: Int,
    val classesInspected: Int = 0,
    val classesExcluded: Int = 0,
    val methodsWithCode: Int = 0,
    val noArgumentMethods: Int = 0,
    val scalarNoArgumentMethods: Int = 0,
    val semanticNamesMatched: Int = 0,
    val rejectedReturnTypes: Int = 0,
    val excludedAmbiguousProgressionNames: Int = 0,
    val nativeLibrariesObserved: Int = 0,
    val diagnostics: List<String> = emptyList(),
) {
    val explanation: String
        get() = when {
            opportunities.isNotEmpty() ->
                "Найдены локальные методы с проверенной сигнатурой. " +
                    "Эффект модификации нужно проверить в запущенной игре."
            dexFilesExamined == 0 ->
                "DEX-код отсутствует. Логика может быть в нативных библиотеках " +
                    "или приложение содержит только ресурсы."
            methodsExamined == 0 ->
                "DEX присутствует, но пригодных классов приложения не обнаружено. " +
                    "Возможно, это только загрузчик Unity или системные библиотеки."
            semanticNamesMatched > 0 && rejectedReturnTypes > 0 ->
                "Найдены имена, похожие на игровые функции, но часть имеет " +
                    "неподдерживаемые параметры или возвращаемый тип."
            excludedAmbiguousProgressionNames > 0 ->
                "Пропущено " + excludedAmbiguousProgressionNames +
                    " методов с неоднозначными именами Level/Experience: " +
                    "они не имеют доказанного игрового контекста."
            else ->
                "DEX прочитан, но подходящих ло…9985 tokens truncated…cement = prefix + Il2CppNativeMutationDraftBuilder.parseHex(AArch64ScalarReturnEncoder.encodeHex(returnKind, value))
                        if (replacement.size > symbol.size || replacement.size > 64 || replacement.contentEquals(code.copyOf(replacement.size)) ||
                            symbols.any { it.value > symbol.value && it.value < symbol.value + replacement.size }) return@candidate
                        found += method to Recipe("jni:" + hex(MessageDigest.getInstance("SHA-256").digest(method.key.toByteArray())), kind.first.label.substringBefore(" /") + " · значение $value",
                            kind.first.label.substringBefore(" /"), method.key, img.module, symbol.value,
                            hex(code.copyOf(replacement.size)), hex(replacement), imageHash)
                    }
                }
            } catch (error: Exception) {
                if (error is AnalysisCancelledException) throw error
                incomplete = true
                warnings += "JNI: ${img.module}: ${error.javaClass.simpleName}"
            } finally { extracted.delete() }
        }
        // The VM may search several loaded libraries. Never select an arbitrary matching export.
        if (incomplete) return Scan(emptyList(), warnings + "JNI: индекс библиотек не подтверждён", true)
        val recipes = found.groupBy { it.first.key }.values.filter { it.size == 1 }.map { it.single() }
            .filter { (method, _) -> owners[exportName(method, declarations, owners.keys)] == 1 }.map { it.second }
        return Scan(recipes.take(128), warnings.distinct().take(32), recipes.size > 128)
    }
}
