package pl.aimasterpro.app.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** Simulates commit() returning false after apply() has already changed the in-memory map. */
internal class AmbiguousCommitContext(
    base: Context,
    private val targetPreferencesName: String
) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val delegate = super.getSharedPreferences(name, mode)
        if (name != targetPreferencesName) return delegate
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            if (method.name != "edit") return@newProxyInstance method.invoke(delegate, *(args ?: emptyArray()))
            val editor = delegate.edit()
            lateinit var wrappedEditor: SharedPreferences.Editor
            wrappedEditor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { _, editorMethod, editorArgs ->
                when (editorMethod.name) {
                    "commit" -> {
                        editor.apply()
                        false
                    }
                    "apply" -> editor.apply()
                    else -> {
                        val result = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                        if (result === editor) wrappedEditor else result
                    }
                }
            } as SharedPreferences.Editor
            wrappedEditor
        } as SharedPreferences
    }
}
