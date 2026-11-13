package pl.aimasterpro.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONObject
import pl.aimasterpro.app.core.CompanionIdentityChallenge
import pl.aimasterpro.app.core.CompanionPeerPinSet
import pl.aimasterpro.app.core.CompanionPinnedTlsProfile
import pl.aimasterpro.app.core.CompanionPairingAuthority
import pl.aimasterpro.app.core.CompanionSessionOwner
import pl.aimasterpro.app.core.CompanionProvisionedSession
import pl.aimasterpro.app.core.CompanionSpkiSha256Pin
import pl.aimasterpro.app.core.CompanionTrustRevocationEvent
import pl.aimasterpro.app.core.CompanionTrustRevocationListener
import pl.aimasterpro.app.core.CompanionTrustRevocationFenceListener
import pl.aimasterpro.app.core.CompanionTrustRevocationPublisher
import pl.aimasterpro.app.core.CompanionTrustRevocationSource
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Raised when persisted Companion trust data cannot be decrypted; recovery must be explicit. */
class CompanionProvisioningRecoveryRequiredException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Trust data is already durably changed when this exception is thrown. */
class CompanionTrustRevocationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {
    val trustChangeCommitted: Boolean = true
}

/** Public, non-secret description of the local Keystore identity. */
data class CompanionLocalIdentity(
    val alias: String,
    val certificate: X509Certificate,
    val publicKeySha256: String
)

/** Narrow I/O boundary so failure outcomes can be tested against the production coordinator. */
internal interface CompanionTrustIo {
    fun commit(editor: SharedPreferences.Editor): Boolean
    fun atomicReplace(source: java.nio.file.Path, target: java.nio.file.Path)
    fun deleteIfExists(path: java.nio.file.Path): Boolean
    fun syncDirectory(directory: File)
    fun deleteKeyAlias(alias: String)
}

/** Real Android/JVM implementation; tests may decorate individual operations deterministically. */
internal object AndroidCompanionTrustIo : CompanionTrustIo {
    override fun commit(editor: SharedPreferences.Editor): Boolean = editor.commit()

