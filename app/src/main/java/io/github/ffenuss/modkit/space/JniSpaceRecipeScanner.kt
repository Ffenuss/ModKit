package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.patch.*
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Typed, exported JNI getters: engine-neutral, with no guessed C++ return type or metadata address. */
internal object JniSpaceRecipeScanner {
    data class Method(val owner: String, val name: String, val result: String, val parameters: List<String>) {
        val key get() = "$owner->$name(${parameters.joinToString("")})$result"
        val shortName get() = "Java_${mangle(owner.removePrefix("L").removeSuffix(";"))}_${mangle(name)}"
        val longName get() = shortName + "__" + mangle(parameters.joinToString(""))
    }
    data class Recipe(val id: String, val title: String, val category: String, val evidence: String,
        val module: String, val address: Long, val expected: String, val replacement: String, val imageSha256: String, val abi: String = "arm64-v8a")
    data class Scan(val recipes: List<Recipe>, val warnings: List<String>, val truncated: Boolean)

    // JNI escaping applies to UTF-16 code units, including each surrogate separately.
    fun mangle(text: String): String = buildString {
        for (c in text) append(when {
            c == '/' -> "_"; c == '_' -> "_1"; c == ';' -> "_2"; c == '[' -> "_3"
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> c.toString()
            else -> "_0" + c.code.toString(16).padStart(4, '0')
        })
    }
    fun exportName(method: Method, declarations: List<Method>, exported: Set<String>): String? {
        return exportName(method, declarations.groupingBy { it.owner to it.name }.eachCount(),
            declarations.groupingBy { it.longName }.eachCount(), exported)
    }
    private fun exportName(method: Method, overloadCounts: Map<Pair<String, String>, Int>,
        longNameCounts: Map<String, Int>, exported: Set<String>): String? {
        if (method.shortName in exported) return method.shortName.takeIf {
            overloadCounts[method.owner to method.name] == 1
        }
        // JNI's long name does not encode the return type. DEX declarations that
        // share owner/name/arguments cannot prove which return ABI the export uses.
        return method.longName.takeIf { it in exported && longNameCounts[it] == 1 }
    }
    internal fun returnKind(result: String): Il2CppNativeReturnKind? = when (result) {
        "Z", "B", "C", "S", "I", "J" -> Il2CppNativeReturnKind.INTEGER
        "F" -> Il2CppNativeReturnKind.FLOAT32
        "D" -> Il2CppNativeReturnKind.FLOAT64
        else -> null
    }
    private val primitiveNames = mapOf("Z" to "boolean", "B" to "byte", "C" to "char", "S" to "short",
        "I" to "int", "J" to "long", "F" to "float", "D" to "double")
    internal fun supportedParameters(parameters: List<String>): Boolean =
        parameters.size <= 8 && parameters.all { it in primitiveNames }
    internal fun replacementValue(result: String, action: DexLocalAction): String {
        val permitted = when (result) {
            "Z" -> setOf(DexLocalAction.TRUE, DexLocalAction.FALSE)
            "B", "C", "S", "I", "J" -> setOf(DexLocalAction.INT_9999, DexLocalAction.INT_99)
            "F", "D" -> setOf(DexLocalAction.FLOAT_2)
            else -> emptySet()
        }
        require(action in permitted) { "JNI action does not match the declared result type" }
        val value = when (action) {
            DexLocalAction.TRUE -> 1L; DexLocalAction.FALSE -> 0L
            DexLocalAction.INT_9999 -> 9999L; DexLocalAction.INT_99 -> 99L; DexLocalAction.FLOAT_2 -> 2L
        }
        val maximum = when (result) { "B" -> 127L; "S" -> 32767L; "C" -> 65535L; else -> Long.MAX_VALUE }
        return minOf(value, maximum).toString()
    }
    private fun check(signal: CancellationSignal) { if (signal.isCancelled()) throw AnalysisCancelledException() }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun scan(files: List<File>, temporaryRoot: File, signal: CancellationSignal): Scan {
        val warnings = mutableListOf<String>(); var truncated = false
        val declarations = mutableListOf<Method>()
        files.forEach { apk -> ZipFile(apk).use { zip ->
            require(zip.entries().asSequence().map { it.name }.toList().let { it.size == it.toSet().size }) { "Ambiguous APK entries" }
            zip.entries().asSequence().filter { Regex("classes(?:[0-9]+)?\\.dex").matches(it.name) }.forEach dexEntry@ { entry ->
                check(signal)
                if (entry.size !in 1..(96L * 1024 * 1024)) { truncated = true; warnings += "JNI: DEX превышает лимит: ${entry.name}"; return@dexEntry }
                val bytes = zip.getInputStream(entry).use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(32768); val crc = CRC32()
                    while (true) { check(signal); val n = input.read(buffer); if (n < 0) break
                        require(output.size().toLong() + n <= entry.size) { "DEX expansion exceeds declared size" }
                        output.write(buffer, 0, n); crc.update(buffer, 0, n)
                    }
                    require(output.size().toLong() == entry.size && crc.value == entry.crc) { "DEX size/CRC mismatch" }
                    output.toByteArray()
                }
                val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
                dex.classes.forEach { cls -> check(signal); cls.methods.forEach { method ->
                    if (AccessFlags.NATIVE.isSet(method.accessFlags)) {
                        if (declarations.size >= 16384) { truncated = true }
                        else declarations += Method(cls.type, method.name, method.returnType, method.parameterTypes.map { it.toString() })
                    }
                } }
            }
        } }
        val declarationCounts = declarations.groupingBy { it.key }.eachCount()
        val overloadCounts = declarations.groupingBy { it.owner to it.name }.eachCount()
        val longNameCounts = declarations.groupingBy { it.longName }.eachCount()
        val candidates = declarations.filter { supportedParameters(it.parameters) && returnKind(it.result) != null &&
            DexLocalPatchEngine.nativeGameplayKind(it.owner, it.name, it.result) != null && declarationCounts[it.key] == 1 }
        if (candidates.isEmpty()) return Scan(emptyList(), warnings, truncated)
        if (truncated) return Scan(emptyList(), warnings + "JNI: неполный индекс деклараций; привязки не выдаются", true)
        data class Image(val apk: File, val entry: String, val module: String, val abi: String)
        val images = files.flatMap { apk -> ZipFile(apk).use { zip -> zip.entries().asSequence()
            .filter { !it.isDirectory && Regex("lib/(?:arm64-v8a|armeabi-v7a|x86_64)/[^/]+\\.so").matches(it.name) }
            .map { Image(apk, it.name, it.name.substringAfterLast('/'), it.name.split('/')[1]) }.toList() } }
        if (images.size > 64) return Scan(emptyList(), warnings + "JNI: слишком много библиотек; однозначность не доказана", true)
        if (images.map { it.abi to it.module }.distinct().size != images.size) return Scan(emptyList(), warnings + "JNI: неоднозначные имена библиотек", false)
        val found = mutableListOf<Triple<Method, String, Recipe>>()
        val owners = mutableMapOf<String, Int>()
        var incomplete = false
        val knownSymbols = candidates.flatMap { listOf(it.shortName, it.longName) }.toSet()
        temporaryRoot.mkdirs()
        images.filter { img -> images.count { it.abi == img.abi && it.module == img.module } == 1 }.forEach { img ->
            check(signal)
            val extracted = File.createTempFile("jni-space-", ".so", temporaryRoot)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                ZipFile(img.apk).use { zip ->
                    val entry = requireNotNull(zip.getEntry(img.entry))
                    require(entry.size in 1..(512L * 1024 * 1024))
                    val crc = CRC32(); var total = 0L
                    zip.getInputStream(entry).use { input -> extracted.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) { check(signal); val n = input.read(buffer); if (n < 0) break
                            total += n; require(total <= entry.size); digest.update(buffer, 0, n); crc.update(buffer, 0, n); output.write(buffer, 0, n) }
                    } }
                    require(total == entry.size && crc.value == entry.crc)
                }
                val imageHash = hex(digest.digest())
                ElfImage.open(extracted, signal).use { elf ->
                    require(elf.is64Bit == JniAbiRecipe.is64Bit(img.abi) && elf.machine == JniAbiRecipe.machine(img.abi)) { "ELF/ABI mismatch" }
                    if (img.abi == "armeabi-v7a") {
                        val flags = java.io.RandomAccessFile(extracted, "r").use { input ->
                            input.seek(36); java.lang.Integer.reverseBytes(input.readInt())
                        }
                        // Only Android EABI5 base/softfp calling convention. Hard-float changes result registers.
                        require(flags ushr 24 == 5 && flags and 0x600 == 0x200) { "Unsupported ARM calling convention" }
                    }
                    val symbols = elf.dynamicSymbols.filter { it.defined && it.type == 2 && it.binding in 1..2 }
                    val exported = elf.dynamicSymbols.filter { it.defined && it.binding in 1..2 }.map { it.name }.toSet()
                    exported.filter { it in knownSymbols }.forEach { owners[img.abi + ":" + it] = (owners[img.abi + ":" + it] ?: 0) + 1 }
                    if (exported.none { it in knownSymbols }) return@use
                    // Inspect all APK libraries for ambiguity, but emit only identities accepted by the runtime contract.
                    if (img.module.length > 255 || !Regex("[A-Za-z0-9_-]+\\.so").matches(img.module)) {
                        warnings += "JNI: неподдерживаемое имя библиотеки: ${img.module}"; return@use
                    }
                    candidates.forEach candidate@ { method ->
                        val name = exportName(method, overloadCounts, longNameCounts, exported) ?: return@candidate
                        val symbol = symbols.singleOrNull { it.name == name } ?: return@candidate
                        if (img.abi == "armeabi-v7a" && symbol.value % 4L != 0L) {
                            if (warnings.size < 32) warnings += "JNI armeabi-v7a ${method.name}: Thumb/interworking пока не поддерживается"
                            return@candidate
                        }
                        if (symbols.count { it.value == symbol.value } != 1 || symbol.value <= 0 || symbol.value > Long.MAX_VALUE - 64 || symbol.value % 4L != 0L ||
                            symbol.size !in 1..1024 || (img.abi != "x86_64" && symbol.size % 4L != 0L) || !elf.isExecutableVa(symbol.value) ||
                            elf.fileOffsetForVa(symbol.value, symbol.size) == null) return@candidate
                        val code = elf.readFileWindowAtVa(symbol.value, symbol.size.toInt()) ?: return@candidate
                        val proof = JniAbiRecipe.inspect(img.abi, code)
                        if (!proof.supported) {
                            if (warnings.size < 32) warnings += "JNI ${img.abi} ${method.name}: ${proof.reason}"
                            return@candidate
                        }
                        val kind = requireNotNull(DexLocalPatchEngine.nativeGameplayKind(method.owner, method.name, method.result))
                        val value = replacementValue(method.result, kind.second)
                        val replacement = JniAbiRecipe.encode(img.abi, method.result, value, proof.prefix) ?: return@candidate
                        if (replacement.size > symbol.size || replacement.size > 64 || replacement.contentEquals(code.copyOf(replacement.size)) ||
                            symbols.any { it.value > symbol.value && it.value < symbol.value + replacement.size }) return@candidate
                        val argumentLabel = if (method.parameters.isEmpty() && method.result !in setOf("B", "S", "C")) "" else
                            " · ${method.name}(${method.parameters.joinToString(", ") { requireNotNull(primitiveNames[it]) }})"
                        found += Triple(method, name, Recipe("jni:" + hex(MessageDigest.getInstance("SHA-256").digest((if (img.abi == "arm64-v8a") method.key else img.abi + ":" + method.key).toByteArray())), kind.first.label.substringBefore(" /") + " · значение $value" + argumentLabel,
                            kind.first.label.substringBefore(" /"), method.key, img.module, symbol.value,
                            hex(code.copyOf(replacement.size)), hex(replacement), imageHash, img.abi))
                    }
                }
            } catch (error: Exception) {
                if (error is AnalysisCancelledException) throw error
                incomplete = true
                warnings += "JNI ${img.abi}: ${img.module}: ${error.message ?: error.javaClass.simpleName}"
            } finally { extracted.delete() }
        }
        // The VM may search several loaded libraries. Never select an arbitrary matching export.
        if (incomplete) return Scan(emptyList(), warnings + "JNI: индекс библиотек не подтверждён", true)
        val recipes = found.groupBy { it.third.abi to it.first.key }.values.filter { it.size == 1 }.map { it.single() }
            .filter { (method, name, recipe) -> name == exportName(method, overloadCounts, longNameCounts,
                owners.keys.filter { it.startsWith(recipe.abi + ":") }.map { it.substringAfter(':') }.toSet()) && owners[recipe.abi + ":" + name] == 1 }.map { it.third }
        val nonOverlapping = recipes.filter { candidate -> recipes.none { other ->
            other !== candidate && other.abi == candidate.abi && other.module == candidate.module &&
                candidate.address < other.address + other.expected.length / 2 && other.address < candidate.address + candidate.expected.length / 2
        } }
        return Scan(nonOverlapping.take(128), warnings.distinct().take(32), nonOverlapping.size > 128)
    }
}
