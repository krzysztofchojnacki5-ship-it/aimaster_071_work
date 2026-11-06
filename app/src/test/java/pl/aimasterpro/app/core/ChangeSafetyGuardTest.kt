package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class ChangeSafetyGuardTest {
    @Test fun rejectsTraversalAbsoluteAndProtectedPaths() {
        val result = ChangeSafetyGuard.validate(listOf("../outside.kt", "C:/secret.txt", "app/src/Main.kt", "app/build/output.bin"))
        assertFalse(result.allowed)
        assertTrue(result.findings.any { it.code == "UNSAFE_PATH" })
        assertTrue(result.findings.any { it.code == "PROTECTED_PATH" })
        assertTrue(result.normalizedPaths.contains("app/src/Main.kt"))
    }

    @Test fun rejectsPrivateKeysAndKnownApiTokenPatterns() {
        val result = ChangeSafetyGuard.validate(listOf("app/src/Config.kt"), mapOf(
            "app/src/Config.kt" to "val token = \"ghp_123456789012345678901234567890123456\""
        ))
        assertFalse(result.allowed)
        assertTrue(result.findings.any { it.code == "SECRET_DETECTED" })
    }

    @Test fun acceptsSafeUniqueRelativePaths() {
        val result = ChangeSafetyGuard.validate(listOf("app/src/main/Foo.kt", "docs/README.md"))
        assertTrue(result.allowed)
        assertEquals(listOf("app/src/main/Foo.kt", "docs/README.md"), result.normalizedPaths)
    }
}
