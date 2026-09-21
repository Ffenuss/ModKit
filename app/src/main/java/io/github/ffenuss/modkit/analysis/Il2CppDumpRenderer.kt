package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File

object Il2CppDumpRenderer {
    fun write(
        model: Il2CppMetadataModel,
        output: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ) {
        output.parentFile?.mkdirs()
        // Type definitions already contain contiguous field/method ranges.
        // Do not duplicate large metadata tables into groupBy maps on Android.
        output.bufferedWriter(Charsets.UTF_8, 128 * 1024).use { writer ->
            writer.appendLine("// ModKit IL2CPP metadata dump")
            writer.appendLine("// metadata_version=" + (model.metadataVersion ?: "unknown"))
            writer.appendLine("// layout=" + (model.layoutProfile ?: "unsupported"))
            writer.appendLine("// exact_native_binding=false")
            writer.appendLine("// RVA/VA/file offsets are intentionally absent until CodeGen/binary proof succeeds.")
            writer.appendLine("// types=" + model.types.size + "/" + (model.declaredTypeCount ?: 0))
            writer.appendLine("// methods=" + model.methods.size + "/" + (model.declaredMethodCount ?: 0))
            writer.appendLine("// fields=" + model.fields.size + "/" + (model.declaredFieldCount ?: 0))
            if (model.truncated) writer.appendLine("// fast_reconstruction_truncated=true")
            model.warnings.forEach { writer.appendLine("// warning: " + sanitize(it)) }
            writer.appendLine()

            if (!model.structuredSupported) {
                writer.appendLine("// Structured reconstruction is not supported for this metadata layout yet.")
                return@use
            }

            model.types.forEachIndexed { typeIndex, type ->
                if (typeIndex % 128 == 0) {
                    if (cancellation.isCancelled()) throw AnalysisCancelledException()
                    progress.publish(
                        EngineProgress(
                            engineId = "il2cpp.fast-dump",
                            scheduleClass = EngineScheduleClass.TARGETED,
                            state = RunState.RUNNING,
                            currentTask = "IL2CPP: формирование dump",
                            currentArtifact = output.name,
                            processed = typeIndex.toLong(),
                            total = model.types.size.toLong(),
                            lastHeartbeatEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }

                if (type.namespace.isNotBlank()) {
                    writer.appendLine("namespace " + safeQualified(type.namespace))
                    writer.appendLine("{")
                }
                val indent = if (type.namespace.isNotBlank()) "    " else ""
                writer.appendLine(indent + "// TypeDefIndex: " + type.index + " · token: 0x" + type.token.toString(16))
                writer.appendLine(indent + "class " + safeIdentifier(type.name))
                writer.appendLine(indent + "{")

                if (
                    type.fieldStart >= 0 &&
                    type.fieldCount > 0
                ) {
                    repeat(type.fieldCount) {
                            relative ->
                        val field =
                            model.fields.getOrNull(
                                type.fieldStart +
                                    relative,
                            ) ?: return@repeat
                        if (
                            field.declaringTypeIndex !=
                            type.index
                        ) {
                            return@repeat
                        }
                        writer.appendLine(
                            indent + "    // FieldIndex: " + field.index +
                                " · token: 0x" + field.token.toString(16) +
                                " · typeIndex: " + field.typeIndex,
                        )
                        writer.appendLine(
                            indent + "    /*TypeRef#" + field.typeIndex + "*/ object " +
                                safeIdentifier(field.name) + ";",
                        )
                    }
                }

                if (
                    type.methodStart >= 0 &&
                    type.methodCount > 0
                ) {
                    repeat(type.methodCount) {
                            relative ->
                        val method =
                            model.methods.getOrNull(
                                type.methodStart +
                                    relative,
                            ) ?: return@repeat
                        if (
                            method.declaringTypeIndex !=
                            type.index
                        ) {
                            return@repeat
                        }
                        writer.appendLine(
                            indent + "    // MethodIndex: " + method.index +
                                " · token: 0x" + method.token.toString(16) +
                                " · flags: 0x" + method.flags.toString(16),
                        )
                        writer.append(
                            indent + "    object " +
                                safeIdentifier(method.name) +
                                "(",
                        )
                        repeat(method.parameterCount) {
                                parameterIndex ->
                            if (parameterIndex > 0) {
                                writer.append(", ")
                            }
                            writer.append(
                                "object arg" +
                                    parameterIndex,
                            )
                        }
                        writer.appendLine(");")
                    }
                }

                writer.appendLine(indent + "}")
                if (type.namespace.isNotBlank()) writer.appendLine("}")
                writer.appendLine()
            }
        }
    }

    fun preview(model: Il2CppMetadataModel, maxTypes: Int = 10): String =
        buildString {
            appendLine("metadata v" + (model.metadataVersion ?: "?") + " · " + (model.layoutProfile ?: "layout unsupported"))
            appendLine(
                "types " + model.types.size + "/" + (model.declaredTypeCount ?: 0) +
                    ", methods " + model.methods.size + "/" + (model.declaredMethodCount ?: 0) +
                    ", fields " + model.fields.size + "/" + (model.declaredFieldCount ?: 0),
            )
            if (model.truncated) appendLine("FAST reconstruction is partial.")
            model.types.take(maxTypes).forEach { appendLine(it.fullName) }
        }.trim()

    private fun safeQualified(value: String): String =
        value.split('.').joinToString(".") { safeIdentifier(it) }

    private fun safeIdentifier(value: String): String {
        if (value.isBlank()) return "unnamed"
        return buildString(value.length + 1) {
            value.forEachIndexed { index, ch ->
                when {
                    ch == '_' || ch.isLetterOrDigit() -> {
                        if (index == 0 && ch.isDigit()) append('_')
                        append(ch)
                    }
                    else -> append('_')
                }
            }
        }
    }

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(320)
}
