package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTarget

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
