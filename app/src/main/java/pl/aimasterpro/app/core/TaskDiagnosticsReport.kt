package pl.aimasterpro.app.core

/**
 * Builds a single, task-scoped diagnostic artifact for copy/share.
 *
 * Unlike the compact event feed, this report intentionally includes sanitized agent reports
 * and handoff payloads so a reviewer can reconstruct one execution without stitching cards.
 * Source-read failures and missing reports are explicit; they are never represented as success.
 */
object TaskDiagnosticsReport {
    data class AgentReport(
        val agentId: String,
        val providerId: String,
        val model: String,
        val createdAt: Long,
        val content: String
    )

    data class Result(val text: String, val isComplete: Boolean, val missing: List<String>)

    private const val MAX_REPORT_CHARS = 180_000
    private const val MAX_EVENTS = 240
    private const val MAX_HANDOFFS = 80
    private const val MAX_AGENT_REPORT_CHARS = 32_000

    fun build(
        task: MasterTaskQueueStore.Task?,
        queue: MasterTaskQueueStore.Snapshot,
        handoffs: AgentHandoffStore.Snapshot,
        reports: List<AgentReport>,
        reportArchiveReadable: Boolean,
        generatedAt: Long = System.currentTimeMillis()
    ): Result {
        val missing = mutableListOf<String>()
        if (queue.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) missing += "DZIENNIK KOLEJKI: ${queue.status}"
        if (handoffs.status != AgentHandoffStore.ReadStatus.AVAILABLE) missing += "SKRZYNKA PRZEKAZAŃ: ${handoffs.status}"
        if (!reportArchiveReadable) missing += "ARCHIWUM RAPORTÓW: niedostępne lub uszkodzone"
        if (task == null) missing += "ZADANIE: nie znaleziono w czytelnym dzienniku"

        val events = if (queue.status == MasterTaskQueueStore.ReadStatus.AVAILABLE && task != null) {
            queue.events.filter { it.taskId == task.id }.sortedWith(
                compareBy<MasterTaskQueueStore.Event> { it.timestamp }.thenBy { it.id }
            )
        } else emptyList()
        val taskHandoffs = if (handoffs.status == AgentHandoffStore.ReadStatus.AVAILABLE && task != null) {
            handoffs.messages.filter { it.taskId == task.id }.sortedWith(
                compareBy<AgentHandoffStore.Handoff> { it.createdAt }.thenBy { it.id }
            )
        } else emptyList()

        if (task != null && reportArchiveReadable) {
            val present = reports.map { it.agentId }.toSet()
            task.assignedAgentIds.filter { it !in present }.forEach {
                missing += "BRAK RAPORTU AGENTA: $it (przebieg #${task.runId})"
            }
        }

        val output = buildString {
            appendLine("AIFORTECA — PEŁNY KONTEKST DIAGNOSTYCZNY ZADANIA")
            appendLine("Wygenerowano: $generatedAt")
            appendLine("Status kompletności: ${if (missing.isEmpty()) "KOMPLETNY W ZAKRESIE DOSTĘPNYCH ŹRÓDEŁ" else "NIEKOMPLETNY"}")
            appendLine()
            appendLine("[ZADANIE]")
            if (task == null) {
                appendLine("Dane zadania niedostępne.")
            } else {
                appendLine("ID: ${safe(task.id, 200)}")
                appendLine("Tytuł: ${safe(task.title, 500)}")
                appendLine("Status: ${task.status}")
                appendLine("Przebieg: #${task.runId}")
                appendLine("Utworzono: ${task.createdAt} • Aktualizacja: ${task.updatedAt}")
                appendLine("Priorytet: ${task.priority}")
                appendLine("Agenci: ${task.assignedAgentIds.joinToString(", ")}")
                appendLine("Zależności: ${task.dependencies.joinToString(", ").ifBlank { "brak" }}")
                appendLine("Bieżący krok: ${safe(task.currentStep, 1000)}")
                appendLine("Cel:")
                appendLine(safe(task.objective, 4_000))
            }
            appendLine()
            appendLine("[ZDARZENIA — ${events.size}]")
            if (queue.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) {
                appendLine("NIE ODCZYTANO: ${queue.status}; zdarzeń nie można uznać za brakujące/puste.")
            } else if (events.isEmpty()) appendLine("Brak zapisanych zdarzeń dla tego zadania.")
            events.takeLast(MAX_EVENTS).forEach { event ->
                appendLine("${event.timestamp} | run #${event.runId} | ${event.operation} | ${safe(event.actor, 160)} → ${safe(event.recipient, 160)}")
                val route = listOf(event.providerId, event.model).filter { it.isNotBlank() }.joinToString("/")
                if (route.isNotBlank()) appendLine("Dostawca/model: ${safe(route, 300)}")
                appendLine("Stan: ${event.fromStatus} → ${event.toStatus}")
                if (event.detail.isNotBlank()) appendLine("Szczegóły: ${safe(event.detail, 1_000)}")
            }
            if (events.size > MAX_EVENTS) appendLine("UWAGA: pokazano ostatnie $MAX_EVENTS z ${events.size} zdarzeń.")
            appendLine()
            appendLine("[PRZEKAZANIA AGENT-AGENT — ${taskHandoffs.size}]")
            if (handoffs.status != AgentHandoffStore.ReadStatus.AVAILABLE) {
                appendLine("NIE ODCZYTANO: ${handoffs.status}; skrzynka nie jest traktowana jako pusta.")
            } else if (taskHandoffs.isEmpty()) appendLine("Brak zapisanych przekazań dla tego zadania.")
            taskHandoffs.takeLast(MAX_HANDOFFS).forEach { item ->
                appendLine("ID: ${item.id} | run #${item.runId} | ${item.state}")
                appendLine("Od: ${item.senderAgentId} → Do: ${item.recipientAgentId}")
                appendLine("Utworzono: ${item.createdAt} • Aktualizacja: ${item.updatedAt} • Przejęto: ${item.claimedAt}")
                if (item.resolutionReason.isNotBlank()) appendLine("Powód rozstrzygnięcia: ${safe(item.resolutionReason, 500)}")
                appendLine("Treść przekazania (po redakcji sekretów):")
                appendLine(safe(item.payload, 8_000))
            }
            if (taskHandoffs.size > MAX_HANDOFFS) appendLine("UWAGA: pokazano ostatnie $MAX_HANDOFFS z ${taskHandoffs.size} przekazań.")
            appendLine()
            appendLine("[PEŁNE RAPORTY AGENTÓW — ${reports.size}]")
            if (!reportArchiveReadable) appendLine("Archiwum raportów jest niedostępne; brak raportów nie oznacza pustych wyników.")
            else if (reports.isEmpty()) appendLine("Nie znaleziono zapisanych raportów agentów dla bieżącego przebiegu.")
            reports.forEach { report ->
                appendLine("--- ${safe(report.agentId, 160)} | ${safe(report.providerId, 200)}/${safe(report.model, 240)} | ${report.createdAt} ---")
                appendLine(safe(report.content, MAX_AGENT_REPORT_CHARS))
            }
            appendLine()
            appendLine("[BRAKI I OGRANICZENIA]")
            if (missing.isEmpty()) appendLine("Nie wykryto braków w źródłach przekazanych do raportu.")
            else missing.forEach { appendLine("- ${safe(it, 500)}") }
            appendLine()
            appendLine("Prywatność: raport zawiera treść celu, zdarzeń, przekazań i raportów agentów. Typowe sekrety API są redagowane. Sprawdź treść przed udostępnieniem.")
        }.let { raw ->
            if (raw.length <= MAX_REPORT_CHARS) raw
            else raw.take(MAX_REPORT_CHARS - 120) + "\n\n[OBCIĘTO: raport przekroczył limit ${MAX_REPORT_CHARS} znaków.]"
        }

        return Result(output, missing.isEmpty(), missing.toList())
    }

    private fun safe(value: String, max: Int): String =
        CommunicationLogSanitizer.sanitize(value).take(max)
}
