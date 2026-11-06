package pl.aimasterpro.app.agents

import pl.aimasterpro.app.core.FreeModelsCatalog
import pl.aimasterpro.app.core.ProviderConfig
import pl.aimasterpro.app.core.TokenUsageResult
import pl.aimasterpro.app.core.WorkMode
import pl.aimasterpro.app.network.ProviderClient
import pl.aimasterpro.app.network.ProviderException
import java.nio.file.Paths
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class TeamTask(
    val id: String,
    val agentId: String,
    val goal: String,
    val prompt: String,
    /** Workspace-relative ownership declaration. Empty means read-only. */
    val writeScope: List<String> = emptyList(),
    /** Original report author when this task is a peer review; blank for ordinary tasks. */
    val reviewOfAgentId: String = ""
)

data class TeamTaskResult(
    val taskId: String,
    val agentId: String,
    val status: Status,
    val report: String,
    val durationMs: Long = 0L,
    val attempts: Int = 0,
    val providerId: String = "",
    val model: String = "",
    /** Original report author when this result is a peer review; blank for ordinary tasks. */
    val reviewOfAgentId: String = ""
) {
    enum class Status { COMPLETED, FAILED, BLOCKED }
}

/**
 * Android-side equivalent of the author's team_run orchestration.
 * It runs independent LLM analysis sessions concurrently using individual model assignments.
 */
