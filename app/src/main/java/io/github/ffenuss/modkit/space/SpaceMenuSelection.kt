package io.github.ffenuss.modkit.space

/** The transfer cap must not hide executable recipes behind inventory-only candidates. */
internal object SpaceMenuSelection {
    fun <T> select(items: List<T>, limit: Int, executable: (T) -> Boolean): List<T> {
        require(limit in 0..256)
        // Preserve the caller's genre/evidence order within each capability group.
        return (items.filter(executable) + items.filterNot(executable)).take(limit)
    }
}
