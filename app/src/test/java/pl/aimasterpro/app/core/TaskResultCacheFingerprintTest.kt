package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class TaskResultCacheFingerprintTest {
    @Test fun exactInputsProduceStableFingerprint() {
        val a = TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://api.example/v1", "model")
        val b = TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://api.example/v1", "model")
        assertEquals(a, b)
    }
    @Test fun embeddedSeparatorsCannotMakeDifferentInputsCollide() {
        val a = TaskResultCache.fingerprint("dev", "goal\u001fprompt", "x", "project", "", "", "provider-a", "https://api.example/v1", "model")
        val b = TaskResultCache.fingerprint("dev", "goal", "prompt\u001fx", "project", "", "", "provider-a", "https://api.example/v1", "model")
        assertNotEquals(a, b)
    }

    @Test fun changedContextAttachmentOrModelInvalidatesCacheKey() {
        val base = TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://api.example/v1", "model")
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "changed", "", "", "provider-a", "https://api.example/v1", "model"))
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "image/png", "abc", "provider-a", "https://api.example/v1", "model"))
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://api.example/v1", "other-model"))
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-b", "https://api.example/v1", "model"))
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://other.example/v1", "model"))
    }
}
