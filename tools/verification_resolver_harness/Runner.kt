package harness
fun main() {
    val names = listOf(
        "pl.aimasterpro.app.agents.AgentPromptsTest",
        "pl.aimasterpro.app.core.VerificationEvidenceResolverTest",
        "pl.aimasterpro.app.core.MasterEvidenceContextBuilderTest",
        "pl.aimasterpro.app.core.HostActiveEvidenceScopeResolverTest",
        "pl.aimasterpro.app.core.ProjectWorkspaceOperationLockTest",
        "pl.aimasterpro.app.core.ToolExecutionRegistryTest",
        "pl.aimasterpro.app.core.VerificationEvidenceStoreTest",
        "pl.aimasterpro.app.core.VerificationEvidenceTest",
        "pl.aimasterpro.app.core.EvidenceTrackedToolAdapterTest",
        "pl.aimasterpro.app.core.MasterResponseCheckTest"
    )
    var passed = 0
    var failed = 0
    names.forEach { name ->
        val type = Class.forName(name)
        type.declaredMethods.filter { it.getAnnotation(org.junit.Test::class.java) != null }
            .sortedBy { it.name }.forEach { method ->
                try { method.invoke(type.getDeclaredConstructor().newInstance()); passed++; println("PASS $name.${method.name}") }
                catch (failure: Throwable) { failed++; val cause = failure.cause ?: failure; System.err.println("FAIL $name.${method.name}: $cause") }
            }
    }
    println("RESULT passed=$passed failed=$failed")
    check(failed == 0) { "Harness failures: $failed" }
}
