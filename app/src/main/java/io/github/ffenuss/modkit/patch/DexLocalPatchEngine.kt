package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
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
            else ->
                "DEX прочитан, но подходящих локальных методов не найдено. " +
                    "Возможны обфускация, нестандартная логика или нативный движок."
        }
}

data class DexLocalRewrite(
    val file: File,
    val appliedIds: Set<String>,
    val sourceSha256: String,
    val resultSha256: String,
)

/**
 * Scan the base APK plus installed split APKs with explicit CPU/heap limits.
 * A limit produces a visible warning rather than silently claiming no mods.
 */
object DexLocalPatchEngine {
    private const val MAX_DEX_BYTES = 96L * 1024L * 1024L
    private const val MAX_METHODS = 350_000
    private const val MAX_DISPLAYED_CANDIDATES = 256
    private val DEX_NAME = Regex("classes(?:[0-9]+)?[.]dex")

    private val excludedClassMarkers = listOf(
        "billing", "receipt", "purchaseclient", "payment",
        "authentication", "anticheat", "integrity", "network",
        "remoteservice", "server", "account", "licensing",
        "google/android/gms", "android/", "kotlin/",
        "androidx/", "java/", "unity3d/", "com/google/",
    )

    fun scanApks(
        apkFiles: List<File>,
        developerTestMode: Boolean,
        cancellation: CancellationSignal,
    ): DexLocalScan {
        val discovered = ArrayList<DexLocalOpportunity>()
        val warnings = ArrayList<String>()
        var dexCount = 0
        var methodCount = 0
        var inspectedClasses = 0
        var excludedClasses = 0
        var methodsWithCode = 0
        var noArgumentMethods = 0
        var scalarMethods = 0
        var semanticMatches = 0
        var rejectedReturnTypes = 0
        var nativeLibraries = 0
        val diagnostics = ArrayList<String>()
        apkFiles.forEachIndexed { apkIndex, apk ->
            checkCancelled(cancellation)
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkCancelled(cancellation)
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("lib/") &&
                        entry.name.endsWith(".so") && !entry.isDirectory
                    ) nativeLibraries++
                    if (entry.isDirectory || !DEX_NAME.matches(entry.name)) continue
                    dexCount++
                    if (entry.size !in 1..MAX_DEX_BYTES) {
                        warnings += apk.name + ":" + entry.name +
                            ": DEX exceeds 96 MiB or size is unknown; skipped."
                        continue
                    }
                    try {
                        val bytes = zip.getInputStream(entry).use { input ->
                            readBounded(input, MAX_DEX_BYTES)
                        }
                        val scan = scanDex(
                            bytes = bytes,
                            apkIndex = apkIndex,
                            dexEntry = entry.name,
                            developerTestMode = developerTestMode,
                            cancellation = cancellation,
                        )
                        methodCount += scan.methodsExamined
                        inspectedClasses += scan.classesInspected
                        excludedClasses += scan.classesExcluded
                        methodsWithCode += scan.methodsWithCode
                        noArgumentMethods += scan.noArgumentMethods
                        scalarMethods += scan.scalarNoArgumentMethods
                        semanticMatches += scan.semanticNamesMatched
                        rejectedReturnTypes += scan.rejectedReturnTypes
                        diagnostics += scan.diagnostics.map {
                            apk.name + ":" + entry.name + ": " + it
                        }.take(20)
                        warnings += scan.warnings.map {
                            apk.name + ":" + entry.name + ": " + it
                        }
                        discovered += scan.opportunities
                    } catch (failure: Throwable) {
                        if (failure is AnalysisCancelledException) throw failure
                        warnings += apk.name + ":" + entry.name + ": " +
                            (failure.message ?: failure.javaClass.simpleName)
                    }
                    if (discovered.size >= MAX_DISPLAYED_CANDIDATES) {
                        warnings += "Showing the first 256 DEX candidates; narrow the target if needed."
                        break
                    }
                }
            }
        }
        return DexLocalScan(
            opportunities = discovered
                .distinctBy { it.id }
                .sortedWith(compareBy<DexLocalOpportunity> { it.category.rank }
                    .thenBy { it.displayName })
                .take(MAX_DISPLAYED_CANDIDATES),
            warnings = warnings.distinct(),
            dexFilesExamined = dexCount,
            methodsExamined = methodCount,
            classesInspected = inspectedClasses,
            classesExcluded = excludedClasses,
            methodsWithCode = methodsWithCode,
            noArgumentMethods = noArgumentMethods,
            scalarNoArgumentMethods = scalarMethods,
            semanticNamesMatched = semanticMatches,
            rejectedReturnTypes = rejectedReturnTypes,
            nativeLibrariesObserved = nativeLibraries,
            diagnostics = diagnostics.distinct().take(30),
        )
    }

    fun scanDex(
        bytes: ByteArray,
        apkIndex: Int,
        dexEntry: String,
        developerTestMode: Boolean,
        cancellation: CancellationSignal,
    ): DexLocalScan {
        require(bytes.size.toLong() <= MAX_DEX_BYTES) {
            "DEX exceeds the bounded scanner limit."
        }
        val dex = parseDex(bytes)
        val sha = sha256(bytes)
        val candidates = ArrayList<DexLocalOpportunity>()
        val warnings = ArrayList<String>()
        var methods = 0
        var classesInspected = 0
        var classesExcluded = 0
        var methodsWithCode = 0
        var noArgumentMethods = 0
        var scalarMethods = 0
        var semanticMatches = 0
        var rejectedReturnTypes = 0
        val diagnostics = ArrayList<String>()
        for (classDef in dex.classes) {
            checkCancelled(cancellation)
            classesInspected++
            if (excludedClass(classDef.type)) {
                classesExcluded++
                continue
            }
            for (method in classDef.methods) {
                methods++
                if (methods >= MAX_METHODS) {
                    warnings += "Method scan limit reached; results may be incomplete."
                    break
                }
                val impl = method.implementation
                if (impl != null) methodsWithCode++
                val noArgs = method.parameters.isEmpty()
                if (noArgs) noArgumentMethods++
                if (noArgs && method.returnType in setOf("Z", "I", "F")) {
                    scalarMethods++
                }
                val semantic = looksLikeGameplay(method.name)
                if (semantic) {
                    semanticMatches++
                    if (diagnostics.size < 20) {
                        diagnostics += method.definingClass + "->" +
                            method.name + "(" +
                            method.parameterTypes.joinToString("") +
                            ")" + method.returnType
                    }
                }
                if (semantic && (!noArgs || method.returnType !in
                        setOf("Z", "I", "F")
                    )
                ) rejectedReturnTypes++
                if (!noArgs || impl == null || impl.registerCount < 1) continue
                val match = classify(method.name, method.returnType) ?: continue
                val id = stableId(apkIndex, dexEntry, method)
                candidates += DexLocalOpportunity(
                    id = id,
                    apkIndex = apkIndex,
                    dexEntry = dexEntry,
                    className = method.definingClass,
                    methodName = method.name,
                    signature = "()" + method.returnType,
                    originalDexSha256 = sha,
                    category = match.first,
                    action = match.second,
                    selectable = match.first !in
                        setOf(DexLocalCategory.FULL_VERSION, DexLocalCategory.DEBUG_UI) ||
                        developerTestMode,
                    reason = if (match.first in
                        setOf(DexLocalCategory.FULL_VERSION, DexLocalCategory.DEBUG_UI) &&
                        !developerTestMode) {
                        "Доступно только в тестовом режиме собственной игры или приложения."
                    } else {
                        "Exact DEX method and return type verified; gameplay effect requires an on-device test."
                    },
                )
                if (candidates.size >= MAX_DISPLAYED_CANDIDATES) {
                    warnings += "DEX candidate display cap reached (256)."
                    break
                }
            }
            if (methods >= MAX_METHODS || candidates.size >= MAX_DISPLAYED_CANDIDATES) break
        }
        return DexLocalScan(
            opportunities = candidates,
            warnings = warnings,
            dexFilesExamined = 1,
            methodsExamined = methods,
            classesInspected = classesInspected,
            classesExcluded = classesExcluded,
            methodsWithCode = methodsWithCode,
            noArgumentMethods = noArgumentMethods,
            scalarNoArgumentMethods = scalarMethods,
            semanticNamesMatched = semanticMatches,
            rejectedReturnTypes = rejectedReturnTypes,
            diagnostics = diagnostics,
        )
    }

    /**
     * Re-resolve each method in the current source DEX before rewriting.
     * A complete new DEX is emitted by dexlib2, not patched at guessed offsets.
     */
    fun rewriteDex(
        bytes: ByteArray,
        apkIndex: Int,
        dexEntry: String,
        selected: List<DexLocalOpportunity>,
        destination: File,
        developerTestMode: Boolean,
        cancellation: CancellationSignal,
    ): DexLocalRewrite {
        require(selected.isNotEmpty()) { "No DEX changes selected." }
        val originalSha = sha256(bytes)
        val current = scanDex(
            bytes, apkIndex, dexEntry, developerTestMode, cancellation,
        ).opportunities.associateBy { it.id }
        val selectedIds = selected.map { it.id }
        require(selectedIds.toSet().size == selectedIds.size) {
            "Duplicate DEX method selections."
        }
        selected.forEach { request ->
            val candidate = current[request.id]
                ?: error("DEX method disappeared or is no longer eligible: " +
                    request.displayName)
            require(candidate.selectable && request.selectable) {
                "Developer test mode is not enabled for this target."
            }
            require(candidate.originalDexSha256 == originalSha &&
                request.originalDexSha256 == originalSha &&
                candidate.action == request.action &&
                candidate.className == request.className &&
                candidate.methodName == request.methodName &&
                candidate.signature == request.signature
            ) {
                "DEX method provenance changed since selection."
            }
        }

        val dex = parseDex(bytes)
        val wanted = selected.associateBy { it.id }
        val writtenIds = LinkedHashSet<String>()
        val classes = dex.classes.map { clazz ->
            checkCancelled(cancellation)
            val direct = clazz.directMethods.map { method ->
                rewriteMethod(method, apkIndex, dexEntry, wanted, writtenIds)
            }
            val virtual = clazz.virtualMethods.map { method ->
                rewriteMethod(method, apkIndex, dexEntry, wanted, writtenIds)
            }
            if ((direct + virtual).none { method ->
                stableId(apkIndex, dexEntry, method) in wanted
            }) {
                clazz
            } else {
                ImmutableClassDef(
                    clazz.type, clazz.accessFlags, clazz.superclass,
                    clazz.interfaces, clazz.sourceFile, clazz.annotations,
                    clazz.staticFields, clazz.instanceFields, direct, virtual,
                )
            }
        }
        require(writtenIds == wanted.keys) {
            "Not every selected DEX method was rewritten."
        }
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        temp.delete()
        try {
            val rewritten = object : DexFile {
                override fun getOpcodes(): Opcodes = dex.opcodes
                override fun getClasses(): Set<out ClassDef> = classes.toSet()
            }
            DexPool.writeTo(temp.absolutePath, rewritten)
            val verified = temp.inputStream().buffered().use {
                DexBackedDexFile.fromInputStream(null, it)
            }
            val verifiedIds = HashSet<String>()
            for (clazz in verified.classes) {
                for (method in clazz.methods) {
                    val id = stableId(apkIndex, dexEntry, method)
                    val expectation = wanted[id] ?: continue
                    val instructions =
                        method.implementation?.instructions?.toList().orEmpty()
                    val expectedOpcode = when (expectation.action) {
                        DexLocalAction.TRUE, DexLocalAction.FALSE -> Opcode.CONST_4
                        DexLocalAction.INT_9999, DexLocalAction.FLOAT_2 -> Opcode.CONST
                    }
                    val expectedValue = when (expectation.action) {
                        DexLocalAction.TRUE -> 1
                        DexLocalAction.FALSE -> 0
                        DexLocalAction.INT_9999 -> 9999
                        DexLocalAction.FLOAT_2 -> 2.0f.toBits()
                    }
                    val actualValue =
                        (instructions.firstOrNull() as? NarrowLiteralInstruction)
                            ?.narrowLiteral
                    require(instructions.size == 2 &&
                        instructions[0].opcode == expectedOpcode &&
                        actualValue == expectedValue &&
                        instructions[1].opcode == Opcode.RETURN
                    ) {
                        "Rewritten DEX failed exact return-value verification: " + id
                    }
                    require(verifiedIds.add(id)) {
                        "Rewritten DEX contains a duplicate selected method: " + id
                    }
                }
            }
            require(verifiedIds == wanted.keys) {
                "Rewritten DEX is missing one or more selected methods."
            }
            require(temp.renameTo(destination)) {
                "Failed to finalize the rewritten DEX."
            }
            return DexLocalRewrite(
                file = destination,
                appliedIds = writtenIds,
                sourceSha256 = originalSha,
                resultSha256 = sha256(destination.readBytes()),
            )
        } catch (failure: Throwable) {
            temp.delete()
            destination.delete()
            throw failure
        }
    }

    private fun rewriteMethod(
        original: Method,
        apkIndex: Int,
        dexEntry: String,
        wanted: Map<String, DexLocalOpportunity>,
        written: MutableSet<String>,
    ): Method {
        val id = stableId(apkIndex, dexEntry, original)
        val request = wanted[id] ?: return original
        val implementation = requireNotNull(original.implementation)
        require(original.returnType == when (request.action) {
            DexLocalAction.TRUE, DexLocalAction.FALSE -> "Z"
            DexLocalAction.INT_9999 -> "I"
            DexLocalAction.FLOAT_2 -> "F"
        })
        require(implementation.registerCount >= 1)
        val instruction = when (request.action) {
            DexLocalAction.TRUE ->
                ImmutableInstruction11n(Opcode.CONST_4, 0, 1)
            DexLocalAction.FALSE ->
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0)
            DexLocalAction.INT_9999 ->
                ImmutableInstruction31i(Opcode.CONST, 0, 9999)
            DexLocalAction.FLOAT_2 ->
                ImmutableInstruction31i(Opcode.CONST, 0, 2.0f.toBits())
        }
        val instructions: List<Instruction> = listOf(
            instruction,
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )
        check(written.add(id)) { "Method identity is not unique: " + id }
        return ImmutableMethod(
            original.definingClass, original.name, original.parameters,
            original.returnType, original.accessFlags, original.annotations,
            original.hiddenApiRestrictions,
            ImmutableMethodImplementation(
                implementation.registerCount,
                instructions,
                emptyList(),
                emptyList(),
            ),
        )
    }

    private fun classify(
        methodName: String,
        returnType: String,
    ): Pair<DexLocalCategory, DexLocalAction>? {
        val key = methodName.lowercase().filter(Char::isLetterOrDigit)
        if (key.contains("billing") || key.contains("receipt") ||
            key.contains("license") || key.contains("server") ||
            key.contains("verify") || key.contains("authenticate") ||
            key.contains("payment") || key.contains("anticheat")
        ) return null

        if (returnType == "Z") {
            val category = when (key) {
                "isfullversion", "getisfullversion", "hasfullversion",
                "gethasfullversion", "ispremium", "getispremium",
                "haspremium", "gethaspremium", "premiumunlocked",
                "ispremiumunlocked", "isprounlocked",
                "getfullversion", "getpremium", "getproversion" ->
                    DexLocalCategory.FULL_VERSION
                "isinvincible", "getisinvincible", "isimmortal",
                "getisimmortal", "isgodmode", "hasinfinitehealth" ->
                    DexLocalCategory.HEALTH
                "hasinfinitestamina", "isinfiniteenergy", "hasinfiniteenergy" ->
                    DexLocalCategory.STAMINA
                "hasinfiniteammo", "isinfiniteammo" ->
                    DexLocalCategory.AMMO
                "canrun", "cansprint", "canmove", "canjump" ->
                    DexLocalCategory.MOVEMENT
                "isoncooldown", "getisoncooldown", "isstunned",
                "getisstunned", "ismovementblocked" ->
                    DexLocalCategory.COOLDOWN
                else -> return null
            }
            val action = if (category == DexLocalCategory.COOLDOWN) {
                DexLocalAction.FALSE
            } else DexLocalAction.TRUE
            return category to action
        }

        if (returnType == "I") {
            val category = when (key) {
                "gethealth", "getmaxhealth", "getcurrenthealth" ->
                    DexLocalCategory.HEALTH
                "getammo", "getmaxammo" ->
                    DexLocalCategory.AMMO
                "getstamina", "getmaxstamina" ->
                    DexLocalCategory.STAMINA
                else -> return null
            }
            return category to DexLocalAction.INT_9999
        }
        if (returnType == "F") {
            val category = when (key) {
                "getmovespeed", "getrunspeed", "getwalkspeed" ->
                    DexLocalCategory.MOVEMENT
                else -> return null
            }
            return category to DexLocalAction.FLOAT_2
        }
        return null
    }

    private fun excludedClass(name: String): Boolean {
        val path = name.lowercase()
        return excludedClassMarkers.any { it in path } ||
            (path.startsWith("ljava/") || path.startsWith("lorg/junit/"))
    }

    private fun stableId(
        apkIndex: Int,
        dexEntry: String,
        method: Method,
    ): String = apkIndex.toString() + ":" + dexEntry + ":" +
        method.definingClass + "->" + method.name +
        "(" + method.parameterTypes.joinToString("") + ")" + method.returnType

    private fun parseDex(bytes: ByteArray): DexBackedDexFile {
        require(bytes.size >= 112 && bytes[0] == 0x64.toByte() &&
            bytes[1] == 0x65.toByte() && bytes[2] == 0x78.toByte()
        ) { "Unsupported or corrupt DEX magic." }
        return DexBackedDexFile(null, bytes)
    }

    private fun readBounded(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(128 * 1024)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            require(bytes <= limit) { "DEX expansion limit exceeded." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }
}
