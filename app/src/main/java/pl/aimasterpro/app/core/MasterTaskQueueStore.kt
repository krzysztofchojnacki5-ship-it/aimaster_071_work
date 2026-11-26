package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * MASTER-owned durable work queue.
 *
 * Unlike the legacy work journal (which stores plans), this store owns task lifecycle,
 * assignments, dependencies and an append-only transition history. Agent-facing calls
 * are deliberately narrower than coordinator calls.
 */
object MasterTaskQueueStore {
    private const val PREFS = "aiforteca_master_task_queue_v1"
    private const val KEY = "state"
    private const val VERSION = 1
    private const val MAX_TASKS = 120
    private const val MAX_EVENTS = 800
    // Keep the latest run's result for every possible task/assignee in addition to the rolling journal.
    private const val MAX_RESULT_EVENTS = MAX_TASKS * 8
    private const val MAX_STORED_EVENTS = MAX_EVENTS + MAX_RESULT_EVENTS
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{2,119}")
    private val progressStates = setOf(
        "QUEUED", "RUNNING", "REQUESTING", "BLOCKED", "USAGE_RECORDED", "USAGE_PENDING",
        "RESULT_PERSIST_PENDING", "FAILED", "COMPLETED", "TIMED_OUT", "CANCELLED"
    )
    private val conversationStatuses = setOf("MESSAGE", "HANDOFF", "REPORT", "PROGRESS")
    private val traceTypePattern = Regex("[A-Z][A-Z0-9_]{0,39}")
    private val providerEndpointPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")

    enum class ReadStatus { AVAILABLE, CORRUPT, UNAVAILABLE }
    enum class Status { QUEUED, ASSIGNED, RUNNING, BLOCKED, PAUSED, COMPLETED, FAILED, CANCELLED }

    data class Task(
        val id: String,
        val title: String,
        val objective: String,
        val assignedAgentIds: List<String>,
        val dependencies: List<String>,
        val priority: Int,
        val status: Status,
        val currentStep: String,
        val createdAt: Long,
        val updatedAt: Long,
        /** Monotonic execution epoch; retries must not inherit prior agent results. */
        val runId: Int = 0
    )

    data class Event(
        val id: String,
        val taskId: String,
        val actor: String,
        val operation: String,
        val fromStatus: String,
        val toStatus: String,
        val detail: String,
        val timestamp: Long,
        val recipient: String = "",
        val providerId: String = "",
        val model: String = "",
        val runId: Int = 0
    )

    data class Snapshot(
        val status: ReadStatus,
        val tasks: List<Task> = emptyList(),
        val events: List<Event> = emptyList(),
        val errorCode: String = ""
    )

