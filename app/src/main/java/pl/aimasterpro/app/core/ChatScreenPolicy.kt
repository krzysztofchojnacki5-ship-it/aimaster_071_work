package pl.aimasterpro.app.core

/** Keeps actions inside the chat surface from accidentally switching conversation modes. */
object ChatScreenPolicy {
    fun destination(currentScreen: String): String =
        if (currentScreen.equals("Asystent", ignoreCase = true)) "Asystent" else "Czat"
}
