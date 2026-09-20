package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

data class NativeMutationDraft(
    val request: MutationRequest,
    val targetDisplayName: String,
    val originalHex: String,
    val replacementHex: String,
    val extractedLibraryPath: String,
)

data class NativeCodeWindow(
    val targetId: String,
    val targetDisplayName: String,
    val abi: String,
    val fileOffset: Long,
    val binaryVirtualAddress: Long?,
    val nextMethodFileOffset: Long?,
    val originalHex: String,
    val byteLength: Int,
    val extractedLibraryPath: String,
)

/**
 * Builds a concrete IL2CPP native in-place mutation draft from user-supplied
 * replacement bytes and the exact binary evidence already extracted by the
 * confirmation engine.
 *
 * The draft is still not writable until [MutationPreflightEngine] and source
 * SHA/range checks pass.
 */
object Il2CppNativeMutationDraftBuilder {
    private const val MAX_PATCH_BYTES = 4096
    private const val DEFAULT_CODE_WINDOW_BYTES = 64
    private const val MAX_CODE_WINDOW_BYTES = 1024

    fun readCodeWindow(
        result: FastAnalysisResult,
        targetId: String,
        analysisResultsRoot: File,
        maxBytes: Int = DEFAULT_CODE_WINDOW_BYTES,
    ): NativeCodeWindow {
        require(maxBytes in 4..MAX_CODE_WINDOW_BYTES) {
            "Размер окна кода вне допустимого диапазона."
        }
        val target =
            result.evidenceGraph
                ?.targets
                ?.singleOrNull { it.id == targetId }
                ?: error("Подтверждённая цель не найдена в Evidence Graph.")

        require(target.runtimeId == "unity_il2cpp") {
            "Окно native-кода доступно только для IL2CPP."
        }
        require(target.kind == EvidenceTargetKind.METHOD) {
            "Окно native-кода требует method target."
        }
        require(
            target.proofLevel == ProofLevel.EXACT_BINARY ||
                target.proofLevel == ProofLevel.RUNTIME_CONFIRMED ||
                target.proofLevel == ProofLevel.CHANGE_READY,
        ) {
            "Метод ещё не имеет достаточного executable proof."
        }
        require(
            target.userStatus == UserFindingStatus.CONFIRMED ||
                target.userStatus == UserFindingStatus.READY,
        ) {
            "Метод ещё не подтверждён для редактирования."
        }

        val offset = requireNotNull(target.fileOffset) {
            "Для метода отсутствует подтверждённый file offset."
        }
        require(offset >= 0L) { "Некорректный file offset." }

        val abi = requireNotNull(target.abi) {
            "Для метода не определён ABI."
        }
        val artifact = requireNotNull(target.artifact) {
            "Для метода не определён исходный native artifact."
        }
        val safeAbi =
            abi.replace(
                Regex("[^A-Za-z0-9._-]"),
                "_",
            )
        val extracted =
            File(
                analysisResultsRoot,
                result.index.artifactSha256 +
                    "/il2cpp/native/" +
                    safeAbi +
                    "-libil2cpp.so",
            )
        require(extracted.isFile && extracted.canRead()) {
            "Извлечённый libil2cpp.so для этого ABI не найден."
        }
        require(offset < extracted.length()) {
            "File offset метода выходит за границы libil2cpp.so."
        }

        val nextMethodOffset =
            result.evidenceGraph
                ?.targets
                .orEmpty()
                .asSequence()
                .filter {
                    it.runtimeId == "unity_il2cpp" &&
                        it.kind == EvidenceTargetKind.METHOD &&
                        it.artifact == artifact &&
                        it.abi == abi &&
                        it.fileOffset != null &&
                        requireNotNull(it.fileOffset) > offset
                }
                .mapNotNull { it.fileOffset }
                .minOrNull()

        val availableToNext =
            nextMethodOffset
                ?.minus(offset)
                ?: maxBytes.toLong()
        val availableInFile =
            extracted.length() - offset
        var length =
            minOf(
                maxBytes.toLong(),
                availableToNext,
                availableInFile,
            ).toInt()
        if (abi.equals("arm64-v8a", ignoreCase = true)) {
            length -= length % 4
        }
        require(length > 0) {
            "Не удалось выделить безопасное окно байтов этого метода."
        }

        val original = ByteArray(length)
        RandomAccessFile(extracted, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(original)
        }

        return NativeCodeWindow(
            targetId = target.id,
            targetDisplayName = target.displayName,
            abi = abi,
            fileOffset = offset,
            binaryVirtualAddress =
                target.binaryVirtualAddress,
            nextMethodFileOffset = nextMethodOffset,
            originalHex = original.toDisplayHex(),
            byteLength = original.size,
            extractedLibraryPath = extracted.absolutePath,
        )
    }