    /**
     * Legacy TeamRunEngine logs were historically persisted as AGENT_MESSAGE.
     * Only explicit communication kinds are shown in the conversation timeline;
     * unknown and diagnostic statuses are conservatively excluded.
     */
    fun isConversationMessage(event: Event): Boolean =
        event.operation == "AGENT_MESSAGE" &&
            event.toStatus in setOf("MESSAGE", "HANDOFF", "REPORT", "PROGRESS")

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            return Snapshot(ReadStatus.UNAVAILABLE, errorCode = "STORAGE_UNCERTAIN")
        }
        return read(context)
    }

    /**
     * Runs a short durable side-effect while holding the same monitor as queue transitions.
     * This makes checking the active run and writing its handoff one atomic operation relative
     * to cancellation, recovery and other coordinator transitions.
     */
    @Synchronized
    fun <T> withActiveRun(context: Context, taskId: String, runId: Int, operation: () -> T): T? {
        if (runId <= 0 || SharedPreferencesWriteHealth.isUncertain(PREFS)) return null
        val current = snapshot(context)
        val task = current.tasks.firstOrNull { it.id == taskId }
        if (current.status != ReadStatus.AVAILABLE || task?.status != Status.RUNNING || task.runId != runId) return null
        return operation()
    }

    /**
     * Runs a short durable side-effect only for the exact interrupted run while it is BLOCKED.
     * Shares the queue monitor with transitions, so a retry cannot race a manual resolution.
     */
    @Synchronized
    fun <T> withBlockedRun(context: Context, taskId: String, runId: Int, operation: () -> T): T? {
        if (runId <= 0 || SharedPreferencesWriteHealth.isUncertain(PREFS)) return null
        val current = snapshot(context)
        val task = current.tasks.firstOrNull { it.id == taskId }
        if (current.status != ReadStatus.AVAILABLE || task?.status != Status.BLOCKED || task.runId != runId) return null
        return operation()
    }

    /** Creates a task in QUEUED state. Task content is bounded and never contains provider secrets. */
    @Synchronized
    fun enqueue(
        context: Context,
        id: String,
        title: String,
        objective: String,
        priority: Int = 2,
        dependencies: List<String> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): Boolean = mutate(context) { tasks, events ->
        if (!id.matches(idPattern) || tasks.any { it.id == id } || tasks.size >= MAX_TASKS ||
            title.isBlank() || title.length > 180 || objective.isBlank() || objective.length > 1200 ||
            priority !in 0..4 || now < 0L || dependencies.size > 20 ||
            dependencies.distinct().size != dependencies.size || id in dependencies ||
            dependencies.any { dep -> tasks.none { it.id == dep && it.status == Status.COMPLETED } }) return@mutate null
        val safeTitle = CommunicationLogSanitizer.sanitize(title.trim()).take(180)
        val safeObjective = CommunicationLogSanitizer.sanitize(objective.trim()).take(1200)
        if (safeTitle.isBlank() || safeObjective.isBlank()) return@mutate null
        val task = Task(id, safeTitle, safeObjective, emptyList(), dependencies, priority,
            Status.QUEUED, "Oczekuje na decyzję MASTER-a.", now, now)
        Triple(tasks + task, events + event(id, "MASTER", "CREATE", "", Status.QUEUED.name,
            "Utworzono zadanie w kolejce.", now), true)
    }

    /** Assignment is a coordinator-only operation; disabled/unknown agents are rejected. */
    @Synchronized
    fun assign(context: Context, taskId: String, agentIds: List<String>, now: Long = System.currentTimeMillis()): Boolean =
        mutate(context) { tasks, events ->
            val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
            if (current.status !in setOf(Status.QUEUED, Status.PAUSED) || agentIds.isEmpty() ||
                agentIds.size > 8 || agentIds.distinct().size != agentIds.size || now < 0L ||
                agentIds.any { !AgentManagerStore.isEnabled(context, it) } ||
                current.dependencies.any { dep -> tasks.none { it.id == dep && it.status == Status.COMPLETED } }) return@mutate null
            val next = current.copy(assignedAgentIds = agentIds, status = Status.ASSIGNED,
                currentStep = "Przydzielono: ${agentIds.joinToString(", ")}", updatedAt = now)
            Triple(tasks.map { if (it.id == taskId) next else it },
                events + event(taskId, "MASTER", "ASSIGN", current.status.name, next.status.name,
                    "Przydzielono ${agentIds.size} agenta(ów).", now), true)
        }

    /**
     * Commits a MASTER planning result only if the exact queued task snapshot is still current.
     * Duplicate objectives and agents already at their active-task limit are rejected inside
     * the same queue transaction, closing the plan/assign race.
     */
    @Synchronized
    fun assignPlanned(
        context: Context,
        taskId: String,
        agentIds: List<String>,
        expectedUpdatedAt: Long,
        expectedRunId: Int,
        maxActiveTasksPerAgent: Int = 1,
        now: Long = System.currentTimeMillis()
    ): Boolean = mutate(context) { tasks, events ->
        val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
        if (current.status !in setOf(Status.QUEUED, Status.PAUSED) ||
            current.updatedAt != expectedUpdatedAt || current.runId != expectedRunId ||
            agentIds.isEmpty() || agentIds.size > 8 || agentIds.distinct().size != agentIds.size ||
            maxActiveTasksPerAgent !in 1..4 || now < 0L ||
            (current.runId > 0 && !agentIds.containsAll(current.assignedAgentIds)) ||
            agentIds.any { !AgentManagerStore.isEnabled(context, it) } ||
            current.dependencies.any { dep -> tasks.none { it.id == dep && it.status == Status.COMPLETED } }) {
            return@mutate null
        }
        val normalizedObjective = current.objective.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        if (normalizedObjective.length >= 40 && tasks.any { other ->
                other.id != taskId &&
                    other.status in setOf(Status.ASSIGNED, Status.RUNNING, Status.BLOCKED) &&
                    other.objective.trim().lowercase(java.util.Locale.ROOT)
                        .replace(Regex("\\s+"), " ") == normalizedObjective
            }) return@mutate null

        val activeLoads = tasks.asSequence()
            .filter { it.id != taskId && it.status in setOf(Status.ASSIGNED, Status.RUNNING) }
            .flatMap { it.assignedAgentIds.asSequence() }
            .groupingBy { it }.eachCount()
        if (agentIds.any { (activeLoads[it] ?: 0) >= maxActiveTasksPerAgent }) return@mutate null

        val next = current.copy(assignedAgentIds = agentIds, status = Status.ASSIGNED,
            currentStep = "MASTER przydzielił: ${agentIds.joinToString(", ")}", updatedAt = now)
        Triple(tasks.map { if (it.id == taskId) next else it },
            events + event(taskId, "MASTER", "ASSIGN_PLANNED", current.status.name, next.status.name,
                "Przydział zatwierdzony na podstawie aktualnego snapshotu; agentów: ${agentIds.size}.", now), true)
    }

    /**
     * Agents may report only their own RUNNING task as completed/failed/blocked.
     * Only MASTER or USER may pause, cancel, unblock or otherwise coordinate lifecycle.
     */
    @Synchronized
    fun transition(
        context: Context,
        taskId: String,
        actor: String,
        target: Status,
        detail: String,
        now: Long = System.currentTimeMillis(),
        expectedRunId: Int? = null,
        expectedCurrentRunId: Int? = null,
        expectedAssignedAgentIds: List<String>? = null
    ): Boolean = mutate(context) { tasks, events ->
        val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
        if (actor.isBlank() || actor.length > 100 || detail.length > 500 || now < 0L) return@mutate null
        // Coordinator actions may carry a run epoch to prevent a delayed UI/lifecycle
        // callback from mutating a later retry of the same task.
        if (actor in setOf("MASTER", "USER") && expectedCurrentRunId != null &&
            current.runId != expectedCurrentRunId) return@mutate null

        // Starting a queued team is conditional on the exact assignment snapshot MASTER planned.
        // This closes the gap between reading ASSIGNED and committing RUNNING after budget/config work.
        if (actor in setOf("MASTER", "USER") && target == Status.RUNNING &&
            (expectedCurrentRunId == null || expectedAssignedAgentIds == null ||
                current.runId != expectedCurrentRunId ||
                current.assignedAgentIds != expectedAssignedAgentIds)) {
            return@mutate null
        }
        val agentId = actor.removePrefix("agent:")
        val isAgent = actor.startsWith("agent:")
        val allowed = if (isAgent) {
            // A single agent may close its own task directly. Multi-agent runs must use
            // recordAgentResult so one early callback cannot close the whole team task.
            current.assignedAgentIds.size == 1 &&
                agentId in current.assignedAgentIds && current.status == Status.RUNNING &&
                expectedRunId != null && expectedRunId == current.runId &&
                target in setOf(Status.COMPLETED, Status.FAILED, Status.BLOCKED)
        } else {
            actor in setOf("MASTER", "USER") && allowedCoordinatorTransition(current.status, target)
        }
        if (!allowed || current.status == target) return@mutate null
        if (target == Status.RUNNING && (current.status != Status.ASSIGNED || current.runId == Int.MAX_VALUE)) return@mutate null
        val nextRunId = if (target == Status.RUNNING) current.runId + 1 else current.runId
        val safeDetail = CommunicationLogSanitizer.sanitize(detail.trim()).take(500)
        val next = current.copy(status = target, currentStep = safeDetail.ifBlank { target.name },
            updatedAt = now, runId = nextRunId)
        Triple(tasks.map { if (it.id == taskId) next else it },
            events + event(taskId, actor, "TRANSITION", current.status.name, target.name, safeDetail, now,
                runId = nextRunId), true)
    }

    /** Records one assigned agent's terminal execution result; the queue task closes only after every assignee reports. */
    @Synchronized
    fun recordAgentResult(
        context: Context,
        taskId: String,
        agentId: String,
        result: Status,
        detail: String,
        now: Long = System.currentTimeMillis(),
        expectedRunId: Int? = null
    ): Boolean = mutate(context) { tasks, events ->
        val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
        if (current.status != Status.RUNNING || (expectedRunId == null || current.runId != expectedRunId) ||
            agentId !in current.assignedAgentIds ||
            result !in setOf(Status.COMPLETED, Status.FAILED, Status.BLOCKED) ||
            detail.length > 500 || now < 0L) return@mutate null
        val alreadyReported = events.any {
            it.taskId == taskId && it.actor == "agent:$agentId" &&
                it.operation == "AGENT_RESULT" && it.runId == current.runId
        }
        if (alreadyReported) return@mutate null
        val activityAt = maxOf(now, current.updatedAt,
            events.asSequence().filter { it.taskId == taskId }.maxOfOrNull { it.timestamp } ?: 0L)
        val nextEvents = events + event(taskId, "agent:$agentId", "AGENT_RESULT", Status.RUNNING.name,
            result.name, detail.trim(), activityAt, runId = current.runId)
        val reports = nextEvents.filter {
            it.taskId == taskId && it.operation == "AGENT_RESULT" && it.runId == current.runId
        }
        val reportedAgents = reports.map { it.actor.removePrefix("agent:") }.toSet()
        val allReported = current.assignedAgentIds.all { it in reportedAgents }
        val finalStatus = if (!allReported) Status.RUNNING else when {
            reports.any { it.toStatus == Status.FAILED.name } -> Status.FAILED
            reports.any { it.toStatus == Status.BLOCKED.name } -> Status.BLOCKED
            else -> Status.COMPLETED
        }
        val step = if (allReported) "Wyniki wszystkich przypisanych agentów zapisane."
            else "Wynik agenta $agentId zapisany (${reportedAgents.size}/${current.assignedAgentIds.size})."
        val next = current.copy(status = finalStatus, currentStep = step, updatedAt = activityAt)
        Triple(tasks.map { if (it.id == taskId) next else it }, nextEvents, true)
    }

    /** Persists live execution progress from an assigned agent without changing the parent RUNNING state. */
    @Synchronized
    fun recordAgentProgress(
        context: Context,
        taskId: String,
        agentId: String,
        state: String,
        detail: String,
        providerId: String = "",
        model: String = "",
        attempts: Int = 0,
        now: Long = System.currentTimeMillis(),
        expectedRunId: Int? = null
    ): Boolean = mutate(context) { tasks, events ->
        val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
        if (current.status != Status.RUNNING || (expectedRunId == null || current.runId != expectedRunId) ||
            agentId !in current.assignedAgentIds ||
            state !in progressStates || detail.length > 500 ||
            providerId.length > 120 || model.length > 180 || attempts !in 0..100 ||
            now < 0L) return@mutate null
        val safeDetail = CommunicationLogSanitizer.sanitize(detail).take(300)
        val safeProvider = CommunicationLogSanitizer.sanitize(providerId).take(80)
        val safeModel = CommunicationLogSanitizer.sanitize(model).take(120)
        val summary = listOf(state, safeDetail, listOf(safeProvider, safeModel).filter { it.isNotBlank() }.joinToString("/"),
            if (attempts > 0) "próba $attempts" else "").filter { it.isNotBlank() }.joinToString(" • ").take(500)
        val activityAt = maxOf(now, current.updatedAt,
            events.asSequence().filter { it.taskId == taskId }.maxOfOrNull { it.timestamp } ?: 0L)
        val next = current.copy(currentStep = summary, updatedAt = activityAt)
        Triple(tasks.map { if (it.id == taskId) next else it },
            events + event(taskId, "agent:$agentId", "AGENT_PROGRESS", Status.RUNNING.name,
                state, summary, activityAt, providerId = safeProvider, model = safeModel, runId = current.runId), true)
    }

    /** Appends sanitized agent-to-agent / agent-to-MASTER communication to the owning task timeline. */
    @Synchronized
    fun recordAgentMessage(
        context: Context,
        taskId: String,
        sender: String,
        recipient: String,
        status: String,
        message: String,
        now: Long = System.currentTimeMillis(),
        providerId: String = "",
        model: String = "",
        expectedRunId: Int? = null
    ): Boolean = mutate(context) { tasks, events ->
        val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
        if (current.status != Status.RUNNING || (expectedRunId == null || current.runId != expectedRunId) ||
            sender.isBlank() || sender.length > 100 ||
            CommunicationLogSanitizer.sanitize(sender) != sender ||
            recipient.isBlank() || recipient.length > 100 || status !in conversationStatuses ||
            message.isBlank() || message.length > 8_000 || now < 0L ||
            providerId.length > 120 || model.length > 180) return@mutate null

        // The event stream is not a free-form log sink: bind both endpoints to this task's
        // assigned team. Built-in and custom agents are resolved from the durable catalog,
        // including disabled agents that were validly assigned before the run started.
        val catalogState = AgentManagerStore.snapshot(context)
        if (catalogState.status != AgentManagerStore.ReadStatus.AVAILABLE) return@mutate null
        val knownAgents = pl.aimasterpro.app.agents.AgentCatalog.all + catalogState.customAgents
        fun resolveAgentId(endpoint: String): String? {
            val token = if (endpoint.startsWith("agent:", ignoreCase = true)) endpoint.substringAfter(':') else endpoint
            return knownAgents.firstOrNull {
                it.id.equals(token, ignoreCase = true) || it.name.equals(token, ignoreCase = true)
            }?.id
        }
        val senderId = resolveAgentId(sender)
        val recipientId = resolveAgentId(recipient)
        val senderAllowed = sender.equals("MASTER", true) || sender.equals("SYSTEM", true) ||
            (senderId != null && senderId in current.assignedAgentIds)
        val recipientAllowed = recipient.equals("MASTER", true) || recipient.equals("SYSTEM", true) ||
            (recipientId != null && recipientId in current.assignedAgentIds)
        if (!senderAllowed || !recipientAllowed ||
            (senderId != null && senderId == recipientId)) return@mutate null

        // Persist stable identities rather than caller-supplied aliases or display-name casing.
        // This keeps the audit trail unambiguous and makes read-time provenance validation exact.
        fun canonicalMessageEndpoint(endpoint: String, agentId: String?): String = when {
            endpoint.equals("MASTER", ignoreCase = true) -> "MASTER"
            endpoint.equals("SYSTEM", ignoreCase = true) -> "SYSTEM"
            agentId != null -> "agent:$agentId"
            else -> endpoint
        }
        val canonicalSender = canonicalMessageEndpoint(sender, senderId)
        val canonicalRecipient = canonicalMessageEndpoint(recipient, recipientId)
        val safe = CommunicationLogSanitizer.sanitize(message).take(350)
        val safeProvider = CommunicationLogSanitizer.sanitize(providerId).take(80)
        val safeModel = CommunicationLogSanitizer.sanitize(model).take(120)
        val detail = "Do: $canonicalRecipient • $status • $safe"
        val activityAt = maxOf(now, current.updatedAt,
            events.asSequence().filter { it.taskId == taskId }.maxOfOrNull { it.timestamp } ?: 0L)
        val next = current.copy(updatedAt = activityAt)
        Triple(tasks.map { if (it.id == taskId) next else it },
            events + event(taskId, canonicalSender, "AGENT_MESSAGE", "", status, detail, activityAt,
                recipient = canonicalRecipient, providerId = safeProvider, model = safeModel, runId = current.runId), true)
    }

    /**
     * Persists TeamRunEngine diagnostics as technical traces, never as agent-to-agent messages.
     * Unknown endpoints are accepted only as explicitly tagged provider endpoints paired with
     * an assigned agent; task/run binding and durable agent-catalog availability remain mandatory.
     */
    @Synchronized
    fun recordAgentTrace(
        context: Context,
        taskId: String,
        source: String,
        target: String,
        traceType: String,
        detail: String,
        now: Long = System.currentTimeMillis(),
        providerId: String = "",
        model: String = "",
        expectedRunId: Int? = null
    ): Boolean = mutate(context) { tasks, events ->
        val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
        if (current.status != Status.RUNNING || (expectedRunId == null || current.runId != expectedRunId) ||
            source.isBlank() || source.length > 140 ||
            target.isBlank() || target.length > 140 ||
            !traceType.matches(traceTypePattern) ||
            CommunicationLogSanitizer.sanitize(source) != source ||
            detail.isBlank() || detail.length > 8_000 || now < 0L ||
            providerId.length > 120 || model.length > 180) return@mutate null

        val catalogState = AgentManagerStore.snapshot(context)
        if (catalogState.status != AgentManagerStore.ReadStatus.AVAILABLE) return@mutate null
        val knownAgents = pl.aimasterpro.app.agents.AgentCatalog.all + catalogState.customAgents
        fun resolveAgentId(endpoint: String): String? {
            val token = if (endpoint.startsWith("agent:", ignoreCase = true)) endpoint.substringAfter(':') else endpoint
            return knownAgents.firstOrNull {
                it.id.equals(token, ignoreCase = true) || it.name.equals(token, ignoreCase = true)
            }?.id
        }
        val sourceId = resolveAgentId(source)
        val targetId = resolveAgentId(target)
        val sourceIsCoordinator = source.equals("MASTER", true) || source.equals("SYSTEM", true)
        val targetIsCoordinator = target.equals("MASTER", true) || target.equals("SYSTEM", true)
        val sourceIsProvider = source.startsWith("provider:", ignoreCase = true)
        val targetIsProvider = target.startsWith("provider:", ignoreCase = true)
        val sourceProviderId = source.substringAfter(':', "").takeIf { sourceIsProvider }
        val targetProviderId = target.substringAfter(':', "").takeIf { targetIsProvider }
        if ((sourceIsProvider && (sourceProviderId == null || !sourceProviderId.matches(providerEndpointPattern))) ||
            (targetIsProvider && (targetProviderId == null || !targetProviderId.matches(providerEndpointPattern)))) return@mutate null

        val sourceAllowed = sourceIsCoordinator ||
            (sourceId != null && sourceId in current.assignedAgentIds) ||
            (sourceIsProvider && targetId != null && targetId in current.assignedAgentIds)
        val targetAllowed = targetIsCoordinator ||
            (targetId != null && targetId in current.assignedAgentIds) ||
            (targetIsProvider && sourceId != null && sourceId in current.assignedAgentIds)
        if (!sourceAllowed || !targetAllowed) return@mutate null

        // Persist only canonical endpoint identities. The resolver deliberately accepts agent
        // IDs and display names case-insensitively; storing the caller's spelling would make
        // the strict read-time provenance check reject an event that this writer just accepted.
        fun canonicalEndpoint(endpoint: String, agentId: String?, isProvider: Boolean): String = when {
            endpoint.equals("MASTER", ignoreCase = true) -> "MASTER"
            endpoint.equals("SYSTEM", ignoreCase = true) -> "SYSTEM"
            agentId != null -> "agent:$agentId"
            isProvider -> "provider:${endpoint.substringAfter(':')}"
            else -> endpoint
        }
        val canonicalSource = canonicalEndpoint(source, sourceId, sourceIsProvider)
        val canonicalTarget = canonicalEndpoint(target, targetId, targetIsProvider)
        val safe = CommunicationLogSanitizer.sanitize(detail).take(350)
        val safeProvider = CommunicationLogSanitizer.sanitize(providerId).take(80)
        val safeModel = CommunicationLogSanitizer.sanitize(model).take(120)
        val summary = "Do: $canonicalTarget • $traceType • $safe"
        val activityAt = maxOf(now, current.updatedAt,
            events.asSequence().filter { it.taskId == taskId }.maxOfOrNull { it.timestamp } ?: 0L)
        val next = current.copy(updatedAt = activityAt)
        Triple(tasks.map { if (it.id == taskId) next else it },
            events + event(taskId, canonicalSource, "AGENT_TRACE", "", traceType, summary, activityAt,
                recipient = canonicalTarget, providerId = safeProvider, model = safeModel, runId = current.runId), true)
    }

    /**
     * Re-enters the exact blocked execution epoch after recovery preflight has proved that
     * completed reports are archived and no pending agent has an ambiguous provider request.
     */
    @Synchronized
    fun resumeInterruptedRun(
        context: Context,
        taskId: String,
        expectedRunId: Int,
        now: Long = System.currentTimeMillis()
    ): Boolean = mutate(context) { tasks, events ->
        val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
        if (current.status != Status.BLOCKED || current.runId <= 0 ||
            current.runId == Int.MAX_VALUE || current.runId != expectedRunId ||
            current.assignedAgentIds.isEmpty() || now < 0L) {
            return@mutate null
        }
        val recoveryAt = maxOf(now, current.updatedAt)
        val nextRunId = current.runId + 1
        val next = current.copy(
            status = Status.RUNNING,
            currentStep = "MASTER wznowił zadanie jako przebieg #$nextRunId; wcześniejsze raporty zostaną zachowane.",
            updatedAt = recoveryAt,
            runId = nextRunId
        )
        Triple(
            tasks.map { if (it.id == taskId) next else it },
            events + event(taskId, "MASTER", "RESUME_INTERRUPTED", Status.BLOCKED.name,
                Status.RUNNING.name, "Wznowiono zadanie w nowym, odgrodzonym przebiegu; zachowano wcześniejsze raporty.", recoveryAt,
                runId = nextRunId),
            true
        )
    }

    /** Moves interrupted RUNNING tasks to BLOCKED after process restart; never auto-replays uncertain API work. */
    @Synchronized
    fun recoverInterruptedRuns(context: Context, now: Long = System.currentTimeMillis()): Int {
        if (now < 0L) return 0
        var recovered = 0
        val saved = mutate(context) { tasks, events ->
            // ASSIGNED is a pre-dispatch state: executeQueuedMasterTask commits RUNNING
            // before TeamRunEngine can send provider requests. A process death in ASSIGNED
            // can therefore be returned to PAUSED without replaying or losing the team.
            val interrupted = tasks.filter {
                it.status == Status.RUNNING || it.status == Status.ASSIGNED
            }
            if (interrupted.isEmpty()) return@mutate null
            val updated = tasks.map { task ->
                when (task.status) {
                    Status.RUNNING -> {
                        recovered++
                        task.copy(status = Status.BLOCKED,
                            currentStep = "Przerwano działanie aplikacji. wynik żądań może być nieznany; sprawdź historię API przed ponownym uruchomieniem.",
                            updatedAt = maxOf(now, task.updatedAt))
                    }
                    Status.ASSIGNED -> {
                        recovered++
                        task.copy(status = Status.PAUSED,
                            currentStep = "Odzyskano przydział bez rozpoczęcia wysyłania żądań API. Zadanie wymaga ponownego uruchomienia przez MASTER-a.",
                            updatedAt = maxOf(now, task.updatedAt))
                    }
                    else -> task
                }
            }
            val recoveryEvents = interrupted.map { task ->
                // Recovery must not make the durable task timeline move backwards when the
                // device clock was corrected or a caller supplied an older timestamp.
                val recoveryAt = maxOf(now, task.updatedAt)
                val target = if (task.status == Status.RUNNING) Status.BLOCKED else Status.PAUSED
                event(task.id, "MASTER_RECOVERY",
                    if (task.status == Status.RUNNING) "RECOVER_INTERRUPTED" else "RECOVER_UNSTARTED_ASSIGNMENT",
                    task.status.name, target.name,
                    if (task.status == Status.RUNNING)
                        "Proces aplikacji zakończył się podczas pracy. Nie wznowiono automatycznie, aby uniknąć duplikowania żądań API."
                    else
                        "Proces aplikacji zakończył się po przydziale, ale przed trwałym rozpoczęciem przebiegu. Przydział zachowano; nie wysłano ponownie żądań API.",
                    recoveryAt, runId = task.runId)
            }
            Triple(updated, events + recoveryEvents, true)
        }
        return if (saved) recovered else 0
    }

    private fun allowedCoordinatorTransition(from: Status, to: Status): Boolean = when (from) {
        Status.QUEUED -> to in setOf(Status.ASSIGNED, Status.CANCELLED, Status.BLOCKED)
        Status.ASSIGNED -> to in setOf(Status.RUNNING, Status.PAUSED, Status.CANCELLED, Status.BLOCKED)
        Status.RUNNING -> to in setOf(Status.PAUSED, Status.BLOCKED, Status.CANCELLED)
        Status.BLOCKED -> to in setOf(Status.QUEUED, Status.PAUSED, Status.CANCELLED)
        Status.PAUSED -> to in setOf(Status.QUEUED, Status.ASSIGNED, Status.CANCELLED)
        Status.COMPLETED, Status.FAILED, Status.CANCELLED -> false
    }

    private fun event(taskId: String, actor: String, operation: String, from: String,
                      to: String, detail: String, now: Long, recipient: String = "",
                      providerId: String = "", model: String = "", runId: Int = 0) =
        Event(UUID.randomUUID().toString(), taskId, actor, operation, from, to,
            CommunicationLogSanitizer.sanitize(detail).take(500), now,
            CommunicationLogSanitizer.sanitize(recipient).take(100),
            CommunicationLogSanitizer.sanitize(providerId).take(80),
            CommunicationLogSanitizer.sanitize(model).take(120), runId)

    private fun mutate(
        context: Context,
        operation: (List<Task>, List<Event>) -> Triple<List<Task>, List<Event>, Boolean>?
    ): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val current = read(context)
                if (current.status != ReadStatus.AVAILABLE) return@withHealthyAccess false
                val changed = operation(current.tasks, current.events) ?: return@withHealthyAccess false
                if (changed.first.size > MAX_TASKS) return@withHealthyAccess false

                // A device clock correction or a delayed callback must not move a task's
                // durable update time or its per-task audit timeline backwards.
                val previousTasks = current.tasks.associateBy { it.id }
                val normalizedTasks = changed.first.map { next ->
                    val previous = previousTasks[next.id]
                    if (previous != null && next.updatedAt < previous.updatedAt) {
                        next.copy(updatedAt = previous.updatedAt)
                    } else next
                }
                val knownEventIds = current.events.mapTo(mutableSetOf()) { it.id }
                val lastEventTime = mutableMapOf<String, Long>()
                current.tasks.forEach { task -> lastEventTime[task.id] = task.updatedAt }
                current.events.forEach { event ->
                    lastEventTime[event.taskId] = maxOf(lastEventTime[event.taskId] ?: 0L, event.timestamp)
                }
                val normalizedEvents = changed.second.map { event ->
                    if (event.id in knownEventIds) event else {
                        val timestamp = maxOf(event.timestamp, lastEventTime[event.taskId] ?: 0L)
                        lastEventTime[event.taskId] = timestamp
                        event.copy(timestamp = timestamp)
                    }
                }
                // A persisted task's activity time must never lag behind its own audit journal,
                // including when recovering older-but-valid version-1 state.
                val latestEventTimeByTask = normalizedEvents.groupBy { it.taskId }
                    .mapValues { (_, rows) -> rows.maxOfOrNull { it.timestamp } ?: 0L }
                val fullyNormalizedTasks = normalizedTasks.map { task ->
                    val latestEventAt = latestEventTimeByTask[task.id] ?: 0L
                    if (task.updatedAt < latestEventAt) task.copy(updatedAt = latestEventAt) else task
                }
                // Event history is a rolling bounded journal; reaching the cap must not freeze the queue.
                write(context, fullyNormalizedTasks, normalizedEvents)
            }
        }.getOrDefault(false)
    }

    private fun read(context: Context): Snapshot {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            return Snapshot(ReadStatus.UNAVAILABLE, errorCode = "STORAGE_UNCERTAIN")
        }
        val raw = try {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            }
        } catch (error: Throwable) {
            return Snapshot(ReadStatus.UNAVAILABLE, errorCode = "STORAGE_READ_FAILED:${error.javaClass.simpleName}")
        } ?: return Snapshot(ReadStatus.AVAILABLE)

        return runCatching {
            require(raw.isNotBlank() && raw.length <= 512_000)
            val root = JSONObject(raw)
            val versionValue = root.opt("version")
            require((versionValue is Int || versionValue is Long) &&
                (versionValue as Number).toLong() == VERSION.toLong()) {
                "Queue version is not an exact supported integer."
            }
            val taskArray = root.getJSONArray("tasks")
            val eventArray = root.getJSONArray("events")
            require(taskArray.length() <= MAX_TASKS && eventArray.length() <= MAX_STORED_EVENTS)
            fun exactLong(row: JSONObject, key: String, default: Long? = null): Long {
                if (!row.has(key)) return default ?: error("Missing exact integer field '$key'.")
                val value = row.opt(key)
                require(value is Int || value is Long) { "Queue field '$key' is not an exact integer." }
                return (value as Number).toLong()
            }
            val tasks = (0 until taskArray.length()).map { i ->
                val o = taskArray.getJSONObject(i)
                val priority = exactLong(o, "priority")
                val createdAt = exactLong(o, "createdAt")
                val updatedAt = exactLong(o, "updatedAt")
                val runId = exactLong(o, "runId", 0L)
                require(priority in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                    runId in 0L..Int.MAX_VALUE.toLong())
                val agents = strings(o.getJSONArray("agents"), 8)
                val deps = strings(o.getJSONArray("dependencies"), 20)
                val task = Task(o.getString("id"), o.getString("title"), o.getString("objective"),
                    agents, deps, priority.toInt(), Status.valueOf(o.getString("status")),
                    o.getString("step"), createdAt, updatedAt, runId.toInt())
                require(valid(task))
                task
            }
            require(tasks.map { it.id }.distinct().size == tasks.size)
            // Dependencies are immutable prerequisites: enqueue accepts only already-completed
            // tasks. Preserve that append-order invariant when validating persisted state.
            val taskIndexById = tasks.mapIndexed { index, task -> task.id to index }.toMap()
            require(tasks.indices.all { index ->
                val task = tasks[index]
                task.dependencies.all { dependency ->
                    val dependencyIndex = taskIndexById[dependency]
                    dependencyIndex != null && dependencyIndex < index &&
                        tasks[dependencyIndex].status == Status.COMPLETED
                }
            }) { "Queue task dependencies are missing, forward-referenced or not completed." }
            val events = (0 until eventArray.length()).map { i ->
                val o = eventArray.getJSONObject(i)
                fun requiredString(key: String): String {
                    val value = o.opt(key)
                    require(value is String) { "Queue event field '$key' is not a string." }
                    return value
                }
                fun optionalString(key: String): String {
                    if (!o.has(key)) return ""
                    val value = o.opt(key)
                    require(value is String) { "Queue event field '$key' is not a string." }
                    return value
                }
                val timestamp = exactLong(o, "timestamp")
                val runId = exactLong(o, "runId", 0L)
                require(runId in 0L..Int.MAX_VALUE.toLong()) {
                    "Queue event run ID is outside the supported range."
                }
                val event = Event(
                    requiredString("id"),
                    requiredString("taskId"),
                    requiredString("actor"),
                    requiredString("operation"),
                    requiredString("from"),
                    requiredString("to"),
                    requiredString("detail"),
                    timestamp,
                    optionalString("recipient"),
                    optionalString("providerId"),
                    optionalString("model"),
                    runId.toInt()
                )
                require(valid(event))
                event
            }
            require(events.map { it.id }.distinct().size == events.size)
            val taskById = tasks.associateBy { it.id }
            require(events.all { event ->
                val owner = taskById[event.taskId]
                owner != null && event.runId <= owner.runId
            }) {
                "Queue event references a missing task or an execution run newer than its task."
            }

            // Persisted events are a trusted audit timeline only when their ordering and
            // agent/run provenance agree with the owning task. Runtime writers enforce these
            // constraints; validate them again on read so edited or partially corrupted JSON
            // cannot manufacture another agent's result or a foreign technical trace.
            val lastTimestampByTask = mutableMapOf<String, Long>()
            val resultActorsByRun = mutableSetOf<String>()
            events.forEach { event ->
                val owner = taskById.getValue(event.taskId)
                val previousTimestamp = lastTimestampByTask[event.taskId] ?: owner.createdAt
                require(event.timestamp >= previousTimestamp) {
                    "Queue event timeline moves backwards for task ${event.taskId}."
                }
                lastTimestampByTask[event.taskId] = event.timestamp

                when (event.operation) {
                    "AGENT_RESULT" -> {
                        val agentId = event.actor.removePrefix("agent:")
                        require(event.actor.startsWith("agent:") &&
                            agentId in owner.assignedAgentIds &&
                            event.toStatus in setOf("COMPLETED", "FAILED", "BLOCKED") &&
                            event.fromStatus == Status.RUNNING.name && event.runId > 0) {
                            "Agent result does not match an assigned agent or terminal state."
                        }
                        require(resultActorsByRun.add("${event.taskId}:${event.runId}:$agentId")) {
                            "Duplicate agent result exists for one task execution."
                        }
                    }
                    "AGENT_PROGRESS" -> {
                        val agentId = event.actor.removePrefix("agent:")
                        require(event.actor.startsWith("agent:") && agentId in owner.assignedAgentIds &&
                            event.toStatus in progressStates && event.fromStatus == Status.RUNNING.name && event.runId > 0) {
                            "Agent progress does not match an assigned agent or supported state."
                        }
                    }
                    "AGENT_MESSAGE" -> {
                        val source = event.actor
                        val target = event.recipient
                        // Accept legacy raw agent IDs on read, but new writes always use agent:<id>.
                        val sourceAgent = source.removePrefix("agent:")
                        val targetAgent = target.removePrefix("agent:")
                        val sourceIsAgent = sourceAgent in owner.assignedAgentIds &&
                            (source == sourceAgent || source.startsWith("agent:"))
                        val targetIsAgent = targetAgent in owner.assignedAgentIds &&
                            (target == targetAgent || target.startsWith("agent:"))
                        val sourceIsCoordinator = source == "MASTER" || source == "SYSTEM"
                        val targetIsCoordinator = target == "MASTER" || target == "SYSTEM"
                        require(event.toStatus in conversationStatuses && event.fromStatus.isEmpty() &&
                            event.runId > 0 && (sourceIsAgent || sourceIsCoordinator) &&
                            (targetIsAgent || targetIsCoordinator) &&
                            !(sourceIsAgent && targetIsAgent && sourceAgent == targetAgent)) {
                            "Agent communication does not match an assigned sender, recipient or message state."
                        }
                    }
                    "AGENT_TRACE" -> {
                        val source = event.actor
                        val target = event.recipient
                        val sourceAgent = source.removePrefix("agent:")
                        val targetAgent = target.removePrefix("agent:")
                        val sourceIsAgent = source.startsWith("agent:") && sourceAgent in owner.assignedAgentIds
                        val targetIsAgent = target.startsWith("agent:") && targetAgent in owner.assignedAgentIds
                        val sourceIsCoordinator = source == "MASTER" || source == "SYSTEM"
                        val targetIsCoordinator = target == "MASTER" || target == "SYSTEM"
                        val sourceIsProvider = source.startsWith("provider:")
                        val targetIsProvider = target.startsWith("provider:")
                        val sourceProviderId = source.substringAfter(":", "").takeIf { sourceIsProvider }
                        val targetProviderId = target.substringAfter(":", "").takeIf { targetIsProvider }
                        val endpointsMatch = (sourceIsAgent || sourceIsCoordinator ||
                            (sourceIsProvider && targetIsAgent)) &&
                            (targetIsAgent || targetIsCoordinator || (targetIsProvider && sourceIsAgent))
                        val traceSchemaValid = traceTypePattern.matches(event.toStatus) &&
                            event.fromStatus.isEmpty() && event.runId > 0 &&
                            (!sourceIsProvider || (sourceProviderId != null && sourceProviderId.matches(providerEndpointPattern))) &&
                            (!targetIsProvider || (targetProviderId != null && targetProviderId.matches(providerEndpointPattern)))
                        require(endpointsMatch && traceSchemaValid) {
                            "Technical trace endpoints do not belong to the assigned team."
                        }
                    }
                }
            }
            Snapshot(ReadStatus.AVAILABLE, tasks, events)
        }.getOrElse {
            Snapshot(ReadStatus.CORRUPT, errorCode = "QUEUE_READ_FAILED:${it.javaClass.simpleName}")
        }
    }

    private fun strings(array: JSONArray, max: Int): List<String> {
        require(array.length() <= max)
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun valid(t: Task): Boolean =
        t.id.matches(idPattern) && CommunicationLogSanitizer.sanitize(t.id) == t.id &&
            t.title.isNotBlank() && t.title.length <= 180 &&
            t.objective.isNotBlank() && t.objective.length <= 1200 &&
            t.assignedAgentIds.size <= 8 &&
            t.assignedAgentIds.all { it.isNotBlank() && it.length <= 100 &&
                CommunicationLogSanitizer.sanitize(it) == it } &&
            t.assignedAgentIds.distinct().size == t.assignedAgentIds.size &&
            t.dependencies.size <= 20 && t.dependencies.all { it.matches(idPattern) } &&
            t.dependencies.distinct().size == t.dependencies.size &&
            t.priority in 0..4 && t.currentStep.length <= 500 &&
            t.createdAt >= 0L && t.updatedAt >= t.createdAt && t.runId >= 0 &&
            CommunicationLogSanitizer.sanitize(t.title) == t.title &&
            CommunicationLogSanitizer.sanitize(t.objective) == t.objective &&
            CommunicationLogSanitizer.sanitize(t.currentStep) == t.currentStep

    private fun valid(event: Event): Boolean =
        event.id.isNotBlank() && event.id.length <= 80 &&
            event.taskId.matches(idPattern) && CommunicationLogSanitizer.sanitize(event.taskId) == event.taskId &&
            event.actor.isNotBlank() && event.actor.length <= 140 &&
            event.operation.isNotBlank() && event.operation.length <= 40 &&
            event.fromStatus.length <= 40 && event.toStatus.length <= 40 &&
            event.detail.length <= 500 && event.timestamp >= 0L &&
            event.recipient.length <= 100 && event.providerId.length <= 80 &&
            event.model.length <= 120 && event.runId >= 0 &&
            CommunicationLogSanitizer.sanitize(event.actor) == event.actor &&
            CommunicationLogSanitizer.sanitize(event.operation) == event.operation &&
            CommunicationLogSanitizer.sanitize(event.fromStatus) == event.fromStatus &&
            CommunicationLogSanitizer.sanitize(event.toStatus) == event.toStatus &&
            CommunicationLogSanitizer.sanitize(event.detail) == event.detail &&
            CommunicationLogSanitizer.sanitize(event.recipient) == event.recipient &&
            CommunicationLogSanitizer.sanitize(event.providerId) == event.providerId &&
            CommunicationLogSanitizer.sanitize(event.model) == event.model

    private fun write(context: Context, tasks: List<Task>, events: List<Event>): Boolean = runCatching {
        val taskArray = JSONArray()
        tasks.forEach { t ->
            taskArray.put(JSONObject().put("id", t.id).put("title", t.title).put("objective", t.objective)
                .put("agents", JSONArray(t.assignedAgentIds)).put("dependencies", JSONArray(t.dependencies))
                .put("priority", t.priority).put("status", t.status.name).put("step", t.currentStep)
                .put("createdAt", t.createdAt).put("updatedAt", t.updatedAt).put("runId", t.runId))
        }
        val eventArray = JSONArray()
        val currentRunResultIds = events.filter { e ->
            e.operation == "AGENT_RESULT" && tasks.any { it.id == e.taskId && it.runId == e.runId }
        }.map { it.id }.toSet()
        val retainedIds = events.filterNot { it.id in currentRunResultIds }.takeLast(MAX_EVENTS)
            .map { it.id }.toSet() + currentRunResultIds
        require(retainedIds.size <= MAX_STORED_EVENTS)
        events.filter { it.id in retainedIds }.forEach { e ->
            eventArray.put(JSONObject().put("id", e.id).put("taskId", e.taskId).put("actor", e.actor)
                .put("operation", e.operation).put("from", e.fromStatus).put("to", e.toStatus)
                .put("detail", e.detail).put("timestamp", e.timestamp)
                .put("recipient", e.recipient).put("providerId", e.providerId).put("model", e.model)
                .put("runId", e.runId))
        }
        val raw = JSONObject().put("version", VERSION).put("tasks", taskArray).put("events", eventArray).toString()
        raw.length <= 512_000 && SharedPreferencesWriteHealth.commit(PREFS,
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw))
    }.getOrDefault(false)
}
