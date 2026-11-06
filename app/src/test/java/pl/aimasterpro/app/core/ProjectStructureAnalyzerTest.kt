package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class ProjectStructureAnalyzerTest {
    private fun record(path: String, content: String) = ProjectWorkspaceStore.FileRecord(
        path, content.toByteArray(Charsets.UTF_8).size.toLong(), "0".repeat(64)
    )

    @Test fun findsKotlinSymbolsAndIgnoresCommentsAndStrings() {
        val source = """
            package sample.app
            // class FakeComment
            /* fun fake() {} */
            val example = "class FakeString { fun nope() {} }"
            class RealClass
            interface RealContract
            fun realFunction() = Unit
        """.trimIndent()
        val report = ProjectStructureAnalyzer.analyze(listOf(record("app/src/Main.kt", source))) { path, _ ->
            ProjectWorkspaceStore.TextFile(path, source, "0".repeat(64))
        }
        val symbols = report.analyzedFiles.single().symbols
        assertTrue(symbols.any { it.name == "RealClass" && it.kind == "class" })
        assertTrue(symbols.any { it.name == "RealContract" && it.kind == "interface" })
        assertTrue(symbols.any { it.name == "realFunction" && it.kind == "function" })
        assertEquals(5, symbols.first { it.name == "RealClass" }.line)
        assertEquals(7, symbols.first { it.name == "realFunction" }.line)
        assertFalse(symbols.any { it.name.startsWith("Fake") || it.name == "fake" || it.name == "nope" })
    }

    @Test fun parsesJavaTypesAndMethodsWithoutTreatingCallsAsDeclarations() {
        val source = """
            package sample.java;
            import java.util.List;
            public interface Contract { void run(); }
            public class Worker implements Contract {
                public void run() { helper(); }
                private int calculate(int value) { return value; }
                private void helper() {}
            }
        """.trimIndent()
        val report = ProjectStructureAnalyzer.analyze(listOf(record("app/src/Worker.java", source))) { path, _ ->
            ProjectWorkspaceStore.TextFile(path, source, "0".repeat(64))
        }
        val symbols = report.analyzedFiles.single().symbols
        assertTrue(symbols.any { it.name == "Contract" && it.kind == "interface" })
        assertTrue(symbols.any { it.name == "Worker" && it.kind == "class" })
        assertTrue(symbols.any { it.name == "run" && it.kind == "method" })
        assertTrue(symbols.any { it.name == "calculate" && it.kind == "method" })
        assertFalse(symbols.any { it.name == "helper" && it.kind == "function" })
    }

    @Test fun enforcesFourMegabyteTotalReadLimit() {
        val content = "x".repeat(ProjectStructureAnalyzer.MAX_FILE_BYTES)
        val records = (0 until 64).map { record("src/File${it.toString().padStart(3, '0')}.kt", content) } +
            record("zz/Last.kt", "x")
        val report = ProjectStructureAnalyzer.analyze(records) { path, _ ->
            ProjectWorkspaceStore.TextFile(path, if (path.endsWith("Last.kt")) "x" else content, "0".repeat(64))
        }
        assertEquals(64, report.analyzedFiles.size)
        assertEquals(ProjectStructureAnalyzer.MAX_TOTAL_BYTES, report.totalBytesRead)
        assertEquals("zz/Last.kt", report.skippedFiles.single().first)
    }

    @Test fun resolvesImportsAndBuildsModuleGraph() {
        val a = "package demo.core\nclass Engine"
        val b = "package demo.ui\nimport demo.core.Engine\nclass Screen"
        val report = ProjectStructureAnalyzer.analyze(listOf(record("core/src/Engine.kt", a), record("app/src/Screen.kt", b))) { path, _ ->
            val body = if (path.endsWith("Engine.kt")) a else b
            ProjectWorkspaceStore.TextFile(path, body, "0".repeat(64))
        }
        assertEquals(1, report.edges.size)
        assertEquals("app/src/Screen.kt", report.edges.single().from)
        assertEquals("core/src/Engine.kt", report.edges.single().to)
        assertEquals(setOf("core"), report.moduleEdges["app"])
        assertTrue(report.unresolvedImports.isEmpty())
    }

    @Test fun resolvesPackageAndTypeWildcardImports() {
        val engine = "package demo.core\nclass Engine"
        val helper = "package demo.core\nclass Helper"
        val screen = "package demo.ui\nimport demo.core.*\nimport demo.core.Engine.*\nclass Screen"
        val files = mapOf(
            "core/src/Engine.kt" to engine,
            "core/src/Helper.kt" to helper,
            "app/src/Screen.kt" to screen
        )
        val report = ProjectStructureAnalyzer.analyze(files.map { record(it.key, it.value) }) { path, _ ->
            val body = files.getValue(path)
            ProjectWorkspaceStore.TextFile(path, body, "0".repeat(64))
        }
        assertEquals(setOf("core/src/Engine.kt", "core/src/Helper.kt"), report.edges.map { it.to }.toSet())
        assertEquals(setOf("core"), report.moduleEdges["app"])
    }

    @Test fun detectsKotlinEnumsAndJavaAnnotationInterfaces() {
        val kotlin = """package sample
            enum class Direction { NORTH, SOUTH }
        """.trimIndent()
        val java = "package sample;\npublic @interface Marker {}"
        val report = ProjectStructureAnalyzer.analyze(listOf(
            record("app/src/Direction.kt", kotlin), record("app/src/Marker.java", java)
        )) { path, _ ->
            val body = if (path.endsWith("Direction.kt")) kotlin else java
            ProjectWorkspaceStore.TextFile(path, body, "0".repeat(64))
        }
        assertTrue(report.analyzedFiles.flatMap { it.symbols }.any { it.name == "Direction" && it.kind == "enum" })
        assertTrue(report.analyzedFiles.flatMap { it.symbols }.any { it.name == "Marker" && it.kind == "interface" })
    }

    @Test fun reportsDuplicateTypesButNotOverloadedFunctions() {
        val a = "package same\nclass Shared\nfun convert(x: Int) = x"
        val b = "package same\nclass Shared\nfun convert(x: String) = x"
        val report = ProjectStructureAnalyzer.analyze(listOf(record("one/A.kt", a), record("two/B.kt", b))) { path, _ ->
            val body = if (path.endsWith("A.kt")) a else b
            ProjectWorkspaceStore.TextFile(path, body, "0".repeat(64))
        }
        assertEquals(listOf("one/A.kt", "two/B.kt"), report.duplicateSymbols["same.Shared"])
        assertEquals(1, report.duplicateSymbols.size)
    }

    @Test fun enforcesPerFileLimitAndCapturesReadFailures() {
        val tooLarge = "x".repeat(ProjectStructureAnalyzer.MAX_FILE_BYTES + 1)
        val small = "package ok\nclass Fine"
        val report = ProjectStructureAnalyzer.analyze(listOf(
            record("app/src/Large.kt", tooLarge), record("app/src/Good.kt", small)
        )) { path, _ ->
            if (path.endsWith("Good.kt")) ProjectWorkspaceStore.TextFile(path, small, "0".repeat(64))
            else error("should not read oversized file")
        }
        assertEquals(1, report.analyzedFiles.size)
        assertEquals(1, report.skippedFiles.size)
        assertEquals("Fine", report.analyzedFiles.single().symbols.single().name)
    }
}