    override fun atomicReplace(source: java.nio.file.Path, target: java.nio.file.Path) {
        Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    override fun deleteIfExists(path: java.nio.file.Path): Boolean = Files.deleteIfExists(path)

    override fun syncDirectory(directory: File) {
        try {
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use {
                it.force(true)
            }
        } catch (_: Throwable) {
            // Ignore on platforms/filesystems where directory channel opening is restricted (e.g. Windows Robolectric).
        }
    }

    override fun deleteKeyAlias(alias: String) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}

/**
 * Android-backed Companion provisioning primitives.
 * Private signing and metadata-encryption keys never leave Android Keystore. Peer pins are stored
 * as authenticated ciphertext in app-private preferences. Loss of the metadata key is fail-closed:
 * this class never silently creates a replacement that would make old trust records disappear.
 * The generated Keystore certificate is a local key identity, not a CA-issued TLS certificate;
 * production TLS still requires a certificate chain with the correct SANs from the pairing/CA flow.
 */
class CompanionProvisioningStore internal constructor(
    context: Context,
    private val trustIo: CompanionTrustIo = AndroidCompanionTrustIo
) : CompanionTrustRevocationSource {
    private val appContext = context.applicationContext
    private val processTrustState = PROCESS_TRUST_STATE
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // The publisher must share the same process scope as the trust monitor: a mutation through
    // any store instance must fence sessions bound through the application-scoped store.
    private val revocationPublisher = processTrustState.revocationPublisher

    /** Internal deterministic fault seam; production leaves it as a no-op. */
    @Volatile internal var faultInjector: ((String) -> Unit)? = null

    private fun hitFaultPoint(point: String) {
        faultInjector?.invoke(point)
    }

    override fun addTrustRevocationListener(listener: CompanionTrustRevocationListener): Boolean =
        revocationPublisher.add(listener)

    override fun removeTrustRevocationListener(listener: CompanionTrustRevocationListener): Boolean =
        revocationPublisher.remove(listener)

    override fun addTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean =
        revocationPublisher.addFence(listener)

    override fun removeTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean =
        revocationPublisher.removeFence(listener)

    @Synchronized
    fun provisionLocalIdentity(): CompanionLocalIdentity {
        val store = androidKeyStore()
        if (!store.containsAlias(IDENTITY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    IDENTITY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=AI MASTER Companion"))
                    .setCertificateSerialNumber(java.math.BigInteger.valueOf(1L))
                    .setCertificateNotBefore(java.util.Date(0L))
                    .setCertificateNotAfter(java.util.Date(4_102_444_800_000L))
                    .build()
            )
            generator.generateKeyPair()
        }
        val certificate = store.getCertificate(IDENTITY_ALIAS) as? X509Certificate
            ?: throw CompanionProvisioningRecoveryRequiredException("Brak certyfikatu lokalnej tożsamości Companion.")
        val privateKey = store.getKey(IDENTITY_ALIAS, null) as? java.security.PrivateKey
            ?: throw CompanionProvisioningRecoveryRequiredException("Brak prywatnego klucza lokalnej tożsamości Companion.")
        require(privateKey.algorithm.equals("EC", ignoreCase = true)) {
            "Nieobsługiwany algorytm klucza lokalnej tożsamości Companion."
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        val fingerprint = digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        return CompanionLocalIdentity(IDENTITY_ALIAS, certificate, fingerprint)
    }

    /** Signs a challenge with the non-exportable local EC private key. */
    @Synchronized
    fun signChallenge(challenge: ByteArray): ByteArray {
        val signedMessage = CompanionIdentityChallenge.message(challenge)
        val store = androidKeyStore()
        val key = store.getKey(IDENTITY_ALIAS, null) as? java.security.PrivateKey
            ?: throw IllegalStateException("Lokalna tożsamość Companion nie została provisionowana.")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(signedMessage)
            sign()
        }
    }

    fun readPeerPins(): Map<String, CompanionPeerPinSet> = synchronized(processTrustState.monitor) {
        decodeRecords(readEncryptedPayload())
    }

    /** Ready-to-use peerId -> accepted current/next SPKI map for CompanionPinnedTlsProfile.create(). */
    fun readTlsPinsByPeerId(): Map<String, Set<CompanionSpkiSha256Pin>> =
        readPeerPins().mapValues { (_, pins) -> setOfNotNull(pins.current, pins.next) }

    /**
     * Builds a fresh TLS profile from the currently persisted trust snapshot. The identity
     * KeyStore must contain a TLS-capable private key and its complete certificate chain; the
     * locally generated self-signed provisioning certificate is not automatically suitable for
     * TLS because it has no issued SAN/CA chain. Call this again after trust changes and close
     * all sessions/profiles created from an older snapshot after peer revocation.
     */
    @Synchronized
    fun createPinnedTlsProfile(
        tlsIdentityKeyStore: KeyStore,
        keyPassword: CharArray?,
        trustStore: KeyStore
    ): CompanionPinnedTlsProfile {
        val pins = readTlsPinsByPeerId()
        check(pins.isNotEmpty()) { "Brak provisionowanych peerów; TLS Companion pozostaje zablokowany." }
        return CompanionPinnedTlsProfile.create(tlsIdentityKeyStore, keyPassword, trustStore, pins)
    }

    /**
     * Snapshots persisted peer pins and binds the resulting profile to one session owner. The
     * returned capability prevents callers from accidentally pairing that owner with another TLS
     * profile at the admission boundary.
     */
    @Synchronized
    fun createSessionProvision(
        pairingAuthority: CompanionPairingAuthority,
        pairingSecret: ByteArray,
        tlsIdentityKeyStore: KeyStore,
        keyPassword: CharArray?,
        trustStore: KeyStore,
        handshakeTimeoutNanos: Long,
        idleTimeoutNanos: Long,
        transferTimeoutNanos: Long,
        hardLifetimeNanos: Long
    ): CompanionProvisionedSession {
        val profile = createPinnedTlsProfile(tlsIdentityKeyStore, keyPassword, trustStore)
        try {
            val owner = CompanionSessionOwner(
                pairingAuthority,
                pairingSecret,
                profile,
                handshakeTimeoutNanos,
                idleTimeoutNanos,
                transferTimeoutNanos,
                hardLifetimeNanos,
                trustValidator = { peerId, pin ->
                    try {
                        synchronized(processTrustState.monitor) {
                            readPeerPins()[peerId]?.accepts(pin) == true
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
            )
            return CompanionProvisionedSession(profile, owner)
        } catch (error: Throwable) {
            profile.close()
            throw error
        }
    }

    @Deprecated(
        message = "Użyj createSessionProvision(), aby nie rozdzielać profilu TLS i ownera sesji."
    )
    @Synchronized
    fun createSessionOwner(
        pairingAuthority: CompanionPairingAuthority,
        pairingSecret: ByteArray,
        tlsIdentityKeyStore: KeyStore,
        keyPassword: CharArray?,
        trustStore: KeyStore,
        handshakeTimeoutNanos: Long,
        idleTimeoutNanos: Long,
        transferTimeoutNanos: Long,
        hardLifetimeNanos: Long
    ): CompanionSessionOwner {
        val profile = createPinnedTlsProfile(tlsIdentityKeyStore, keyPassword, trustStore)
        return try {
            CompanionSessionOwner(
                pairingAuthority,
                pairingSecret,
                profile,
                handshakeTimeoutNanos,
                idleTimeoutNanos,
                transferTimeoutNanos,
                hardLifetimeNanos,
                trustValidator = { peerId, pin ->
                    try {
                        synchronized(processTrustState.monitor) {
                            readPeerPins()[peerId]?.accepts(pin) == true
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
            )
        } catch (error: Throwable) {
            profile.close()
            throw error
        }
    }

    fun cancelPeerRotation(peerId: String) {
        synchronized(processTrustState.monitor) {
            validatePeerId(peerId)
            val records = decodeRecords(readEncryptedPayload()).toMutableMap()
            val existing = records[peerId] ?: throw IllegalArgumentException("Nieznany peer.")
            records[peerId] = existing.cancelStaged()
            persist(records)
        }
    }

    fun provisionPeer(peerId: String, currentPin: CompanionSpkiSha256Pin) {
        synchronized(processTrustState.monitor) {
            validatePeerId(peerId)
            val records = decodeRecords(readEncryptedPayload()).toMutableMap()
            require(peerId !in records) { "Peer jest już provisionowany; użyj jawnej procedury rotacji." }
            records[peerId] = CompanionPeerPinSet(currentPin)
            persist(records)
        }
    }

    /** Stages an overlap pin; existing sessions remain valid until explicit promotion. */
    /** Stages an overlap pin; active sessions stay alive until promotePeerRotation(). */
    fun stagePeerRotation(peerId: String, nextPin: CompanionSpkiSha256Pin) {
        synchronized(processTrustState.monitor) {
            validatePeerId(peerId)
            val records = decodeRecords(readEncryptedPayload()).toMutableMap()
            val existing = records[peerId] ?: throw IllegalArgumentException("Nieznany peer.")
            records[peerId] = existing.stage(nextPin)
            persist(records)
        }
    }

    /**
     * Promotes the explicitly staged SPKI pin and invalidates every live session for this peer.
     * Staging deliberately keeps existing sessions alive; promotion is the trust-boundary change
     * and therefore fences admission before the durable write, then closes detached sessions
     * after releasing the store monitor. If persistence fails, the peer remains fail-closed and
     * its existing sessions are still invalidated.
     */
    fun promotePeerRotation(peerId: String) {
        validatePeerId(peerId)
        val event = CompanionTrustRevocationEvent(peerId)
        var mutationFailure: Throwable? = null
        var mutationCommitted = false
        val fenceBatch = synchronized(processTrustState.monitor) {
            // Validate caller preconditions before advancing the revocation epoch. An unknown peer
            // or absent staged pin is not a failed durable mutation and must not wipe all trust.
            val records = try {
                decodeRecords(readEncryptedPayload()).toMutableMap()
            } catch (error: Throwable) {
                val batch = revocationPublisher.beginRevocation(event)
                mutationFailure = error
                lockTrustAfterFailedMutationLocked(error)
                return@synchronized batch
            }
            val existing = records[peerId] ?: throw IllegalArgumentException("Nieznany peer.")
            val promoted = existing.promoteNext()
            val batch = revocationPublisher.beginRevocation(event)
            if (batch.preparationFailures.isNotEmpty()) {
                val failure = batch.preparationFailures.first()
                mutationFailure = failure
                lockTrustAfterFailedMutationLocked(failure)
            } else {
                try {
                    records[peerId] = promoted
                    persist(records)
                    mutationCommitted = true
                } catch (error: Throwable) {
                    mutationFailure = error
                    lockTrustAfterFailedMutationLocked(error)
                }
            }
            batch
        }
        publishRevocationAfterMutation(
            event, mutationFailure, mutationCommitted,
            "Nie udało się potwierdzić trwałej promocji pinu peera $peerId; aktywne sesje zostały unieważnione.",
            "Pin peera $peerId został zmieniony, ale nie wszystkie stare sesje potwierdziły zamknięcie.",
            fenceBatch = fenceBatch
        )
    }

    /**
     * Revocation removes both current and staged pins. Durable commit is performed under the
     * store monitor; listeners are always invoked after releasing it. An absent record still
     * publishes the event so a retry can invalidate handles left by an earlier callback failure.
     */
    fun revokePeer(peerId: String) {
        validatePeerId(peerId)
        val event = CompanionTrustRevocationEvent(peerId)
        var mutationFailure: Throwable? = null
        var mutationCommitted = false
        val fenceBatch = synchronized(processTrustState.monitor) {
            val batch = revocationPublisher.beginRevocation(event)
            if (batch.preparationFailures.isNotEmpty()) {
                val failure = batch.preparationFailures.first()
                mutationFailure = failure
                lockTrustAfterFailedMutationLocked(failure)
            } else {
                try {
                    val records = decodeRecords(readEncryptedPayload()).toMutableMap()
                    if (records.remove(peerId) != null) persist(records)
                    mutationCommitted = true
                } catch (error: Throwable) {
                    mutationFailure = error
                    lockTrustAfterFailedMutationLocked(error)
                }
            }
            batch
        }
        publishRevocationAfterMutation(
            event, mutationFailure, mutationCommitted,
            "Nie udało się potwierdzić trwałego usunięcia peera $peerId; aktywne sesje zostały unieważnione.",
            "Peer $peerId został usunięty z magazynu, ale nie wszystkie sesje potwierdziły unieważnienie.",
            fenceBatch = fenceBatch
        )
    }

    /**
     * Explicit recovery only (CompanionTrustRecovery). Every wipe attempt triggers global session
     * invalidation, even if SharedPreferences commit returns false or throws. Alias deletion is
     * attempted only after pin removal has been confirmed; listener dispatch always occurs after
     * releasing the store monitor.
     */
    fun wipeAllTrust() {
        val event = CompanionTrustRevocationEvent(peerId = null)
        var mutationFailure: Throwable? = null
        var mutationCommitted = false
        var aliasFailure: Throwable? = null
        val fenceBatch = synchronized(processTrustState.monitor) {
            val batch = revocationPublisher.beginRevocation(event)
            if (batch.preparationFailures.isNotEmpty()) {
                val failure = batch.preparationFailures.first()
                mutationFailure = failure
                lockTrustAfterFailedMutationLocked(failure)
            } else {
                try {
                    hitFaultPoint("wipe.before_preferences_commit")
                    mutationCommitted = trustIo.commit(
                        prefs.edit().remove(PINS_KEY).remove(TRUST_LOCK_KEY)
                    )
                    hitFaultPoint("wipe.after_preferences_commit")
                    check(mutationCommitted) { "Nie udało się trwale usunąć magazynu zaufania Companion." }
                    // The independent no-backup marker must also be removed before trust can
                    // become available again. If deletion fails, keep the process latch closed.
                    hitFaultPoint("wipe.before_file_marker_delete")
                    if (!deletePersistentTrustLockFile()) {
                        aliasFailure = IllegalStateException(
                            "Nie udało się usunąć trwałego znacznika blokady Companion."
                        )
                    } else {
                        hitFaultPoint("wipe.after_file_marker_delete")
                        processTrustState.unavailableAfterFailedMutation = false
                    }
                } catch (error: Throwable) {
                    mutationFailure = error
                    lockTrustAfterFailedMutationLocked(error)
                }
                if (mutationCommitted) {
                    try {
                        hitFaultPoint("wipe.before_metadata_alias_delete")
                        trustIo.deleteKeyAlias(METADATA_ALIAS)
                        hitFaultPoint("wipe.after_metadata_alias_delete")
                    } catch (error: Throwable) {
                        val previousFailure = aliasFailure
                        if (previousFailure == null) aliasFailure = error
                        else if (previousFailure !== error) previousFailure.addSuppressed(error)
                    }
                }
            }
            batch
        }
        publishRevocationAfterMutation(
            event, mutationFailure, mutationCommitted,
            "Nie udało się potwierdzić trwałego usunięcia magazynu zaufania; aktywne sesje zostały unieważnione.",
            "Magazyn pinów został trwale usunięty; wystąpił błąd podczas pełnego unieważnienia Companion.",
            aliasFailure, fenceBatch
        )
    }

    /** Always dispatches after a trust mutation attempt, preserving fatal failures until cleanup. */
    private fun publishRevocationAfterMutation(
        event: CompanionTrustRevocationEvent,
        mutationFailure: Throwable?,
        mutationCommitted: Boolean,
        mutationFailureMessage: String,
        dispatchFailureMessage: String,
        aliasFailure: Throwable? = null,
        fenceBatch: CompanionTrustRevocationPublisher.FenceBatch? = null
    ) {
        var fenceFailure: Throwable? = null
        try {
            fenceBatch?.close()
        } catch (error: Throwable) {
            fenceFailure = error
        }
        var dispatchFatal: Error? = null
        val listenerFailures = try {
            revocationPublisher.publish(event)
        } catch (error: Error) {
            // Publisher has attempted every listener before propagating a fatal callback.
            dispatchFatal = error
            emptyList()
        }

        val fatalMutation = mutationFailure as? Error
        val fatalAlias = aliasFailure as? Error
        val fatalFence = fenceFailure as? Error
        val primaryFatal = fatalMutation ?: fatalAlias ?: fatalFence ?: dispatchFatal
        if (primaryFatal != null) {
            listOfNotNull(mutationFailure, aliasFailure, fenceFailure, dispatchFatal).forEach { secondary ->
                if (secondary !== primaryFatal) primaryFatal.addSuppressed(secondary)
            }
            throw primaryFatal
        }

        if (!mutationCommitted || mutationFailure != null) {
            val error = IllegalStateException(mutationFailureMessage, mutationFailure ?: fenceFailure)
            listOfNotNull(aliasFailure, fenceFailure).forEach { if (it !== mutationFailure) error.addSuppressed(it) }
            listenerFailures.forEach(error::addSuppressed)
            throw error
        }

        throwIfRevocationIncomplete(dispatchFailureMessage, listenerFailures, aliasFailure ?: fenceFailure)
    }

    private fun throwIfRevocationIncomplete(
        message: String,
        listenerFailures: List<Exception>,
        aliasFailure: Throwable? = null
    ) {
        if (listenerFailures.isEmpty() && aliasFailure == null) return
        val cause = listenerFailures.firstOrNull() ?: aliasFailure
        val error = CompanionTrustRevocationException(message, cause)
        listenerFailures.drop(1).forEach(error::addSuppressed)
        if (aliasFailure != null && aliasFailure !== cause) error.addSuppressed(aliasFailure)
        throw error
    }


    /**
     * A failed destructive mutation must not leave the old encrypted pins usable for fresh
     * handshakes. The in-process latch closes the admission window immediately; deleting the
     * metadata key makes the persisted ciphertext undecryptable after process restart as well.
     * If Keystore deletion itself fails, that failure is attached to the original mutation error
     * and the process-local latch remains active. Recovery must be explicit, never silent.
     * Caller holds the application-context process trust monitor.
     */
    private fun lockTrustAfterFailedMutationLocked(cause: Throwable) {
        processTrustState.unavailableAfterFailedMutation = true
        // Redundant durable barriers: preferences and a no-backup file have independent
        // persistence paths. The process latch closes the current process immediately.
        try {
            hitFaultPoint("lock.before_preferences_commit")
            check(trustIo.commit(prefs.edit().putBoolean(TRUST_LOCK_KEY, true))) {
                "Nie udało się utrwalić znacznika blokady Companion w preferences."
            }
            hitFaultPoint("lock.after_preferences_commit")
        } catch (lockFailure: Throwable) {
            if (lockFailure !== cause) cause.addSuppressed(lockFailure)
        }
        try {
            hitFaultPoint("lock.before_file_marker_publish")
            writePersistentTrustLockFile()
            hitFaultPoint("lock.after_file_marker_publish")
        } catch (lockFailure: Throwable) {
            if (lockFailure !== cause) cause.addSuppressed(lockFailure)
        }
        // Last-resort cryptographic barrier: old ciphertext becomes undecryptable after restart.
        try {
            hitFaultPoint("lock.before_metadata_alias_delete")
            trustIo.deleteKeyAlias(METADATA_ALIAS)
            hitFaultPoint("lock.after_metadata_alias_delete")
        } catch (lockFailure: Throwable) {
            if (lockFailure !== cause) cause.addSuppressed(lockFailure)
        }
    }

    private fun persistentTrustLockFile(): File =
        File(appContext.noBackupFilesDir, TRUST_LOCK_FILE_NAME)

    /** Fsynced temporary file + atomic rename publishes the independent lock marker. */
    private fun writePersistentTrustLockFile() {
        val target = persistentTrustLockFile()
        val directory = target.parentFile
            ?: throw IllegalStateException("Brak katalogu dla znacznika blokady Companion.")
        check(directory.exists() || directory.mkdirs()) {
            "Nie udało się utworzyć katalogu znacznika blokady Companion."
        }
        // A unique temporary path prevents two store instances from truncating or moving
        // each other's in-progress marker. The final target remains a single atomic name.
        val temporaryPath = Files.createTempFile(
            directory.toPath(),
            "$TRUST_LOCK_FILE_NAME.",
            ".tmp"
        )
        var primaryFailure: Throwable? = null
        try {
            FileOutputStream(temporaryPath.toFile()).use { output ->
                output.write(TRUST_LOCK_FILE_CONTENT.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            hitFaultPoint("marker.before_atomic_move")
            trustIo.atomicReplace(temporaryPath, target.toPath())
            hitFaultPoint("marker.after_atomic_move")
            // fsync on the file persists its contents, not necessarily the directory entry
            // created by rename. Sync the parent directory before reporting durable publication.
            hitFaultPoint("marker.before_directory_fsync_publish")
            syncPersistentTrustLockDirectory(directory)
            hitFaultPoint("marker.after_directory_fsync_publish")
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                trustIo.deleteIfExists(temporaryPath)
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original != null) {
                    if (cleanupFailure !== original) original.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private fun persistentTrustLockFileExists(): Boolean = try {
        Files.readAttributes(
            persistentTrustLockFile().toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun deletePersistentTrustLockFile(): Boolean {
        val target = persistentTrustLockFile()
        hitFaultPoint("marker.before_delete")
        if (trustIo.deleteIfExists(target.toPath())) {
            hitFaultPoint("marker.after_delete")
            val directory = target.parentFile
                ?: throw IllegalStateException("Brak katalogu dla znacznika blokady Companion.")
            // Do not unlock the current process until marker deletion is durable at directory level.
            hitFaultPoint("marker.before_directory_fsync_delete")
            syncPersistentTrustLockDirectory(directory)
            hitFaultPoint("marker.after_directory_fsync_delete")
        }
        return true
    }

    /** Persists directory-entry changes after atomic marker publication or deletion. */
    private fun syncPersistentTrustLockDirectory(directory: File) {
        trustIo.syncDirectory(directory)
    }

    private fun persist(records: Map<String, CompanionPeerPinSet>) {
        require(records.size <= MAX_PEERS) { "Osiągnięto limit provisionowanych peerów." }
        val json = JSONObject()
        records.toSortedMap().forEach { (id, pins) ->
            json.put(id, JSONObject().put("current", pins.current.toString()).apply {
                pins.next?.let { put("next", it.toString()) }
            })
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, metadataKey())
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        hitFaultPoint("pins.before_preferences_commit")
        val saved = trustIo.commit(
            prefs.edit().putString(PINS_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
        )
        hitFaultPoint("pins.after_preferences_commit")
        check(saved) { "Nie udało się trwale zapisać mapy zaufania Companion." }
    }

    private fun readEncryptedPayload(): String = synchronized(processTrustState.monitor) {
        val persistentlyLocked = try {
            prefs.getBoolean(TRUST_LOCK_KEY, false) || persistentTrustLockFileExists()
        } catch (error: Throwable) {
            throw CompanionProvisioningRecoveryRequiredException(
                "Nie można wiarygodnie odczytać stanu blokady Companion; dostęp do zaufania pozostaje zamknięty.",
                error
            )
        }
        if (processTrustState.unavailableAfterFailedMutation || persistentlyLocked) {
            throw CompanionProvisioningRecoveryRequiredException(
                "Magazyn zaufania Companion pozostaje zablokowany po nieudanej zmianie; wymagane jawne odzyskanie zaufania."
            )
        }
        val encoded = prefs.getString(PINS_KEY, null) ?: return "{}"
        try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size in (GCM_IV_BYTES + GCM_TAG_BYTES)..MAX_ENCRYPTED_BYTES) { "Nieprawidłowy rozmiar magazynu Companion." }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, metadataKey(), GCMParameterSpec(GCM_TAG_BITS, packed.copyOfRange(0, GCM_IV_BYTES)))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(packed.copyOfRange(GCM_IV_BYTES, packed.size)), Charsets.UTF_8)
        } catch (error: Exception) {
            throw CompanionProvisioningRecoveryRequiredException(
                "Nie można uwierzytelnić magazynu zaufania Companion. Dane pozostają zablokowane; wymagane jawne ponowne parowanie.", error
            )
        }
    }

    private fun decodeRecords(payload: String): Map<String, CompanionPeerPinSet> {
        val root = JSONObject(payload)
        require(root.length() <= MAX_PEERS) { "Magazyn zawiera zbyt wiele peerów." }
        val result = linkedMapOf<String, CompanionPeerPinSet>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            validatePeerId(id)
            val item = root.getJSONObject(id)
            val current = CompanionSpkiSha256Pin.parse(item.getString("current"))
            val next = if (item.has("next")) CompanionSpkiSha256Pin.parse(item.getString("next")) else null
            result[id] = CompanionPeerPinSet(current, next)
        }
        return result.toMap()
    }

    private fun metadataKey(): SecretKey {
        val store = androidKeyStore()
        if (store.containsAlias(METADATA_ALIAS)) {
            return store.getKey(METADATA_ALIAS, null) as? SecretKey
                ?: throw CompanionProvisioningRecoveryRequiredException("Klucz szyfrujący magazyn Companion ma nieprawidłowy typ.")
        }
        if (prefs.contains(PINS_KEY)) {
            throw CompanionProvisioningRecoveryRequiredException("Brak klucza Android Keystore do istniejących danych Companion.")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(METADATA_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun validatePeerId(peerId: String) {
        require(peerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Nieprawidłowy identyfikator peer." }
    }

    companion object {
        private class ProcessTrustState {
            val monitor = Any()
            val revocationPublisher = CompanionTrustRevocationPublisher()
            @Volatile var unavailableAfterFailedMutation: Boolean = false
        }

        /** One trust monitor/latch for every store instance in this Android application process. */
        private val PROCESS_TRUST_STATE = ProcessTrustState()

        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val PREFS_NAME = "companion_provisioning_v1"
        private const val IDENTITY_ALIAS = "ai_master_companion_identity_v1"
        private const val METADATA_ALIAS = "ai_master_companion_metadata_aes_v1"
        private const val PINS_KEY = "peer_pin_records_v1"
        private const val TRUST_LOCK_KEY = "trust_unavailable_after_failed_mutation_v1"
        private const val TRUST_LOCK_FILE_NAME = "companion_trust_unavailable.lock"
        private const val TRUST_LOCK_FILE_CONTENT = "AI_MASTER_COMPANION_TRUST_LOCK_V1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AAD = "AI_MASTER_COMPANION_PEER_PINS_V1"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val MAX_PEERS = 64
        private const val MAX_ENCRYPTED_BYTES = 64 * 1024
    }
}
