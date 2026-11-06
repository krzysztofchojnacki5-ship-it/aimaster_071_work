package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQualityRankerTest {
    @Test fun requiresFiveIndependentTasks() {
        val four = (1..4).map { AgentQualityRanker.Evidence("t$it", 1, "code") }
        assertNull(AgentQualityRanker.score(four, "code"))
        assertTrue(AgentQualityRanker.score(four + AgentQualityRanker.Evidence("t5", -1, "code"), "code")!! > 0.5)
    }

    @Test fun duplicateTaskIdsDoNotInflateEvidence() {
        val rows = (1..4).map { AgentQualityRanker.Evidence("t$it", 1, "code") } +
            AgentQualityRanker.Evidence("t1", -1, "code")
        assertNull(AgentQualityRanker.score(rows, "code"))
    }

    @Test fun usesCategoryEvidenceWhenItHasEnoughSamples() {
        val rows = (1..5).map { AgentQualityRanker.Evidence("code$it", 1, "code") } +
            (1..10).map { AgentQualityRanker.Evidence("other$it", -1, "other") }
        val score = AgentQualityRanker.score(rows, "code")!!
        assertEquals(7.0 / 9.0, score, 0.0001)
    }
    @Test fun sparseCategoryEvidenceIsShrunkTowardGlobalPerformance() {
        val rows = (1..10).map { AgentQualityRanker.Evidence("other-$it", 1, "other") } +
            AgentQualityRanker.Evidence("code-1", -1, "code")
        val global = AgentQualityRanker.score(rows)!!
        val category = AgentQualityRanker.score(rows, "code")!!
        assertTrue(category < global)
        assertTrue(category > 0.5)
    }

    @Test fun unknownCategoryUsesGlobalEvidenceWithoutExtraSamples() {
        val rows = (1..5).map { AgentQualityRanker.Evidence("task-$it", if (it <= 3) 1 else -1, "other") }
        assertEquals(AgentQualityRanker.score(rows)!!, AgentQualityRanker.score(rows, "unknown")!!, 0.0001)
    }

    @Test fun snapshotShowsSampleSizeAndWideUncertaintyForSmallSamples() {
        val rows = (1..5).map { AgentQualityRanker.Evidence("s$it", 1, "code") }
        val snapshot = AgentQualityRanker.snapshot(rows, "code")!!
        assertEquals(5, snapshot.sampleCount)
        assertEquals(5, snapshot.categorySampleCount)
        assertEquals("wstępna próba", snapshot.confidenceLabel)
        assertTrue(snapshot.intervalLow < snapshot.score)
        assertTrue(snapshot.intervalHigh > snapshot.score)
    }

    @Test fun wilsonIntervalIsBoundedAndNarrowsWithMoreEvidence() {
        val small = AgentQualityRanker.wilsonInterval(8, 10)
        val large = AgentQualityRanker.wilsonInterval(80, 100)
        assertTrue(small.first >= 0.0 && small.second <= 1.0)
        assertTrue(large.second - large.first < small.second - small.first)
    }

}
