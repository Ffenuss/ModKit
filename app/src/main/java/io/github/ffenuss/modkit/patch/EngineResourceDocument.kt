package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.Locale

enum class EngineResourceFormat(val label: String) { FLUTTER_JSON("Flutter"), UNREAL_INI("Unreal") }

internal data class ResourceScalar(val key: String, val start: Int, val end: Int, val value: String) {
    val boolean: Boolean get() = value.lowercase(Locale.ROOT) in setOf("true", "false")
    fun choices(): List<String> {
        if (boolean) return listOf(if (value.equals("true", true)) "false" else "true")
        val number = value.toBigDecimalOrNull() ?: return emptyList()
        // Without a schema we cannot promise a consuming field's width or purpose.
        // Offer conservative numeric values, keep integer/decimal representation.
        val decimal = value.any { it == '.' || it == 'e' || it == 'E' }
        val twice = number.multiply(2.toBigDecimal())
        return (listOf(twice) + listOf(0, 1, 2, 10, 99, 9999).map { it.toBigDecimal() })
            .filter { it.compareTo(number) != 0 && it.abs() <= Int.MAX_VALUE.toBigDecimal() }
            .map { it.stripTrailingZeros().toPlainString().let { n -> if (decimal && '.' !in n) "$n.0" else n } }
            .distinct()
    }
}

