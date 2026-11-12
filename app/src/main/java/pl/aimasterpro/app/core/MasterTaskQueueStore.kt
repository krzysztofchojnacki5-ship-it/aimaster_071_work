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

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            return Snapshot(ReadStatus.UNAVAILABLE, errorCode = "STORAGE_UNCERTAIN")
        }
        return read(context)
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
        now: Long = System.currentTimeMillis()
    ): Boolean = mutate(context) { tasks, events ->
        val current = tasks.firstOrNull { it.id == taskId } ?: return@mutate null
        if (actor.isBlank() || actor.length > 100 || detail.length > 500 || now < 0L) return@mutate null
        val agentId = actor.removePrefix("agent:")
        val isAgent = actor.startsWith("agent:")
        val allowed = if (isAgent) {
            agentId in current.assignedAgentIds && current.status == Status.RUNNING &&
                target in setOf(Status.COMPLETED, Status.FAILED, Status.BLOCKED)
        } else {
            actor in setOf("MASTER", "USER") && allowedCoordinatorTransition(current.status, target)
        }
        if (!allowed || current.status == target) return@mutate null
        if (target == Status.RUNNING && (current.status != Status.ASSIGNED || current.runId == Int.MAX_VALUE)) return@mutate null
        val nextRunId = if (target == Status.RUNNING) current.runId + 1 else current.runId
        val next = current.copy(status = target, currentStep = detail.trim().ifBlank { target.name },
            updatedAt = now, runId = nextRunId)
        Triple(tasks.map { if (it.id == taskId) next else it },
            events + event(taskId, actor, "TRANSITION", current.status.name, target.name, detail.trim(), now,
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
        if (current.status != Status.RUNNING || (expectedRunId != null && current.runId != expectedRunId) ||
            agentId !in current.assignedAgentIds ||
            result !in setOf(Status.COMPLETED, Status.FAILED, Status.BLOCKED) ||
            detail.length > 500 || now < 0L) return@mutate null
        val alreadyReported = events.any {
            it.taskId == taskId && it.actor == "agent:$agentId" &&
                it.operation == "AGENT_RESULT" && it.runId == current.runId
        }
        if (alreadyReported) return@mutate null
        val nextEvents = events + event(taskId, "agent:$agentId", "AGENT_RESULT", Status.RUNNING.name,
            result.name, detail.trim(), now, runId = current.runId)
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
        val next = current.copy(status = finalStatus, currentStep = step, updatedAt = now)
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
        if (current.status != Status.RUNNING || (expectedRunId != null && current.runId != expectedRunId) ||
            agentId !in current.assignedAgentIds ||
            state.isBlank() || state.length > 40 || detail.length > 500 ||
            providerId.length > 120 || model.length > 180 || attempts !in 0..100 ||
            now < 0L) return@mutate null
        val safeDetail = CommunicationLogSanitizer.sanitize(detail).take(300)
        val safeProvider = CommunicationLogSanitizer.sanitize(providerId).take(80)
        val safeModel = CommunicationLogSanitizer.sanitize(model).take(120)
        val summary = listOf(state, safeDetail, listOf(safeProvider, safeModel).filter { it.isNotBlank() }.joinToString("/"),
            if (attempts > 0) "próba $attempts" else "").filter { it.isNotBlank() }.joinToString(" • ").take(500)
        val next = current.copy(currentStep = summary, updatedAt = maxOf(now, current.updatedAt))
        Triple(tasks.map { if (it.id == taskId) next else it },
            events + event(taskId, "agent:$agentId", "AGENT_PROGRESS", Status.RUNNING.name,
                state, summary, now, providerId = safeProvider, model = safeModel, runId = current.runId), true)
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
        if (current.status != Status.RUNNING || (expectedRunId != null && current.runId != expectedRunId) ||
            sender.isBlank() || sender.length > 100 ||
            recipient.isBlank() || recipient.length > 100 || status.length > 30 ||
            message.isBlank() || message.length > 8_000 || now < 0L ||
            providerId.length > 120 || model.length > 180) return@mutate null

        // The event stream is not a free-form log sink: bind both endpoints to this task's
        // assigned team. Built-in and custom agents are resolved from the durable catalog,
        // including disabled agents that were validly assigned before the run started.
        val catalogState = AgentManagerStore.snapshot(context)
        if (catalogState.status != AgentManagerStore.ReadStatus.AVAILABLE) return@mutate null
        val knownAgents = pl.aimasterpro.app.agents.AgentCatalog.all + catalogState.customAgents
        fun resolveAgentId(endpoint: String): String? {
            val token = endpoint.removePrefix("agent:")
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
        if (!senderAllowed || !recipientAllowed) return@mutate null

        val safe = CommunicationLogSanitizer.sanitize(message).take(350)
        val detail = "Do: $recipient • $status • $safe"
        Triple(tasks, events + event(taskId, sender, "AGENT_MESSAGE", "", status, detail, now,
            recipient = recipient, providerId = providerId, model = model, runId = current.runId), true)
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
        if (current.status != Status.RUNNING || (expectedRunId != null && current.runId != expectedRunId) ||
            source.isBlank() || source.length > 140 ||
            target.isBlank() || target.length > 140 ||
            traceType.isBlank() || traceType.length > 40 ||
            detail.isBlank() || detail.length > 8_000 || now < 0L ||
            providerId.length > 120 || model.length > 180) return@mutate null

        val catalogState = AgentManagerStore.snapshot(context)
        if (catalogState.status != AgentManagerStore.ReadStatus.AVAILABLE) return@mutate null
        val knownAgents = pl.aimasterpro.app.agents.AgentCatalog.all + catalogState.customAgents
        fun resolveAgentId(endpoint: String): String? {
            val token = endpoint.removePrefix("agent:")
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
        val sourceAllowed = sourceIsCoordinator ||
            (sourceId != null && sourceId in current.assignedAgentIds) ||
            (sourceIsProvider && targetId != null && targetId in current.assignedAgentIds)
        val targetAllowed = targetIsCoordinator ||
            (targetId != null && targetId in current.assignedAgentIds) ||
            (targetIsProvider && sourceId != null && sourceId in current.assignedAgentIds)
        if (!sourceAllowed || !targetAllowed) return@mutate null

        val safe = CommunicationLogSanitizer.sanitize(detail).take(350)
        val summary = "Do: $target • $traceType • $safe"
        Triple(tasks, events + event(taskId, source, "AGENT_TRACE", "", traceType, summary, now,
            recipient = target, providerId = providerId, model = model, runId = current.runId), true)
    }

    /** Moves interrupted RUNNING tasks to BLOCKED after process restart; never auto-replays uncertain API work. */
    @Synchronized
    fun recoverInterruptedRuns(context: Context, now: Long = System.currentTimeMillis()): Int {
        if (now < 0L) return 0
        var recovered = 0
        val saved = mutate(context) { tasks, events ->
            val running = tasks.filter { it.status == Status.RUNNING }
            if (running.isEmpty()) return@mutate null
            val updated = tasks.map { task ->
                if (task.status != Status.RUNNING) task else {
                    recovered++
                    task.copy(status = Status.BLOCKED,
                        currentStep = "Przerwano działanie aplikacji. wynik żądań może być nieznany; sprawdź historię API przed ponownym uruchomieniem.",
                        updatedAt = maxOf(now, task.updatedAt))
                }
            }
            val recoveryEvents = running.map { task ->
                event(task.id, "MASTER_RECOVERY", "RECOVER_INTERRUPTED", Status.RUNNING.name,
                    Status.BLOCKED.name,
                    "Proces aplikacji zakończył się podczas pracy. Nie wznowiono automatycznie, aby uniknąć duplikowania żądań API.", now)
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
                // Event history is a rolling bounded journal; reaching the cap must not freeze the queue.
                write(context, changed.first, changed.second)
            }
        }.getOrDefault(false)
    }

    private fun read(context: Context): Snapshot = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return@withHealthyAccess Snapshot(ReadStatus.AVAILABLE)
            require(raw.isNotBlank() && raw.length <= 512_000)
            val root = JSONObject(raw)
            require(root.optInt("version", -1) == VERSION)
            val taskArray = root.getJSONArray("tasks")
            val eventArray = root.getJSONArray("events")
            require(taskArray.length() <= MAX_TASKS && eventArray.length() <= MAX_STORED_EVENTS)
            val tasks = (0 until taskArray.length()).map { i ->
                val o = taskArray.getJSONObject(i)
                val agents = strings(o.getJSONArray("agents"), 8)
                val deps = strings(o.getJSONArray("dependencies"), 20)
                val task = Task(o.getString("id"), o.getString("title"), o.getString("objective"),
                    agents, deps, o.getInt("priority"), Status.valueOf(o.getString("status")),
                    o.getString("step"), o.getLong("createdAt"), o.getLong("updatedAt"), o.optInt("runId", 0))
                require(valid(task))
                task
            }
            require(tasks.map { it.id }.distinct().size == tasks.size)
            val events = (0 until eventArray.length()).map { i ->
                val o = eventArray.getJSONObject(i)
                Event(o.getString("id"), o.getString("taskId"), o.getString("actor"),
                    o.getString("operation"), o.getString("from"), o.getString("to"),
                    o.getString("detail"), o.getLong("timestamp"),
                    o.optString("recipient"), o.optString("providerId"), o.optString("model"), o.optInt("runId", 0))
            }
            require(events.map { it.id }.distinct().size == events.size)
            Snapshot(ReadStatus.AVAILABLE, tasks, events)
        }
    }.getOrElse { Snapshot(ReadStatus.CORRUPT, errorCode = "QUEUE_READ_FAILED:${it.javaClass.simpleName}") }

    private fun strings(array: JSONArray, max: Int): List<String> {
        require(array.length() <= max)
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun valid(t: Task): Boolean =
        t.id.matches(idPattern) && t.title.isNotBlank() && t.title.length <= 180 &&
            t.objective.isNotBlank() && t.objective.length <= 1200 &&
            t.assignedAgentIds.size <= 8 && t.assignedAgentIds.distinct().size == t.assignedAgentIds.size &&
            t.dependencies.size <= 20 && t.dependencies.distinct().size == t.dependencies.size &&
            t.priority in 0..4 && t.currentStep.length <= 500 &&
            t.createdAt >= 0L && t.updatedAt >= t.createdAt && t.runId >= 0

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
