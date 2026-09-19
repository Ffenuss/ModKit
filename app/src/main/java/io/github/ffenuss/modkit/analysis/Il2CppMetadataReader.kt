package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.RandomAccessFile

data class Il2CppTableRange(val name: String, val offset: Long, val sizeBytes: Long) : java.io.Serializable

data class Il2CppTypeDefinition(
    val index: Int,
    val namespace: String,
    val name: String,
    val fullName: String,
    val methodStart: Int,
    val methodCount: Int,
    val fieldStart: Int,
    val fieldCount: Int,
    val token: Long,
) : java.io.Serializable

data class Il2CppMethodDefinition(
    val index: Int,
    val declaringTypeIndex: Int,
    val declaringType: String,
    val name: String,
    val parameterCount: Int,
    val token: Long,
    val flags: Int,
) : java.io.Serializable

data class Il2CppFieldDefinition(
    val index: Int,
    val declaringTypeIndex: Int,
    val declaringType: String,
    val name: String,
    val typeIndex: Int,
    val token: Long,
) : java.io.Serializable

data class Il2CppImageDefinition(
    val index: Int,
    val name: String,
    val assemblyIndex: Int,
    val typeStart: Int,
    val typeCount: Int,
    val token: Long,
) : java.io.Serializable

data class Il2CppMetadataModel(
    val sizeBytes: Long,
    val magicValid: Boolean,
    val metadataVersion: Int?,
    val layoutProfile: String?,
    val tableRanges: List<Il2CppTableRange>,
    val declaredTypeCount: Int?,
    val declaredMethodCount: Int?,
    val declaredFieldCount: Int?,
    val declaredImageCount: Int?,
    val images: List<Il2CppImageDefinition>,
    val types: List<Il2CppTypeDefinition>,
    val methods: List<Il2CppMethodDefinition>,
    val fields: List<Il2CppFieldDefinition>,
    val structuredSupported: Boolean,
    val truncated: Boolean,
    val warnings: List<String>,
) : java.io.Serializable

object Il2CppMetadataReader {
    private const val MAGIC = 0xFAB11BAFL
    private const val HEARTBEAT_MS = 1_500L

    data class Limits(
        val maxFileBytes: Long = 1L * 1024L * 1024L * 1024L,
        val maxTypes: Int = 30_000,
        val maxMethods: Int = 100_000,
        val maxFields: Int = 100_000,
        val maxImages: Int = 4_096,
        val maxStringBytes: Int = 16 * 1024,
    )

