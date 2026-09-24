package io.github.ffenuss.modkit.patch

/**
 * A one-tap build combines already queued manual changes with checkbox
 * selections. Newly selected changes replace prior drafts for the same
 * exact target; unrelated manual drafts remain intact.
 *
 * Fail on duplicate targets instead of silently dropping patch requests.
 */
object PatchBuildSelectionMerger {
    fun <T> merge(
        queued: List<T>,
        selected: List<T>,
        targetId: (T) -> String,
    ): List<T> {
        val queuedIds = queued.map(targetId)
        val selectedIds = selected.map(targetId)
        require((queuedIds + selectedIds).all(String::isNotBlank)) {
            "Выбрано изменение без идентификатора метода."
        }
        require(queuedIds.distinct().size == queuedIds.size) {
            "В существующем списке повторяется цель изменения."
        }
        require(selectedIds.distinct().size == selectedIds.size) {
            "Один и тот же метод отмечен несколькими модификациями. " +
                "Оставьте одно изменение для него."
        }
        val replacements = selectedIds.toSet()
        return queued.filterNot { targetId(it) in replacements } + selected
    }
}
