package pl.aimasterpro.app.core
object ChangeSafetyGuard {
    data class Finding(val code: String)
    data class Validation(val allowed: Boolean, val normalizedPaths: List<String>, val findings: List<Finding>)
}
