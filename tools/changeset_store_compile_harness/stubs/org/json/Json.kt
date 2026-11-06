package org.json
class JSONArray {
    constructor()
    constructor(source: String) { require(source.isNotEmpty()) }
    constructor(values: Collection<*>) { values.size }
    fun length(): Int = 0
    fun optJSONObject(index: Int): JSONObject? { index.hashCode(); return null }
    fun getString(index: Int): String { index.hashCode(); return "" }
    fun optString(index: Int): String { index.hashCode(); return "" }
    fun put(value: Any?): JSONArray { value.hashCode(); return this }
}
class JSONObject {
    constructor()
    constructor(source: String) { require(source.isNotEmpty()) }
    fun put(key: String, value: Any?): JSONObject { key.hashCode(); value.hashCode(); return this }
    fun getString(key: String): String { key.hashCode(); return "" }
    fun optString(key: String): String { key.hashCode(); return "" }
    fun optString(key: String, fallback: String): String { key.hashCode(); return fallback }
    fun optLong(key: String): Long { key.hashCode(); return 0L }
    fun getLong(key: String): Long { key.hashCode(); return 0L }
    fun optJSONArray(key: String): JSONArray? { key.hashCode(); return null }
    fun has(key: String): Boolean { key.hashCode(); return false }
    override fun toString(): String = "{}"
}