class TeamRunEngine(
    private val client: ProviderClient,
    private val maxParallel: Int = 4
) : AutoCloseable {

    fun runBatch(
        tasks: List<TeamTask>,
        provider: ProviderConfig,
        agentProvidersMap: Map<String, ProviderConfig> = emptyMap(),
        fallbackProviders: List<ProviderConfig> = emptyList(),
        isFreeProvider: (ProviderConfig) -> Boolean = { FreeModelsCatalog.isFreeModel(it.id, it.model, it.baseUrl) },
        mode: WorkMode,
        projectContext: String = "",
        attachmentMime: String = "",
        attachmentBase64: String = "",
        onLog: (String, String, String, String) -> Unit = { _, _, _, _ -> },
        onUsage: (String, String, ProviderConfig, TokenUsageResult) -> Unit = { _, _, _, _ -> },
        rankProviders: (List<ProviderConfig>) -> List<ProviderConfig> = { it },
        onProviderHealth: (ProviderConfig, Boolean, String, Long) -> Unit = { _, _, _, _ -> },
        persistedCooldownMs: (ProviderConfig) -> Long = { 0L },
        onRateLimited: (ProviderConfig) -> Long = { 0L },
        onProviderSuccess: (ProviderConfig) -> Unit = { },
        onTaskState: (TeamTask, String, String, String, String, Int) -> Unit = { _, _, _, _, _, _ -> },
        requestScope: ProviderClient.RequestScope? = null
    ): List<TeamTaskResult> {
        validate(tasks, mode)
        if (tasks.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(maxParallel.coerceIn(1, 8).coerceAtMost(tasks.size))
        return try {
            val futures = tasks.map { task ->
                runCatching { onTaskState(task, "QUEUED", "", "", "", 0) }
                pool.submit(Callable {
                    val taskStartedAt = System.currentTimeMillis()
                    var attemptCount = 0
                    var actualProviderId = ""
                    var actualModel = ""
                    val agent = AgentCatalog.find(task.agentId)
                        ?: return@Callable TeamTaskResult(task.id, task.agentId, TeamTaskResult.Status.BLOCKED, "Nieznany agent: ${task.agentId}", System.currentTimeMillis() - taskStartedAt, reviewOfAgentId = task.reviewOfAgentId)

                    runCatching { onTaskState(task, "RUNNING", "", "", "", 0) }
                    val hasExplicitConfig = agentProvidersMap.containsKey(task.agentId) && agentProvidersMap[task.agentId] != null
                    val agentProvider = agentProvidersMap[task.agentId] ?: provider
                    val candidates = listOf(agentProvider) + fallbackProviders.filter { it.id != agentProvider.id }
                    val orderedProviders = rankProviders(candidates.filter(isFreeProvider))

                    if (orderedProviders.isEmpty()) {
                        onLog("SYSTEM", agent.name, "[FREE FAILOVER GUARD] Zablokowano przełączenie na model płatny. Brak darmowych modeli zapasowych.", "BLOCKED")
                        runCatching { onTaskState(task, "BLOCKED", "Brak dostępnego zweryfikowanego darmowego modelu.", "", "", 0) }
                        return@Callable TeamTaskResult(task.id, task.agentId, TeamTaskResult.Status.FAILED, "BRAK DOSTĘPNEGO DARMOWEGO MODELU ZAPASOWEGO. Skonfiguruj darmowy model zapasowy.", System.currentTimeMillis() - taskStartedAt, reviewOfAgentId = task.reviewOfAgentId)
                    }

                    if (!hasExplicitConfig && fallbackProviders.isEmpty() && agentProvidersMap.isEmpty()) {
                        val missingLog = """
                            [CONFIG MISSING]
                            Agent: ${agent.name} (${task.agentId})
                            Diagnostic: CONFIG_MISSING: Brak skonfigurowanego dostawcy dla agenta oraz brak dostawców zapasowych.
                        """.trimIndent()
                        onLog("SYSTEM", agent.name, missingLog, "CONFIG_MISSING")
                        runCatching { onTaskState(task, "BLOCKED", "Brak skonfigurowanego dostawcy.", "", "", 0) }
                        return@Callable TeamTaskResult(task.id, task.agentId, TeamTaskResult.Status.FAILED, "CONFIG_MISSING: Agent nie posiada skonfigurowanego dostawcy.", System.currentTimeMillis() - taskStartedAt, reviewOfAgentId = task.reviewOfAgentId)
                    }

                    if (!hasExplicitConfig) {
                        val missingLog = """
                            [CONFIG MISSING]
                            Agent: ${agent.name} (${task.agentId})
                            Diagnostic: Brak jawnie skonfigurowanego dostawcy. Stosowanie zdefiniowanej polityki zapasowej (Domyślny model: ${provider.name}).
                        """.trimIndent()
                        onLog("SYSTEM", agent.name, missingLog, "CONFIG_WARNING")
                    }

                    val routingLog = """
                        [ROUTING]
                        Agent: ${agent.name} (${task.agentId})
                        Configured provider: ${if (hasExplicitConfig) agentProvider.name else "BRAK (użyto domyślnego)"}
                        Configured model: ${agentProvider.model}
                        Selected provider: ${orderedProviders.firstOrNull()?.name ?: "Brak"}
                        Selected model: ${orderedProviders.firstOrNull()?.model ?: "Brak"}
                    """.trimIndent()
                    onLog("SYSTEM", agent.name, routingLog, "ROUTING")

                    try {
                        val scope = if (task.writeScope.isEmpty()) "Tylko odczyt — nie modyfikuj plików." else task.writeScope.joinToString(", ")
                        val system = buildString {
                            appendLine(agent.systemPrompt)
                            appendLine()
                            appendLine("AKTYWNY TRYB: ${mode.label}")
                            appendLine(mode.systemInstruction)
                            appendLine("ZASADA DOSTĘPU DO DANYCH: korzystasz wyłącznie z treści polecenia, załączników i kontekstu jawnie przekazanego poniżej. Nie zakładaj dostępu do plików, repozytorium, internetu ani narzędzi, których nie otrzymałeś. Jeśli kontekst jest niepełny, wskaż brakujące dane.")
                            if (projectContext.isNotBlank()) appendLine("\nKONTEKST PROJEKTU PRZEKAZANY DO TEGO ZADANIA (może być częściowy):\n$projectContext")
                        }
                        val user = """
                            ID zadania: ${task.id}
                            Cel: ${task.goal}
                            Właściciel zakresu: ${agent.name}
                            Deklarowany zakres zapisu: $scope

                            Zlecenie:
                            ${task.prompt}

                            Zwróć raport do koordynatora. Nie zwracaj się bezpośrednio do użytkownika.
                        """.trimIndent()

                        var report = ""
                        var lastException: Throwable? = null
                        val dnsFailedHosts = mutableSetOf<String>()

                        providerLoop@ for (prov in orderedProviders) {
                            if (Thread.currentThread().isInterrupted) {
                                lastException = InterruptedException("Zadanie agenta zostało przerwane; nie wysyłam kolejnego żądania do API.")
                                break@providerLoop
                            }
                            val providerHost = runCatching { URL(prov.baseUrl).host.lowercase() }.getOrDefault(prov.baseUrl.lowercase())
                            if (providerHost in dnsFailedHosts) {
                                onLog("SYSTEM", agent.name, "Pominięto fallback ${prov.name}: host $providerHost już zgłosił błąd DNS w tym zadaniu.", "FALLBACK_SKIPPED")
                                continue@providerLoop
                            }
                            val quotaKey = pl.aimasterpro.app.core.ProviderQuotaScheduler.key(prov.parentProviderId.ifBlank { prov.id }, prov.baseUrl, prov.model)
                            val persistedWait = runCatching { persistedCooldownMs(prov) }.getOrDefault(0L)
                            if (persistedWait > 0L) {
                                onLog("SYSTEM", agent.name, "[PERSISTENT COOLDOWN] ${prov.name}/${prov.model} po wcześniejszym HTTP 429 pozostaje wstrzymany jeszcze ${persistedWait / 1000}s; pomijam bez żądania API.", "MODEL_COOLDOWN")
                                lastException = ProviderException(429, "Trwa zapisany cooldown dostawcy (${persistedWait / 1000}s).", category = "RATE_LIMIT_COOLDOWN")
                                continue@providerLoop
                            }
                            val gate = pl.aimasterpro.app.core.ProviderQuotaScheduler.acquire(quotaKey, isFreeProvider(prov))
                            val lease = gate.lease
                            if (lease == null) {
                                onProviderHealth(prov, false, gate.reason ?: "MODEL_BUSY", 0L)
                                val reason = "Model ${prov.name}/${prov.model} jest zajęty lub w cooldown (${gate.reason ?: "LIMIT"}, ${gate.waitMs} ms)."
                                onLog("SYSTEM", agent.name, "[SMART SCHEDULER] $reason Pomijam ten profil bez wysyłania kolejnego żądania.", "MODEL_THROTTLED")
                                lastException = if (gate.reason == "INTERRUPTED") InterruptedException(reason)
                                    else ProviderException(429, reason, category = "RATE_LIMIT_COOLDOWN")
                                if (gate.reason == "INTERRUPTED") break@providerLoop
                                continue@providerLoop
                            }
                            var callStartedAt = 0L
                            try {
                                    attemptCount++
                                    actualProviderId = prov.id
                                    actualModel = prov.model
                                    callStartedAt = System.currentTimeMillis()
                                    // Record the exact text payload sent to the provider. Binary attachment
                                    // contents are deliberately excluded; only their MIME/type is recorded.
                                    val requestTrace = buildString {
                                        appendLine("[REQUEST]")
                                        appendLine("TaskId: ${task.id}")
                                        appendLine("TaskType: ${if (task.reviewOfAgentId.isBlank()) "SPECIALIST_ANALYSIS" else "PEER_REVIEW"}")
                                        if (task.reviewOfAgentId.isNotBlank()) appendLine("ReviewOfAgent: ${task.reviewOfAgentId}")
                                        appendLine("Agent: ${agent.name} (${task.agentId})")
                                        appendLine("Provider: ${prov.name}")
                                        appendLine("Model: ${prov.model}")
                                        appendLine("Attempt: $attemptCount/${orderedProviders.size}")
                                        appendLine("Attachment MIME: ${attachmentMime.ifBlank { "brak" }}")
                                        appendLine("Attachment payload: ${if (attachmentBase64.isBlank()) "brak" else "dołączony, treść binarna pominięta w dzienniku"}")
                                        appendLine("--- SYSTEM ---")
                                        appendLine(system)
                                        appendLine("--- USER ---")
                                        append(user)
                                    }
                                    onLog("MASTER", agent.name, requestTrace, "REQUEST")
                                    val chatRes = client.chat(prov, system, user, attachmentMime, attachmentBase64, requestScope)
                                    pl.aimasterpro.app.core.ProviderQuotaScheduler.markSuccess(quotaKey)
                                    runCatching { onProviderSuccess(prov) }
                                    onProviderHealth(prov, true, "", System.currentTimeMillis() - callStartedAt)
                                    // A successful HTTP response can consume tokens even when its text is empty.
                                    // Record usage before validating the report so the budget cannot undercount it.
                                    onUsage(agent.id, task.id, prov, chatRes.usage)
                                    report = chatRes.text
                                    if (report.isNotBlank()) {
                                        val completeLog = """
                                            [AGENT COMPLETE]
                                            Agent: ${agent.name} (${task.agentId})
                                            TaskId: ${task.id}
                                            Status: COMPLETED
                                            Actual provider: ${prov.name}
                                            Actual model: ${prov.model}
                                            HTTP: 200
                                            Input tokens: ${if (chatRes.usage.status == "REAL") chatRes.usage.inputTokens else "BRAK DANYCH"}
                                            Output tokens: ${if (chatRes.usage.status == "REAL") chatRes.usage.outputTokens else "BRAK DANYCH"}
                                        """.trimIndent()
                                        onLog(agent.name, "MASTER", completeLog, "COMPLETE")

                                        if (prov.id != agentProvider.id) {
                                            onLog("SYSTEM", agent.name, "Agent użył profilu ${prov.name}/${prov.model} zamiast przypisanego ${agentProvider.name}/${agentProvider.model} (routing kondycji lub fallback).", "FAILOVER")
                                        }
                                    }
                                } catch (t: Throwable) {
                                    if (t is InterruptedException || Thread.currentThread().isInterrupted) {
                                        Thread.currentThread().interrupt()
                                        lastException = t
                                        onLog("SYSTEM", agent.name, "[CANCELLED] Przerwano zadanie; nie uruchamiam fallbacku, aby nie wysłać kolejnego płatnego żądania.", "CANCELLED")
                                        break@providerLoop
                                    }
                                    val categoryForHealth = if ((t as? ProviderException)?.httpCode == 429) "HTTP_429" else (t as? ProviderException)?.category ?: "UNKNOWN"
                                    onProviderHealth(prov, false, categoryForHealth, if (callStartedAt > 0L) System.currentTimeMillis() - callStartedAt else 0L)
                                    lastException = t
                                    val httpCode = (t as? ProviderException)?.httpCode
                                    val category = (t as? ProviderException)?.category ?: "UNKNOWN"
                                    val isAuthOrClientError = httpCode != null && httpCode in setOf(400, 401, 403, 404)
                                    val isTokenLimit = httpCode == 429 || category == "RATE_LIMIT_QUOTA"
                                    if (httpCode == 429 || category == "RATE_LIMIT_QUOTA") {
                                        val processCooldown = pl.aimasterpro.app.core.ProviderQuotaScheduler.markRateLimited(quotaKey)
                                        val persisted = runCatching { onRateLimited(prov) }.getOrDefault(0L)
                                        val cooldown = maxOf(processCooldown, persisted)
                                        onLog("SYSTEM", agent.name, "[SMART SCHEDULER] ${prov.name}/${prov.model} otrzymał limit 429; cooldown ${cooldown / 1000}s (zapisany między uruchomieniami).", "MODEL_COOLDOWN")
                                    }

                                    if (isAuthOrClientError) {
                                        val nextProv = orderedProviders.getOrNull(orderedProviders.indexOf(prov) + 1)
                                        val failoverLog = """
                                            [FAILOVER]
                                            Agent: ${agent.name}
                                            Previous provider: ${prov.name}
                                            Previous model: ${prov.model}
                                            HTTP: ${httpCode ?: "HTTP_ERROR"}
                                            Reason: Błąd klucza/autoryzacji ($category)
                                            New provider: ${nextProv?.name ?: "Brak"}
                                            New model: ${nextProv?.model ?: "Brak"}
                                        """.trimIndent()
                                        onLog(prov.name, agent.name, failoverLog, "CONFIG_ERROR")
                                        // 401/403/400/404 are configuration or permission errors.
                                        // Never hide them by silently trying another provider.
                                        break@providerLoop
                                    } else if (isTokenLimit) {
                                        val nextProv = orderedProviders.drop(orderedProviders.indexOf(prov) + 1)
                                            .firstOrNull { runCatching { URL(it.baseUrl).host.lowercase() }.getOrDefault(it.baseUrl.lowercase()) !in dnsFailedHosts }
                                        val failoverLog = """
                                            [FAILOVER]
                                            Agent: ${agent.name}
                                            Previous provider: ${prov.name}
                                            Previous model: ${prov.model}
                                            HTTP: ${httpCode ?: "429"}
                                            Reason: Quota / Rate limit ($category)
                                            New provider: ${nextProv?.name ?: "Brak"}
                                            New model: ${nextProv?.model ?: "Brak"}
                                        """.trimIndent()
                                        onLog(prov.name, agent.name, failoverLog, "TOKEN_LIMIT")
                                        // Try the next explicitly supplied free profile, if any.
                                        continue@providerLoop
                                    } else if (category == "DNS_ERROR" || category == "CONNECT_ERROR") {
                                        // A DNS/connect failure is isolated to this agent. Do not mark the
                                        // provider globally unavailable; skip only duplicate profiles for
                                        // the same host in this task and try a distinct configured host.
                                        dnsFailedHosts.add(providerHost)
                                        val nextProv = orderedProviders.drop(orderedProviders.indexOf(prov) + 1)
                                            .firstOrNull { candidate ->
                                                val host = runCatching { URL(candidate.baseUrl).host.lowercase() }.getOrDefault(candidate.baseUrl.lowercase())
                                                host !in dnsFailedHosts
                                            }
                                        onLog(prov.name, agent.name, "[NETWORK FAILOVER] ${category}: ${t.message}. Kolejny niezależny host: ${nextProv?.name ?: "brak"}. Błąd dotyczy tylko tego agenta; konfiguracja dostawcy pozostaje aktywna.", "NETWORK_FAILOVER")
                                        if (nextProv != null) continue@providerLoop
                                        break@providerLoop
                                    } else {
                                        // READ_TIMEOUT may happen after the provider processed the POST.
                                        // Never replay it or silently charge another provider.
                                        onLog(prov.name, agent.name, "[NETWORK STOP] $category: ${t.message}. Bez automatycznego powtórzenia, aby uniknąć podwójnego rozliczenia.", "NETWORK_ERROR")
                                        break@providerLoop
                                    }
                            } finally {
                                lease.close()
                            }
                            if (report.isNotBlank()) break
                        }
                        if (report.isBlank() && lastException != null) throw lastException

                        if (report.isBlank()) {
                            onLog(agent.name, "MASTER", "Agent zwrócił pusty raport.", "FAILED")
                            runCatching { onTaskState(task, "FAILED", "Agent zwrócił pusty raport.", actualProviderId, actualModel, attemptCount) }
                            TeamTaskResult(task.id, task.agentId, TeamTaskResult.Status.FAILED, "Agent zwrócił pusty raport.", System.currentTimeMillis() - taskStartedAt, attemptCount, actualProviderId, actualModel, task.reviewOfAgentId)
                        } else {
                            onLog(agent.name, "MASTER", report, "REPORT")
                            runCatching { onTaskState(task, "COMPLETED", "Raport odebrany.", actualProviderId, actualModel, attemptCount) }
                            TeamTaskResult(task.id, task.agentId, TeamTaskResult.Status.COMPLETED, report, System.currentTimeMillis() - taskStartedAt, attemptCount, actualProviderId, actualModel, task.reviewOfAgentId)
                        }
                    } catch (t: Throwable) {
                        val err = "${t.javaClass.simpleName}: ${t.message ?: "Nieznany błąd"}"
                        onLog(agent.name, "MASTER", err, "ERROR")
                        runCatching { onTaskState(task, if (t is InterruptedException || Thread.currentThread().isInterrupted) "CANCELLED" else "FAILED", err, actualProviderId, actualModel, attemptCount) }
                        TeamTaskResult(task.id, task.agentId, TeamTaskResult.Status.FAILED, err, System.currentTimeMillis() - taskStartedAt, attemptCount, actualProviderId, actualModel, task.reviewOfAgentId)
                    }
                })
            }

            val taskResults = futures.mapIndexed { index, future ->
                try {
                    future.get(90, TimeUnit.SECONDS)
                } catch (t: TimeoutException) {
                    // A timed-out future must not keep running and later issue another fallback request.
                    future.cancel(true)
                    runCatching { onTaskState(tasks[index], "TIMED_OUT", "Przekroczono limit 90 s; zadanie anulowano.", "", "", 0) }
                    TeamTaskResult(tasks[index].id, tasks[index].agentId, TeamTaskResult.Status.FAILED,
                        "Przekroczono limit 90 s dla agenta. Zadanie anulowano; automatyczny fallback nie będzie kontynuowany.", reviewOfAgentId = tasks[index].reviewOfAgentId)
                } catch (t: Throwable) {
                    if (t is InterruptedException) Thread.currentThread().interrupt()
                    TeamTaskResult(tasks[index].id, tasks[index].agentId, TeamTaskResult.Status.FAILED,
                        "Nie odebrano wyniku agenta: ${t.message ?: t.javaClass.simpleName}", reviewOfAgentId = tasks[index].reviewOfAgentId)
                }
            }

            val completedCount = taskResults.count { it.status == TeamTaskResult.Status.COMPLETED }
            val failedCount = taskResults.count { it.status == TeamTaskResult.Status.FAILED }
            val blockedCount = taskResults.count { it.status == TeamTaskResult.Status.BLOCKED }
            val uniqueAgentIds = taskResults.map { it.agentId }.distinct().size
            val duplicates = taskResults.size - uniqueAgentIds

            val aggregationLog = """
                [MASTER AGGREGATION]
                Odebrane raporty: $completedCount/${taskResults.size}
                Brakujące raporty: ${failedCount + blockedCount}
                Duplikaty: $duplicates
                Status syntezy: ${if (completedCount > 0) "SUCCESS" else "FAILED"}
            """.trimIndent()
            onLog("MASTER", "SYSTEM", aggregationLog, "AGGREGATION")

            taskResults
        } finally {
            pool.shutdownNow()
        }
    }

    private fun validate(tasks: List<TeamTask>, mode: WorkMode) {
        require(tasks.isNotEmpty()) { "Batch musi zawierać co najmniej jedno zadanie." }
        require(tasks.map { it.id }.distinct().size == tasks.size) { "Identyfikatory zadań muszą być unikalne." }
        tasks.forEach { task ->
            require(task.id.isNotBlank() && task.agentId.isNotBlank() && task.goal.isNotBlank() && task.prompt.isNotBlank()) {
                "Każde zadanie wymaga id, agenta, celu i promptu."
            }
            require(AgentCatalog.find(task.agentId) != null) { "Nieznany agent: ${task.agentId}" }
            require(task.writeScope.none { it.isBlank() }) { "writeScope nie może zawierać pustych ścieżek." }
            if (!mode.allowsWriteScopes) require(task.writeScope.isEmpty()) {
                "Tryb ${mode.label} dopuszcza wyłącznie zadania tylko do odczytu."
            }
        }
        val owners = mutableListOf<Pair<String, String>>()
        tasks.forEach { task ->
            task.writeScope.forEach { raw ->
                val p = Paths.get(raw.replace('\\', '/'))
                require(!p.isAbsolute) { "writeScope musi być względny: $raw" }
                require(!(raw.length >= 3 && raw[0].isLetter() && raw[1] == ':' && (raw[2] == '/' || raw[2].code == 92))) { "writeScope nie może używać ścieżki bezwzględnej Windows: $raw" }
                val normalized = p.normalize()
                require(!normalized.startsWith("..")) { "writeScope nie może wychodzić poza workspace: $raw" }
                val current = normalized.toString().replace('\\', '/').lowercase().ifBlank { "." }
                owners.forEach { (otherTask, otherPath) ->
                    val overlap = current == otherPath || current == "." || otherPath == "." ||
                        current.startsWith("$otherPath/") || otherPath.startsWith("$current/")
                    require(!overlap) { "Nakładające się writeScope zadań $otherTask i ${task.id}: $raw" }
                }
                owners.add(task.id to current)
            }
        }
    }

    override fun close() { /* Per-batch executors are shut down in runBatch. */ }
}
