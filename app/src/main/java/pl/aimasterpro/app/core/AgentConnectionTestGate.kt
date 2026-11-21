package pl.aimasterpro.app.core

/**
 * Prevents stale asynchronous agent-connection tests from publishing results after an
 * assignment changes or a newer test for the same role starts.
 */
internal class AgentConnectionTestGate {
    data class Request(
        val roleId: String,
        val providerId: String,
        val generation: Long
    )

    private data class ActiveRequest(
        val providerId: String,
        val generation: Long
    )

    private val generations = mutableMapOf<String, Long>()
    private val active = mutableMapOf<String, ActiveRequest>()

    @Synchronized
    fun begin(roleId: String, providerId: String): Request {
        val generation = (generations[roleId] ?: 0L) + 1L
        generations[roleId] = generation
        active[roleId] = ActiveRequest(providerId, generation)
        return Request(roleId, providerId, generation)
    }

    @Synchronized
    fun isCurrent(request: Request): Boolean {
        val current = active[request.roleId] ?: return false
        return current.providerId == request.providerId &&
            current.generation == request.generation
    }

    /** Completes only the currently active request and makes duplicate callbacks stale. */
    @Synchronized
    fun complete(request: Request): Boolean {
        if (!isCurrent(request)) return false
        active.remove(request.roleId)
        return true
    }

    @Synchronized
    fun invalidate(roleId: String) {
        generations[roleId] = (generations[roleId] ?: 0L) + 1L
        active.remove(roleId)
    }

    @Synchronized
    fun invalidateAll() {
        (generations.keys + active.keys).forEach { roleId ->
            generations[roleId] = (generations[roleId] ?: 0L) + 1L
        }
        active.clear()
    }
}
