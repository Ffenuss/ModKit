package io.github.ffenuss.modkit.spaceengine

import io.github.ffenuss.modkit.analysis.*
import java.io.File
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Boundary uses only boot-classloader types; no Kotlin objects escape to the host. */
object SpaceAnalysisBridge {
    @JvmStatic
    fun analyze(files: Array<File>, outputRoot: File, cancelled: BooleanSupplier, onProgress: Consumer<String>): String {
        val signal = object : CancellationSignal {
            override fun isCancelled() = cancelled.asBoolean || Thread.currentThread().isInterrupted
        }
        val progress = ProgressSink { event ->
            if (signal.isCancelled()) throw AnalysisCancelledException()
            onProgress.accept("${event.currentTask.orEmpty()}\n${event.currentArtifact.orEmpty()}")
        }
        val built = PortableArtifactIndexer.index(files.toList(), signal, progress)
        val workspace = AnalysisWorkspace(built.index, files.mapIndexed { i, file -> WorkspaceSource(built.index.sources[i], file) })
        val dex = DexInventoryEngine.analyze(workspace, signal, progress)
        val elf = UniversalElfInventoryEngine.analyze(workspace, outputRoot, signal, progress)
        val metadata = Il2CppMetadataInventoryEngine.analyze(workspace, outputRoot, signal, progress)
        if (signal.isCancelled()) throw AnalysisCancelledException()
        return buildString {
            appendLine("Анализаторы ModKit · общий движок")
            appendLine("Идентификатор индекса: ${built.index.artifactSha256}")
            appendLine("ABI: ${built.index.detectedAbis.joinToString()}")
            appendLine("Индекс: ${built.index.entries.size} файлов; ограничен: ${built.index.truncated}")
            appendLine("\nПризнаки сред выполнения (не доступные модификации):")
            built.index.runtimeProfiles.take(16).forEach { profile ->
                appendLine("${profile.title}: ${profile.status} / ${profile.confidence}")
                profile.evidence.take(4).forEach { appendLine("  $it") }
            }
            appendLine("\nDEX · таблицы заголовка: ${dex.records.size}")
            dex.records.take(64).forEach { record ->
                appendLine("${record.container}:${record.entryPath}")
                appendLine("  v${record.version}; types=${record.typeIdsCount}; methods=${record.methodIdsCount}; classes=${record.classDefsCount}")
                record.warnings.take(4).forEach { appendLine("  Предупреждение: $it") }
            }
            appendLine("\nELF · структурные записи: ${elf.records.size}")
            elf.records.take(64).forEach { record ->
                appendLine("${record.container}:${record.entryPath}")
                appendLine("  ${record.bits}-bit ${record.architecture}; PT_LOAD=${record.loadSegmentCount}; executable=${record.executableLoadSegmentCount}; defined symbols=${record.definedDynamicSymbolCount}")
                appendLine("  Symbols: ${record.sampledDefinedSymbols.take(12).joinToString()}")
                record.warnings.take(4).forEach { appendLine("  Предупреждение: $it") }
            }
            appendLine("\nIL2CPP metadata · записи: ${metadata.records.size}")
            metadata.records.forEach { record ->
                appendLine("${record.container}:${record.path}")
                appendLine("  version=${record.version}; magic=${record.magicValid}; layout supported=${record.structuredSupported}; truncated=${record.truncated}")
                appendLine("  types=${record.parsedTypes}/${record.declaredTypes}; methods=${record.parsedMethods}/${record.declaredMethods}; fields=${record.parsedFields}/${record.declaredFields}; images=${record.parsedImages}/${record.declaredImages}")
                record.imageSamples.forEach { appendLine("  Image: ${it.name}; token=0x${it.token.toString(16)}") }
                record.typeSamples.forEach { appendLine("  Type: ${it.fullName}; token=0x${it.token.toString(16)}") }
                record.methodSamples.forEach { appendLine("  Method: ${it.declaringType}.${it.name}; params=${it.parameterCount}; token=0x${it.token.toString(16)}") }
                record.fieldSamples.forEach { appendLine("  Field: ${it.declaringType}.${it.name}; typeIndex=${it.typeIndex}; token=0x${it.token.toString(16)}") }
                record.warnings.take(8).forEach { appendLine("  Предупреждение: $it") }
            }
            (built.index.warnings + dex.warnings + elf.warnings + metadata.warnings).distinct().take(32).forEach { appendLine("Предупреждение: $it") }
            appendLine("\nDEX проверяет заголовки и счётчики таблиц, ELF — сегменты и динамические символы. Выборка отчёта ограничена. " +
                "IL2CPP metadata разбирается с ограничениями; показана выборка имён и токенов. Metadata token не является адресом функции в памяти. Смещения для модификаций и runtime-контроллеры не подключены.")
        }
    }
}
