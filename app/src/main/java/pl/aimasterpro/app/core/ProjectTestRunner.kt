package pl.aimasterpro.app.core

/**
 * Fail-closed verification coordinator for a patched private workspace.
 *
 * This Android build deliberately does not execute imported Gradle scripts, shell files,
 * tests, or binaries. Android does not provide this app with a configured OS-level sandbox
 * capable of enforcing CPU, memory and network limits for arbitrary project build logic.
 * The runner therefore performs bounded integrity checks and reports PROJECT_TESTS as
 * NOT_RUN until a real isolated executor is installed.
 */
class ProjectTestRunner(private val workspace: ProjectWorkspaceStore) {
    enum class Status { PASS, FAIL, NOT_RUN }
    data class Check(val name: String, val status: Status, val detail: String)
    data class Result(
        val status: Status,
        val checks: List<Check>,
        val changedPaths: List<String>,
        val elapsedMs: Long,
        val projectCodeExecuted: Boolean,
        val output: String,
        val evidence: List<VerificationEvidence> = emptyList()
    ) {
        val evidenceAssessment: VerificationAssessment
            get() = VerificationAssessment.assess(evidence, REQUIRED_EVIDENCE_KINDS)
    }

    /**
     * Verifies one immutable workspace generation. The public boundary acquires the same
     * process-wide gate used by imports, patching, rollback and recovery. Callers that already
     * hold the gate remain safe because the lock is reentrant.
     */
    fun verifyAppliedChange(
        snapshotId: String,
        expectedChangedPaths: List<String>,
        taskId: String = snapshotId,
        chatId: String = ""
    ): Result = ProjectWorkspaceOperationLock.withLock {
        verifyAppliedChangeLocked(snapshotId, expectedChangedPaths, taskId, chatId)
    }

