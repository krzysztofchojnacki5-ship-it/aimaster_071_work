package pl.aimasterpro.app.core

/** Memoizes one catalog-price classification per stable provider/endpoint/model identity. */
internal class CatalogPriceStatusCache<K, V>(private val resolve: (K) -> V) {
    private val values = mutableMapOf<K, V>()

    fun get(key: K): V = values.getOrPut(key) { resolve(key) }

    internal fun sizeForTests(): Int = values.size
}
