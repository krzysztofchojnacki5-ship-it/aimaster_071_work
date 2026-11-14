package pl.aimasterpro.app.core

/**
 * Keeps the parsed value for one exact persisted snapshot. A failed loader never
 * replaces the last successful entry; callers still validate live identities separately.
 */
internal class RawSnapshotCache<V : Any> {
    private var cachedRaw: String? = null
    private var cachedValue: V? = null
    private var buildCount = 0

    @Synchronized
    fun get(raw: String, loader: () -> V): V {
        if (cachedRaw == raw) cachedValue?.let { return it }
        val loaded = loader()
        cachedRaw = raw
        cachedValue = loaded
        buildCount++
        return loaded
    }

    @Synchronized
    internal fun buildCountForTests(): Int = buildCount

    @Synchronized
    internal fun clear() {
        cachedRaw = null
        cachedValue = null
        buildCount = 0
    }
}