/** A lexical edit: no JSON reserialization, INI normalization or guessed engine code offsets. */
internal class EngineResourceDocument private constructor(
    val text: String,
    val scalars: List<ResourceScalar>,
    private val charset: Charset,
    private val bom: ByteArray,
) {
    fun replace(values: Map<String, String>): ByteArray {
        val selected = scalars.filter { it.key in values }
        require(selected.size == values.size) { "Resource field is missing or ambiguous." }
        val result = StringBuilder(text)
        selected.sortedByDescending { it.start }.forEach { scalar ->
            val value = values.getValue(scalar.key)
            require(value in scalar.choices()) { "Resource value is outside the prepared choices." }
            result.replace(scalar.start, scalar.end, value)
        }
        return bom + result.toString().toByteArray(charset)
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private val number = Regex("-?(?:0|[1-9][0-9]*)(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?")
        private fun scalar(value: String): Boolean = value.equals("true", true) || value.equals("false", true) ||
            (value.length <= 64 && number.matches(value) && value.toDoubleOrNull()?.isFinite() == true &&
                value.toBigDecimalOrNull()?.scale()?.let { it in -32..32 } == true)

        fun parse(bytes: ByteArray, format: EngineResourceFormat, signal: CancellationSignal): EngineResourceDocument {
            require(bytes.size in 1..MAX_BYTES) { "Resource must be at most 2 MiB." }
            val bomSize: Int
            val charset: Charset
            when {
                bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> {
                    bomSize = 3; charset = Charsets.UTF_8
                }
                format == EngineResourceFormat.UNREAL_INI && bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> {
                    bomSize = 2; charset = Charsets.UTF_16LE
                }
                format == EngineResourceFormat.UNREAL_INI && bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> {
                    bomSize = 2; charset = Charsets.UTF_16BE
                }
                else -> { bomSize = 0; charset = Charsets.UTF_8 }
            }
            val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, bomSize, bytes.size - bomSize)).toString()
            require('\u0000' !in text) { "Binary data is not an editable text resource." }
            val bom = bytes.copyOfRange(0, bomSize)
            require((bom + text.toByteArray(charset)).contentEquals(bytes))
            val scalars = when (format) {
                EngineResourceFormat.FLUTTER_JSON -> JsonReader(text, signal).parse()
                EngineResourceFormat.UNREAL_INI -> ini(text, signal)
            }
            return EngineResourceDocument(text, scalars, charset, bom)
        }

        private fun ini(text: String, signal: CancellationSignal): List<ResourceScalar> {
            val found = mutableListOf<ResourceScalar>()
            val occurrences = mutableMapOf<String, Int>()
            var section: String? = null
            var offset = 0
            for (line in text.splitToSequence('\n')) {
                checkCancelled(signal)
                val trimmed = line.trim()
                if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                    section = trimmed.substring(1, trimmed.length - 1).takeIf { it.isNotBlank() }
                } else if (section != null && trimmed.isNotBlank() && !trimmed.startsWith(';') && !trimmed.startsWith('#')) {
                    val equal = line.indexOf('=')
                    if (equal >= 0) {
                        val name = line.substring(0, equal).trim()
                        val identity = "${section.lowercase(Locale.ROOT)}\u001f${name.trimStart('+', '-', '!', '.').lowercase(Locale.ROOT)}"
                        occurrences[identity] = (occurrences[identity] ?: 0) + 1
                        val value = line.substring(equal + 1).trim()
                        if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) && scalar(value)) {
                            val start = offset + equal + 1 + line.substring(equal + 1).indexOfFirst { !it.isWhitespace() }
                            found += ResourceScalar(identity, start, start + value.length, value)
                        }
                    }
                }
                offset += line.length + 1
            }
            // Repeated sections, case variants and array operators can change Unreal's merge semantics.
            return found.filter { occurrences[it.key] == 1 }
        }

        private class JsonReader(val text: String, val signal: CancellationSignal) {
            var position = 0
            var nodes = 0
            val result = mutableListOf<ResourceScalar>()
            fun parse(): List<ResourceScalar> {
                value("", 0); whitespace()
                require(position == text.length) { "JSON has trailing content." }
                return result
            }
            fun whitespace() { while (position < text.length && text[position] in " \r\n\t") position++ }
            fun consume(c: Char): Boolean { whitespace(); return (position < text.length && text[position] == c).also { if (it) position++ } }
            fun value(path: String, depth: Int) {
                checkCancelled(signal)
                require(depth <= 32 && ++nodes <= 100_000 && path.length <= 4096) { "JSON structure exceeds supported limits." }
                whitespace(); require(position < text.length) { "Truncated JSON." }
                when (text[position]) {
                    '{' -> {
                        position++; val keys = hashSetOf<String>()
                        if (consume('}')) return
                        do {
                            whitespace(); val key = string()
                            require(keys.add(key)) { "Duplicate JSON key: $key" }
                            require(consume(':')) { "Missing JSON colon." }
                            value(path + "/" + key.replace("~", "~0").replace("/", "~1"), depth + 1)
                        } while (consume(','))
                        require(consume('}')) { "Missing JSON object terminator." }
                    }
                    '[' -> {
                        position++; var index = 0
                        if (consume(']')) return
                        do { value("$path/${index++}", depth + 1) } while (consume(','))
                        require(consume(']')) { "Missing JSON array terminator." }
                    }
                    '"' -> string()
                    else -> {
                        val start = position
                        while (position < text.length && text[position] !in ",]} \n\r\t") position++
                        val token = text.substring(start, position)
                        require(token in setOf("null", "true", "false") || number.matches(token)) { "Invalid JSON scalar." }
                        if (scalar(token)) result += ResourceScalar(path, start, position, token)
                    }
                }
            }
            fun string(): String {
                require(position < text.length && text[position++] == '"') { "JSON object key must be a string." }
                val out = StringBuilder()
                while (position < text.length) {
                    if (position % 4096 == 0) checkCancelled(signal)
                    val c = text[position++]
                    if (c == '"') return out.toString()
                    require(c >= ' ') { "Unescaped control in JSON string." }
                    if (c != '\\') { out.append(c); continue }
                    require(position < text.length)
                    out.append(when (val escaped = text[position++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            require(position + 4 <= text.length)
                            val code = text.substring(position, position + 4).toIntOrNull(16)
                            require(code != null) { "Invalid JSON unicode escape." }
                            position += 4; code.toChar()
                        }
                        else -> error("Invalid JSON escape.")
                    })
                }
                error("Unterminated JSON string.")
            }
        }
        private fun checkCancelled(signal: CancellationSignal) { if (signal.isCancelled()) throw AnalysisCancelledException() }
    }
}
