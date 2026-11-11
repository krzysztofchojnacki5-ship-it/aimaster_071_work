package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pl.aimasterpro.app.agents.AgentCatalog
import pl.aimasterpro.app.agents.AgentSpec

/** Durable local catalog controls. Corrupt state disables specialist routing rather than re-enabling agents. */
object AgentManagerStore {
    private const val PREFS = "aiforteca_agent_manager_v1"
    private const val KEY = "state"
    private const val VERSION = 1
    private const val MAX_DISABLED = 120
    private const val MAX_CUSTOM = 24
    private const val MAX_KEYWORDS = 20
    private const val MAX_REMOVED = 500

    enum class ReadStatus { AVAILABLE, CORRUPT, UNAVAILABLE }
    data class Snapshot(
        val status: ReadStatus,
        val disabledIds: Set<String> = emptySet(),
        val customAgents: List<AgentSpec> = emptyList(),
        val errorCode: String = "",
        val removedCustomIds: Set<String> = emptySet()
    )

    fun snapshot(context: Context): Snapshot {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            return Snapshot(ReadStatus.UNAVAILABLE, errorCode = "STORAGE_UNCERTAIN")
        }
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                    ?: return@withHealthyAccess Snapshot(ReadStatus.AVAILABLE)
                require(raw.isNotBlank() && raw.length <= 256_000) { "AGENT_MANAGER_STATE_SIZE" }
                val root = JSONObject(raw)
                require(root.optInt("version", -1) == VERSION) { "AGENT_MANAGER_VERSION" }
                val disabledArray = root.optJSONArray("disabled") ?: JSONArray()
                require(disabledArray.length() <= MAX_DISABLED) { "AGENT_MANAGER_DISABLED_LIMIT" }
                val disabled = (0 until disabledArray.length()).map { disabledArray.getString(it) }
                require(disabled.all { it.matches(ID_PATTERN) } && disabled.distinct().size == disabled.size) {
                    "AGENT_MANAGER_DISABLED_IDS"
                }
                val removedArray = root.optJSONArray("removedCustomIds") ?: JSONArray()
                require(removedArray.length() <= MAX_REMOVED) { "AGENT_MANAGER_REMOVED_LIMIT" }
                val removed = (0 until removedArray.length()).map { removedArray.getString(it) }
                require(removed.all { it.matches(ID_PATTERN) } && removed.distinct().size == removed.size) {
                    "AGENT_MANAGER_REMOVED_IDS"
                }
                val customArray = root.optJSONArray("customAgents") ?: JSONArray()
                require(customArray.length() <= MAX_CUSTOM) { "AGENT_MANAGER_CUSTOM_LIMIT" }
                val custom = (0 until customArray.length()).map { index ->
                    val item = customArray.getJSONObject(index)
                    val keywordsArray = item.optJSONArray("keywords") ?: JSONArray()
                    require(keywordsArray.length() in 1..MAX_KEYWORDS) { "AGENT_MANAGER_KEYWORDS" }
                    val agent = AgentSpec(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        category = item.getString("category"),
                        responsibility = item.getString("responsibility"),
                        keywords = (0 until keywordsArray.length()).map { keywordsArray.getString(it) },
                        systemPromptOverride = item.getString("systemPrompt")
                    )
                    require(validCustom(agent)) { "AGENT_MANAGER_CUSTOM_AGENT_INVALID" }
                    agent
                }
                require(custom.map { it.id }.distinct().size == custom.size) { "AGENT_MANAGER_CUSTOM_DUPLICATE" }
                require(custom.none { AgentCatalog.find(it.id) != null }) { "AGENT_MANAGER_CUSTOM_BUILTIN_COLLISION" }
                require(custom.none { it.id in removed }) { "AGENT_MANAGER_REMOVED_CUSTOM_REAPPEARED" }
                Snapshot(ReadStatus.AVAILABLE, disabled.toSet(), custom, removedCustomIds = removed.toSet())
            }
        }.getOrElse { Snapshot(ReadStatus.CORRUPT, errorCode = "AGENT_MANAGER_READ_FAILED:${it.javaClass.simpleName}") }
    }

    fun enabledCatalog(context: Context): List<AgentSpec> {
        val state = snapshot(context)
        if (state.status != ReadStatus.AVAILABLE) return emptyList()
        return (AgentCatalog.all + state.customAgents).filterNot { it.id in state.disabledIds }
    }

    fun isEnabled(context: Context, agentId: String): Boolean {
        val state = snapshot(context)
        if (state.status != ReadStatus.AVAILABLE || agentId in state.disabledIds) return false
        return AgentCatalog.find(agentId) != null || state.customAgents.any { it.id == agentId }
    }

    fun setEnabled(context: Context, agentId: String, enabled: Boolean): Boolean {
        if (!agentId.matches(ID_PATTERN)) return false
        val state = snapshot(context)
        if (state.status != ReadStatus.AVAILABLE) return false
        if (AgentCatalog.find(agentId) == null && state.customAgents.none { it.id == agentId }) return false
        val disabled = state.disabledIds.toMutableSet()
        if (enabled) disabled.remove(agentId) else disabled.add(agentId)
        return write(context, disabled, state.customAgents, state.removedCustomIds)
    }

    fun addCustomAgent(context: Context, agent: AgentSpec): Boolean {
        if (!validCustom(agent)) return false
        val state = snapshot(context)
        if (state.status != ReadStatus.AVAILABLE || state.customAgents.size >= MAX_CUSTOM) return false
        if (AgentCatalog.find(agent.id) != null || agent.id in state.removedCustomIds ||
            state.customAgents.any { it.id == agent.id }) return false
        return write(context, state.disabledIds, state.customAgents + agent, state.removedCustomIds)
    }

    fun updateCustomAgent(context: Context, agent: AgentSpec): Boolean {
        if (!validCustom(agent) || AgentCatalog.find(agent.id) != null) return false
        val state = snapshot(context)
        if (state.status != ReadStatus.AVAILABLE || state.customAgents.none { it.id == agent.id }) return false
        return write(context, state.disabledIds, state.customAgents.map { current ->
            if (current.id == agent.id) agent else current
        }, state.removedCustomIds)
    }

    fun removeCustomAgent(context: Context, agentId: String): Boolean {
        val state = snapshot(context)
        if (state.status != ReadStatus.AVAILABLE || state.customAgents.none { it.id == agentId }) return false
        if (state.removedCustomIds.size >= MAX_REMOVED) return false
        return write(
            context, state.disabledIds - agentId,
            state.customAgents.filterNot { it.id == agentId },
            state.removedCustomIds + agentId
        )
    }

    private fun validCustom(agent: AgentSpec): Boolean =
        agent.id.startsWith("custom-") && agent.id.matches(ID_PATTERN) &&
            agent.name.isNotBlank() && agent.name.length <= 100 &&
            agent.category.isNotBlank() && agent.category.length <= 120 &&
            agent.responsibility.isNotBlank() && agent.responsibility.length <= 500 &&
            agent.keywords.size in 1..MAX_KEYWORDS &&
            agent.keywords.all { it.isNotBlank() && it.length <= 80 } &&
            agent.systemPromptOverride.isNotBlank() && agent.systemPromptOverride.length <= 4_000

    private fun write(
        context: Context,
        disabledIds: Set<String>,
        customAgents: List<AgentSpec>,
        removedCustomIds: Set<String>
    ): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS) ||
            disabledIds.size > MAX_DISABLED || customAgents.size > MAX_CUSTOM ||
            removedCustomIds.size > MAX_REMOVED || removedCustomIds.any { !it.matches(ID_PATTERN) } ||
            customAgents.any { it.id in removedCustomIds } ||
            customAgents.any { !validCustom(it) } ||
            customAgents.map { it.id }.distinct().size != customAgents.size) return false
        val disabled = JSONArray().also { array -> disabledIds.sorted().forEach(array::put) }
        val custom = JSONArray().also { array ->
            customAgents.forEach { agent ->
                array.put(JSONObject()
                    .put("id", agent.id)
                    .put("name", agent.name)
                    .put("category", agent.category)
                    .put("responsibility", agent.responsibility)
                    .put("keywords", JSONArray(agent.keywords))
                    .put("systemPrompt", agent.systemPromptOverride))
            }
        }
        val removed = JSONArray().also { array -> removedCustomIds.sorted().forEach(array::put) }
        val raw = JSONObject().put("version", VERSION).put("disabled", disabled)
            .put("customAgents", custom).put("removedCustomIds", removed).toString()
        if (raw.length > 256_000) return false
        return SharedPreferencesWriteHealth.commit(
            PREFS,
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw)
        )
    }

    internal fun resetStorageHealthForTests() {
        SharedPreferencesWriteHealth.resetForTests(PREFS)
    }

    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{2,80}")
}
