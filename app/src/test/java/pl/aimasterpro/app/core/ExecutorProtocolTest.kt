package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ExecutorProtocolTest {
    private val now = 1_800_000_000_000L
    private fun manifest() = ExecutorProtocol.createManifest("bundle-1", "workspace-1", "snapshot-1", now,
        listOf(ExecutorProtocol.BundleFile("app/build.gradle.kts", 120, "a".repeat(64)),
            ExecutorProtocol.BundleFile("README.md", 50, "b".repeat(64))))
    private fun request() = ExecutorProtocol.ExecutionRequest(1, "run-1", manifest(),
        ExecutorProtocol.Task.UNIT_TEST_DEBUG, ExecutorProtocol.ResourcePolicy())

    @Test fun manifestIsCanonicalAndDetectsTampering() {
        val good = manifest()
        assertTrue(ExecutorProtocol.validateManifest(good).valid)
        val changed = good.copy(totalBytes = good.totalBytes + 1)
        assertFalse(ExecutorProtocol.validateManifest(changed).valid)
    }

    @Test fun manifestRejectsTraversalSecretsAndDuplicatePaths() {
        val base = manifest()
        val bad = base.copy(files = listOf(
            ExecutorProtocol.BundleFile("../outside", 1, "a".repeat(64)),
            ExecutorProtocol.BundleFile("local.properties", 1, "b".repeat(64)),
            ExecutorProtocol.BundleFile("README.md", 1, "c".repeat(64)),
            ExecutorProtocol.BundleFile("readme.md", 1, "d".repeat(64))
        ), totalBytes = 4)
        assertFalse(ExecutorProtocol.validateManifest(bad, verifyDigest = false).valid)
    }

    @Test fun requestRejectsNetworkAndExcessiveResourcePolicy() {
        val valid = request()
        assertTrue(ExecutorProtocol.validateRequest(valid).valid)
        assertFalse(ExecutorProtocol.validateRequest(valid.copy(policy = valid.policy.copy(networkAllowed = true))).valid)
        assertFalse(ExecutorProtocol.validateRequest(valid.copy(policy = valid.policy.copy(timeoutMs = Long.MAX_VALUE))).valid)
        assertFalse(ExecutorProtocol.validateRequest(valid.copy(policy = valid.policy.copy(memoryMb = 8192))).valid)
    }

    @Test fun signedReceiptPassesOnlyWhenBoundToRequestAndPinnedKey() {
        val req = request()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val unsigned = ExecutorProtocol.ExecutionReceipt(1, req.runId, req.manifest.digest, req.task,
            "desktop-executor", "0.1.0", now - 2_000, now - 1_000, 0, "PASS", true, true,
            req.policy.timeoutMs, req.policy.memoryMb, req.policy.cpuCores, "c".repeat(64), "")
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private); update(ExecutorProtocol.canonicalReceipt(unsigned).toByteArray(Charsets.UTF_8))
        }
        val receipt = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()))
        assertTrue(ExecutorProtocol.verifyReceipt(req, receipt, pair.public, now).valid)
        assertFalse(ExecutorProtocol.verifyReceipt(req, receipt.copy(runId = "other-run"), pair.public, now).valid)
        val otherPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(ExecutorProtocol.verifyReceipt(req, receipt, otherPair.public, now).valid)
    }

    @Test fun receiptRejectsAmbiguousExecutorVersionAndExecutionBeyondTimeout() {
        val req = request()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun signed(value: ExecutorProtocol.ExecutionReceipt): ExecutorProtocol.ExecutionReceipt {
            val signer = Signature.getInstance("SHA256withRSA").apply {
                initSign(pair.private); update(ExecutorProtocol.canonicalReceipt(value).toByteArray(Charsets.UTF_8))
            }
            return value.copy(signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()))
        }
        val base = ExecutorProtocol.ExecutionReceipt(1, req.runId, req.manifest.digest, req.task,
            "desktop-executor", "0.1.0", now - 2_000, now - 1_000, 0, "PASS", true, true,
            req.policy.timeoutMs, req.policy.memoryMb, req.policy.cpuCores, "e".repeat(64), "")
        assertFalse(ExecutorProtocol.verifyReceipt(req, signed(base.copy(executorVersion = "1.0\nFAKE")), pair.public, now).valid)
        assertFalse(ExecutorProtocol.verifyReceipt(req, signed(base.copy(startedAt = now - req.policy.timeoutMs - 60_000)), pair.public, now).valid)
    }

    @Test fun receiptCannotPassWithoutSandboxOrWithExpiredTimestamp() {
        val req = request()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val unsigned = ExecutorProtocol.ExecutionReceipt(1, req.runId, req.manifest.digest, req.task,
            "desktop-executor", "0.1.0", now - 2_000, now - 1_000, 0, "PASS", false, true,
            req.policy.timeoutMs, req.policy.memoryMb, req.policy.cpuCores, "d".repeat(64), "")
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private); update(ExecutorProtocol.canonicalReceipt(unsigned).toByteArray(Charsets.UTF_8))
        }
        val receipt = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()))
        assertFalse(ExecutorProtocol.verifyReceipt(req, receipt, pair.public, now).valid)
        assertFalse(ExecutorProtocol.verifyReceipt(req, receipt.copy(completedAt = now - 900_000), pair.public, now).valid)
    }
}
