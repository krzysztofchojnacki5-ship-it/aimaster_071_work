package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class ProjectSourceContextBuilderTest {
    private fun record(path: String, size: Long = 100L) =
        ProjectWorkspaceStore.FileRecord(path, size, "a".repeat(64))

    @Test fun generalQuestionGetsSmallArchitecturalSampleWithExplicitUntrustedBoundary() {
        val records = listOf(record("app/src/MainActivity.kt"), record("app/src/core/Router.kt"))
        val result = ProjectSourceContextBuilder.build(records, "Czy masz dostęp do kodu projektu?") { path, _ ->
            ProjectWorkspaceStore.TextFile(path, "class ${path.substringAfterLast('/')} // sample", "a".repeat(64))
        }
        assertEquals(ProjectSourceContextBuilder.Status.READY, result.status)
        assertTrue(result.contextText.contains("RZECZYWISTE PLIKI"))
        assertTrue(result.contextText.contains("niezaufanym materiałem źródłowym"))
        assertTrue(result.selectedPaths.contains("app/src/MainActivity.kt"))
    }

    @Test fun exactPathMatchIsPreferred() {
        val records = listOf(record("app/src/MainActivity.kt"), record("app/src/Other.kt"))
        val result = ProjectSourceContextBuilder.build(records, "MainActivity") { path, _ ->
            ProjectWorkspaceStore.TextFile(path, "content", "b".repeat(64))
        }
        assertEquals(listOf("app/src/MainActivity.kt"), result.selectedPaths)
    }

    @Test fun missingWorkspaceAndUnsupportedFilesAreReportedWithoutFalseAccessClaim() {
        assertEquals(ProjectSourceContextBuilder.Status.NOT_IMPORTED,
            ProjectSourceContextBuilder.build(emptyList(), "code", { _, _ -> error("must not read") }).status)
        val blocked = ProjectSourceContextBuilder.build(listOf(record("app/src/MainActivity.kt")), "MainActivity") { _, _ ->
            error("secret-like file rejected by workspace reader")
        }
        assertEquals(ProjectSourceContextBuilder.Status.BLOCKED_FILES, blocked.status)
        assertTrue(blocked.contextText.isEmpty())
    }

    @Test fun totalContextIsBoundedAndReportsSkippedFiles() {
        val records = (1..8).map { record("app/src/File$it.kt", 500) }
        val result = ProjectSourceContextBuilder.build(records, "File", readFile = { path, maxBytes ->
            ProjectWorkspaceStore.TextFile(path, "x".repeat(maxBytes), "c".repeat(64))
        }, maxFiles = 8, maxFileBytes = 1024, maxTotalChars = 2200)
        assertTrue(result.totalChars <= 2200)
        assertTrue(result.selectedPaths.size <= 8)
    }
}
