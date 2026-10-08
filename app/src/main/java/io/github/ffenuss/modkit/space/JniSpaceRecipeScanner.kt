package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.analysis.nativecode.AArch64ReadOnlyBody
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
        val module: String, val address: Long, val expected: String, val replacement: String, val imageSha256: String)
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
        if (method.shortName in exported) return method.shortName.takeIf {
            declarations.count { it.owner == method.owner && it.name == method.name } == 1
        }
        return method.longName.takeIf { it in exported }
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
        val candidates = declarations.filter { it.parameters.isEmpty() && DexLocalPatchEngine.nativeGameplayKind(it.owner, it.name, it.result) != null }
            .filter { m -> declarations.count { it.key == m.key } == 1 }
        if (candidates.isEmpty()) return Scan(emptyList(), warnings, truncated)
        if (truncated) return Scan(emptyList(), warnings + "JNI: неполный индекс деклараций; привязки не выдаются", true)
        data class Image(val apk: File, val entry: String, val module: String)
        val images = files.flatMap { apk -> ZipFile(apk).use { zip -> zip.entries().asSequence()
            .filter { !it.isDirectory && Regex("lib/arm64-v8a/[^/]+\\.so").matches(it.name) }
            .map { Image(apk, it.name, it.name.substringAfterLast('/')) }.toList() } }
        if (images.size > 64) return Scan(emptyList(), warnings + "JNI: слишком много библиотек; однозначность не доказана", true)
        if (images.map { it.module }.distinct().size != images.size) return Scan(emptyList(), warnings + "JNI: неоднозначные имена библиотек", false)
        val found = mutableListOf<Triple<Method, String, Recipe>>()
        val owners = mutableMapOf<String, Int>()
        var incomplete = false
        val knownSymbols = candidates.flatMap { listOf(it.shortName, it.longName) }.toSet()
        temporaryRoot.mkdirs()
        images.filter { img -> images.count { it.module == img.module } == 1 }.forEach { img ->
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
                    require(elf.is64Bit && elf.machine == 183) { "Invalid ARM64 image" }
                    val symbols = elf.dynamicSymbols.filter { it.defined && it.type == 2 && it.binding in 1..2 }
                    val exported = elf.dynamicSymbols.filter { it.defined && it.binding in 1..2 }.map { it.name }.toSet()
                    exported.filter { it in knownSymbols }.forEach { owners[it] = (owners[it] ?: 0) + 1 }
                    if (exported.none { it in knownSymbols }) return@use
                    // Inspect all APK libraries for ambiguity, but emit only identities accepted by the runtime contract.
                    if (img.module.length > 255 || !Regex("[A-Za-z0-9_-]+\\.so").matches(img.module)) {
                        warnings += "JNI: неподдерживаемое имя библиотеки: ${img.module}"; return@use
                    }
                    candidates.forEach candidate@ { method ->
                        val name = exportName(method, declarations, exported) ?: return@candidate
                        val symbol = symbols.singleOrNull { it.name == name } ?: return@candidate
                        if (symbols.count { it.value == symbol.value } != 1 || symbol.value <= 0 || symbol.value > Long.MAX_VALUE - 64 || symbol.value % 4L != 0L ||
                            symbol.size !in 8..1024 || symbol.size % 4L != 0L || !elf.isExecutableVa(symbol.value) ||
                            elf.fileOffsetForVa(symbol.value, symbol.size) == null) return@candidate
                        val code = elf.readFileWindowAtVa(symbol.value, symbol.size.toInt()) ?: return@candidate
                        val proof = AArch64ReadOnlyBody.inspect(code)
                        if (!proof.supported) return@candidate
                        val kind = requireNotNull(DexLocalPatchEngine.nativeGameplayKind(method.owner, method.name, method.result))
                        val value = when (kind.second) { DexLocalAction.TRUE -> "1"; DexLocalAction.FALSE -> "0";
                            DexLocalAction.INT_9999 -> "9999"; DexLocalAction.INT_99 -> "99"; DexLocalAction.FLOAT_2 -> "2" }
                        val returnKind = if (method.result == "F") Il2CppNativeReturnKind.FLOAT32 else Il2CppNativeReturnKind.INTEGER
                        val prefix = proof.entryLandingPad?.let { word -> (0..3).map { (word ushr (it * 8)).toByte() }.toByteArray() } ?: byteArrayOf()
                        val replacement = prefix + Il2CppNativeMutationDraftBuilder.parseHex(AArch64ScalarReturnEncoder.encodeHex(returnKind, value))
                        if (replacement.size > symbol.size || replacement.size > 64 || replacement.contentEquals(code.copyOf(replacement.size)) ||
                            symbols.any { it.value > symbol.value && it.value < symbol.value + replacement.size }) return@candidate
                        found += Triple(method, name, Recipe("jni:" + hex(MessageDigest.getInstance("SHA-256").digest(method.key.toByteArray())), kind.first.label.substringBefore(" /") + " · значение $value",
                            kind.first.label.substringBefore(" /"), method.key, img.module, symbol.value,
                            hex(code.copyOf(replacement.size)), hex(replacement), imageHash))
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
            .filter { (method, name, _) -> name == exportName(method, declarations, owners.keys) && owners[name] == 1 }.map { it.third }
        return Scan(recipes.take(128), warnings.distinct().take(32), recipes.size > 128)
    }
}
