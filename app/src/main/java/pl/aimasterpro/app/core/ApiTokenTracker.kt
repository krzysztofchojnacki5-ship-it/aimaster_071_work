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
    val costPln: Double,
    /** Tokens written to the default (5-minute) prompt cache. */
    val cacheWriteTokens: Long = 0L,
    /** Tokens written to a 1-hour prompt cache; zero for legacy ledger rows. */
    val cacheWrite1hTokens: Long = 0L,
    /** False when the provider did not expose a reasoning-token breakdown. */
    val reasoningTokensKnown: Boolean = false,
    /** False when usage is known but pricing has not been verified. Legacy rows default to true. */
    val costKnown: Boolean = true,
    /** Exact endpoint identity for provider-specific model accounting; blank on legacy rows. */
    val baseUrl: String = ""
)

class UnknownModelPricingException(message: String) : IllegalStateException(message)

data class ModelPricing(
    val modelKey: String, // model name or prefix
    var inputPer1MUsd: Double,
    var outputPer1MUsd: Double,
    val cachedInputPer1MUsd: Double? = null,
    val cacheWriteInputPer1MUsd: Double? = null,
    val internalReasoningPer1MUsd: Double? = null,
    val cacheWriteInput1hPer1MUsd: Double? = null
)

data class CatalogModelPrice(
    val modelName: String,
    val inputPer1MUsd: Double?,
    val outputPer1MUsd: Double?,
    val explicitlyFree: Boolean? = null,
    val cachedInputPer1MUsd: Double? = null,
    val cacheWriteInputPer1MUsd: Double? = null,
    val internalReasoningPer1MUsd: Double? = null,
    val cacheWriteInput1hPer1MUsd: Double? = null
)

data class ModelTokenLimitConfig(
    val providerId: String,
    val baseUrl: String,
    val modelName: String,
    val dailyLimitTokens: Long,
    val warningPercent: Int = 90,
    val enabled: Boolean = true
)

