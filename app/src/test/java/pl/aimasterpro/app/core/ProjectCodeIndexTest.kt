package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class ProjectCodeIndexTest {
    private fun record(path: String, size: Long = 100L) = ProjectWorkspaceStore.FileRecord(path, size, "0".repeat(64))

    @Test fun groupsLanguagesAndModulesDeterministically() {
        val summary = ProjectCodeIndex.build(listOf(
            record("app/src/MainActivity.kt"),
            record("app/src/Screen.xml"),
            record("core/src/Agent.java"),
            record("README.md"),
            record("app/build/output.apk")
        ))
        assertEquals(5, summary.totalFiles)
        assertEquals(4, summary.entries.size)
        assertEquals(2, summary.modules["app"])
        assertEquals(1, summary.modules["core"])
        assertEquals(1, summary.languages["Kotlin"])
        assertEquals(1, summary.languages["Java"])
    }

    @Test fun ranksRelevantPathsAndCapsSelection() {
        val summary = ProjectCodeIndex.build(listOf(
            record("app/src/MainActivity.kt"),
            record("app/src/Other.kt"),
            record("core/src/Router.kt"),
            record("README.md")
        ))
        val selected = ProjectCodeIndex.selectRelevant(summary, "MainActivity", 2)
        assertEquals(1, selected.size)
        assertEquals("app/src/MainActivity.kt", selected.first().path)
        assertTrue(ProjectCodeIndex.selectRelevant(summary, "", 1).size <= 1)
    }
}
