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
    private data class ActiveReservationTask(val taskId: String, val agentId: String, val providerId: String, val baseUrl: String, val modelName: String, var tokensRemaining: Long, var costPlnRemaining: Double, var completed: Boolean = false)
    private data class ActiveReservation(val id: String, val tasks: MutableList<ActiveReservationTask>, var globalTokensRemaining: Long, val globalReserveAgentId: String = "", val globalReserveCostPln: Double = 0.0, var releaseRequested: Boolean = false)
    private val activeReservations = linkedMapOf<String, ActiveReservation>()

    private fun addTokenCounts(left: Long, right: Long): Long {
        val a = left.coerceAtLeast(0L)
        val b = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }

    private fun sumTokenCounts(values: Iterable<Long>): Long =
        values.fold(0L) { total, value -> addTokenCounts(total, value) }

    private fun exceedsTokenLimit(current: Long, additional: Long, limit: Long): Boolean {
        if (additional < 0L) return true
        val used = current.coerceAtLeast(0L)
        return used > limit || additional > limit - used
    }

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
        reservationId: String = "",
        idempotencyKey: String = ""
    ): TokenRecord {
        require(inputTokens >= 0L && outputTokens >= 0L && reasoningTokens >= 0L && cachedTokens >= 0L) {
            "Liczby tokenów nie mogą być ujemne."
        }
        val stableQueryId = idempotencyKey.trim().ifBlank { UUID.randomUUID().toString() }
        val persistedBefore = readPersistedRecords(context)
            ?: throw IllegalStateException("Rejestr użycia API jest uszkodzony; księgowanie idempotentne zostało wstrzymane.")
        val existing = (persistedBefore + sessionRecords).firstOrNull { it.queryId == stableQueryId }
        if (existing != null) {
            require(existing.providerId == providerId && existing.modelName == modelName &&
                existing.agentId == agentId && existing.taskId == taskId) {
                "Klucz idempotencji użycia API został ponownie użyty dla innego żądania."
            }
            // A late authoritative response may resolve an earlier UNAVAILABLE estimate for
            // the same POST. Upgrade that one row in place; never append a second charge and
            // never let a later estimate downgrade already-known REAL usage.
            if (existing.status == "UNAVAILABLE" && status == "REAL") {
                val upgradedTotal = Math.addExact(inputTokens, outputTokens)
                val upgradePricing = getPricing(context, modelName, providerId, baseUrl)
                require(upgradePricing.inputPer1MUsd.isFinite() && upgradePricing.outputPer1MUsd.isFinite() &&
                    upgradePricing.inputPer1MUsd >= 0.0 && upgradePricing.outputPer1MUsd >= 0.0) {
                    "Nieprawidłowy cennik modelu."
                }
                val upgradeRate = getExchangeRate(context)
                require(upgradeRate.isFinite() && upgradeRate > 0.0) {
                    "Nieprawidłowy kurs USD/PLN; rozliczenie API wstrzymano."
                }
                val upgradeUsd = (inputTokens.toDouble() * upgradePricing.inputPer1MUsd +
                    outputTokens.toDouble() * upgradePricing.outputPer1MUsd) / 1_000_000.0
                val upgradePln = upgradeUsd * upgradeRate
                require(upgradeUsd.isFinite() && upgradePln.isFinite() && upgradeUsd >= 0.0 && upgradePln >= 0.0) {
                    "Nieprawidłowy koszt użycia API."
                }
                val upgraded = existing.copy(
                    timestamp = System.currentTimeMillis(),
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = upgradedTotal,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                    status = "REAL",
                    costUsd = upgradeUsd,
                    costPln = upgradePln
                )
                check(saveRecordToStorage(context, upgraded)) {
                    "Nie udało się trwale uzgodnić rzeczywistego usage z wcześniejszym wpisem UNAVAILABLE."
                }
                sessionRecords.removeAll { it.queryId == stableQueryId }
                sessionRecords.add(0, upgraded)
                adjustActiveReservationForUsageUpgrade(existing, upgraded, reservationId, baseUrl)
                return upgraded
            }
            if (persistedBefore.none { it.queryId == stableQueryId }) {
                check(saveRecordToStorage(context, existing)) {
                    "Nie udało się trwale odtworzyć istniejącego rekordu użycia API."
                }
            }
            return existing
        }

        val total = Math.addExact(inputTokens, outputTokens)
        val pricing = getPricing(context, modelName, providerId, baseUrl)
        require(pricing.inputPer1MUsd.isFinite() && pricing.outputPer1MUsd.isFinite() &&
            pricing.inputPer1MUsd >= 0.0 && pricing.outputPer1MUsd >= 0.0) { "Nieprawidłowy cennik modelu." }
        val exchangeRate = getExchangeRate(context)
        require(exchangeRate.isFinite() && exchangeRate > 0.0) { "Nieprawidłowy kurs USD/PLN; rozliczenie API wstrzymano." }
        val usd = (inputTokens.toDouble() * pricing.inputPer1MUsd + outputTokens.toDouble() * pricing.outputPer1MUsd) / 1_000_000.0
        val pln = usd * exchangeRate
        require(usd.isFinite() && pln.isFinite() && usd >= 0.0 && pln >= 0.0) { "Nieprawidłowy koszt użycia API." }

        val record = TokenRecord(
            queryId = stableQueryId,
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
        consumeActiveReservation(record, reservationId, baseUrl)
        check(persisted) { "Nie udało się trwale zapisać rejestru użycia API." }
        return record
    }

    /**
     * An UNAVAILABLE estimate may already have consumed part of a live reservation.
     * When REAL usage arrives for the same request, reserve only the positive delta.
     * We deliberately do not refund a smaller actual value: conservative headroom is safer.
     */
    private fun adjustActiveReservationForUsageUpgrade(
        previous: TokenRecord,
        upgraded: TokenRecord,
        reservationId: String,
        baseUrl: String
    ) {
        if (reservationId.isBlank()) return
        val reservation = activeReservations[reservationId] ?: return
        val task = reservation.tasks.singleOrNull {
            it.taskId == upgraded.taskId &&
                it.agentId.equals(upgraded.agentId, true) &&
                it.providerId.equals(upgraded.providerId, true) &&
                it.baseUrl.equals(baseUrl, true) &&
                it.modelName.equals(upgraded.modelName, true)
        } ?: return
        val extraTokens = (upgraded.totalTokens - previous.totalTokens).coerceAtLeast(0L)
        val extraCost = (upgraded.costPln - previous.costPln).coerceAtLeast(0.0)
        task.tokensRemaining = (task.tokensRemaining - extraTokens).coerceAtLeast(0L)
        task.costPlnRemaining = (task.costPlnRemaining - extraCost).coerceAtLeast(0.0)
    }

    private fun consumeActiveReservation(record: TokenRecord, reservationId: String, baseUrl: String = "") {
        if (reservationId.isBlank() || record.taskId.isBlank() || record.taskId == "general") return
        val reservation = activeReservations[reservationId] ?: return
        // A fallback provider must not consume the estimate priced for another provider.
        // Leaving that estimate reserved until batch release is conservative and prevents
        // a cheaper/different fallback from freeing the wrong provider's cost allowance.
        val task = reservation.tasks.singleOrNull {
            it.taskId == record.taskId &&
                it.agentId.equals(record.agentId, true) &&
                it.providerId.equals(record.providerId, true) &&
                it.baseUrl.equals(baseUrl, true) &&
                it.modelName.equals(record.modelName, true)
        } ?: return
        task.tokensRemaining = (task.tokensRemaining - record.totalTokens.coerceAtLeast(0L)).coerceAtLeast(0L)
        task.costPlnRemaining = (task.costPlnRemaining - record.costPln.coerceAtLeast(0.0)).coerceAtLeast(0.0)
    }

    /** Immediate release for reservations whose work was never submitted. */
    @Synchronized
    fun releaseBatchReservation(reservationId: String) {
        if (reservationId.isNotBlank()) activeReservations.remove(reservationId)
    }

    /**
     * Defers release until every submitted worker has actually left its callable.
     * Future.cancel(true) can mark a Future done before an interrupt-ignoring worker exits,
     * so callers must use this method for reservations backing TeamRunEngine batches.
     */
    @Synchronized
    fun requestBatchReservationRelease(reservationId: String) {
        if (reservationId.isBlank()) return
        val reservation = activeReservations[reservationId] ?: return
        reservation.releaseRequested = true
        if (reservation.tasks.all { it.completed }) activeReservations.remove(reservationId)
    }

    /** Called exactly once when a batch worker exits, or when a queued worker is cancelled before start. */
    @Synchronized
    fun completeReservationTask(reservationId: String, taskId: String) {
        if (reservationId.isBlank() || taskId.isBlank()) return
        val reservation = activeReservations[reservationId] ?: return
        val task = reservation.tasks.singleOrNull { it.taskId == taskId } ?: return
        task.completed = true
        if (reservation.releaseRequested && reservation.tasks.all { it.completed }) {
            activeReservations.remove(reservationId)
        }
    }

    private fun saveRecordToStorage(context: Context, record: TokenRecord): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Never turn an unreadable usage ledger into an empty one: the old records may
        // represent billable calls and must remain intact until explicitly repaired.
        val persisted = readPersistedRecords(context) ?: return false
        val all = (persisted + sessionRecords).distinctBy { it.queryId }.toMutableList()
        all.removeAll { it.queryId == record.queryId }
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
        val seenQueryIds = hashSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val queryId = o.optString("qId")
            require(queryId.isNotBlank()) { "Usage record has no query id." }
            require(seenQueryIds.add(queryId)) {
                "Usage ledger contains a duplicate idempotency key; refusing ambiguous double accounting."
            }
            // JSONObject.optLong/optDouble coerce strings and truncate fractional values.
            // That is unsafe for a billing ledger: malformed persisted usage must fail closed.
            fun exactLong(key: String): Long {
                val value = o.opt(key)
                require(value is Int || value is Long) { "Usage field '$key' is not an exact integer." }
                return (value as Number).toLong()
            }
            fun exactString(key: String): String {
                val value = o.opt(key)
                require(value is String) { "Usage field '$key' is not a string." }
                return value as String
            }
            val timestamp = exactLong("ts")
            val total = exactLong("tot")
            val input = exactLong("in")
            val output = exactLong("out")
            val reasoning = exactLong("reas")
            val cached = exactLong("cache")
            val usdValue = o.opt("usd")
            val plnValue = o.opt("pln")
            require(usdValue is Number && plnValue is Number) { "Usage cost fields are not numeric." }
            val costUsd = (usdValue as Number).toDouble()
            val costPln = (plnValue as Number).toDouble()
            val providerId = exactString("prov")
            val modelName = exactString("model")
            val agentId = exactString("agent")
            val taskId = exactString("task")
            val status = exactString("status")
            require(timestamp >= 0L && providerId.isNotBlank() && modelName.isNotBlank() &&
                agentId.isNotBlank() && taskId.isNotBlank() && status.isNotBlank()) {
                "Usage record is missing valid identity or timestamp fields."
            }
            require(input >= 0L && output >= 0L && total >= 0L && reasoning >= 0L && cached >= 0L) { "Usage record has negative token counts." }
            val expectedTotal = Math.addExact(input, output)
            require(total == expectedTotal) { "Usage record total does not match input plus output token counts." }
            require(costUsd.isFinite() && costPln.isFinite() && costUsd >= 0.0 && costPln >= 0.0) { "Usage record has invalid cost values." }
            list.add(TokenRecord(
                queryId = queryId,
                timestamp = timestamp,
                providerId = providerId,
                modelName = modelName,
                agentId = agentId,
                taskId = taskId,
                inputTokens = input,
                outputTokens = output,
                totalTokens = total,
                reasoningTokens = reasoning,
                cachedTokens = cached,
                status = status,
                costUsd = costUsd,
                costPln = costPln
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

    @Synchronized
    fun getPricing(context: Context, modelName: String, providerId: String = "", baseUrl: String = ""): ModelPricing {
        // A verified free/local model must never inherit the generic paid fallback price.
        if (FreeModelsCatalog.isFreeModel(providerId, modelName, baseUrl)) {
            return ModelPricing(modelName, 0.0, 0.0)
        }
        val lower = modelName.lowercase(Locale.ROOT)
        for (p in defaultPricing) {
            if (lower.contains(p.modelKey)) return p.copy()
        }
        return defaultPricing.last().copy()
    }

    @Synchronized
    fun getExchangeRate(context: Context): Double {
        val prefs = context.getSharedPreferences(PRICING_PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat("exchange_rate_pln", 4.0f).toDouble()
    }

    @Synchronized
    fun setExchangeRate(context: Context, rate: Double) {
        require(rate.isFinite() && rate in 0.01..1_000.0) { "Kurs USD/PLN musi być skończony i mieścić się w zakresie 0,01–1000." }
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
        globalReserveTokens: Long = 0L,
        globalReserveAgentId: String = "",
        globalReserveCostPln: Double = 0.0
    ): BatchBudgetPlan {
        val limits = getLimits(context)
        val persisted = readPersistedRecords(context)
        if (persisted == null) {
            val reason = "Wstrzymano: rejestr użycia API jest uszkodzony lub nieczytelny; nie można bezpiecznie obliczyć pozostałego budżetu."
            return BatchBudgetPlan(emptySet(), requests.associate { it.taskId to reason }, 0L, 0.0)
        }
        if (limits.dailyTokenLimit <= 0L || limits.agentTokenLimit <= 0L ||
            !limits.monthlyCostPlnLimit.isFinite() || limits.monthlyCostPlnLimit < 0.0 ||
            globalReserveTokens < 0L || !globalReserveCostPln.isFinite() || globalReserveCostPln < 0.0 ||
            (globalReserveTokens > 0L && globalReserveAgentId.isNotBlank() && globalReserveAgentId.length > 128) || requests.any {
                it.taskId.isBlank() || it.agentId.isBlank() || it.providerId.isBlank() ||
                    it.estimatedInputTokens < 0L || it.estimatedOutputTokens < 0L ||
                    runCatching { Math.addExact(it.estimatedInputTokens, it.estimatedOutputTokens) }.isFailure
            } || requests.map { it.taskId }.distinct().size != requests.size) {
            val reason = "Wstrzymano: nieprawidłowa konfiguracja limitów lub danych zadań (identyfikatory, provider, tokeny albo rezerwa globalna)."
            return BatchBudgetPlan(emptySet(), requests.associate { it.taskId to reason }, 0L, 0.0)
        }
        val all = (persisted + sessionRecords).distinctBy { it.queryId }
        val today = filterRecords(all, "Dzisiaj")
        val recent = filterRecords(all, "30 dni")
        // Keep a reserve for the MASTER's final synthesis after specialist reports return.
        val reservedTasks = activeReservations.values.flatMap { it.tasks }
        val activeGlobalTokens = sumTokenCounts(activeReservations.values.map { it.globalTokensRemaining })
        var dailyUsed = sumTokenCounts(
            listOf(sumTokenCounts(today.map { it.totalTokens }), activeGlobalTokens,
                sumTokenCounts(reservedTasks.map { it.tokensRemaining }), globalReserveTokens)
        )
        val perAgentUsed = today.groupBy { it.agentId.lowercase(Locale.ROOT) }
            .mapValues { entry -> sumTokenCounts(entry.value.map { it.totalTokens }) }
            .toMutableMap()
        reservedTasks.forEach { task ->
            val key = task.agentId.lowercase(Locale.ROOT)
            perAgentUsed[key] = addTokenCounts(perAgentUsed[key] ?: 0L, task.tokensRemaining)
        }
        activeReservations.values.forEach { reservation ->
            if (reservation.globalReserveAgentId.isNotBlank()) {
                val key = reservation.globalReserveAgentId.lowercase(Locale.ROOT)
                perAgentUsed[key] = addTokenCounts(perAgentUsed[key] ?: 0L, reservation.globalTokensRemaining)
            }
        }
        if (globalReserveTokens > 0L && globalReserveAgentId.isNotBlank()) {
            val key = globalReserveAgentId.lowercase(Locale.ROOT)
            perAgentUsed[key] = addTokenCounts(perAgentUsed[key] ?: 0L, globalReserveTokens)
        }
        var monthlyCostUsed = recent.sumOf { it.costPln.coerceAtLeast(0.0) } +
            activeReservations.values.sumOf { r ->
                r.globalReserveCostPln + r.tasks.sumOf { it.costPlnRemaining.coerceAtLeast(0.0) }
            } + globalReserveCostPln
        if (limits.monthlyCostPlnLimit > 0.0 && monthlyCostUsed > limits.monthlyCostPlnLimit) {
            val reason = "Wstrzymano: sama rezerwa kosztowa wraz z dotychczasowym użyciem przekracza miesięczny limit."
            return BatchBudgetPlan(emptySet(), requests.associate { it.taskId to reason }, 0L, 0.0)
        }
        val allowed = linkedSetOf<String>()
        val blocked = linkedMapOf<String, String>()
        var reservedTokens = 0L
        var reservedCost = 0.0
        val newReservationTasks = mutableListOf<ActiveReservationTask>()

        requests.forEach { request ->
            val estimate = Math.addExact(request.estimatedInputTokens, request.estimatedOutputTokens)
            val agentKey = request.agentId.lowercase(Locale.ROOT)
            val agentUsed = perAgentUsed[agentKey] ?: 0L
            val pricing = getPricing(context, request.modelName, request.providerId, request.baseUrl)
            val usd = (request.estimatedInputTokens.coerceAtLeast(0L) * pricing.inputPer1MUsd +
                request.estimatedOutputTokens.coerceAtLeast(0L) * pricing.outputPer1MUsd) / 1_000_000.0
            val exchangeRate = getExchangeRate(context)
            val estimatedPln = usd * exchangeRate
            val reason = when {
                !exchangeRate.isFinite() || exchangeRate <= 0.0 || !estimatedPln.isFinite() || estimatedPln < 0.0 -> "Wstrzymano: nieprawidłowy kurs waluty lub wycena modelu; nie można bezpiecznie obliczyć kosztu."
                exceedsTokenLimit(dailyUsed, estimate, limits.dailyTokenLimit) -> "Pominięto: brak dziennego budżetu tokenów (limit ${limits.dailyTokenLimit})."
                exceedsTokenLimit(agentUsed, estimate, limits.agentTokenLimit) -> "Pominięto: agent przekroczyłby dzienny limit ${limits.agentTokenLimit} tokenów."
                limits.monthlyCostPlnLimit > 0.0 && monthlyCostUsed + estimatedPln > limits.monthlyCostPlnLimit -> "Pominięto: przekroczony zostałby miesięczny budżet ${limits.monthlyCostPlnLimit} PLN."
                else -> null
            }
            if (reason == null) {
                allowed += request.taskId
                dailyUsed = addTokenCounts(dailyUsed, estimate)
                perAgentUsed[agentKey] = addTokenCounts(agentUsed, estimate)
                monthlyCostUsed += estimatedPln
                reservedTokens += estimate
                reservedCost += estimatedPln
                newReservationTasks += ActiveReservationTask(request.taskId, request.agentId, request.providerId, request.baseUrl, request.modelName, estimate, estimatedPln)
            } else {
                blocked[request.taskId] = reason
            }
        }
        var reservationId = UUID.randomUUID().toString()
        while (reservationId.isBlank() || activeReservations.containsKey(reservationId)) {
            reservationId = UUID.randomUUID().toString()
        }
        if (allowed.isNotEmpty() || globalReserveTokens > 0L || globalReserveCostPln > 0.0) {
            activeReservations[reservationId] = ActiveReservation(
                reservationId, newReservationTasks, globalReserveTokens, globalReserveAgentId.trim(), globalReserveCostPln
            )
        } else {
            reservationId = ""
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
        tokens = sumTokenCounts(activeReservations.values.map { r ->
            addTokenCounts(r.globalTokensRemaining, sumTokenCounts(r.tasks.map { it.tokensRemaining }))
        }),
        costPln = activeReservations.values.sumOf { r -> r.globalReserveCostPln + r.tasks.sumOf { it.costPlnRemaining.coerceAtLeast(0.0) } }
    )

    /** A read-only budget snapshot shared by the API monitor and diagnostics screen. */
    @Synchronized
    fun budgetUsageSnapshot(context: Context): BudgetUsageSnapshot {
        val limits = getLimits(context)
        val records = loadAllRecordsFromStorage(context)
        val todayTokens = sumTokenCounts(filterRecords(records, "Dzisiaj").map { it.totalTokens })
        val recentCost = filterRecords(records, "30 dni").sumOf { it.costPln.coerceAtLeast(0.0) }
        val reservations = activeReservationStats()
        val ledgerIsHealthy = readPersistedRecords(context) != null
        val usedIncludingReservations = addTokenCounts(todayTokens, reservations.tokens)
        return BudgetUsageSnapshot(
            recordedTodayTokens = todayTokens,
            activeReservedTokens = reservations.tokens,
            dailyLimitTokens = limits.dailyTokenLimit,
            remainingTokens = if (ledgerIsHealthy) (limits.dailyTokenLimit - usedIncludingReservations).coerceAtLeast(0L) else 0L,
            recorded30DayCostPln = recentCost,
            activeReservedCostPln = reservations.costPln,
            monthlyCostLimitPln = limits.monthlyCostPlnLimit,
            perAgentLimitTokens = limits.agentTokenLimit,
            ledgerHealthy = ledgerIsHealthy
        )
    }

    @Synchronized
    fun getLimits(context: Context): LimitConfig {
        val prefs = context.getSharedPreferences(LIMITS_PREFS, Context.MODE_PRIVATE)
        return LimitConfig(
            dailyTokenLimit = prefs.getLong("daily_token_limit", 100_000L),
            monthlyCostPlnLimit = prefs.getFloat("monthly_cost_limit", 50.0f).toDouble(),
            agentTokenLimit = prefs.getLong("agent_token_limit", 50_000L)
        )
    }

    @Synchronized
    fun setLimits(context: Context, daily: Long, monthly: Double, perAgent: Long) {
        require(monthly.isFinite() && monthly >= 0.0) { "Miesięczny limit kosztów musi być skończony i nieujemny." }
        val monthlyFloat = monthly.toFloat()
        require(monthlyFloat.isFinite()) { "Miesięczny limit kosztów przekracza zakres zapisu ustawień." }
        require(monthly == 0.0 || monthlyFloat > 0.0f) {
            "Dodatni miesięczny limit kosztów jest zbyt mały dla zapisu ustawień; nie może zostać zaokrąglony do zera, które wyłącza limit."
        }
        val prefs = context.getSharedPreferences(LIMITS_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("daily_token_limit", daily.coerceAtLeast(1L))
            // 0 PLN disables the monetary limit; negative values are never accepted.
            .putFloat("monthly_cost_limit", monthlyFloat)
            .putLong("agent_token_limit", perAgent.coerceAtLeast(1L))
            .apply()
    }

    @Synchronized
    fun clearRecords(context: Context): Boolean {
        // Clearing historical usage must not free budget still owned by running workers.
        val committed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("records").commit()
        if (!committed) return false
        sessionRecords.clear()
        return true
    }

    /** Isolated in-memory reset for Robolectric tests; never used by application flows. */
    @Synchronized
    internal fun resetInMemoryStateForTests() {
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
        if (limits.dailyTokenLimit <= 0L || limits.agentTokenLimit <= 0L ||
            !limits.monthlyCostPlnLimit.isFinite() || limits.monthlyCostPlnLimit < 0.0) {
            return LimitCheckResult(false, "Konfiguracja limitów budżetowych jest nieprawidłowa; zapytanie wstrzymano.")
        }
        if (estimatedTokens < 0L) return LimitCheckResult(false, "Szacowana liczba tokenów nie może być ujemna.")
        if (readPersistedRecords(context) == null) {
            return LimitCheckResult(false, "Rejestr użycia API jest uszkodzony lub nieczytelny. Zapytanie wstrzymano, aby nie przekroczyć nieznanego budżetu.")
        }
        val all = loadAllRecordsFromStorage(context)
        val todayRecords = filterRecords(all, "Dzisiaj")
        val todayTokens = addTokenCounts(
            sumTokenCounts(todayRecords.map { it.totalTokens }),
            sumTokenCounts(activeReservations.values.map { r ->
                addTokenCounts(r.globalTokensRemaining, sumTokenCounts(r.tasks.map { it.tokensRemaining }))
            })
        )
        val agentTodayTokens = addTokenCounts(
            sumTokenCounts(todayRecords.filter { it.agentId.equals(agentId, true) }.map { it.totalTokens }),
            sumTokenCounts(activeReservations.values.map { r ->
                addTokenCounts(
                    sumTokenCounts(r.tasks.filter { it.agentId.equals(agentId, true) }.map { it.tokensRemaining }),
                    if (r.globalReserveAgentId.equals(agentId, true)) r.globalTokensRemaining else 0L
                )
            })
        )

        val monthlyRecords = filterRecords(all, "30 dni")
        val monthlyCost = monthlyRecords.sumOf { it.costPln.coerceAtLeast(0.0) } +
            activeReservations.values.sumOf { r ->
                r.globalReserveCostPln + r.tasks.sumOf { it.costPlnRemaining.coerceAtLeast(0.0) }
            }

        if (exceedsTokenLimit(todayTokens, estimatedTokens, limits.dailyTokenLimit)) {
            return LimitCheckResult(false, "Osiągnięto dzienny limit tokenów (${limits.dailyTokenLimit}). Zapytanie zostało wstrzymane dla agenta $agentId.")
        }
        if (limits.monthlyCostPlnLimit > 0.0 && monthlyCost >= limits.monthlyCostPlnLimit) {
            return LimitCheckResult(false, "Osiągnięto miesięczny limit kosztów (${limits.monthlyCostPlnLimit} PLN).")
        }
        if (exceedsTokenLimit(agentTodayTokens, estimatedTokens, limits.agentTokenLimit)) {
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