data class ModelTokenUsage(
    val config: ModelTokenLimitConfig?,
    val usedTokens: Long,
    val reservedTokens: Long,
    val limitTokens: Long,
    val remainingTokens: Long,
    val usagePercent: Int,
    val warningReached: Boolean,
    val limitReached: Boolean
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
    private const val MODEL_LIMITS_PREFS = "api_model_token_limits_v1"

    private val sessionRecords = mutableListOf<TokenRecord>()
    // Session means the current app process, not an in-memory list that disappears from the monitor.
    private val sessionStartedAt = System.currentTimeMillis()
    private data class ActiveReservationTask(val taskId: String, val agentId: String, var providerId: String, var baseUrl: String, var modelName: String, val estimatedInputTokens: Long, val estimatedOutputTokens: Long, var tokensRemaining: Long, var costPlnRemaining: Double, var completed: Boolean = false)
    private data class ActiveReservation(val id: String, val tasks: MutableList<ActiveReservationTask>, var globalTokensRemaining: Long, val globalReserveAgentId: String = "", val globalReserveCostPln: Double = 0.0, var releaseRequested: Boolean = false)
    private val activeReservations = linkedMapOf<String, ActiveReservation>()

    private fun modelBudgetKey(providerId: String, baseUrl: String, modelName: String): String =
        listOf(
            providerId.trim().lowercase(Locale.ROOT),
            baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT),
            modelName.trim().lowercase(Locale.ROOT)
        ).joinToString("|")

    private fun readModelLimits(context: Context): Map<String, ModelTokenLimitConfig> {
        val raw = context.getSharedPreferences(MODEL_LIMITS_PREFS, Context.MODE_PRIVATE)
            .getString("limits", null) ?: return emptyMap()
        val array = JSONArray(raw)
        val result = linkedMapOf<String, ModelTokenLimitConfig>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val config = ModelTokenLimitConfig(
                providerId = item.getString("providerId"),
                baseUrl = item.getString("baseUrl"),
                modelName = item.getString("modelName"),
                dailyLimitTokens = item.getLong("dailyLimitTokens"),
                warningPercent = item.optInt("warningPercent", 90),
                enabled = item.optBoolean("enabled", true)
            )
            require(config.providerId.isNotBlank() && config.baseUrl.isNotBlank() &&
                config.modelName.isNotBlank() && config.dailyLimitTokens > 0L &&
                config.warningPercent in 1..99) { "Nieprawidłowa konfiguracja limitu modelu." }
            result[modelBudgetKey(config.providerId, config.baseUrl, config.modelName)] = config
        }
        return result
    }

    @Synchronized
    fun getModelTokenLimit(
        context: Context, providerId: String, baseUrl: String, modelName: String
    ): ModelTokenLimitConfig? = readModelLimits(context)[modelBudgetKey(providerId, baseUrl, modelName)]

    @Synchronized
    fun setModelTokenLimit(context: Context, config: ModelTokenLimitConfig): Boolean {
        require(config.providerId.isNotBlank() && config.providerId.length <= 128)
        require(config.baseUrl.isNotBlank() && config.baseUrl.length <= 2048)
        require(config.modelName.isNotBlank() && config.modelName.length <= 256)
        require(config.dailyLimitTokens > 0L && config.warningPercent in 1..99)
        val all = readModelLimits(context).toMutableMap()
        all[modelBudgetKey(config.providerId, config.baseUrl, config.modelName)] = config
        val array = JSONArray()
        all.values.forEach { item ->
            array.put(JSONObject()
                .put("providerId", item.providerId)
                .put("baseUrl", item.baseUrl)
                .put("modelName", item.modelName)
                .put("dailyLimitTokens", item.dailyLimitTokens)
                .put("warningPercent", item.warningPercent)
                .put("enabled", item.enabled))
        }
        return context.getSharedPreferences(MODEL_LIMITS_PREFS, Context.MODE_PRIVATE)
            .edit().putString("limits", array.toString()).commit()
    }

    @Synchronized
    fun getModelTokenUsage(
        context: Context, providerId: String, baseUrl: String, modelName: String
    ): ModelTokenUsage {
        val config = getModelTokenLimit(context, providerId, baseUrl, modelName)
        val identity = modelBudgetKey(providerId, baseUrl, modelName)
        val records = loadAllRecordsFromStorage(context)
        val used = sumTokenCounts(filterRecords(records, "Dzisiaj").filter {
            modelBudgetKey(it.providerId, it.baseUrl, it.modelName) == identity
        }.map { it.totalTokens })
        val reserved = sumTokenCounts(activeReservations.values.flatMap { it.tasks }.filter {
            modelBudgetKey(it.providerId, it.baseUrl, it.modelName) == identity
        }.map { it.tokensRemaining })
        val limit = config?.takeIf { it.enabled }?.dailyLimitTokens ?: 0L
        val total = addTokenCounts(used, reserved)
        val percent = if (limit > 0L) ((total.toDouble() / limit.toDouble()) * 100.0)
            .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else 0
        return ModelTokenUsage(
            config = config, usedTokens = used, reservedTokens = reserved,
            limitTokens = limit,
            remainingTokens = if (limit > 0L) (limit - total).coerceAtLeast(0L) else 0L,
            usagePercent = percent,
            warningReached = limit > 0L && percent >= (config?.warningPercent ?: 90),
            limitReached = limit > 0L && total >= limit
        )
    }

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
        ModelPricing("claude", 3.00, 15.00)
    )

    private fun calculateUsdCost(
        inputTokens: Long, outputTokens: Long, reasoningTokens: Long, cachedTokens: Long,
        cacheWriteTokens: Long, cacheWrite1hTokens: Long, reasoningTokensKnown: Boolean,
        pricing: ModelPricing
    ): Double {
        val cachedRead = minOf(inputTokens, cachedTokens)
        val cacheWrite = minOf(inputTokens - cachedRead, cacheWriteTokens)
        val cacheWrite1h = minOf(inputTokens - cachedRead - cacheWrite, cacheWrite1hTokens)
        val regularInput = inputTokens - cachedRead - cacheWrite - cacheWrite1h
        val reasoningBreakdownKnown = reasoningTokensKnown || reasoningTokens > 0L
        val reasoning = if (reasoningBreakdownKnown) minOf(outputTokens, reasoningTokens) else 0L
        val visibleOutput = outputTokens - reasoning
        return (regularInput.toDouble() * pricing.inputPer1MUsd +
            cachedRead.toDouble() * (pricing.cachedInputPer1MUsd ?: pricing.inputPer1MUsd) +
            cacheWrite.toDouble() * (pricing.cacheWriteInputPer1MUsd ?: pricing.inputPer1MUsd) +
            cacheWrite1h.toDouble() * (pricing.cacheWriteInput1hPer1MUsd ?: pricing.cacheWriteInputPer1MUsd ?: pricing.inputPer1MUsd) +
            (if (!reasoningBreakdownKnown && pricing.internalReasoningPer1MUsd != null) {
                outputTokens.toDouble() * maxOf(pricing.outputPer1MUsd, pricing.internalReasoningPer1MUsd)
            } else {
                visibleOutput.toDouble() * pricing.outputPer1MUsd +
                    reasoning.toDouble() * (pricing.internalReasoningPer1MUsd ?: pricing.outputPer1MUsd)
            })) / 1_000_000.0
    }

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
        idempotencyKey: String = "",
        cacheWriteTokens: Long = 0L,
        cacheWrite1hTokens: Long = 0L,
        reasoningTokensKnown: Boolean = false,
    ): TokenRecord {
        require(inputTokens >= 0L && outputTokens >= 0L && reasoningTokens >= 0L &&
            reasoningTokens <= outputTokens && cachedTokens >= 0L && cacheWriteTokens >= 0L && cacheWrite1hTokens >= 0L &&
            Math.addExact(Math.addExact(cachedTokens, cacheWriteTokens), cacheWrite1hTokens) <= inputTokens) {
            "Liczby tokenów nie mogą być ujemne ani przekraczać liczby tokenów wejściowych."
        }
        val stableQueryId = idempotencyKey.trim().ifBlank { UUID.randomUUID().toString() }
        val persistedBefore = readPersistedRecords(context)
        val existing = (persistedBefore.orEmpty() + sessionRecords).firstOrNull { it.queryId == stableQueryId }
        if (existing != null) {
            require(existing.providerId == providerId &&
                existing.agentId == agentId && existing.taskId == taskId) {
                "Klucz idempotencji użycia API został ponownie użyty dla innego żądania."
            }
            // An UNAVAILABLE checkpoint knows the requested alias, not necessarily the
            // canonical model selected by a gateway. Permit that one identity refinement
            // only when the same request is upgraded by authoritative REAL usage.
            val lateRealUpgrade = existing.status == "UNAVAILABLE" && status == "REAL"
            val staleUnavailableReplay = existing.status == "REAL" && status == "UNAVAILABLE"
            require(existing.modelName == modelName || lateRealUpgrade || staleUnavailableReplay) {
                "Klucz idempotencji użycia API został ponownie użyty dla innego modelu."
            }
            // A late authoritative response may resolve an earlier UNAVAILABLE estimate for
            // the same POST. Upgrade that one row in place; never append a second charge and
            // never let a later estimate downgrade already-known REAL usage.
            if (existing.status == "UNAVAILABLE" && status == "REAL") {
                val upgradedTotal = Math.addExact(inputTokens, outputTokens)
                val upgradePricing = try {
                    getPricing(context, modelName, providerId, baseUrl)
                } catch (error: UnknownModelPricingException) {
                    val unavailableAccounting = existing.status == "UNAVAILABLE"
                    if (!unavailableAccounting) {
                        require(hasUnknownPriceApproval(context, providerId, baseUrl, modelName)) {
                            "Nieznana cena wymaga osobnej zgody dla tego dostawcy, endpointu i modelu."
                        }
                        require(hasMatchingActiveReservation(
                            reservationId, taskId, agentId, providerId, baseUrl, modelName
                        )) { "Nieznana cena wymaga aktywnej rezerwacji tokenów dla dokładnej tożsamości modelu." }
                        require(getLimits(context).monthlyCostPlnLimit == 0.0) {
                            "Nieznany koszt modelu wymaga wyłączenia limitu finansowego; limit tokenów nadal obowiązuje."
                        }
                    }
                    null
                }
                val upgradeRate = getExchangeRate(context)
                require(upgradeRate.isFinite() && upgradeRate > 0.0) {
                    "Nieprawidłowy kurs USD/PLN; rozliczenie API wstrzymano."
                }
                val upgradeUsd = upgradePricing?.let {
                    calculateUsdCost(inputTokens, outputTokens, reasoningTokens, cachedTokens, cacheWriteTokens, cacheWrite1hTokens, reasoningTokensKnown, it)
                }
                val upgradePln = upgradeUsd?.times(upgradeRate)
                require(upgradeUsd == null || (upgradeUsd.isFinite() && upgradePln!!.isFinite() && upgradeUsd >= 0.0 && upgradePln >= 0.0)) {
                    "Nieprawidłowy koszt użycia API."
                }
                val upgraded = existing.copy(
                    timestamp = System.currentTimeMillis(),
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = upgradedTotal,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                    cacheWriteTokens = cacheWriteTokens,
                    cacheWrite1hTokens = cacheWrite1hTokens,
                    reasoningTokensKnown = reasoningTokensKnown || reasoningTokens > 0L,
                    modelName = modelName,
                    status = "REAL",
                    costUsd = upgradeUsd ?: 0.0,
                    costPln = upgradePln ?: 0.0,
                    costKnown = upgradeUsd != null
                )
                check(saveRecordToStorage(context, upgraded)) {
                    "Nie udało się trwale uzgodnić rzeczywistego usage z wcześniejszym wpisem UNAVAILABLE."
                }
                sessionRecords.removeAll { it.queryId == stableQueryId }
                sessionRecords.add(0, upgraded)
                adjustActiveReservationForUsageUpgrade(existing, upgraded, reservationId, baseUrl)
                return upgraded
            }
            if (persistedBefore == null) {
                throw IllegalStateException("Rejestr użycia API jest uszkodzony; księgowanie idempotentne zostało wstrzymane.")
            }
            if (persistedBefore.none { it.queryId == stableQueryId }) {
                check(saveRecordToStorage(context, existing)) {
                    "Nie udało się trwale odtworzyć istniejącego rekordu użycia API."
                }
            }
            return existing
        }

        val total = Math.addExact(inputTokens, outputTokens)
        val pricing = try {
            getPricing(context, modelName, providerId, baseUrl)
        } catch (error: UnknownModelPricingException) {
            val unavailableAccounting = status == "UNAVAILABLE"
            if (!unavailableAccounting) {
                require(hasUnknownPriceApproval(context, providerId, baseUrl, modelName)) {
                    "Nieznana cena wymaga osobnej zgody dla tego dostawcy, endpointu i modelu."
                }
                require(hasMatchingActiveReservation(
                    reservationId, taskId, agentId, providerId, baseUrl, modelName
                )) { "Nieznana cena wymaga aktywnej rezerwacji tokenów dla dokładnej tożsamości modelu." }
                require(getLimits(context).monthlyCostPlnLimit == 0.0) {
                    "Nieznany koszt modelu wymaga wyłączenia limitu finansowego; limit tokenów nadal obowiązuje."
                }
            }
                    null
        }
        val exchangeRate = getExchangeRate(context)
        require(exchangeRate.isFinite() && exchangeRate > 0.0) { "Nieprawidłowy kurs USD/PLN; rozliczenie API wstrzymano." }
        val usd = pricing?.let { calculateUsdCost(inputTokens, outputTokens, reasoningTokens, cachedTokens, cacheWriteTokens, cacheWrite1hTokens, reasoningTokensKnown, it) }
        val pln = usd?.times(exchangeRate)
        require(usd == null || (usd.isFinite() && pln!!.isFinite() && usd >= 0.0 && pln >= 0.0)) { "Nieprawidłowy koszt użycia API." }

        val record = TokenRecord(
            queryId = stableQueryId,
            timestamp = System.currentTimeMillis(),
            providerId = providerId,
            modelName = modelName,
            baseUrl = baseUrl.trim().trimEnd('/'),
            agentId = agentId,
            taskId = taskId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = total,
            reasoningTokens = reasoningTokens,
            cachedTokens = cachedTokens,
            cacheWriteTokens = cacheWriteTokens,
            cacheWrite1hTokens = cacheWrite1hTokens,
            reasoningTokensKnown = reasoningTokensKnown || reasoningTokens > 0L,
            status = status,
            costUsd = usd ?: 0.0,
            costPln = pln ?: 0.0,
            costKnown = usd != null
        )

        // Keep the usage visible and charged against the in-process reservation even if disk
        // persistence fails. The caller receives an exception and must report the accounting gap.
        val persisted = persistedBefore != null &&
            runCatching { saveRecordToStorage(context, record) }.getOrDefault(false)
        // Keep a billable in-process record even when the persisted ledger is corrupt or a
        // durable write fails. Mark it UNAVAILABLE so diagnostics never imply durable accounting.
        sessionRecords.add(0, if (persisted) record else record.copy(status = "UNAVAILABLE"))
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

    private fun hasMatchingActiveReservation(
        reservationId: String,
        taskId: String,
        agentId: String,
        providerId: String,
        baseUrl: String,
        modelName: String
    ): Boolean {
        if (reservationId.isBlank() || taskId.isBlank() || taskId == "general" ||
            agentId.isBlank() || providerId.isBlank() || baseUrl.isBlank() || modelName.isBlank()) return false
        val reservation = activeReservations[reservationId] ?: return false
        return reservation.tasks.any { task ->
            !task.completed &&
                task.taskId == taskId &&
                task.agentId.equals(agentId, true) &&
                task.providerId.equals(providerId, true) &&
                task.baseUrl.trim().trimEnd('/').equals(baseUrl.trim().trimEnd('/'), true) &&
                task.modelName.equals(modelName, true)
        }
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

    /**
     * Atomically moves an existing task reservation to a fallback provider/model.
     * The global and per-agent token reservations remain unchanged; only the
     * provider-specific model bucket and estimated monetary reservation are retargeted.
     */
    @Synchronized
    fun retargetReservationTask(
        context: Context,
        reservationId: String,
        taskId: String,
        agentId: String,
        providerId: String,
        baseUrl: String,
        modelName: String
    ): Boolean {
        if (reservationId.isBlank() || taskId.isBlank() || agentId.isBlank() ||
            providerId.isBlank() || baseUrl.isBlank() || modelName.isBlank()) return false
        val reservation = activeReservations[reservationId] ?: return false
        val task = reservation.tasks.singleOrNull {
            it.taskId == taskId && it.agentId.equals(agentId, true)
        } ?: return false
        if (task.completed) return false
        val persisted = readPersistedRecords(context) ?: return false
        val limits = getLimits(context)
        val allRecords = (persisted + sessionRecords).distinctBy { it.queryId }
        val today = filterRecords(allRecords, "Dzisiaj")
        val recent = filterRecords(allRecords, "30 dni")
        val targetKey = modelBudgetKey(providerId, baseUrl, modelName)
        val modelLimit = readModelLimits(context)[targetKey]?.takeIf { it.enabled }?.dailyLimitTokens ?: 0L
        val otherReservedForTarget = sumTokenCounts(activeReservations.values.flatMap { it.tasks }.filter {
            !(it === task) && modelBudgetKey(it.providerId, it.baseUrl, it.modelName) == targetKey
        }.map { it.tokensRemaining })
        val recordedForTarget = sumTokenCounts(today.filter {
            modelBudgetKey(it.providerId, it.baseUrl, it.modelName) == targetKey
        }.map { it.totalTokens })
        val targetUsed = addTokenCounts(recordedForTarget, otherReservedForTarget)
        if (modelLimit > 0L && exceedsTokenLimit(targetUsed, task.tokensRemaining, modelLimit)) return false

        if (task.tokensRemaining != addTokenCounts(task.estimatedInputTokens, task.estimatedOutputTokens)) return false
        val pricing = try {
            getPricing(context, modelName, providerId, baseUrl)
        } catch (error: UnknownModelPricingException) {
            if (limits.monthlyCostPlnLimit > 0.0 ||
                !hasUnknownPriceApproval(context, providerId, baseUrl, modelName)) return false
            null
        }
        val targetCost = if (pricing == null) {
            0.0
        } else {
            val inputRate = maxOf(
                pricing.inputPer1MUsd,
                pricing.cachedInputPer1MUsd ?: pricing.inputPer1MUsd,
                pricing.cacheWriteInputPer1MUsd ?: pricing.inputPer1MUsd,
                pricing.cacheWriteInput1hPer1MUsd ?: pricing.cacheWriteInputPer1MUsd ?: pricing.inputPer1MUsd
            )
            val outputRate = maxOf(pricing.outputPer1MUsd, pricing.internalReasoningPer1MUsd ?: pricing.outputPer1MUsd)
            val estimatedUsd = (task.estimatedInputTokens * inputRate.coerceAtLeast(0.0) +
                task.estimatedOutputTokens * outputRate.coerceAtLeast(0.0)) / 1_000_000.0
            val exchangeRate = getExchangeRate(context)
            val calculated = estimatedUsd * exchangeRate
            if (!calculated.isFinite() || calculated < 0.0 || !exchangeRate.isFinite() || exchangeRate <= 0.0) return false
            calculated
        }

        if (limits.monthlyCostPlnLimit > 0.0) {
            if (recent.any { !it.costKnown }) return false
            val otherReservedCosts = activeReservations.values.flatMap { active ->
                listOf(active.globalReserveCostPln) + active.tasks.filter { it !== task }.map { it.costPlnRemaining }
            }
            val committedCosts = recent.filter { it.costKnown }.map { it.costPln }
            val monthlyUsed = trySumNonNegativeCosts(committedCosts + otherReservedCosts) ?: return false
            val projected = tryAddNonNegativeCosts(monthlyUsed, targetCost) ?: return false
            if (projected > limits.monthlyCostPlnLimit) return false
        }

        task.providerId = providerId
        task.baseUrl = baseUrl
        task.modelName = modelName
        task.costPlnRemaining = targetCost
        return true
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
                .put("baseUrl", r.baseUrl)
                .put("agent", r.agentId)
                .put("task", r.taskId)
                .put("in", r.inputTokens)
                .put("out", r.outputTokens)
                .put("tot", r.totalTokens)
                .put("reas", r.reasoningTokens)
                .put("cache", r.cachedTokens)
                .put("cacheWrite", r.cacheWriteTokens)
                .put("cacheWrite1h", r.cacheWrite1hTokens)
                .put("reasKnown", r.reasoningTokensKnown)
                .put("status", r.status)
                .put("usd", r.costUsd)
                .put("pln", r.costPln)
                .put("costKnown", r.costKnown)
            )
        }
        // Usage may represent a billable provider response. Return the commit result so
        // recordQuery can preserve an in-memory accounting fallback and surface the failure.
        return SharedPreferencesWriteHealth.commit(
            PREFS_NAME, prefs.edit().putString("records", arr.toString())
        )
    }

    /**
     * Returns null when the persisted ledger cannot be parsed or validated.
     * Null is intentionally different from an empty ledger so budget enforcement
     * and writes can fail closed instead of undercounting or overwriting usage.
     */
    private fun readPersistedRecords(context: Context): List<TokenRecord>? = runCatching {
        val raw = SharedPreferencesWriteHealth.withHealthyAccess(PREFS_NAME) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("records", null)
        }
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
            // Legacy records predate separate cache-write accounting; treat missing as zero.
            val cacheWrite = if (o.has("cacheWrite")) exactLong("cacheWrite") else 0L
            val cacheWrite1h = if (o.has("cacheWrite1h")) exactLong("cacheWrite1h") else 0L
            val reasoningKnown = if (o.has("reasKnown")) o.opt("reasKnown") as? Boolean ?: throw IllegalArgumentException("Invalid reasoning-known flag.") else false
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
            require(input >= 0L && output >= 0L && total >= 0L && reasoning >= 0L &&
                reasoning <= output && cached >= 0L && cacheWrite >= 0L && cacheWrite1h >= 0L &&
                Math.addExact(Math.addExact(cached, cacheWrite), cacheWrite1h) <= input) { "Usage record has invalid token counts." }
            val expectedTotal = Math.addExact(input, output)
            require(total == expectedTotal) { "Usage record total does not match input plus output token counts." }
            require(costUsd.isFinite() && costPln.isFinite() && costUsd >= 0.0 && costPln >= 0.0) { "Usage record has invalid cost values." }
            list.add(TokenRecord(
                queryId = queryId,
                timestamp = timestamp,
                providerId = providerId,
                modelName = modelName,
                baseUrl = o.optString("baseUrl", ""),
                agentId = agentId,
                taskId = taskId,
                inputTokens = input,
                outputTokens = output,
                totalTokens = total,
                reasoningTokens = reasoning,
                cachedTokens = cached,
                cacheWriteTokens = cacheWrite,
                cacheWrite1hTokens = cacheWrite1h,
                reasoningTokensKnown = reasoningKnown,
                status = status,
                costUsd = costUsd,
                costPln = costPln,
                costKnown = if (o.has("costKnown")) o.opt("costKnown") as? Boolean ?: throw IllegalArgumentException("Invalid cost-known flag.") else true
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

    /**
     * Atomically replaces one provider's catalog prices. One commit per catalog refresh
     * avoids O(n²) preference rewrites when a provider exposes hundreds of models.
     */
    @Synchronized
    fun replaceCatalogPricingForProvider(
        context: Context, providerId: String, baseUrl: String, models: List<CatalogModelPrice>
    ) {
        require(providerId.isNotBlank()) { "Id dostawcy cennika nie może być pusty." }
        require(models.all { it.modelName.isNotBlank() &&
            ((it.inputPer1MUsd == null && it.outputPer1MUsd == null) ||
                (it.inputPer1MUsd != null && it.outputPer1MUsd != null &&
                    it.inputPer1MUsd.isFinite() && it.outputPer1MUsd.isFinite() &&
                    it.inputPer1MUsd >= 0.0 && it.outputPer1MUsd >= 0.0)) &&
            (it.cachedInputPer1MUsd == null || (it.cachedInputPer1MUsd.isFinite() && it.cachedInputPer1MUsd >= 0.0)) &&
            (it.cacheWriteInputPer1MUsd == null || (it.cacheWriteInputPer1MUsd.isFinite() && it.cacheWriteInputPer1MUsd >= 0.0)) &&
            (it.cacheWriteInput1hPer1MUsd == null || (it.cacheWriteInput1hPer1MUsd.isFinite() && it.cacheWriteInput1hPer1MUsd >= 0.0)) &&
            (it.internalReasoningPer1MUsd == null || (it.internalReasoningPer1MUsd.isFinite() && it.internalReasoningPer1MUsd >= 0.0)) &&
            (it.explicitlyFree != true ||
                ((it.inputPer1MUsd == null || it.inputPer1MUsd == 0.0) &&
                    (it.outputPer1MUsd == null || it.outputPer1MUsd == 0.0) &&
                    (it.cachedInputPer1MUsd == null || it.cachedInputPer1MUsd == 0.0) &&
                    (it.cacheWriteInputPer1MUsd == null || it.cacheWriteInputPer1MUsd == 0.0) &&
                    (it.cacheWriteInput1hPer1MUsd == null || it.cacheWriteInput1hPer1MUsd == 0.0) &&
                    (it.internalReasoningPer1MUsd == null || it.internalReasoningPer1MUsd == 0.0)))
        }) { "Katalog zawiera nieprawidłowe ceny modelu." }
        require(models.map { it.modelName }.distinct().size == models.size) {
            "Katalog zawiera zduplikowane identyfikatory modeli."
        }
        val prefs = context.getSharedPreferences(PRICING_PREFS, Context.MODE_PRIVATE)
        // Never turn unreadable persisted pricing into an empty catalog during refresh.
        // Other providers' billing rates must remain intact until the catalog is repaired.
        val rawCatalog = prefs.getString("catalog_model_prices", "[]") ?: "[]"
        val arr = try {
            JSONArray(rawCatalog)
        } catch (error: Exception) {
            throw IllegalStateException("Zapisany katalog cen jest uszkodzony; odświeżenie wstrzymano bez nadpisania danych.", error)
        }
        val normalizedBase = baseUrl.trimEnd('/').lowercase(Locale.ROOT)
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i)
                ?: throw IllegalStateException("Zapisany katalog cen zawiera nieprawidłowy wpis; odświeżenie wstrzymano.")
            val storedProvider = row.opt("providerId")
            val storedModel = row.opt("modelName")
            val storedBase = row.opt("baseUrl")
            require(storedProvider is String && storedProvider.isNotBlank() &&
                storedModel is String && storedModel.isNotBlank() &&
                storedBase is String && storedBase.isNotBlank()) {
                "Zapisany katalog cen zawiera niekompletną tożsamość wpisu."
            }
            val sameProviderCatalog = storedProvider == providerId &&
                storedBase.trimEnd('/').lowercase(Locale.ROOT) == normalizedBase
            if (!sameProviderCatalog) kept.put(row)
        }
        models.forEach { model ->
            kept.put(JSONObject()
                .put("providerId", providerId)
                .put("modelName", model.modelName)
                .put("baseUrl", normalizedBase)
                .put("inputPer1MUsd", model.inputPer1MUsd ?: JSONObject.NULL)
                .put("outputPer1MUsd", model.outputPer1MUsd ?: JSONObject.NULL)
                .put("cachedInputPer1MUsd", model.cachedInputPer1MUsd ?: JSONObject.NULL)
                .put("cacheWriteInputPer1MUsd", model.cacheWriteInputPer1MUsd ?: JSONObject.NULL)
                .put("cacheWriteInput1hPer1MUsd", model.cacheWriteInput1hPer1MUsd ?: JSONObject.NULL)
                .put("internalReasoningPer1MUsd", model.internalReasoningPer1MUsd ?: JSONObject.NULL)
                .put("explicitlyFree", model.explicitlyFree ?: JSONObject.NULL))
        }
        check(prefs.edit().putString("catalog_model_prices", kept.toString()).commit()) {
            "Nie udało się trwale zapisać cennika katalogowego."
        }
    }

    private fun readCatalogRate(row: JSONObject, key: String): Double? {
        if (!row.has(key) || row.isNull(key)) return null
        val value = row.opt(key) as? Number
            ?: throw IllegalStateException("Katalog cen zawiera nieprawidłową wartość stawki: $key.")
        val rate = value.toDouble()
        require(rate.isFinite() && rate >= 0.0) {
            "Katalog cen zawiera ujemną lub nieskończoną stawkę: $key."
        }
        return rate
    }

    @Synchronized
    fun getPricing(context: Context, modelName: String, providerId: String = "", baseUrl: String = ""): ModelPricing {
        val raw = context.getSharedPreferences(PRICING_PREFS, Context.MODE_PRIVATE)
            .getString("catalog_model_prices", "[]") ?: "[]"
        val catalog = try {
            JSONArray(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Zapisany katalog cen jest uszkodzony; bezpieczna wycena została wstrzymana.", error)
        }
        require(catalog.length() <= 10_000) { "Katalog cen przekracza bezpieczny rozmiar." }

        val normalizedBase = baseUrl.trimEnd('/').lowercase(Locale.ROOT)
        var exact: JSONObject? = null
        val baseMatches = mutableListOf<JSONObject>()
        val identities = mutableSetOf<List<String>>()
        for (index in 0 until catalog.length()) {
            val row = catalog.opt(index) as? JSONObject
                ?: throw IllegalStateException("Katalog cen zawiera wpis, który nie jest obiektem.")
            val rowProvider = row.opt("providerId") as? String
            val rowModel = row.opt("modelName") as? String
            val rowBase = row.opt("baseUrl") as? String
            require(!rowProvider.isNullOrBlank() && !rowModel.isNullOrBlank() && !rowBase.isNullOrBlank()) {
                "Katalog cen zawiera niekompletną tożsamość wpisu."
            }
            val rowNormalizedBase = rowBase.trimEnd('/').lowercase(Locale.ROOT)
            require(identities.add(listOf(rowProvider, rowNormalizedBase, rowModel))) {
                "Katalog cen zawiera zduplikowaną tożsamość modelu."
            }
            val freeValue = row.opt("explicitlyFree")
            require(freeValue == null || freeValue === JSONObject.NULL || freeValue is Boolean) {
                "Katalog cen zawiera nieprawidłową flagę bezpłatnego modelu."
            }

            if (rowModel == modelName && rowNormalizedBase == normalizedBase) {
                if (rowProvider == providerId) exact = row
                else if (normalizedBase.isNotBlank()) baseMatches += row
            }
        }

        require(exact != null || baseMatches.size <= 1) {
            "Katalog cen zawiera niejednoznaczne dopasowanie modelu dla adresu bazowego."
        }
        val row = exact ?: baseMatches.singleOrNull()
        if (row != null) {
            val explicitlyFree = row.opt("explicitlyFree") as? Boolean
            val input = readCatalogRate(row, "inputPer1MUsd")
            val output = readCatalogRate(row, "outputPer1MUsd")
            val cached = readCatalogRate(row, "cachedInputPer1MUsd")
            val cacheWrite = readCatalogRate(row, "cacheWriteInputPer1MUsd")
            val cacheWrite1h = readCatalogRate(row, "cacheWriteInput1hPer1MUsd")
            val reasoning = readCatalogRate(row, "internalReasoningPer1MUsd")
            val allRates = listOf(input, output, cached, cacheWrite, cacheWrite1h, reasoning)
            if (explicitlyFree == true && allRates.all { it == null || it == 0.0 }) {
                return ModelPricing(modelName, 0.0, 0.0)
            }
            if (input == null || output == null) {
                throw UnknownModelPricingException("Katalog cen nie zawiera kompletnej stawki wejścia i wyjścia dla modelu $modelName.")
            }
            // A contradictory free flag never erases published paid rates.
            return ModelPricing(modelName, input, output, cached, cacheWrite, reasoning, cacheWrite1h)
        }

        // A verified free/local model must never inherit the generic paid fallback price.
        if (FreeModelsCatalog.isFreeModel(providerId, modelName, baseUrl)) {
            return ModelPricing(modelName, 0.0, 0.0)
        }
        val lower = modelName.lowercase(Locale.ROOT)
        for (pricing in defaultPricing) {
            if (lower.contains(pricing.modelKey)) return pricing.copy()
        }
        return defaultPricing.last().copy()
    }

    private fun hasUnknownPriceApproval(
        context: Context, providerId: String, baseUrl: String, modelName: String
    ): Boolean {
        if (UnknownPriceApprovalStore.isApproved(context, providerId, baseUrl, modelName)) return true
        // Model profiles have their own IDs but inherit catalog identity from the parent provider.
        // Resolve that one unambiguous provider entry before checking the user's exact-scope consent.
        val canonicalProvider = runCatching {
            val catalog = JSONArray(context.getSharedPreferences(PRICING_PREFS, Context.MODE_PRIVATE)
                .getString("catalog_model_prices", "[]") ?: "[]")
            val normalizedBase = baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
            val matches = (0 until catalog.length()).mapNotNull { index ->
                catalog.optJSONObject(index)?.takeIf { row ->
                    row.optString("modelName") == modelName &&
                        row.optString("baseUrl").trim().trimEnd('/').lowercase(Locale.ROOT) == normalizedBase
                }?.optString("providerId")?.takeIf(String::isNotBlank)
            }.distinct()
            matches.singleOrNull()
        }.getOrNull() ?: return false
        return UnknownPriceApprovalStore.isApproved(context, canonicalProvider, baseUrl, modelName)
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

    private fun addNonNegativeCosts(left: Double, right: Double): Double {
        val a = left.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val b = right.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        return if (Double.MAX_VALUE - a < b) Double.MAX_VALUE else a + b
    }

    private fun sumNonNegativeCosts(values: Iterable<Double>): Double =
        values.fold(0.0, ::addNonNegativeCosts)

    /** Exact finite aggregation for admission decisions; saturation is diagnostics-only. */
    private fun tryAddNonNegativeCosts(left: Double, right: Double): Double? {
        val a = left.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val b = right.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        if (a > Double.MAX_VALUE - b) return null
        return a + b
    }

    private fun trySumNonNegativeCosts(values: Iterable<Double>): Double? {
        var total = 0.0
        for (value in values) {
            total = tryAddNonNegativeCosts(total, value) ?: return null
        }
        return total
    }

    fun calculateSummary(records: List<TokenRecord>): SummaryStats = SummaryStats(
        totalQueries = records.size,
        inputTokens = sumTokenCounts(records.map { it.inputTokens }),
        outputTokens = sumTokenCounts(records.map { it.outputTokens }),
        totalTokens = sumTokenCounts(records.map { it.totalTokens }),
        reasoningTokens = sumTokenCounts(records.map { it.reasoningTokens }),
        cachedTokens = sumTokenCounts(records.map { it.cachedTokens }),
        costUsd = sumNonNegativeCosts(records.filter { it.costKnown }.map { it.costUsd }),
        costPln = sumNonNegativeCosts(records.filter { it.costKnown }.map { it.costPln })
    )

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
            val reason = "Wstrzymano: identyfikatory zadań muszą być niepuste i unikalne; provider, tokeny, limity lub rezerwa globalna są nieprawidłowe."
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
        if (limits.monthlyCostPlnLimit > 0.0 && recent.any { !it.costKnown }) {
            val reason = "Wstrzymano: w bieżącym okresie istnieje użycie z nieznanym kosztem; limit finansowy nie może być bezpiecznie zweryfikowany."
            return BatchBudgetPlan(emptySet(), requests.associate { it.taskId to reason }, 0L, 0.0)
        }
        val monthlyCostUsedInitial = trySumNonNegativeCosts(
            recent.filter { it.costKnown }.map { it.costPln } +
                activeReservations.values.flatMap { r ->
                    listOf(r.globalReserveCostPln) + r.tasks.map { it.costPlnRemaining }
                } + listOf(globalReserveCostPln)
        ) ?: run {
            val reason = "Wstrzymano: suma dotychczasowych kosztów i rezerw przekracza zakres bezpiecznej wyceny."
            return BatchBudgetPlan(emptySet(), requests.associate { it.taskId to reason }, 0L, 0.0)
        }
        var monthlyCostUsed = monthlyCostUsedInitial
        if (limits.monthlyCostPlnLimit > 0.0 && monthlyCostUsed > limits.monthlyCostPlnLimit) {
            val reason = "Wstrzymano: sama rezerwa kosztowa wraz z dotychczasowym użyciem przekracza miesięczny limit."
            return BatchBudgetPlan(emptySet(), requests.associate { it.taskId to reason }, 0L, 0.0)
        }
        val allowed = linkedSetOf<String>()
        val blocked = linkedMapOf<String, String>()
        var reservedTokens = 0L
        var reservedCost = 0.0
        val newReservationTasks = mutableListOf<ActiveReservationTask>()
        val modelTokensUsed = mutableMapOf<String, Long>()
        val modelLimits = readModelLimits(context)
        activeReservations.values.flatMap { it.tasks }.forEach { task ->
            val key = modelBudgetKey(task.providerId, task.baseUrl, task.modelName)
            modelTokensUsed[key] = addTokenCounts(modelTokensUsed[key] ?: 0L, task.tokensRemaining)
        }
        all.filter { filterRecords(listOf(it), "Dzisiaj").isNotEmpty() }.forEach { record ->
            val key = modelBudgetKey(record.providerId, record.baseUrl, record.modelName)
            modelTokensUsed[key] = addTokenCounts(modelTokensUsed[key] ?: 0L, record.totalTokens)
        }

        requests.forEach { request ->
            val estimate = Math.addExact(request.estimatedInputTokens, request.estimatedOutputTokens)
            val agentKey = request.agentId.lowercase(Locale.ROOT)
            val agentUsed = perAgentUsed[agentKey] ?: 0L
            val modelKey = modelBudgetKey(request.providerId, request.baseUrl, request.modelName)
            val modelLimit = modelLimits[modelKey]?.takeIf { it.enabled }?.dailyLimitTokens ?: 0L
            val modelUsed = modelTokensUsed[modelKey] ?: 0L
            val pricing = try {
                getPricing(context, request.modelName, request.providerId, request.baseUrl)
            } catch (error: UnknownModelPricingException) {
                if (limits.monthlyCostPlnLimit > 0.0) {
                    blocked[request.taskId] = "Wstrzymano: cena modelu jest nieznana, a miesięczny limit finansowy jest aktywny."
                    return@forEach
                }
                if (!hasUnknownPriceApproval(context, request.providerId, request.baseUrl, request.modelName)) {
                    blocked[request.taskId] = "Wstrzymano: nieznana cena wymaga osobnej zgody dla tego dostawcy, endpointu i modelu."
                    return@forEach
                }
                null
            }
            // Reserve against the most expensive published input path. Automatic/provider
            // caching can turn part of this prompt into cache writes with a premium rate.
            // With no financial cap, token reservations do not depend on the price catalogue.
            val estimatedPln = if (pricing == null) {
                0.0
            } else {
                val conservativeInputRate = maxOf(
                    maxOf(
                        pricing.inputPer1MUsd,
                        maxOf(
                            pricing.cachedInputPer1MUsd ?: pricing.inputPer1MUsd,
                            pricing.cacheWriteInputPer1MUsd ?: pricing.inputPer1MUsd
                        )
                    ),
                    pricing.cacheWriteInput1hPer1MUsd ?: pricing.cacheWriteInputPer1MUsd ?: pricing.inputPer1MUsd
                )
                val conservativeOutputRate = maxOf(
                    pricing.outputPer1MUsd,
                    pricing.internalReasoningPer1MUsd ?: pricing.outputPer1MUsd
                )
                val usd = (request.estimatedInputTokens.coerceAtLeast(0L) * conservativeInputRate +
                    request.estimatedOutputTokens.coerceAtLeast(0L) * conservativeOutputRate) / 1_000_000.0
                val exchangeRate = getExchangeRate(context)
                val value = usd * exchangeRate
                if (!exchangeRate.isFinite() || exchangeRate <= 0.0 || !value.isFinite() || value < 0.0) {
                    blocked[request.taskId] = "Wstrzymano: nieprawidłowy kurs waluty lub wycena modelu."
                    return@forEach
                }
                value
            }
            val nextMonthlyCost = tryAddNonNegativeCosts(monthlyCostUsed, estimatedPln)
            val nextBatchReserveCost = tryAddNonNegativeCosts(reservedCost, estimatedPln)
            val reason = when {
                nextMonthlyCost == null || nextBatchReserveCost == null -> "Pominięto: łączna wycena zadań i rezerw przekroczyłaby zakres bezpiecznej reprezentacji kosztu."
                exceedsTokenLimit(dailyUsed, estimate, limits.dailyTokenLimit) -> "Pominięto: brak dziennego budżetu tokenów."
                exceedsTokenLimit(agentUsed, estimate, limits.agentTokenLimit) -> "Pominięto: agent przekroczyłby dzienny limit tokenów."
                modelLimit > 0L && exceedsTokenLimit(modelUsed, estimate, modelLimit) -> "Pominięto: model przekroczyłby własny dzienny limit tokenów."
                limits.monthlyCostPlnLimit > 0.0 && nextMonthlyCost > limits.monthlyCostPlnLimit -> "Pominięto: przekroczony zostałby miesięczny budżet."
                else -> null
            }
            if (reason == null) {
                allowed += request.taskId
                dailyUsed = addTokenCounts(dailyUsed, estimate)
                perAgentUsed[agentKey] = addTokenCounts(agentUsed, estimate)
                modelTokensUsed[modelKey] = addTokenCounts(modelUsed, estimate)
                monthlyCostUsed = nextMonthlyCost!!
                reservedTokens += estimate
                reservedCost = nextBatchReserveCost!!
                newReservationTasks += ActiveReservationTask(request.taskId, request.agentId, request.providerId, request.baseUrl, request.modelName, request.estimatedInputTokens, request.estimatedOutputTokens, estimate, estimatedPln)
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
        costPln = sumNonNegativeCosts(activeReservations.values.flatMap { r ->
            listOf(r.globalReserveCostPln) + r.tasks.map { it.costPlnRemaining }
        })
    )

    /** A read-only budget snapshot shared by the API monitor and diagnostics screen. */
    @Synchronized
    fun budgetUsageSnapshot(context: Context): BudgetUsageSnapshot {
        val limits = getLimits(context)
        val records = loadAllRecordsFromStorage(context)
        val todayTokens = sumTokenCounts(filterRecords(records, "Dzisiaj").map { it.totalTokens })
        val recentCost = sumNonNegativeCosts(filterRecords(records, "30 dni").map { it.costPln })
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
        val committed = runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            SharedPreferencesWriteHealth.commit(PREFS_NAME, prefs.edit().remove("records"))
        }.getOrDefault(false)
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
        val monthlyCost = sumNonNegativeCosts(
            monthlyRecords.map { it.costPln } + activeReservations.values.flatMap { r ->
                listOf(r.globalReserveCostPln) + r.tasks.map { it.costPlnRemaining }
            }
        )

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