    fun read(
        file: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        limits: Limits = Limits(),
    ): Il2CppMetadataModel {
        require(file.isFile && file.canRead()) { "global-metadata.dat is not readable" }
        require(file.length() in 8..limits.maxFileBytes) {
            "global-metadata.dat size is outside bounded random-access limits"
        }

        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val magic = u32(raf, 0)
            val rawVersion = i32(raf, 4)
            val version = rawVersion.takeIf { it in 1..1000 }

            if (magic != MAGIC) {
                return emptyModel(
                    size = size,
                    version = version,
                    magicValid = false,
                    warning = "Unexpected IL2CPP metadata magic 0x" + magic.toString(16),
                )
            }

            if (version == null || version !in 27..31) {
                return emptyModel(
                    size = size,
                    version = version,
                    magicValid = true,
                    warning = "Metadata version is validated but structured reconstruction is not enabled for this layout.",
                    tableRanges = scanPairs(raf, size),
                )
            }

            val names = listOf(
                0 to "stringLiterals", 1 to "stringLiteralData", 2 to "strings", 3 to "events",
                4 to "properties", 5 to "methods", 6 to "parameterDefaultValues", 7 to "fieldDefaultValues",
                8 to "fieldAndParameterDefaultValueData", 9 to "fieldMarshaledSizes", 10 to "parameters",
                11 to "fields", 12 to "genericParameters", 13 to "genericParameterConstraints",
                14 to "genericContainers", 15 to "nestedTypes", 16 to "interfaces", 17 to "vtableMethods",
                18 to "interfaceOffsets", 19 to "typeDefinitions",
                20 to "images", 21 to "assemblies",
            )
            val ranges = names.mapNotNull { pair(raf, size, it.first, it.second) }
            val strings = ranges.firstOrNull { it.name == "strings" }
            val methodsRange = ranges.firstOrNull { it.name == "methods" }
            val fieldsRange = ranges.firstOrNull { it.name == "fields" }
            val typesRange = ranges.firstOrNull { it.name == "typeDefinitions" }
            val imagesRange = ranges.firstOrNull { it.name == "images" }

            if (strings == null || methodsRange == null || fieldsRange == null ||
                typesRange == null || strings.sizeBytes <= 0L
            ) {
                return emptyModel(
                    size = size,
                    version = version,
                    magicValid = true,
                    warning = "Required IL2CPP metadata tables are absent or invalid.",
                    tableRanges = ranges,
                    layout = layout(version),
                    structuredSupported = true,
                    truncated = true,
                )
            }

            val typeRecordSize = 88
            val methodRecordSize = if (version == 31) 36 else 32
            val fieldRecordSize = 12
            val imageRecordSize = 40
            val declaredTypes = count(typesRange.sizeBytes, typeRecordSize)
            val declaredMethods = count(methodsRange.sizeBytes, methodRecordSize)
            val declaredFields = count(fieldsRange.sizeBytes, fieldRecordSize)
            val declaredImages = imagesRange?.let { count(it.sizeBytes, imageRecordSize) } ?: 0
            var truncated =
                typesRange.sizeBytes % typeRecordSize != 0L ||
                    methodsRange.sizeBytes % methodRecordSize != 0L ||
                    fieldsRange.sizeBytes % fieldRecordSize != 0L ||
                    (imagesRange?.sizeBytes?.rem(imageRecordSize) ?: 0L) != 0L
            val warnings = mutableListOf<String>()
            if (truncated) warnings += "One or more IL2CPP tables are not aligned to the expected record size."

            val typeCount = minOf(declaredTypes, limits.maxTypes)
            val methodCount = minOf(declaredMethods, limits.maxMethods)
            val fieldCount = minOf(declaredFields, limits.maxFields)
            val imageCount = minOf(declaredImages, limits.maxImages)
            if (typeCount < declaredTypes || methodCount < declaredMethods ||
                fieldCount < declaredFields || imageCount < declaredImages
            ) {
                truncated = true
                warnings += "FAST metadata reconstruction hit bounded record limits."
            }

            val stringCache = HashMap<Long, String>()
            fun metadataString(relativeOffset: Long): String? {
                if (relativeOffset !in 0 until strings.sizeBytes) return null
                val cached = stringCache[relativeOffset]
                if (cached != null) return cached.takeIf { it.isNotEmpty() }
                val value = cString(
                    raf,
                    strings.offset + relativeOffset,
                    minOf(limits.maxStringBytes.toLong(), strings.sizeBytes - relativeOffset),
                ).orEmpty()
                stringCache[relativeOffset] = value
                return value.takeIf { it.isNotEmpty() }
            }

            var lastHeartbeat = 0L
            fun heartbeat(task: String, processed: Int, total: Int) {
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat < HEARTBEAT_MS) return
                lastHeartbeat = now
                progress.publish(
                    EngineProgress(
                        engineId = "il2cpp.fast-dump",
                        scheduleClass = EngineScheduleClass.TARGETED,
                        state = RunState.RUNNING,
                        currentTask = task,
                        currentArtifact = file.name,
                        processed = processed.toLong(),
                        total = total.toLong(),
                        lastHeartbeatEpochMs = now,
                    ),
                )
            }

            val types = ArrayList<Il2CppTypeDefinition>(typeCount)
            repeat(typeCount) { index ->
                if (index % 128 == 0) {
                    checkCancelled(cancellation)
                    heartbeat("IL2CPP: type definitions", index, typeCount)
                }
                val base = typesRange.offset + index.toLong() * typeRecordSize
                if (base + typeRecordSize > size) return@repeat
                val typeName = metadataString(u32(raf, base)) ?: return@repeat
                val namespace = metadataString(u32(raf, base + 4)).orEmpty()
                types += Il2CppTypeDefinition(
                    index = index,
                    namespace = namespace,
                    name = typeName,
                    fullName = if (namespace.isBlank()) typeName else namespace + "." + typeName,
                    fieldStart = i32(raf, base + 32),
                    methodStart = i32(raf, base + 36),
                    methodCount = u16(raf, base + 64),
                    fieldCount = u16(raf, base + 68),
                    token = u32(raf, base + 84),
                )
            }
            val typeByIndex = types.associateBy { it.index }

            val images = ArrayList<Il2CppImageDefinition>(imageCount)
            if (imagesRange != null && imagesRange.sizeBytes > 0L) {
                repeat(imageCount) { index ->
                    if (index % 64 == 0) {
                        checkCancelled(cancellation)
                        heartbeat("IL2CPP: image definitions", index, imageCount)
                    }
                    val base = imagesRange.offset + index.toLong() * imageRecordSize
                    if (base + imageRecordSize > size) return@repeat
                    val imageName = metadataString(u32(raf, base)) ?: return@repeat
                    images += Il2CppImageDefinition(
                        index = index,
                        name = imageName,
                        assemblyIndex = i32(raf, base + 4),
                        typeStart = i32(raf, base + 8),
                        typeCount = u32(raf, base + 12).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        token = u32(raf, base + 28),
                    )
                }
            }

            val fieldOwners = HashMap<Int, Il2CppTypeDefinition>()
            types.forEach { type ->
                if (type.fieldStart >= 0 && type.fieldCount > 0) {
                    repeat(type.fieldCount) { relative ->
                        fieldOwners.putIfAbsent(type.fieldStart + relative, type)
                    }
                }
            }