    fun build(
        result: FastAnalysisResult,
        targetId: String,
        replacementHex: String,
        analysisResultsRoot: File,
        stagingRoot: File,
    ): NativeMutationDraft {
        val target = result.evidenceGraph
            ?.targets
            ?.singleOrNull { it.id == targetId }
            ?: error("Подтверждённая цель не найдена в Evidence Graph.")

        require(target.runtimeId == "unity_il2cpp") {
            "Этот draft builder предназначен только для IL2CPP."
        }
        require(target.kind == EvidenceTargetKind.METHOD) {
            "Native in-place draft требует method target."
        }
        require(
            target.proofLevel == ProofLevel.EXACT_BINARY ||
                target.proofLevel == ProofLevel.RUNTIME_CONFIRMED ||
                target.proofLevel == ProofLevel.CHANGE_READY,
        ) {
            "Метод ещё не имеет достаточного executable proof."
        }
        require(
            target.userStatus == UserFindingStatus.CONFIRMED ||
                target.userStatus == UserFindingStatus.READY,
        ) {
            "Метод ещё не подтверждён для подготовки изменения."
        }

        val offset = requireNotNull(target.fileOffset) {
            "Для метода отсутствует подтверждённый file offset."
        }
        require(offset >= 0L) { "Некорректный file offset." }

        val sameExecutableOffset =
            result.evidenceGraph
                ?.targets
                .orEmpty()
                .filter {
                    it.runtimeId == "unity_il2cpp" &&
                        it.kind ==
                        EvidenceTargetKind.METHOD &&
                        it.artifact == target.artifact &&
                        it.fileOffset == offset
                }
        require(sameExecutableOffset.size == 1) {
            "Этот executable offset разделяется " +
                sameExecutableOffset.size +
                " IL2CPP-методами. Изменение одной metadata-цели " +
                "заблокировано, пока общий native target не выбран явно."
        }

        val abi = requireNotNull(target.abi) {
            "Для метода не определён ABI."
        }
        val replacementBytes = parseHex(replacementHex)
        if (abi.equals("arm64-v8a", ignoreCase = true)) {
            require(offset % 4L == 0L) {
                "ARM64 method file offset должен быть выровнен по 4 байта."
            }
            require(replacementBytes.size % 4 == 0) {
                "ARM64 in-place patch должен содержать целое число 4-байтовых инструкций."
            }
        }
        require(replacementBytes.isNotEmpty()) { "Новые байты не заданы." }
        require(replacementBytes.size <= MAX_PATCH_BYTES) {
            "Размер in-place patch превышает внутренний лимит."
        }

        val safeAbi = abi.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val extracted = File(
            analysisResultsRoot,
            result.index.artifactSha256 +
                "/il2cpp/native/" + safeAbi + "-libil2cpp.so",
        )
        require(extracted.isFile && extracted.canRead()) {
            "Извлечённый libil2cpp.so для этого ABI не найден."
        }
        require(offset + replacementBytes.size <= extracted.length()) {
            "Диапазон изменения выходит за границы libil2cpp.so."
        }

        val original = ByteArray(replacementBytes.size)
        RandomAccessFile(extracted, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(original)
        }
        require(!original.contentEquals(replacementBytes)) {
            "Новые байты совпадают с исходными; изменение отсутствует."
        }

        val replacementSha = sha256(replacementBytes)
        val originalSha = sha256(original)
        val mutationId = "il2cpp-native-" +
            sha256((target.id + ":" + replacementSha).toByteArray())
                .take(16)
        val payloadDir = File(
            stagingRoot,
            result.index.artifactSha256 + "/payloads",
        ).apply { mkdirs() }
        val payload = File(payloadDir, mutationId + ".bin")
        val temp = File(payloadDir, mutationId + ".tmp")
        temp.writeBytes(replacementBytes)
        if (payload.exists()) payload.delete()
        check(temp.renameTo(payload)) {
            temp.delete()
            "Не удалось сохранить payload изменения."
        }

        return NativeMutationDraft(
            request = MutationRequest(
                id = mutationId,
                artifactSha256 = result.index.artifactSha256,
                targetId = target.id,
                kind = MutationKind.NATIVE_IN_PLACE_BYTES,
                expectedOriginalSha256 = originalSha,
                expectedOriginalSize = original.size.toLong(),
                replacement = MutationPayloadRef(
                    sha256 = replacementSha,
                    size = replacementBytes.size.toLong(),
                    storagePath = payload.absolutePath,
                ),
            ),
            targetDisplayName = target.displayName,
            originalHex = original.toDisplayHex(),
            replacementHex = replacementBytes.toDisplayHex(),
            extractedLibraryPath = extracted.absolutePath,
        )
    }

    fun parseHex(value: String): ByteArray {
        val normalized = value
            .replace("0x", "", ignoreCase = true)
            .replace(Regex("[^0-9A-Fa-f]"), "")
        require(normalized.length % 2 == 0) {
            "Hex-строка должна содержать полные байты."
        }
        if (normalized.isEmpty()) return ByteArray(0)
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2)
                .toInt(16)
                .toByte()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ByteArray.toDisplayHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
}
