package io.github.ffenuss.modkit.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounded Flutter StandardMessageCodec reader for generated AssetManifest.bin.
 * Flutter 3.19+ replaced AssetManifest.json with this binary map. This decoder
 * does not disassemble libapp.so or imply that the listed assets are game mods.
 */
object FlutterStandardMessageCodec {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ITEMS = 100_000
    private const val MAX_DEPTH = 16

    fun decodeAssetManifest(bytes: ByteArray): List<String> {
        require(bytes.size in 2..MAX_BYTES) { "Flutter manifest exceeds the safe decode bound." }
        val decoder = Decoder(bytes)
        val root = decoder.value(0)
        require(decoder.remaining() == 0) { "Trailing data after Flutter AssetManifest.bin." }
        require(root is Map<*, *>) { "Flutter asset manifest is not a codec map." }
        require(root.size <= MAX_ITEMS) { "Asset manifest has too many keys." }
        val entries = ArrayList<String>(root.size)
        for ((key, value) in root) {
            require(key is String && key.isNotBlank() && key.length <= 4_096 &&
                value is List<*>
            ) { "Unexpected Flutter asset manifest entry." }
            require(value.all {
                it is String || it is Map<*, *>
            }) { "Unexpected Flutter asset variant." }
            entries += key
        }
        return entries
    }

    private class Decoder(bytes: ByteArray) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private var itemCount = 0

        fun remaining(): Int = input.remaining()

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "Flutter codec nesting exceeds safe bound." }
            require(++itemCount <= MAX_ITEMS) { "Flutter codec item count exceeds safe bound." }
            return when (val type = u8()) {
                0 -> null
                1 -> true
                2 -> false
                3 -> int32()
                4 -> int64()
                5 -> text(size()) // large-int hex string; not needed by asset keys
                6 -> { align(8); requireBytes(8); input.double }
                7 -> text(size())
                8 -> bytes(size())
                9 -> {
                    val count = collectionSize()
                    align(4); requireBytes(count.toLong() * 4L)
                    IntArray(count) { input.int }
                }
                10 -> {
                    val count = collectionSize()
                    align(8); requireBytes(count.toLong() * 8L)
                    LongArray(count) { input.long }
                }
                11 -> {
                    val count = collectionSize()
                    align(8); requireBytes(count.toLong() * 8L)
                    DoubleArray(count) { input.double }
                }
                12 -> {
                    val count = collectionSize()
                    List(count) { value(depth + 1) }
                }
                13 -> {
                    val count = collectionSize()
                    val map = LinkedHashMap<Any?, Any?>(count)
                    repeat(count) {
                        val key = value(depth + 1)
                        val entry = value(depth + 1)
                        require(!map.containsKey(key)) { "Duplicate Flutter codec map key." }
                        map[key] = entry
                    }
                    map
                }
                14 -> {
                    val count = collectionSize()
                    align(4); requireBytes(count.toLong() * 4L)
                    FloatArray(count) { input.float }
                }
                else -> error("Unsupported Flutter StandardMessageCodec type $type.")
            }
        }

        private fun u8(): Int {
            requireBytes(1)
            return input.get().toInt() and 0xff
        }

        private fun size(): Int {
            val marker = u8()
            val n = when (marker) {
                254 -> {
                    requireBytes(2)
                    input.short.toInt() and 0xffff
                }
                255 -> input.run {
                    requireBytes(4)
                    int.toLong() and 0xffffffffL
                }
                else -> marker.toLong()
            }
            require(n in 0..MAX_BYTES.toLong()) { "Flutter codec size out of bounds." }
            return n.toInt()
        }

        private fun collectionSize(): Int =
            size().also { require(it <= MAX_ITEMS) { "Flutter collection too large." } }

        private fun int32(): Int {
            requireBytes(4)
            return input.int
        }

        private fun int64(): Long {
            requireBytes(8)
            return input.long
        }

        private fun text(size: Int): String {
            val bytes = bytes(size)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }

        private fun bytes(size: Int): ByteArray {
            requireBytes(size.toLong())
            return ByteArray(size).also(input::get)
        }

        private fun align(size: Int) {
            val next = (input.position() + size - 1) and (size - 1).inv()
            require(next <= input.limit()) { "Truncated Flutter codec alignment." }
            input.position(next)
        }

        private fun requireBytes(n: Long) {
            require(n >= 0L && n <= input.remaining().toLong()) {
                "Truncated Flutter StandardMessageCodec payload."
            }
        }
    }
}