            val fields = ArrayList<Il2CppFieldDefinition>(fieldCount)
            repeat(fieldCount) { index ->
                if (index % 256 == 0) {
                    checkCancelled(cancellation)
                    heartbeat("IL2CPP: field definitions", index, fieldCount)
                }
                val base = fieldsRange.offset + index.toLong() * fieldRecordSize
                if (base + fieldRecordSize > size) return@repeat
                val fieldName = metadataString(u32(raf, base)) ?: return@repeat
                val owner = fieldOwners[index]
                fields += Il2CppFieldDefinition(
                    index = index,
                    declaringTypeIndex = owner?.index ?: -1,
                    declaringType = owner?.fullName ?: "<unresolved-field-owner>",
                    name = fieldName,
                    typeIndex = i32(raf, base + 4),
                    token = u32(raf, base + 8),
                )
            }

            val methods = ArrayList<Il2CppMethodDefinition>(methodCount)
            repeat(methodCount) { index ->
                if (index % 256 == 0) {
                    checkCancelled(cancellation)
                    heartbeat("IL2CPP: method definitions", index, methodCount)
                }
                val base = methodsRange.offset + index.toLong() * methodRecordSize
                if (base + methodRecordSize > size) return@repeat
                val methodName = metadataString(u32(raf, base)) ?: return@repeat
                val declaringTypeIndex = i32(raf, base + 4)
                val tokenOffset = if (version == 31) 24L else 20L
                val flagsOffset = if (version == 31) 28L else 24L
                val parameterCountOffset = if (version == 31) 34L else 30L
                methods += Il2CppMethodDefinition(
                    index = index,
                    declaringTypeIndex = declaringTypeIndex,
                    declaringType = typeByIndex[declaringTypeIndex]?.fullName ?: "<type#" + declaringTypeIndex + ">",
                    name = methodName,
                    parameterCount = u16(raf, base + parameterCountOffset),
                    token = u32(raf, base + tokenOffset),
                    flags = u16(raf, base + flagsOffset),
                )
            }

            checkCancelled(cancellation)
            return Il2CppMetadataModel(
                sizeBytes = size,
                magicValid = true,
                metadataVersion = version,
                layoutProfile = layout(version),
                tableRanges = ranges,
                declaredTypeCount = declaredTypes,
                declaredMethodCount = declaredMethods,
                declaredFieldCount = declaredFields,
                declaredImageCount = declaredImages,
                images = images,
                types = types,
                methods = methods,
                fields = fields,
                structuredSupported = true,
                truncated = truncated,
                warnings = warnings,
            )
        }
    }

    private fun emptyModel(
        size: Long,
        version: Int?,
        magicValid: Boolean,
        warning: String,
        tableRanges: List<Il2CppTableRange> = emptyList(),
        layout: String? = null,
        structuredSupported: Boolean = false,
        truncated: Boolean = false,
    ) = Il2CppMetadataModel(
        sizeBytes = size,
        magicValid = magicValid,
        metadataVersion = version,
        layoutProfile = layout,
        tableRanges = tableRanges,
        declaredTypeCount = null,
        declaredMethodCount = null,
        declaredFieldCount = null,
        declaredImageCount = null,
        images = emptyList(),
        types = emptyList(),
        methods = emptyList(),
        fields = emptyList(),
        structuredSupported = structuredSupported,
        truncated = truncated,
        warnings = listOf(warning),
    )

    private fun layout(version: Int): String =
        if (version == 31) "IL2CPP_METADATA_V31" else "IL2CPP_METADATA_V27_V30"

    private fun scanPairs(raf: RandomAccessFile, fileSize: Long): List<Il2CppTableRange> {
        val out = mutableListOf<Il2CppTableRange>()
        for (index in 0 until 64) {
            val result = pair(raf, fileSize, index, "pair#" + index) ?: break
            out += result
        }
        return out
    }

    private fun pair(
        raf: RandomAccessFile,
        fileSize: Long,
        index: Int,
        name: String,
    ): Il2CppTableRange? {
        val base = 8L + index.toLong() * 8L
        if (base + 8L > fileSize) return null
        val offset = u32(raf, base)
        val size = u32(raf, base + 4)
        if (offset == 0L && size == 0L) return Il2CppTableRange(name, 0, 0)
        if (offset > fileSize || size > fileSize - offset) return null
        return Il2CppTableRange(name, offset, size)
    }

    private fun count(size: Long, recordSize: Int): Int =
        (size / recordSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun cString(raf: RandomAccessFile, offset: Long, maxBytes: Long): String? {
        if (offset < 0L || offset >= raf.length() || maxBytes <= 0L) return null
        raf.seek(offset)
        val out = ByteArray(minOf(maxBytes, 4096L).toInt())
        var used = 0
        while (used < out.size) {
            val value = raf.read()
            if (value <= 0) break
            out[used++] = value.toByte()
        }
        return if (used == 0) null else out.copyOf(used).toString(Charsets.UTF_8).take(512)
    }

    private fun u16(raf: RandomAccessFile, offset: Long): Int {
        raf.seek(offset)
        return raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
    }

    private fun u32(raf: RandomAccessFile, offset: Long): Long {
        raf.seek(offset)
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun i32(raf: RandomAccessFile, offset: Long): Int = u32(raf, offset).toInt()

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }
}
