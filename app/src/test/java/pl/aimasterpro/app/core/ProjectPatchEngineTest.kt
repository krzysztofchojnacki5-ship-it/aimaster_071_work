package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ProjectPatchEngineTest {
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun record(path: String, content: String) = ProjectWorkspaceStore.FileRecord(path, content.toByteArray().size.toLong(), hash(content))
    private fun reader(content: String): (String, Int) -> ProjectWorkspaceStore.TextFile = { path, _ -> ProjectWorkspaceStore.TextFile(path, content, hash(content)) }

    @Test fun previewsExactModificationWithoutWriting() {
        val base = "one\ntwo\n"
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -1,2 +1,2 @@\n one\n-two\n+three\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("src/Foo.kt", hash(base), diff)), listOf(record("src/Foo.kt", base)), reader(base))
        assertTrue(result.accepted)
        assertEquals("MODIFY", result.files.single().operation)
        assertEquals("one\nthree\n", result.files.single().resultContent)
    }

    @Test fun rejectsStaleBaseHash() {
        val base = "one\n"
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -1 +1 @@\n-one\n+two\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("src/Foo.kt", "0".repeat(64), diff)), listOf(record("src/Foo.kt", base)), reader(base))
        assertFalse(result.accepted)
        assertTrue(result.findings.any { it.message.contains("SHA-256") })
    }

    @Test fun rejectsContextMismatchInsteadOfFuzzyApplying() {
        val base = "one\ntwo\n"
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -1,2 +1,2 @@\n wrong\n-two\n+three\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("src/Foo.kt", hash(base), diff)), listOf(record("src/Foo.kt", base)), reader(base))
        assertFalse(result.accepted)
        assertTrue(result.findings.any { it.message.contains("Kontekst") })
    }

    @Test fun rejectsPathTraversalEvenWithValidDiff() {
        val diff = "--- a/../outside.kt\n+++ b/../outside.kt\n@@ -1 +1 @@\n-a\n+b\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("../outside.kt", hash("a\n"), diff)), emptyList()) { _, _ -> error("must not read") }
        assertFalse(result.accepted)
    }

    @Test fun previewsNewFileWithNullOldHeader() {
        val diff = "--- /dev/null\n+++ b/docs/NEW.md\n@@ -0,0 +1,1 @@\n+hello\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("docs/NEW.md", "", diff)), emptyList()) { _, _ -> error("must not read") }
        assertTrue(result.accepted)
        assertEquals("ADD", result.files.single().operation)
        assertEquals("hello\n", result.files.single().resultContent)
    }

    @Test fun rejectsMalformedHunkCounts() {
        val base = "one\n"
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -1,2 +1,1 @@\n-one\n+two\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("src/Foo.kt", hash(base), diff)), listOf(record("src/Foo.kt", base)), reader(base))
        assertFalse(result.accepted)
    }

    @Test fun rejectsSecretInResult() {
        val base = "val token = \"safe\"\n"
        val changed = "val token = \"ghp_123456789012345678901234567890123456\"\n"
        val diff = "--- a/src/Config.kt\n+++ b/src/Config.kt\n@@ -1 +1 @@\n-${base.trimEnd()}\n+${changed.trimEnd()}\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("src/Config.kt", hash(base), diff)), listOf(record("src/Config.kt", base)), reader(base))
        assertFalse(result.accepted)
        assertTrue(result.findings.any { it.message.contains("sekret") || it.message.contains("SECRET") })
    }

    @Test fun rejectsDuplicateTargets() {
        val base = "a\n"
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -1 +1 @@\n-a\n+b\n"
        val proposals = listOf(ProjectPatchEngine.Proposal("src/Foo.kt", hash(base), diff), ProjectPatchEngine.Proposal("src/Foo.kt", hash(base), diff))
        val result = ProjectPatchEngine.preview(proposals, listOf(record("src/Foo.kt", base)), reader(base))
        assertFalse(result.accepted)
    }

    @Test fun previewsDeletionOnlyWhenAllBaseLinesMatch() {
        val base = "remove me\n"
        val diff = "--- a/docs/OLD.md\n+++ /dev/null\n@@ -1 +0,0 @@\n-remove me\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("docs/OLD.md", hash(base), diff)), listOf(record("docs/OLD.md", base)), reader(base))
        assertTrue(result.accepted)
        assertEquals("DELETE", result.files.single().operation)
        assertEquals(null, result.files.single().resultContent)
    }

    @Test fun preservesExplicitNoFinalNewlineMarker() {
        val base = "old"
        val diff = "--- a/docs/a.txt\n+++ b/docs/a.txt\n@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("docs/a.txt", hash(base), diff)), listOf(record("docs/a.txt", base)), reader(base))
        assertTrue(result.accepted)
        assertEquals("new", result.files.single().resultContent)
    }


    @Test fun rejectsGenericApiTokenAssignments() {
        val base = "val api_token = \"safe-value\"\n"
        val changed = "val api_token = \"abcdefghijk123456\"\n"
        val diff = "--- a/src/Config.kt\n+++ b/src/Config.kt\n@@ -1 +1 @@\n-${base.trimEnd()}\n+${changed.trimEnd()}\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("src/Config.kt", hash(base), diff)), listOf(record("src/Config.kt", base)), reader(base))
        assertFalse(result.accepted)
        assertTrue(result.findings.any { it.message.contains("sekret") || it.message.contains("SECRET") })
    }


    @Test fun rejectsInconsistentNewHunkPosition() {
        val base = "first\nsecond\nthird\n"
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -3 +9 @@\n-third\n+last\n"
        val result = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("src/Foo.kt", hash(base), diff)), listOf(record("src/Foo.kt", base)), reader(base))
        assertFalse(result.accepted)
        assertTrue(result.findings.any { it.message.contains("newStart") })
    }

}
