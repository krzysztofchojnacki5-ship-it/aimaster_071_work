package android.content

open class Context(private val stores: MutableMap<String, SharedPreferences> = mutableMapOf()) {
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        require(name.isNotBlank() && mode == MODE_PRIVATE)
        return stores.getOrPut(name) { MemorySharedPreferences() }
    }
    companion object { const val MODE_PRIVATE = 0 }
}

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String): Editor
        fun remove(key: String): Editor
        fun commit(): Boolean
    }
}

class MemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, String>()
    var nextCommitResult: Boolean? = null
    val commitResults = mutableListOf<Boolean>()
    override fun getString(key: String, defValue: String?): String? = values[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, String?>()
        override fun putString(key: String, value: String): SharedPreferences.Editor { updates[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { updates[key] = null; return this }
        override fun commit(): Boolean {
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return if (commitResults.isNotEmpty()) commitResults.removeAt(0)
            else nextCommitResult.also { nextCommitResult = null } ?: true
        }
    }
}
