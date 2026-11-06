import pl.aimasterpro.app.core.CompanionApplicationGraphTest
fun main() {
    val test = CompanionApplicationGraphTest()
    test.storeFactoryRunsOnceAcrossConcurrentConsumers()
    test.runtimeIsSharedAndRecreatedOnlyAfterGraphClose()
    test.failedStoreFactoryCanBeRetriedWithoutPoisoningGraph()
    println("3/3 CompanionApplicationGraph tests PASS")
}
