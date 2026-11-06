package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class TokenRecord(
    val queryId: String,
    val timestamp: Long,
    val providerId: String,
    val modelName: String,
    val agentId: String,
    val taskId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val reasoningTokens: Long,
    val cachedTokens: Long,
    val status: String, // "REAL", "ESTIMATED", "UNAVAILABLE"
    val costUsd: Double,
    val costPln: Double
)

data class ModelPricing(
    val modelKey: String, // model name or prefix
    var inputPer1MUsd: Double,
    var outputPer1MUsd: Double
)

data class BudgetRequest(
    val taskId: String,
    val agentId: String,
    val providerId: String,
    val baseUrl: String,
    val modelName: String,
    val estimatedInputTokens: Long,
    val estimatedOutputTokens: Long
)

data class BatchBudgetPlan(
    val allowedTaskIds: Set<String>,
    val blockedReasons: Map<String, String>,
    val estimatedReservedTokens: Long,
    val estimatedReservedCostPln: Double,
    val reservationId: String = ""
)

object ApiTokenTracker {
    private const val PREFS_NAME = "api_token_tracker_records_v1"
    private const val PRICING_PREFS = "api_token_pricing_v1"
    private const val LIMITS_PREFS = "api_token_limits_v1"

    private val sessionRecords = mutableListOf<TokenRecord>()
    // Session means the current app process, not an in-memory list that disappears from the monitor.
    private val sessionStartedAt = System.currentTimeMillis()
    private data class ActiveReservationTask(val taskId: String, val agentId: String, var tokensRemaining: Long, var costPlnRemaining: Double)
    private data class ActiveReservation(val id: String, val tasks: MutableList<ActiveReservationTask>, var globalTokensRemaining: Long)
    private val activeReservations = linkedMapOf<String, ActiveReservation>()

    // Default pricing per 1 Million tokens in USD
    private val defaultPricing = listOf(
        ModelPricing("gemini", 0.075, 0.30),
        ModelPricing("gpt-4o-mini", 0.15, 0.60),
        ModelPricing("gpt-", 2.50, 10.00),
        ModelPricing("claude", 3.00, 15.00),
        ModelPricing("default", 0.50, 1.50)
    )

    @Synchronized
    fun recordQuery(
        context: Context,
        providerId: String,
        modelName: String,
        agentId: String,
        taskId: String = "general",
        inputTokens: Long,
        outputTokens: Long,
        reasoningTokens: Long = 0,
        cachedTokens: Long = 0,
        status: String = "ESTIMATED",
        baseUrl: String = "",
        reservationId: String = ""
    ): TokenRecord {
        val total = inputTokens + outputTokens
        val pricing = getPricing(context, modelName, providerId, baseUrl)
        val usd = (inputTokens * pricing.inputPer1MUsd + outputTokens * pricing.outputPer1MUsd) / 1_000_000.0
        val exchangeRate = getExchangeRate(context)
        val pln = usd * exchangeRate

        val record = TokenRecord(
            queryId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            providerId = providerId,
            modelName = modelName,
            agentId = agentId,
            taskId = taskId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = total,
            reasoningTokens = reasoningTokens,
            cachedTokens = cachedTokens,
            status = status,
            costUsd = usd,
            costPln = pln
        )

        // Keep the usage visible and charged against the in-process reservation even if disk
        // persistence fails. The caller receives an exception and must report the accounting gap.
        val persisted = runCatching { saveRecordToStorage(context, record) }.getOrDefault(false)
        sessionRecords.add(0, record)
        if (sessionRecords.size > 2000) sessionRecords.removeAt(sessionRecords.lastIndex)
        consumeActiveReservation(record, reservationId)
        check(persisted) { "Nie udało się trwale zapisać rejestru użycia API." }
        return record
    }

    private fun consumeActiveReservation(record: TokenRecord, reservationId: String) {
        if (reservationId.isBlank() || record.taskId.isBlank() || record.taskId == "general") return
        val reservation = activeReservations[reservationId] ?: return
        var remainingTokens = record.totalTokens.coerceAtLeast(0L)
        var remainingCost = record.costPln.coerceAtLeast(0.0)
        run {
            reservation.tasks.filter { it.taskId == record.taskId && it.agentId.equals(record.agentId, true) }.forEach { task ->
                val used = minOf(task.tokensRemaining, remainingTokens)
                task.tokensRemaining -= used
                remainingTokens -= used
                val costUsed = minOf(task.costPlnRemaining, remainingCost)
                task.costPlnRemaining -= costUsed
                remainingCost -= costUsed
            }
        }
    }