    private fun verifyAppliedChangeLocked(
        snapshotId: String,
        expectedChangedPaths: List<String>,
        taskId: String,
        chatId: String
    ): Result {
        require(snapshotId.isNotBlank() && taskId.isNotBlank()) { "Brak identyfikatora zadania lub snapshotu." }
        val started = System.nanoTime()
        val checks = mutableListOf<Check>()
        val expectedValidation = ChangeSafetyGuard.validate(expectedChangedPaths)
        val expected = expectedValidation.normalizedPaths.toSet()
        var actualChanged = emptyList<String>()

        val current = runCatching { workspace.verifyCurrentWorkspace() }
        if (current.isFailure) {
            checks += Check("WORKSPACE_INTEGRITY", Status.FAIL, current.exceptionOrNull()?.message ?: "Błąd odczytu workspace")
        } else {
            val report = current.getOrThrow()
            checks += Check("WORKSPACE_INTEGRITY", if (report.valid) Status.PASS else Status.FAIL,
                if (report.valid) "${report.checkedFiles} plików zgodnych z manifestem SHA-256." else
                    "Brak: ${report.missing.size}; zmienione: ${report.modified.size}; dodatkowe: ${report.unexpected.size}.")
        }

        val snapshot = runCatching { workspace.verifySnapshot(snapshotId) }
        if (snapshot.isFailure) {
            checks += Check("SNAPSHOT_INTEGRITY", Status.FAIL, snapshot.exceptionOrNull()?.message ?: "Błąd odczytu snapshotu")
        } else {
            val report = snapshot.getOrThrow()
            checks += Check("SNAPSHOT_INTEGRITY", if (report.valid) Status.PASS else Status.FAIL,
                if (report.valid) "Snapshot $snapshotId: ${report.checkedFiles} plików zgodnych z manifestem." else
                    "Snapshot uszkodzony — brak: ${report.missing.size}; zmienione: ${report.modified.size}; dodatkowe: ${report.unexpected.size}.")
        }

        if (!expectedValidation.allowed || expected.size != expectedChangedPaths.size) {
            checks += Check("PATCH_SCOPE", Status.FAIL,
                "Rejestr zawiera nieprawidłowe lub powielone ścieżki: ${expectedValidation.findings.joinToString { it.code }}.")
        } else if (current.isSuccess && snapshot.isSuccess && current.getOrThrow().valid && snapshot.getOrThrow().valid) {
            val comparison = runCatching {
                val before = snapshot.getOrThrow().workspace!!.files.associateBy { it.path }
                val after = workspace.listFiles(ProjectWorkspaceStore.MAX_ENTRIES).associateBy { it.path }
                (before.keys + after.keys).filter { path ->
                    val old = before[path]
                    val new = after[path]
                    old == null || new == null || old.sizeBytes != new.sizeBytes || old.sha256 != new.sha256
                }.sorted()
            }
            if (comparison.isFailure) {
                checks += Check("PATCH_SCOPE", Status.FAIL,
                    "Nie udało się bezpiecznie porównać zakresu patcha: ${comparison.exceptionOrNull()?.message ?: "błąd odczytu"}.")
            } else {
                actualChanged = comparison.getOrThrow()
                val matches = actualChanged.toSet() == expected
                checks += Check("PATCH_SCOPE", if (matches) Status.PASS else Status.FAIL,
                    if (matches) "Rzeczywisty zakres zmian odpowiada rejestrowi (${actualChanged.size} ścieżek)." else
                        "Niezgodność zakresu. Oczekiwano: ${expected.sorted().joinToString()}; wykryto: ${actualChanged.joinToString()}.")
            }
        } else {
            checks += Check("PATCH_SCOPE", Status.NOT_RUN, "Pominięto porównanie zakresu z powodu błędu integralności.")
        }

        // Never promote metadata checks to a project-test PASS. No user code is executed here.
        checks += Check("PROJECT_TESTS", Status.NOT_RUN,
            "Nie uruchomiono kompilacji ani testów projektu. Brak skonfigurowanego sandboxa procesu z limitami CPU/RAM/sieci.")

        val failed = checks.any { it.status == Status.FAIL }
        val status = if (failed) Status.FAIL else Status.NOT_RUN
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        val output = buildString {
            appendLine("WERYFIKACJA ZMIANY — ${status.name}")
            appendLine("Czas: ${elapsed} ms • wykonano kod projektu: NIE")
            checks.forEach { appendLine("${it.status.name} • ${it.name}: ${it.detail}") }
            appendLine("\nPASS integralności nie oznacza PASS kompilacji. Status APPLIED pozostaje zablokowany, dopóki rzeczywiste testy projektu nie przejdą w odizolowanym executorze.")
        }
        val evidence = checks.map { check ->
            val kind = when (check.name) {
                "WORKSPACE_INTEGRITY" -> VerificationEvidenceKind.WORKSPACE_INTEGRITY
                "SNAPSHOT_INTEGRITY" -> VerificationEvidenceKind.SNAPSHOT_INTEGRITY
                "PATCH_SCOPE" -> VerificationEvidenceKind.PATCH_SCOPE
                "PROJECT_TESTS" -> VerificationEvidenceKind.PROJECT_TESTS
                else -> VerificationEvidenceKind.OTHER
            }
            val evidenceStatus = when (check.status) {
                Status.PASS -> VerificationEvidenceStatus.PASS
                Status.FAIL -> VerificationEvidenceStatus.FAIL
                Status.NOT_RUN -> VerificationEvidenceStatus.NOT_RUN
            }
            VerificationEvidence.runtimeCheck(taskId, kind, evidenceStatus,
                "${check.name}: ${check.detail}", chatId = chatId, snapshotId = snapshotId)
        }
        return Result(status, checks, actualChanged, elapsed, false, output, evidence)
    }

    private companion object {
        val REQUIRED_EVIDENCE_KINDS = setOf(
            VerificationEvidenceKind.WORKSPACE_INTEGRITY,
            VerificationEvidenceKind.SNAPSHOT_INTEGRITY,
            VerificationEvidenceKind.PATCH_SCOPE,
            VerificationEvidenceKind.PROJECT_TESTS
        )
    }
}
