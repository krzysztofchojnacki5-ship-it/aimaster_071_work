package pl.aimasterpro.app

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.aimasterpro.app.core.CompanionRuntime
import pl.aimasterpro.app.core.CompanionTlsHandshakeGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = CompanionApplication::class)
class CompanionApplicationTest {
    @Test
    fun provisioningStoreIsOneInstanceForApplicationLifetime() {
        val application = RuntimeEnvironment.getApplication() as CompanionApplication

        val first = application.companionProvisioningStore
        val second = application.companionProvisioningStore

        assertSame(first, second)
    }

    @Test
    fun trustServicesShareApplicationProvisioningStore() {
        val application = RuntimeEnvironment.getApplication() as CompanionApplication
        val store = application.companionProvisioningStore

        val firstService = application.companionTrustService()
        val secondService = application.companionTrustService()

        val field = firstService.javaClass.getDeclaredField("provisioning").apply { isAccessible = true }
        assertSame(store, field.get(firstService))
        assertSame(store, field.get(secondService))
    }
    @Test
    fun runtimeIsOneApplicationScopedInstanceAcrossConcurrentCallers() {
        val application = RuntimeEnvironment.getApplication() as CompanionApplication
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val results = java.util.Collections.synchronizedList(mutableListOf<CompanionRuntime>())
        repeat(8) {
            thread {
                try {
                    start.await()
                    results += application.companionRuntime()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("Wątki nie zakończyły pobierania runtime.", done.await(3, TimeUnit.SECONDS))
        val first = results.first()
        results.forEach { assertSame(first, it) }
        assertSame(first, application.companionRuntime())
        assertSame(first.tlsHandshakeGate, application.companionTlsHandshakeGate())
    }

    @Test
    fun runtimeBindingUsesExactApplicationProvisioningStore() {
        val application = RuntimeEnvironment.getApplication() as CompanionApplication
        val store = application.companionProvisioningStore
        val runtime = application.companionRuntime()

        val bindingField = runtime.javaClass.getDeclaredField("binding").apply { isAccessible = true }
        val binding = bindingField.get(runtime)
        val sourceField = binding.javaClass.getDeclaredField("source").apply { isAccessible = true }
        assertSame(store, sourceField.get(binding))

        val publisherField = store.javaClass.getDeclaredField("revocationPublisher").apply { isAccessible = true }
        val publisher = publisherField.get(store)
        val fencesField = publisher.javaClass.getDeclaredField("fences").apply { isAccessible = true }
        val fences = fencesField.get(publisher) as java.util.Collection<*>
        org.junit.Assert.assertEquals("Runtime powinien zarejestrować dokładnie jeden fence listener.", 1, fences.size())
    }

}
