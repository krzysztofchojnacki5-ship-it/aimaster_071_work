package org.json

private class Parser(private val source: String) {
    private var i = 0
    private fun ws() { while (i < source.length && source[i].isWhitespace()) i++ }
    fun parse(): Any? { ws(); val value = value(); ws(); require(i == source.length); return value }
    private fun value(): Any? {
        ws(); require(i < source.length)
        return when (source[i]) {
            '{' -> obj(); '[' -> array(); '"' -> string()
            't' -> { literal("true"); true }; 'f' -> { literal("false"); false }; 'n' -> { literal("null"); null }
            else -> number()
        }
    }
    private fun literal(s: String) { require(source.startsWith(s, i)); i += s.length }
    private fun string(): String {
        require(source[i++] == '"'); val out = StringBuilder()
        while (i < source.length) { val c = source[i++]; if (c == '"') return out.toString(); if (c == '\\') {
            require(i < source.length); when (val e = source[i++]) { '"','\\','/' -> out.append(e); 'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t'); 'u' -> { val hex = source.substring(i, i + 4); out.append(hex.toInt(16).toChar()); i += 4 }; else -> error("bad escape") }
        } else out.append(c) }
        error("unterminated string")
    }
    private fun number(): Number { val start = i; while (i < source.length && source[i] in "-+0123456789.eE") i++; val s = source.substring(start, i); return s.toLongOrNull() ?: s.toDouble() }
    private fun array(): List<Any?> { i++; ws(); val out = mutableListOf<Any?>(); if (source.getOrNull(i) == ']') { i++; return out }; while (true) { out += value(); ws(); when (source[i++]) { ']' -> return out; ',' -> Unit; else -> error("bad array") } } }
    private fun obj(): Map<String, Any?> { i++; ws(); val out = linkedMapOf<String, Any?>(); if (source.getOrNull(i) == '}') { i++; return out }; while (true) { ws(); val key = string(); ws(); require(source[i++] == ':'); out[key] = value(); ws(); when (source[i++]) { '}' -> return out; ',' -> Unit; else -> error("bad object") } } }
}
private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
private fun encode(v: Any?): String = when (v) { null -> "null"; is String -> quote(v); is Number, is Boolean -> v.toString(); is JSONObject -> v.toString(); is JSONArray -> v.toString(); is Collection<*> -> v.joinToString(prefix="[", postfix="]") { encode(it) }; is Map<*, *> -> v.entries.joinToString(prefix="{", postfix="}") { quote(it.key.toString()) + ":" + encode(it.value) }; else -> quote(v.toString()) }
class JSONArray {
    private val values: MutableList<Any?>
    constructor() { values = mutableListOf() }
    constructor(source: String) { values = (Parser(source).parse() as? List<*>)?.toMutableList() ?: error("not array") }
    constructor(values: Collection<*>) { this.values = values.toMutableList() }
    fun length() = values.size
    fun optJSONObject(index: Int): JSONObject? = (values.getOrNull(index) as? Map<*, *>)?.let { JSONObject(it.entries.associate { e -> e.key.toString() to e.value }) }
    fun getString(index: Int): String = values[index] as? String ?: error("not string")
    fun optString(index: Int): String = values.getOrNull(index) as? String ?: ""
    fun put(value: Any?): JSONArray { values.add(value); return this }
    override fun toString() = encode(values)
}
class JSONObject {
    private val values: MutableMap<String, Any?>
    constructor() { values = linkedMapOf() }
    constructor(source: String) { values = (Parser(source).parse() as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap() ?: error("not object") }
    internal constructor(source: Map<String, Any?>) { values = source.toMutableMap() }
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun getString(key: String): String = values[key] as? String ?: error("missing string $key")
    fun optString(key: String): String = values[key] as? String ?: ""
    fun optString(key: String, fallback: String): String = values[key] as? String ?: fallback
    fun optLong(key: String): Long = (values[key] as? Number)?.toLong() ?: 0L
    fun getLong(key: String): Long = (values[key] as? Number)?.toLong() ?: error("missing long $key")
    fun optJSONArray(key: String): JSONArray? = (values[key] as? List<*>)?.let { JSONArray(it) }
    fun has(key: String) = values.containsKey(key)
    override fun toString() = encode(values)
}
