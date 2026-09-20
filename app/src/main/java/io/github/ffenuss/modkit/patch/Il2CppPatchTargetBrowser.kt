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
        return result.il2cppBinaryBinding
            ?.evidence
            .orEmpty()
            .asSequence()
            .filter { it.libraryEntry == artifact }
            .flatMap { it.bindings.asSequence() }
            .filter { it.metadataToken == token }
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
        Il2CppNativeReturnKind.FLOATING_POINT ->
            "float / double"
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
            Il2CppNativeReturnKind.FLOATING_POINT ->
                "Доказан float/double return, но готового ARM64 semantic preset пока нет."
            Il2CppNativeReturnKind.VALUE_TYPE ->
                "Доказан value-type return; простой X0 preset запрещён ABI-правилами."
            Il2CppNativeReturnKind.UNKNOWN ->
                "Return type не доказан. ModKit не предлагает void/0/1 по имени метода."
        }
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
