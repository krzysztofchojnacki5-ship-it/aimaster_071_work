package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class PatchPeerReviewEngineTest {
    private fun preview(path: String = "app/src/main/Foo.kt", operation: String = "MODIFY") = ProjectPatchEngine.Preview(
        true,
        listOf(ProjectPatchEngine.FilePreview(path, operation, "a".repeat(64), "b".repeat(64), 2, 3, "new content\n")),
        emptyList()
    )

    @Test fun requiresAcceptedPreviewAndExactFile() {
        val invalid = PatchPeerReviewEngine.prepare(preview().copy(accepted = false), "app/src/main/Foo.kt", "diff")
        assertFalse(invalid.acceptedForReview)
        assertEquals("BLOCKED", invalid.riskLabel)
    }

    @Test fun buildsEvidenceBoundPromptWithExactDiff() {
        val diff = "--- a/app/src/main/Foo.kt\n+++ b/app/src/main/Foo.kt\n@@ -1 +1 @@\n-old\n+new\n"
        val result = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", diff)
        assertTrue(result.acceptedForReview)
        assertTrue(result.reviewerPrompt.contains(diff))
        assertTrue(result.reviewerPrompt.contains("Bazowy SHA-256"))
        assertTrue(result.reviewerPrompt.contains("VERDICT: APPROVE"))
    }

    @Test fun flagsCriticalManifestAndPermissionChanges() {
        val diff = "--- a/app/src/main/AndroidManifest.xml\n+++ b/app/src/main/AndroidManifest.xml\n@@ -1 +1,2 @@\n <manifest/>\n+<uses-permission android:name=\"android.permission.READ_SMS\"/>\n"
        val result = PatchPeerReviewEngine.prepare(preview("app/src/main/AndroidManifest.xml"), "app/src/main/AndroidManifest.xml", diff)
        assertTrue(result.findings.any { it.code == "MODIFY_CRITICAL_FILE" })
        assertTrue(result.findings.any { it.code == "ANDROID_PERMISSION_OR_EXPORTED_COMPONENT" })
    }

    @Test fun blocksCredentialLiteral() {
        val diff = "--- a/app/src/main/Config.kt\n+++ b/app/src/main/Config.kt\n@@ -1 +1 @@\n-val api_key = \"placeholder\"\n+val api_key = \"very-secret-value-123\"\n"
        val result = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", diff)
        assertTrue(result.findings.any { it.code == "POSSIBLE_CREDENTIAL_LITERAL" && it.severity == PatchPeerReviewEngine.Severity.BLOCKER })
        assertFalse(result.acceptedForReview)
    }

    @Test fun flagsDynamicExecutionAndWebViewBridge() {
        val diff = "--- a/app/src/main/Foo.kt\n+++ b/app/src/main/Foo.kt\n@@ -1 +1,2 @@\n-old\n+Runtime.getRuntime().exec(cmd)\n+webView.addJavascriptInterface(api, \"bridge\")\n"
        val result = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", diff)
        assertTrue(result.findings.any { it.code == "DYNAMIC_CODE_EXECUTION" })
        assertTrue(result.findings.any { it.code == "WEBVIEW_JS_BRIDGE" })
    }

    @Test fun flagsBuildDependencyChanges() {
        val diff = "--- a/app/build.gradle.kts\n+++ b/app/build.gradle.kts\n@@ -1 +1,2 @@\n dependencies {\n+implementation(\"com.example:lib:1.0\")\n"
        val result = PatchPeerReviewEngine.prepare(preview("app/build.gradle.kts"), "app/build.gradle.kts", diff)
        assertTrue(result.findings.any { it.code == "BUILD_DEPENDENCY_CHANGE" })
    }

    @Test fun positiveVerdictsOnlyLeadToHumanApprovalState() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "VERDICT: APPROVE"),
            PatchPeerReviewEngine.ReviewerResult("agent-b", "VERDICT: APPROVE")
        ))
        assertEquals(PatchPeerReviewEngine.ReviewState.READY_FOR_HUMAN_APPROVAL, result.state)
        assertTrue(result.summary.contains("jawnej zgody"))
    }

    @Test fun conflictingVerdictsNeverAverageToApproval() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "VERDICT: APPROVE"),
            PatchPeerReviewEngine.ReviewerResult("agent-b", "VERDICT: REJECT\nISSUE: [BLOCKER] unsafe")
        ))
        assertEquals(PatchPeerReviewEngine.ReviewState.CONFLICTING_VERDICTS, result.state)
        assertEquals(1, result.issues.size)
    }

    @Test fun requiresTwoIndependentStructuredVerdicts() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "Looks good, probably approve"),
            PatchPeerReviewEngine.ReviewerResult("agent-a", "VERDICT: APPROVE")
        ))
        assertEquals(PatchPeerReviewEngine.ReviewState.INSUFFICIENT_REVIEWS, result.state)
    }

    @Test fun deduplicatesReviewerIdentity() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("same", "VERDICT: APPROVE"),
            PatchPeerReviewEngine.ReviewerResult("same", "VERDICT: APPROVE"),
            PatchPeerReviewEngine.ReviewerResult("other", "VERDICT: APPROVE")
        ))
        assertEquals(2, result.parsedVerdicts.size)
        assertEquals(PatchPeerReviewEngine.ReviewState.READY_FOR_HUMAN_APPROVAL, result.state)
    }

    @Test fun blocksDiffTooLargeForCompleteReview() {
        val hugeDiff = "x".repeat(PatchPeerReviewEngine.MAX_REVIEW_DIFF_CHARS + 1)
        val result = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", hugeDiff)
        assertFalse(result.acceptedForReview)
        assertTrue(result.findings.any { it.code == "REVIEW_DIFF_TOO_LARGE" })
    }

    @Test fun approvalWithBlockerIssueRemainsBlocked() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "VERDICT: APPROVE\nISSUE: [BLOCKER] unsafe path"),
            PatchPeerReviewEngine.ReviewerResult("agent-b", "VERDICT: APPROVE")
        ))
        assertEquals(PatchPeerReviewEngine.ReviewState.BLOCKER_REPORTED, result.state)
    }

    @Test fun reviewerRejectBlocksEvenWhenOtherReviewIsMissing() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "VERDICT: REJECT")
        ), minimumIndependentReviewers = 1)
        assertEquals(PatchPeerReviewEngine.ReviewState.REJECTED, result.state)
    }
    @Test fun oneExplicitRejectionCannotBeHiddenByMissingSecondReview() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "VERDICT: REJECT\nISSUE: [BLOCKER] unsafe")
        ))
        assertEquals(PatchPeerReviewEngine.ReviewState.REJECTED, result.state)
    }

    @Test fun oneApprovalWithBlockerCannotBeHiddenByMissingSecondReview() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "VERDICT: APPROVE\nISSUE: [BLOCKER] unsafe")
        ))
        assertEquals(PatchPeerReviewEngine.ReviewState.BLOCKER_REPORTED, result.state)
    }

    @Test fun oneChangeRequestRemainsBlockingWithoutSecondReview() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "VERDICT: REQUEST_CHANGES")
        ))
        assertEquals(PatchPeerReviewEngine.ReviewState.CHANGES_REQUESTED, result.state)
    }

    @Test fun blockerIssueWithoutStructuredVerdictCannotBeIgnored() {
        val pack = PatchPeerReviewEngine.prepare(preview(), "app/src/main/Foo.kt", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        val result = PatchPeerReviewEngine.combine(pack, listOf(
            PatchPeerReviewEngine.ReviewerResult("agent-a", "ISSUE: [BLOCKER] evidence missing")
        ))
        assertEquals(PatchPeerReviewEngine.ReviewState.BLOCKER_REPORTED, result.state)
    }

}
