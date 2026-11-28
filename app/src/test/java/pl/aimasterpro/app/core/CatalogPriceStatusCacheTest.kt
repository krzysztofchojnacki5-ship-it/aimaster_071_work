package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogPriceStatusCacheTest {
    @Test fun resolvesEachStableCatalogIdentityOnlyOnceAcrossRenders() {
        var resolutions = 0
        val cache = CatalogPriceStatusCache<String, Boolean> {
            resolutions++
            it == "unknown-model"
        }

        assertEquals(true, cache.get("unknown-model"))
        assertEquals(true, cache.get("unknown-model"))
        assertEquals(false, cache.get("paid-model"))
        assertEquals(false, cache.get("paid-model"))

        assertEquals(2, resolutions)
        assertEquals(2, cache.sizeForTests())
    }
}
