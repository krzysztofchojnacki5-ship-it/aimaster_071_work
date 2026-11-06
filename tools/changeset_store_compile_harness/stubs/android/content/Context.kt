package android.content
open class Context {
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        require(name.isNotBlank() && mode == MODE_PRIVATE)
        throw UnsupportedOperationException()
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
