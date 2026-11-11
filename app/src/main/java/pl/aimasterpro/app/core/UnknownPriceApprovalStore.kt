package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Durable, exact-scope user consent for models whose API price cannot be verified.
 * Consent is bound to provider + endpoint + model; it is never inherited by sibling models.
 */
object UnknownPriceApprovalStore {
    private const val PREFS = "unknown_price_approvals_v1"
    private const val KEY = "approvals"

    private data class Approval(
        val providerId: String,
        val baseUrl: String,
        val modelName: String
    )

    private fun normalizedProvider(value: String) = value.trim().lowercase(Locale.ROOT)
    private fun normalizedEndpoint(value: String) = value.trim().trimEnd('/').lowercase(Locale.ROOT)
    private fun identity(providerId: String, baseUrl: String, modelName: String) =
        listOf(normalizedProvider(providerId), normalizedEndpoint(baseUrl), modelName.trim())

    private fun read(context: Context): List<Approval>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        require(array.length() <= 5_000) { "Lista zgód przekracza bezpieczny rozmiar." }
        val result = mutableListOf<Approval>()
        val seen = hashSetOf<List<String>>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val provider = item.getString("providerId")
            val endpoint = item.getString("baseUrl")
            val model = item.getString("modelName")
            require(provider.isNotBlank() && endpoint.isNotBlank() && model.isNotBlank()) {
                "Zgoda na nieznaną cenę ma niekompletną tożsamość."
            }
            val approval = Approval(provider, endpoint, model)
            require(seen.add(identity(provider, endpoint, model))) {
                "Lista zgód zawiera zduplikowaną tożsamość modelu."
            }
            result += approval
        }
        result
    }.getOrNull()

    @Synchronized
    fun isApproved(context: Context, providerId: String, baseUrl: String, modelName: String): Boolean {
        if (providerId.isBlank() || baseUrl.isBlank() || modelName.isBlank()) return false
        val approvals = read(context) ?: return false
        val target = identity(providerId, baseUrl, modelName)
        return approvals.any { identity(it.providerId, it.baseUrl, it.modelName) == target }
    }

    /** Returns false when the existing consent ledger is unreadable or the commit fails. */
    @Synchronized
    fun setApproved(
        context: Context,
        providerId: String,
        baseUrl: String,
        modelName: String,
        approved: Boolean
    ): Boolean {
        if (providerId.isBlank() || providerId.length > 128 ||
            baseUrl.isBlank() || baseUrl.length > 2_048 ||
            modelName.isBlank() || modelName.length > 256) return false
        val current = read(context) ?: return false
        val target = identity(providerId, baseUrl, modelName)
        val next = current.filterNot {
            identity(it.providerId, it.baseUrl, it.modelName) == target
        }.toMutableList()
        if (approved) next += Approval(providerId.trim(), baseUrl.trim().trimEnd('/'), modelName.trim())
        val array = JSONArray()
        next.forEach { item ->
            array.put(JSONObject()
                .put("providerId", item.providerId)
                .put("baseUrl", item.baseUrl)
                .put("modelName", item.modelName))
        }
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).commit()
        }.getOrDefault(false)
    }
}
