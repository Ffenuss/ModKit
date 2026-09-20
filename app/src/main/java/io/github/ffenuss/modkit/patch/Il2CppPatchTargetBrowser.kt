package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.Il2CppMethodBinaryBinding
import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind

object Il2CppPatchTargetBrowser {
    private const val METHOD_PREFIX = "il2cpp:method:"

    fun imageName(target: EvidenceTarget): String? {
        val id = target.id
        if (!id.startsWith(METHOD_PREFIX)) return null
        val rest = id.removePrefix(METHOD_PREFIX)
        val separator = rest.indexOf(':')
        if (separator <= 0) return null
        return rest.substring(0, separator)
            .takeIf { it.isNotBlank() }
    }

    fun matches(
        target: EvidenceTarget,
        query: String,
    ): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return true

        return sequenceOf(
            target.displayName,
            target.id,
            target.declaringType,
            target.memberName,
            imageName(target),
        )
            .filterNotNull()
            .any {
                it.lowercase().contains(normalized)
            }
    }

    fun isAssemblyCSharp(
        target: EvidenceTarget,
    ): Boolean =
        imageName(target)
            ?.lowercase()
            ?.let {
                it == "assembly-csharp" ||
                    it == "assembly-csharp.dll"
            } == true

    fun originLabel(
        target: EvidenceTarget,
    ): String {
        val image =
            imageName(target)
                ?.lowercase()
                .orEmpty()
        return when {
            isAssemblyCSharp(target) ->
                "Код проекта"
            image.startsWith("unityengine") ||
                image.startsWith("unity.") ->
                "Unity / пакет"
            image.startsWith("system") ||
                image.startsWith("microsoft") ||
                image == "mscorlib" ||
                image == "mscorlib.dll" ||
                image == "netstandard" ||
                image == "netstandard.dll" ->
                ".NET / системная библиотека"
            image.isBlank() ->
                "Источник не определён"
            else ->
                "Библиотека / плагин"
        }
    }

    fun bindingFor(
        result: FastAnalysisResult,
        target: EvidenceTarget,
    ): Il2CppMethodBinaryBinding? {
        val token = target.metadataToken ?: return null
        val artifact = target.artifact ?: return null
        val targetImage = imageName(target) ?: return null
        return result.il2cppBinaryBinding
            ?.evidence
            .orEmpty()
            .asSequence()
            .filter { it.libraryEntry == artifact }
            .flatMap { it.bindings.asSequence() }
            .filter {
                it.metadataToken == token &&
                    it.imageName.equals(
                        targetImage,
                        ignoreCase = true,
                    )
            }
            .singleOrNull()
    }

    fun returnKindLabel(
        kind: Il2CppNativeReturnKind,
    ): String = when (kind) {
        Il2CppNativeReturnKind.VOID ->
            "void — значение не возвращается"
        Il2CppNativeReturnKind.BOOLEAN ->
            "bool"
        Il2CppNativeReturnKind.INTEGER ->
            "целочисленный"
        Il2CppNativeReturnKind.POINTER_OR_REFERENCE ->
            "ссылка / указатель"
        Il2CppNativeReturnKind.FLOAT32 ->
            "float (R4)"
        Il2CppNativeReturnKind.FLOAT64 ->
            "double (R8)"
        Il2CppNativeReturnKind.FLOATING_POINT ->
            "float / double (legacy, ширина не доказана)"
        Il2CppNativeReturnKind.VALUE_TYPE ->
            "value type / структура"
        Il2CppNativeReturnKind.UNKNOWN ->
            "не доказан"
    }

    fun presetAdvice(
        result: FastAnalysisResult,
        target: EvidenceTarget,
    ): String {
        val binding = bindingFor(result, target)
            ?: return "Точный return type не связан с этой binary-целью; semantic presets скрыты."
        return when (binding.returnKind) {
            Il2CppNativeReturnKind.VOID ->
                "ModKit доказал void: доступно действие «сразу завершить метод»."
            Il2CppNativeReturnKind.BOOLEAN ->
                "ModKit доказал bool: доступны «всегда false» и «всегда true»."
            Il2CppNativeReturnKind.INTEGER ->
                "ModKit доказал целочисленный return: доступны возврат 0 или 1."
            Il2CppNativeReturnKind.POINTER_OR_REFERENCE ->
                "ModKit доказал ссылочный/указательный return: безопасный готовый вариант — вернуть null."
            Il2CppNativeReturnKind.FLOAT32 ->
                "ModKit доказал IL2CPP float (R4): доступны ARM64 presets 0.0/0.5/1/2/3/5."
            Il2CppNativeReturnKind.FLOAT64 ->
                "ModKit доказал IL2CPP double (R8): доступны ARM64 presets 0.0/0.5/1/2/3/5."
            Il2CppNativeReturnKind.FLOATING_POINT ->
                "Это старый результат анализа без доказанной ширины float/double; пересканируйте APK для безопасных presets."
            Il2CppNativeReturnKind.VALUE_TYPE ->
                "Доказан value-type return; простой X0 preset запрещён ABI-правилами."
            Il2CppNativeReturnKind.UNKNOWN ->
                "Return type не доказан. ModKit не предлагает void/0/1 по имени метода."
        }
    }

    fun reconstructedSourceView(
        result: FastAnalysisResult,
        target: EvidenceTarget,
    ): String {
        val binding = bindingFor(result, target)
        val model = result.il2cppFastDump?.metadata
        val method =
            model?.methods
                ?.firstOrNull {
                    it.token == target.metadataToken &&
                        it.declaringType ==
                        target.declaringType
                }
        val fullType =
            target.declaringType
                ?.takeIf { it.isNotBlank() }
                ?: "UnknownType"
        val namespace =
            fullType.substringBeforeLast(
                '.',
                missingDelimiterValue = "",
            )
        val className =
            fullType.substringAfterLast('.')
        val methodName =
            target.memberName
                ?.takeIf { it.isNotBlank() }
                ?: "Method"
        val parameterCount =
            method?.parameterCount
                ?: 0
        val fields =
            model?.types
                ?.firstOrNull {
                    it.fullName == fullType
                }
                ?.let { type ->
                    model.fields
                        .asSequence()
                        .filter {
                            it.declaringTypeIndex ==
                                type.index
                        }
                        .take(12)
                        .toList()
                }
                .orEmpty()

        return buildString {
            appendLine(
                "// Восстановленное представление из IL2CPP metadata.",
            )
            appendLine(
                "// Это не исходный .cs файл: комментарии, локальные имена " +
                    "и точное C# тело после IL2CPP-компиляции в APK не хранятся.",
            )
            target.metadataToken?.let {
                appendLine(
                    "// metadata token: 0x" +
                        it.toString(16),
                )
            }
            target.fileOffset?.let {
                appendLine(
                    "// native file offset: 0x" +
                        it.toString(16),
                )
            }
            if (namespace.isNotBlank()) {
                appendLine("namespace " + namespace)
                appendLine("{")
            }
            val indent =
                if (namespace.isBlank()) "" else "    "
            appendLine(indent + "class " + className)
            appendLine(indent + "{")
            fields.forEach { field ->
                appendLine(
                    indent +
                        "    object " +
                        field.name +
                        "; // TypeRef#" +
                        field.typeIndex,
                )
            }
            if (fields.isNotEmpty()) {
                appendLine()
            }
            append(
                indent +
                    "    public " +
                    sourceReturnType(
                        binding?.returnKind
                            ?: Il2CppNativeReturnKind.UNKNOWN,
                    ) +
                    " " +
                    methodName +
                    "(",
            )
            repeat(parameterCount) { index ->
                if (index > 0) append(", ")
                append("object arg" + index)
            }
            appendLine(")")
            appendLine(indent + "    {")
            appendLine(
                indent +
                    "        // Исходное C# тело невозможно извлечь из IL2CPP APK.",
            )
            appendLine(
                indent +
                    "        // Ниже в редакторе доступен точный ARM64 body этого метода.",
            )
            appendLine(indent + "    }")
            appendLine(indent + "}")
            if (namespace.isNotBlank()) {
                appendLine("}")
            }
        }.trimEnd()
    }

    private fun sourceReturnType(
        kind: Il2CppNativeReturnKind,
    ): String = when (kind) {
        Il2CppNativeReturnKind.VOID ->
            "void"
        Il2CppNativeReturnKind.BOOLEAN ->
            "bool"
        Il2CppNativeReturnKind.INTEGER ->
            "long /* integer width unknown */"
        Il2CppNativeReturnKind.POINTER_OR_REFERENCE ->
            "object"
        Il2CppNativeReturnKind.FLOAT32 ->
            "float"
        Il2CppNativeReturnKind.FLOAT64 ->
            "double"
        Il2CppNativeReturnKind.FLOATING_POINT ->
            "double /* float/double width not proven */"
        Il2CppNativeReturnKind.VALUE_TYPE ->
            "object /* value type */"
        Il2CppNativeReturnKind.UNKNOWN ->
            "object /* return type unknown */"
    }

    fun methodHint(
        target: EvidenceTarget,
    ): String {
        val name =
            target.memberName.orEmpty()
        return when {
            name.startsWith("get_") ->
                "Getter: чтение свойства."
            name.startsWith("set_") ->
                "Setter: запись свойства."
            name == "Awake" ->
                "Unity lifecycle: ранняя инициализация объекта."
            name == "Start" ->
                "Unity lifecycle: запуск компонента."
            name == "OnEnable" ->
                "Unity lifecycle: компонент включён."
            name == "OnDisable" ->
                "Unity lifecycle: компонент выключен."
            name == "Update" ->
                "Unity lifecycle: вызывается каждый кадр."
            name.startsWith("On") ->
                "Callback/обработчик события по имени метода."
            name.startsWith("Is") ||
                name.startsWith("Has") ||
                name.startsWith("Can") ->
                "По имени похоже на проверку состояния; точный смысл требует контекста."
            else ->
                "Назначение по одной сигнатуре не подтверждено."
        }
    }
}