    @Synchronized
    fun releaseBatchReservation(reservationId: String) {
        if (reservationId.isNotBlank()) activeReservations.remove(reservationId)
    }

    private fun saveRecordToStorage(context: Context, record: TokenRecord): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Never turn an unreadable usage ledger into an empty one: the old records may
        // represent billable calls and must remain intact until explicitly repaired.
        val persisted = readPersistedRecords(context) ?: return false
        val all = (persisted + sessionRecords).distinctBy { it.queryId }.toMutableList()
        all.add(0, record)
        // Keep last 2000 records
        val trimmed = if (all.size > 2000) all.take(2000) else all
        val arr = JSONArray()
        trimmed.forEach { r ->
            arr.put(JSONObject()
                .put("qId", r.queryId)
                .put("ts", r.timestamp)
                .put("prov", r.providerId)
                .put("model", r.modelName)
                .put("agent", r.agentId)
                .put("task", r.taskId)
                .put("in", r.inputTokens)
                .put("out", r.outputTokens)
                .put("tot", r.totalTokens)
                .put("reas", r.reasoningTokens)
                .put("cache", r.cachedTokens)
                .put("status", r.status)
                .put("usd", r.costUsd)
                .put("pln", r.costPln)
            )
        }
        // Usage may represent a billable provider response. Return the commit result so
        // recordQuery can preserve an in-memory accounting fallback and surface the failure.
        return prefs.edit().putString("records", arr.toString()).commit()
    }

    /**
     * Returns null when the persisted ledger cannot be parsed or validated.
     * Null is intentionally different from an empty ledger so budget enforcement
     * and writes can fail closed instead of undercounting or overwriting usage.
     */
    private fun readPersistedRecords(context: Context): List<TokenRecord>? = runCatching {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("records", null)
        if (raw.isNullOrBlank()) return@runCatching emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<TokenRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val queryId = o.optString("qId")
            require(queryId.isNotBlank()) { "Usage record has no query id." }
            val total = o.optLong("tot")
            val input = o.optLong("in")
            val output = o.optLong("out")
            require(input >= 0L && output >= 0L && total >= 0L) { "Usage record has negative token counts." }
            require(o.has("tot") && o.has("qId")) { "Usage record is missing required fields." }
            list.add(TokenRecord(
                queryId = queryId,
                timestamp = o.optLong("ts"),
                providerId = o.optString("prov"),
                modelName = o.optString("model"),
                agentId = o.optString("agent"),
                taskId = o.optString("task", "general"),
                inputTokens = input,
                outputTokens = output,
                totalTokens = total,
                reasoningTokens = o.optLong("reas"),
                cachedTokens = o.optLong("cache"),
                status = o.optString("status", "ESTIMATED"),
                costUsd = o.optDouble("usd"),
                costPln = o.optDouble("pln")
            ))
        }
        list
    }.getOrNull()

    @Synchronized
    fun loadAllRecordsFromStorage(context: Context): List<TokenRecord> {
        // UI/monitor can still display process-local records if disk data is damaged.
        // Budget enforcement and append operations use readPersistedRecords() directly.
        val persisted = readPersistedRecords(context).orEmpty()
        val merged = persisted.toMutableList()
        val knownIds = persisted.mapTo(hashSetOf()) { it.queryId }
        sessionRecords.forEach { record ->
            if (knownIds.add(record.queryId)) merged.add(record)
        }
        return merged.sortedByDescending { it.timestamp }.take(2000)
    }

    fun getPricing(context: Context, modelName: String, providerId: String = "", baseUrl: String = ""): ModelPricing {
        // A verified free/local model must never inherit the generic paid fallback price.
        if (FreeModelsCatalog.isFreeModel(providerId, modelName, baseUrl)) {
            return ModelPricing(modelName, 0.0, 0.0)
        }
        val lower = modelName.lowercase(Locale.ROOT)
        for (p in defaultPricing) {
            if (lower.contains(p.modelKey)) return p
        }
        return defaultPricing.last()
    }

    fun getExchangeRate(context: Context): Double {
        val prefs = context.getSharedPreferences(PRICING_PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat("exchange_rate_pln", 4.0f).toDouble()
    }

    fun setExchangeRate(context: Context, rate: Double) {
        val prefs = context.getSharedPreferences(PRICING_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putFloat("exchange_rate_pln", rate.toFloat()).apply()
    }

    fun filterRecords(records: List<TokenRecord>, period: String): List<TokenRecord> {
        val now = Calendar.getInstance()
        val cutoff = when (period) {
            "Sesja" -> 0L // filtered from persisted records using process start time
            "Dzisiaj" -> {
                now.set(Calendar.HOUR_OF_DAY, 0)
                now.set(Calendar.MINUTE, 0)
                now.set(Calendar.SECOND, 0)
                now.timeInMillis
            }
            "7 dni" -> {
                now.add(Calendar.DAY_OF_YEAR, -7)
                now.timeInMillis
            }
            "30 dni" -> {
                now.add(Calendar.DAY_OF_YEAR, -30)
                now.timeInMillis
            }
            else -> 0L // Cały okres
        }
        if (period == "Sesja") return records.filter { it.timestamp >= sessionStartedAt }
        return records.filter { it.timestamp >= cutoff }
    }

    data class SummaryStats(
        val totalQueries: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val reasoningTokens: Long,
        val cachedTokens: Long,
        val costUsd: Double,
        val costPln: Double
    )

    fun calculateSummary(records: List<TokenRecord>): SummaryStats {
        var q = 0
        var i = 0L
        var o = 0L
        var t = 0L
        var r = 0L
        var c = 0L
        var usd = 0.0
        var pln = 0.0
        records.forEach { rec ->
            q++
            i += rec.inputTokens
            o += rec.outputTokens
            t += rec.totalTokens
            r += rec.reasoningTokens
            c += rec.cachedTokens
            usd += rec.costUsd
            pln += rec.costPln
        }
        return SummaryStats(q, i, o, t, r, c, usd, pln)
    }

    /**
     * Plans a whole batch sequentially so parallel agents cannot each assume
     * that the same remaining daily budget is available.
     * Estimates are conservative: caller supplies prompt estimate and reserved output allowance.
     */
    @Synchronized
    fun planBatch(
        context: Context,
        requests: List<BudgetRequest>,
        globalReserveTokens: Long = 0L
    ): BatchBudgetPlan {
        val limits = getLimits(context)
        val persisted = readPersistedRecords(context)
        if (persisted == null) {
            val reason = "Wstrzymano: rejestr użycia API jest uszkodzony lub nieczytelny; nie można bezpiecznie obliczyć pozostałego budżetu."
            return BatchBudgetPlan(emptySet(), requests.associate { it.taskId to reason }, 0L, 0.0)
        }
        if (requests.any { it.taskId.isBlank() || it.agentId.isBlank() } ||
            requests.map { it.taskId }.distinct().size != requests.size) {
            val reason = "Wstrzymano: zadania budżetowe muszą mieć niepuste i unikalne identyfikatory oraz agentów."
            return BatchBudgetPlan(emptySet(), requests.associate { it.taskId to reason }, 0L, 0.0)
        }
        val all = (persisted + sessionRecords).distinctBy { it.queryId }
        val today = filterRecords(all, "Dzisiaj")
        val recent = filterRecords(all, "30 dni")
        // Keep a reserve for the MASTER's final synthesis after specialist reports return.
        val reservedTasks = activeReservations.values.flatMap { it.tasks }
        val activeGlobalTokens = activeReservations.values.sumOf { it.globalTokensRemaining.coerceAtLeast(0L) }
        var dailyUsed = today.sumOf { it.totalTokens.coerceAtLeast(0L) } + activeGlobalTokens +
            reservedTasks.sumOf { it.tokensRemaining.coerceAtLeast(0L) } + globalReserveTokens.coerceAtLeast(0L)
        val perAgentUsed = today.groupBy { it.agentId.lowercase(Locale.ROOT) }
            .mapValues { entry -> entry.value.sumOf { it.totalTokens.coerceAtLeast(0L) } }
            .toMutableMap()
        reservedTasks.forEach { task ->
            val key = task.agentId.lowercase(Locale.ROOT)
            perAgentUsed[key] = (perAgentUsed[key] ?: 0L) + task.tokensRemaining.coerceAtLeast(0L)
        }
        var monthlyCostUsed = recent.sumOf { it.costPln.coerceAtLeast(0.0) } +
            activeReservations.values.sumOf { r -> r.tasks.sumOf { it.costPlnRemaining.coerceAtLeast(0.0) } }
        val allowed = linkedSetOf<String>()
        val blocked = linkedMapOf<String, String>()
        var reservedTokens = 0L
        var reservedCost = 0.0
        val newReservationTasks = mutableListOf<ActiveReservationTask>()

        requests.forEach { request ->
            val estimate = (request.estimatedInputTokens.coerceAtLeast(0L) + request.estimatedOutputTokens.coerceAtLeast(0L))
            val agentKey = request.agentId.lowercase(Locale.ROOT)
            val agentUsed = perAgentUsed[agentKey] ?: 0L
            val pricing = getPricing(context, request.modelName, request.providerId, request.baseUrl)
            val usd = (request.estimatedInputTokens.coerceAtLeast(0L) * pricing.inputPer1MUsd +
                request.estimatedOutputTokens.coerceAtLeast(0L) * pricing.outputPer1MUsd) / 1_000_000.0
            val estimatedPln = usd * getExchangeRate(context)
            val reason = when {
                dailyUsed + estimate > limits.dailyTokenLimit -> "Pominięto: brak dziennego budżetu tokenów (limit ${limits.dailyTokenLimit})."
                agentUsed + estimate > limits.agentTokenLimit -> "Pominięto: agent przekroczyłby dzienny limit ${limits.agentTokenLimit} tokenów."
                limits.monthlyCostPlnLimit > 0.0 && monthlyCostUsed + estimatedPln > limits.monthlyCostPlnLimit -> "Pominięto: przekroczony zostałby miesięczny budżet ${limits.monthlyCostPlnLimit} PLN."
                else -> null
            }
            if (reason == null) {
                allowed += request.taskId
                dailyUsed += estimate
                perAgentUsed[agentKey] = agentUsed + estimate
                monthlyCostUsed += estimatedPln
                reservedTokens += estimate
                reservedCost += estimatedPln
                newReservationTasks += ActiveReservationTask(request.taskId, request.agentId, estimate, estimatedPln)
            } else {
                blocked[request.taskId] = reason
            }
        }
        val reservationId = UUID.randomUUID().toString()
        if (allowed.isNotEmpty() || globalReserveTokens > 0L) {
            activeReservations[reservationId] = ActiveReservation(reservationId, newReservationTasks, globalReserveTokens.coerceAtLeast(0L))
        }
        return BatchBudgetPlan(allowed, blocked, reservedTokens, reservedCost, reservationId)
    }

    // Limits check
    data class LimitConfig(
        val dailyTokenLimit: Long,
        val monthlyCostPlnLimit: Double,
        val agentTokenLimit: Long
    )

    data class ActiveReservationStats(val tasks: Int, val tokens: Long, val costPln: Double)

    data class BudgetUsageSnapshot(
        val recordedTodayTokens: Long,
        val activeReservedTokens: Long,
        val dailyLimitTokens: Long,
        val remainingTokens: Long,
        val recorded30DayCostPln: Double,
        val activeReservedCostPln: Double,
        val monthlyCostLimitPln: Double,
        val perAgentLimitTokens: Long,
        val ledgerHealthy: Boolean = true
    )

    @Synchronized
    fun activeReservationStats(): ActiveReservationStats = ActiveReservationStats(
        tasks = activeReservations.values.sumOf { it.tasks.count { task -> task.tokensRemaining > 0L } },
        tokens = activeReservations.values.sumOf { r -> r.globalTokensRemaining.coerceAtLeast(0L) + r.tasks.sumOf { it.tokensRemaining.coerceAtLeast(0L) } },
        costPln = activeReservations.values.sumOf { r -> r.tasks.sumOf { it.costPlnRemaining.coerceAtLeast(0.0) } }
    )

    /** A read-only budget snapshot shared by the API monitor and diagnostics screen. */
    @Synchronized
    fun budgetUsageSnapshot(context: Context): BudgetUsageSnapshot {
        val limits = getLimits(context)
        val records = loadAllRecordsFromStorage(context)
        val todayTokens = filterRecords(records, "Dzisiaj").sumOf { it.totalTokens.coerceAtLeast(0L) }
        val recentCost = filterRecords(records, "30 dni").sumOf { it.costPln.coerceAtLeast(0.0) }
        val reservations = activeReservationStats()
        val usedIncludingReservations = todayTokens + reservations.tokens
        return BudgetUsageSnapshot(
            recordedTodayTokens = todayTokens,
            activeReservedTokens = reservations.tokens,
            dailyLimitTokens = limits.dailyTokenLimit,
            remainingTokens = (limits.dailyTokenLimit - usedIncludingReservations).coerceAtLeast(0L),
            recorded30DayCostPln = recentCost,
            activeReservedCostPln = reservations.costPln,
            monthlyCostLimitPln = limits.monthlyCostPlnLimit,
            perAgentLimitTokens = limits.agentTokenLimit,
            ledgerHealthy = readPersistedRecords(context) != null
        )
    }

    fun getLimits(context: Context): LimitConfig {
        val prefs = context.getSharedPreferences(LIMITS_PREFS, Context.MODE_PRIVATE)
        return LimitConfig(
            dailyTokenLimit = prefs.getLong("daily_token_limit", 100_000L),
            monthlyCostPlnLimit = prefs.getFloat("monthly_cost_limit", 50.0f).toDouble(),
            agentTokenLimit = prefs.getLong("agent_token_limit", 50_000L)
        )
    }

    fun setLimits(context: Context, daily: Long, monthly: Double, perAgent: Long) {
        val prefs = context.getSharedPreferences(LIMITS_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("daily_token_limit", daily.coerceAtLeast(1L))
            // 0 PLN disables the monetary limit; negative values are never accepted.
            .putFloat("monthly_cost_limit", monthly.coerceAtLeast(0.0).toFloat())
            .putLong("agent_token_limit", perAgent.coerceAtLeast(1L))
            .apply()
    }

    @Synchronized
    fun clearRecords(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove("records").apply()
        sessionRecords.clear()
        activeReservations.clear()
    }

    data class LimitCheckResult(
        val allowed: Boolean,
        val warningMessage: String?
    )

    @Synchronized
    fun checkLimits(context: Context, agentId: String, estimatedTokens: Long): LimitCheckResult {
        val limits = getLimits(context)
        if (readPersistedRecords(context) == null) {
            return LimitCheckResult(false, "Rejestr użycia API jest uszkodzony lub nieczytelny. Zapytanie wstrzymano, aby nie przekroczyć nieznanego budżetu.")
        }
        val all = loadAllRecordsFromStorage(context)
        val todayRecords = filterRecords(all, "Dzisiaj")
        val todayTokens = todayRecords.sumOf { it.totalTokens.coerceAtLeast(0L) } +
            activeReservations.values.sumOf { r -> r.globalTokensRemaining.coerceAtLeast(0L) + r.tasks.sumOf { it.tokensRemaining.coerceAtLeast(0L) } }
        val agentTodayTokens = todayRecords.filter { it.agentId.equals(agentId, true) }.sumOf { it.totalTokens.coerceAtLeast(0L) } +
            activeReservations.values.sumOf { r -> r.tasks.filter { it.agentId.equals(agentId, true) }.sumOf { it.tokensRemaining.coerceAtLeast(0L) } }

        val monthlyRecords = filterRecords(all, "30 dni")
        val monthlyCost = monthlyRecords.sumOf { it.costPln.coerceAtLeast(0.0) } +
            activeReservations.values.sumOf { r -> r.tasks.sumOf { it.costPlnRemaining.coerceAtLeast(0.0) } }

        if (todayTokens + estimatedTokens > limits.dailyTokenLimit) {
            return LimitCheckResult(false, "Osiągnięto dzienny limit tokenów (${limits.dailyTokenLimit}). Zapytanie zostało wstrzymane dla agenta $agentId.")
        }
        if (limits.monthlyCostPlnLimit > 0.0 && monthlyCost >= limits.monthlyCostPlnLimit) {
            return LimitCheckResult(false, "Osiągnięto miesięczny limit kosztów (${limits.monthlyCostPlnLimit} PLN).")
        }
        if (agentTodayTokens + estimatedTokens > limits.agentTokenLimit) {
            return LimitCheckResult(false, "Agent $agentId przekroczył swój dzienny limit tokenów (${limits.agentTokenLimit}).")
        }

        val dailyRatio = todayTokens.toDouble() / limits.dailyTokenLimit.coerceAtLeast(1L).toDouble()
        val monthlyRatio = if (limits.monthlyCostPlnLimit > 0.0) monthlyCost / limits.monthlyCostPlnLimit else 0.0
        if (dailyRatio >= 0.8 || monthlyRatio >= 0.8) {
            return LimitCheckResult(true, "⚠️ Ostrzeżenie: Wykorzystano ponad 80% limitu budżetowego lub tokenowego.")
        }

        return LimitCheckResult(true, null)
    }
}
