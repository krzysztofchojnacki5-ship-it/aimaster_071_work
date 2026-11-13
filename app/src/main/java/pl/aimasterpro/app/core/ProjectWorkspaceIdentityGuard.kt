package pl.aimasterpro.app.core

/** Prevents a delayed destructive operation from mutating a different imported workspace. */
object ProjectWorkspaceIdentityGuard {
    fun requireUnchanged(expectedIdentity: String, currentIdentity: String?) {
        require(expectedIdentity.isNotBlank()) { "Brak tożsamości workspace z chwili rozpoczęcia operacji." }
        check(currentIdentity == expectedIdentity) {
            "Workspace zmienił się od czasu rozpoczęcia operacji. Destrukcyjna operacja została zablokowana."
        }
    }
}
