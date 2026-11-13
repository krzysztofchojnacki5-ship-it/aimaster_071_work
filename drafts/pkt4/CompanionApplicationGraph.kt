package pl.aimasterpro.app.core

/**
 * DRAFT — process-scoped dependency graph owned by the Android Application.
 * The store factory is evaluated once, lazily and thread-safely. Every runtime is bound to that
 * same store. The graph itself retains no Android Context; the Application supplies a factory
 * that captures only its applicationContext when constructing the Android-backed store.
 *
 * Closing the graph closes the current runtime/listener binding, but deliberately does not erase
 * persisted trust or destroy the store. A later runtime() call may create a fresh empty registry
 * bound to the same durable store; sockets and admission tickets are never restored here.
 */
class CompanionApplicationGraph<S : CompanionTrustRevocationSource>(
    private val storeFactory: () -> S
) : java.io.Closeable {
    private val storeLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED, storeFactory)
    private val runtimeSlot = CompanionRuntimeSlot()

    fun provisioningStore(): S = storeLazy.value

    fun runtime(): CompanionRuntime = runtimeSlot.getOrCreate(provisioningStore())

    override fun close() = runtimeSlot.close()
}
