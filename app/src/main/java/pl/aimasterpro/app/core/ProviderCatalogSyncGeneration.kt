package pl.aimasterpro.app.core

/**
 * Serializes catalog persistence against provider-configuration replacement.
 *
 * A generation snapshot identifies the provider set used by a refresh. Import invalidates
 * that snapshot. The guarded action and invalidation share one monitor, so an old refresh
 * cannot pass its generation check and then persist after the import has invalidated it.
 */
class ProviderCatalogSyncGeneration {
    private val lock = Any()
    private var generation = 0L

    fun snapshot(): Long = synchronized(lock) { generation }

    fun invalidate(): Long = synchronized(lock) { ++generation }

    /** Invalidates old work and performs the provider-set swap before a new snapshot can start. */
    fun <T> invalidateAndRun(action: () -> T): T = synchronized(lock) {
        ++generation
        action()
    }

    fun isCurrent(snapshot: Long): Boolean = synchronized(lock) { generation == snapshot }

    fun commitIfCurrent(snapshot: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (generation != snapshot) {
            false
        } else {
            action()
            true
        }
    }
}
