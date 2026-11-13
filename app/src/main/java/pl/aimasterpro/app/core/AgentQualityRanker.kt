package pl.aimasterpro.app.core

import kotlin.math.sqrt

/** Pure local ranker. It never calls a model and never changes provider/model assignments. */
object AgentQualityRanker {
    data class Evidence(val taskId: String, val rating: Int, val category: String = "")
    data class Snapshot(
        val score: Double,
        val positive: Int,
        val sampleCount: Int,
        val categorySampleCount: Int,
        val confidenceLabel: String,
        val intervalLow: Double,
        val intervalHigh: Double
    )

    /**
     * Returns a smoothed quality score only after enough independent task IDs overall.
     * Sparse category evidence is shrunk toward the agent's global score. The interval is a
     * Wilson 95% interval over observed ratings (not the smoothed routing score).
     */
    fun snapshot(evidence: List<Evidence>, category: String = "", minSamples: Int = 5): Snapshot? {
        require(minSamples >= 1)
        val unique = evidence.asSequence()
            .filter { it.taskId.isNotBlank() && (it.rating == 1 || it.rating == -1) }
            .distinctBy { it.taskId }
            .toList()
        if (unique.size < minSamples) return null

        fun smoothed(rows: List<Evidence>): Double = (rows.count { it.rating > 0 } + 2.0) / (rows.size + 4.0)
        val globalScore = smoothed(unique)
        val scoped = if (category.isBlank()) emptyList() else unique.filter { it.category.equals(category, ignoreCase = true) }
        val score = when {
            category.isBlank() || scoped.isEmpty() -> globalScore
            scoped.size >= minSamples -> smoothed(scoped)
            else -> {
                val priorWeight = 4.0
                (scoped.count { it.rating > 0 } + priorWeight * globalScore) / (scoped.size + priorWeight)
            }
        }
        val observed = if (category.isNotBlank() && scoped.size >= minSamples) scoped else unique
        val positives = observed.count { it.rating > 0 }
        val interval = wilsonInterval(positives, observed.size)
        val confidence = when {
            observed.size >= 20 -> "wysoka próba"
            observed.size >= 10 -> "umiarkowana próba"
            observed.size >= 5 -> "wstępna próba"
            else -> "mało danych"
        }
        return Snapshot(score, positives, unique.size, scoped.size, confidence, interval.first, interval.second)
    }

    fun score(evidence: List<Evidence>, category: String = "", minSamples: Int = 5): Double? =
        snapshot(evidence, category, minSamples)?.score

    /** Wilson score interval (95%) for a binomial proportion; returns [0,1] bounds. */
    fun wilsonInterval(positive: Int, total: Int): Pair<Double, Double> {
        if (total <= 0) return 0.0 to 1.0
        val n = total.toDouble()
        val p = positive.coerceIn(0, total) / n
        val z = 1.96
        val denominator = 1.0 + z * z / n
        val center = (p + z * z / (2.0 * n)) / denominator
        val margin = z * sqrt((p * (1.0 - p) / n) + (z * z / (4.0 * n * n))) / denominator
        return (center - margin).coerceIn(0.0, 1.0) to (center + margin).coerceIn(0.0, 1.0)
    }
}
