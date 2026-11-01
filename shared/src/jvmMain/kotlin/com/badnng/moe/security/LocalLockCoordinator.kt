package com.badnng.moe.security

import com.badnng.moe.settings.LocalLockActions
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.settings.LocalLockSnapshot
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LocalLockWitness(val epoch: Long, val blocked: Boolean)

/** Opaque identity only. The coordinator privately owns all key and root bindings. */
internal class LocalSessionKeyTicket internal constructor(val epoch: Long) {
    override fun toString(): String = "LocalSessionKeyTicket[redacted]"
}

/** Synthetic tests can inject a fake. The production default always uses the fixed strong facade. */
internal interface LocalLockCryptoOperations {
    fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey>
    fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey>
    fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray>
}

private object ProductionLocalLockCrypto : LocalLockCryptoOperations {
    override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long) =
        LocalPasscodeCrypto.create(password, rootIdentity, generation)
    override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long) =
        LocalPasscodeCrypto.open(password, record, rootIdentity, generation)
    override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long) =
        LocalPasscodeCrypto.rewrap(password, key, rootIdentity, generation)
}

/**
 * One coordinator per held root lease. All actions can be called from any thread;
 * slow work is serialized separately from the short admission/revocation monitor.
 * The immutable witness/slot control is readable without either monitor.
 * Engine callbacks must enqueue actor work and never retain borrowed key bytes.
 */
internal class LocalLockCoordinator(
    private val root: LocalProtectionRoot,
    private val worker: Executor,
    private val onStartRequested: (epoch: Long, protected: Boolean) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onWitnessChanged: (LocalLockWitness) -> Unit = {},
    private val schedule: (Long, () -> Unit) -> Unit = { delay, task ->
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(task)
    },
    private val persistence: LocalProtectionPersistence = LocalProtectionRecordStore(root),
    private val crypto: LocalLockCryptoOperations = ProductionLocalLockCrypto,
    private val preferences: LocalLockPreferences = FileLocalLockPreferences(root),
    private val entropy: CryptoEntropy = CryptoEntropy { SecureRandom().nextBytes(it) },
    private val inspectOpenWrap: (ByteArray, ByteArray) -> LocalRecordRead = LocalOpenWrap::inspect,
    private val recordDigest: (ByteArray) -> ByteArray = { MessageDigest.getInstance("SHA-256").digest(it) },
) {
    private val lock = Any()
    /**
     * No root/filesystem calls, KDF or executor work. borrowSessionKey alone
     * admits a bounded synchronous native send and its temporary byte cleanup.
     */
    private val admissionLock = Any()
    private val mutableState = MutableStateFlow(LocalLockSnapshot())
    val state: StateFlow<LocalLockSnapshot> = mutableState.asStateFlow()
    private val controlRef = AtomicReference(Control(LocalLockWitness(0, true)))
    private var control: Control
        get() = controlRef.get()
        set(value) { controlRef.set(value) }
    private val witness: LocalLockWitness get() = control.witness
    private var initialized = false
    private val stopping: Boolean get() = control.stopping
    private val workerBusy: Boolean get() = control.flight != null
    private var record: LocalStoredEnvelope? = null
    private var startupKey: LocalDatabaseKey? = null
    private var startupRootRevision = 0L
    private var startupRootIdentity: ByteArray? = null
    private var startupSubmitted = false
    private val managementKey: LocalDatabaseKey? get() = control.managementKey
    private var managementDeadline: Long? = null
    private var managementTimerArmed = false
    private var failureCount = 0
    private var retryDeadline: Long? = null
    private var retryTimerArmed = false
    private var onStopped: (() -> Unit)? = null

    val actions = LocalLockActions(
        create = { password, confirmation -> submit(Operation.CREATE, password, confirmation) },
        unlock = { password -> submit(Operation.UNLOCK, password) },
        verifyManagement = { password -> submit(Operation.VERIFY, password) },
        change = { password, confirmation -> submit(Operation.CHANGE, password, confirmation) },
        remove = ::removePassword,
        lockNow = ::lockNow,
        managementActivity = ::managementActivity,
        closeManagement = ::closeManagement,
        setAutoLockSeconds = ::setAutoLockSeconds,
        skipSetup = ::skipSetup,
        cancel = ::cancel,
    )

    fun currentWitness(): LocalLockWitness = witness
    fun isCurrentAndOpen(captured: LocalLockWitness): Boolean = !captured.blocked && witness == captured

    /** Stable for this lease. Capturing a handle never borrows D or performs I/O. */
    fun captureSessionKeyTicket(): LocalSessionKeyTicket? = synchronized(admissionLock) {
        val lease = control.sessionLease ?: return null
        lease.ticket.takeIf { currentSessionLeaseLocked(it) === lease && !lease.startupPending }
    }

    /**
     * Admits preparation intent after real Closed, without holding this monitor
     * across any selection/directory operation. The engine owns its single flight
     * and rechecks revocation between separately started I/O operations.
     */
    fun admitSessionPreparation(
        ticket: LocalSessionKeyTicket,
        expectedRootRevision: Long,
        engineCurrent: () -> Boolean,
    ): Boolean = synchronized(admissionLock) {
        val lease = currentSessionLeaseLocked(ticket) ?: return false
        if (lease.createGranted || lease.rootRevision != expectedRootRevision) return false
        val current = try { engineCurrent() } catch (_: Throwable) { false }
        current && currentSessionLeaseLocked(ticket) === lease
    }

    /** Repeated short checks between parameter-validation reads after create grant. */
    fun admitSessionParameterValidation(
        ticket: LocalSessionKeyTicket,
        expectedRootRevision: Long,
        engineCurrent: () -> Boolean,
    ): Boolean = synchronized(admissionLock) {
        val lease = currentSessionLeaseLocked(ticket) ?: return false
        lease.rootRevision == expectedRootRevision && lease.startupPending && lease.createGranted &&
            !lease.startupSubmitted && ownerReadCurrentLocked(lease, engineCurrent)
    }

    /**
     * Grants only creation intent. engineCurrent must contain bounded atomic
     * ownership checks; native create and all I/O run after this monitor returns.
     */
    fun grantSessionCreate(
        ticket: LocalSessionKeyTicket,
        expectedRootRevision: Long,
        engineCurrent: () -> Boolean,
    ): Boolean = synchronized(admissionLock) {
        val lease = currentSessionLeaseLocked(ticket) ?: return false
        if (!lease.startupPending || lease.createGranted || lease.rootRevision != expectedRootRevision) return false
        val current = try { engineCurrent() } catch (_: Throwable) { false }
        if (!current || currentSessionLeaseLocked(ticket) !== lease) return false
        control = control.copy(sessionLease = lease.copy(createGranted = true))
        true
    }

    /**
     * The caller checks its native session/selection/stop ownership inside submit.
     * Only the bounded synchronous native dispatch belongs in that callback.
     */
    fun borrowSessionKey(ticket: LocalSessionKeyTicket, submit: (ByteArray) -> Unit): Boolean =
        borrowSessionKey(ticket, { true }, submit)

    fun borrowSessionKey(
        ticket: LocalSessionKeyTicket,
        engineCurrent: () -> Boolean,
        submit: (ByteArray) -> Unit,
    ): Boolean {
        var changed: LocalLockWitness? = null
        val accepted = synchronized(lock) {
            val lease = synchronized(admissionLock) {
                val current = currentSessionLeaseLocked(ticket) ?: return false
                // Reject a duplicate before any I/O, including a reentrant call
                // from an already admitted synchronous native submit.
                if (current.startupPending && (!current.createGranted || current.startupSubmitted)) return false
                if (!ownerReadCurrentLocked(current, engineCurrent)) return false
                current
            }
            val ownerMatches = sessionOwnerMatches(lease, lease.rootRevision, engineCurrent)
            synchronized(admissionLock) {
                if (!ownerReadCurrentLocked(lease, engineCurrent) || ownerMatches == OwnerMatch.REVOKED) return false
                if (ownerMatches == OwnerMatch.MISMATCH) {
                    changed = recoveryLocked(LocalLockIssue.STALE)
                    false
                } else if (lease.startupPending && (!lease.createGranted || lease.startupSubmitted)) false else {
                    val admitted = if (lease.startupPending) lease.copy(startupSubmitted = true) else lease
                    if (admitted !== lease) control = control.copy(sessionLease = admitted)
                    try {
                        val borrowed = lease.key.borrow {
                            submit(it)
                            LocalCryptoResult.Success(Unit)
                        }
                        if (borrowed is LocalCryptoResult.Success) {
                            true
                        } else {
                            if (ownerReadCurrentLocked(admitted, engineCurrent)) changed = recoveryLocked(LocalLockIssue.CRYPTO_UNAVAILABLE)
                            false
                        }
                    } catch (_: Throwable) {
                        if (ownerReadCurrentLocked(admitted, engineCurrent)) changed = recoveryLocked(LocalLockIssue.IO_FAILURE)
                        false
                    }
                }
            }
        }
        changed?.let(onWitnessChanged)
        return accepted
    }

    /** Call only after real native Closed and committed selection/stop revalidation. */
    fun renewSessionKeyAfterClosed(
        ticket: LocalSessionKeyTicket,
        engineCurrent: () -> Boolean = { true },
    ): LocalSessionKeyTicket? {
        var changed: LocalLockWitness? = null
        var secrets: QueuedSecrets? = null
        var proof: LocalDatabaseKey? = null
        val renewed = try {
            synchronized(lock) {
                val lease = synchronized(admissionLock) {
                    val current = currentSessionLeaseLocked(ticket) ?: return null
                    if (!ownerReadCurrentLocked(current, engineCurrent)) return null
                    current
                }
                if (lease.startupPending) return null
                if (!ownerReadCurrent(lease, engineCurrent)) return null
                val revision = safeRevision()
                if (!ownerReadCurrent(lease, engineCurrent)) return null
                val ownerMatches = if (revision != null) sessionOwnerMatches(lease, revision, engineCurrent) else OwnerMatch.MISMATCH
                synchronized(admissionLock) {
                    if (!ownerReadCurrentLocked(lease, engineCurrent) || ownerMatches == OwnerMatch.REVOKED) return null
                    if (ownerMatches == OwnerMatch.MISMATCH || revision == null) {
                        changed = recoveryLocked(LocalLockIssue.STALE)
                        null
                    } else {
                        val before = control
                        proof = before.managementKey
                        control = before.copy(managementKey = null, managementRevision = next(before.managementRevision))
                        secrets = revokeFlightLocked(managementOnly = true)
                        val snapshot = mutableState.value
                        changed = transitionLocked(snapshot.phase, snapshot.enabled, forceEpoch = true,
                            expectedEpoch = ticket.epoch, allowCreate = snapshot.canCreate)
                        if (control.witness.blocked || control.stopping) null else {
                            val nextTicket = LocalSessionKeyTicket(control.witness.epoch)
                            control = control.copy(sessionLease = lease.copy(ticket = nextTicket, rootRevision = revision,
                                startupPending = true, startupSubmitted = false, createGranted = false), lastRevokedSessionTicket = null)
                            nextTicket
                        }
                    }
                }
            }
        } finally {
            secrets?.clear()
            proof?.close()
        }
        changed?.let(onWitnessChanged)
        return renewed
    }

    /**
     * No native session may remain when called. Accepts only the current lease or
     * its last lock-revoked handle; a successful unlock/record change retires it.
     */
    fun suspendSessionStartup(ticket: LocalSessionKeyTicket): Boolean {
        var changed: LocalLockWitness? = null
        var secrets: QueuedSecrets? = null
        var key: LocalDatabaseKey? = null
        var proof: LocalDatabaseKey? = null
        val accepted = try {
            synchronized(lock) {
                synchronized(admissionLock) {
                    val owner = control
                    val lease = owner.sessionLease
                    val current = lease?.ticket === ticket
                    val retired = owner.witness.blocked && owner.lastRevokedSessionTicket === ticket
                    if (owner.stopping || mutableState.value.phase == LocalLockPhase.RECOVERY || (!current && !retired)) return false
                    key = lease?.key
                    proof = owner.managementKey
                    control = owner.copy(sessionLease = null, lastRevokedSessionTicket = null, managementKey = null,
                        managementRevision = next(owner.managementRevision))
                    secrets = revokeFlightLocked()
                    changed = if (record?.kind == LocalStoredKind.PASSWORD) {
                        transitionLocked(LocalLockPhase.COLD_LOCKED, enabled = true, forceEpoch = true)
                    } else recoveryLocked(LocalLockIssue.STALE)
                    updateSnapshot { it.copy(managementAuthorized = false, canChange = false, canRemove = false,
                        operationRevision = next(it.operationRevision)) }
                    true
                }
            }
        } finally {
            secrets?.clear()
            key?.close()
            proof?.close()
        }
        changed?.let(onWitnessChanged)
        return accepted
    }

    /** Called by Engine only after it has acquired the root-only lease. */
    fun initialize() {
        var changed: LocalLockWitness? = null
        var start: Pair<Long, Boolean>? = null
        synchronized(lock) {
            if (initialized || stopping) return
            initialized = true
            if (!safeRoot()) {
                changed = recoveryLocked(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
            } else {
                val preferencesRead = safePreferencesRead()
                val seconds = if (preferencesRead is LocalLockPreferenceRead.Value) preferencesRead.seconds else 60L
                updateSnapshot { it.copy(autoLockSeconds = seconds) }
                when (val read = safeRead()) {
                    LocalRecordRead.Absent -> {
                        if (safeFresh()) {
                            val available = safeDurability()
                            updateSnapshot { it.copy(
                                phase = LocalLockPhase.NEW_ROOT_SETUP,
                                canCreate = available,
                                issue = if (available) LocalLockIssue.NONE else LocalLockIssue.IO_FAILURE,
                            ) }
                        } else {
                            changed = transitionLocked(LocalLockPhase.UNPROTECTED, enabled = false)
                            updateSnapshot { it.copy(issue = LocalLockIssue.MIGRATION_UNAVAILABLE) }
                            start = witness.epoch to false
                        }
                    }
                    is LocalRecordRead.Failed -> changed = recoveryLocked(read.issue)
                    is LocalRecordRead.Present -> {
                        record = read.envelope
                        if (read.envelope.kind == LocalStoredKind.OPEN) {
                            val rootRevision = safeRevision()
                            val rootIdentity = safeIdentity()
                            val key = read.takeOpenKey()
                            if (rootRevision == null || rootIdentity == null || key == null) {
                                key?.close()
                                changed = recoveryLocked(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
                            } else {
                                startupKey = key
                                startupRootRevision = rootRevision
                                startupRootIdentity = rootIdentity
                                startupSubmitted = false
                                updateSnapshot { it.copy(phase = LocalLockPhase.UNLOCKING,
                                    enabled = false, issue = LocalLockIssue.NONE) }
                                start = witness.epoch to true
                            }
                        } else {
                            updateSnapshot { it.copy(
                                phase = LocalLockPhase.COLD_LOCKED, enabled = true, issue = LocalLockIssue.NONE,
                            ) }
                        }
                        read.close()
                    }
                }
                if (preferencesRead === LocalLockPreferenceRead.Failure && mutableState.value.phase != LocalLockPhase.RECOVERY) {
                    updateSnapshot { it.copy(issue = LocalLockIssue.IO_FAILURE) }
                }
            }
        }
        changed?.let(onWitnessChanged)
        start?.let { requestStart(it.first, it.second) }
    }

    private fun skipSetup() = submit(Operation.SKIP, CharArray(0))

    private enum class Operation { CREATE, SKIP, UNLOCK, VERIFY, CHANGE, REMOVE }
    private enum class FlightStage { RESERVING, QUEUED, RUNNING, ADMITTED }

    /** The slot owns queued secrets; RUNNING transfers them exclusively to its worker. */
    private data class Flight(
        val id: Long,
        val operation: Operation,
        val phase: LocalLockPhase,
        val epoch: Long,
        val managementRevision: Long,
        val stage: FlightStage = FlightStage.RESERVING,
        val canceled: Boolean = false,
        val password: CharArray? = null,
        val confirmation: CharArray? = null,
        val proof: LocalDatabaseKey? = null,
    )

    /** One linearization domain for slot ownership, revocation, proof and the witness. */
    private data class Control(
        val witness: LocalLockWitness,
        val sequence: Long = 0,
        val managementRevision: Long = 0,
        val flight: Flight? = null,
        val managementKey: LocalDatabaseKey? = null,
        val sessionLease: SessionKeyLease? = null,
        val lastRevokedSessionTicket: LocalSessionKeyTicket? = null,
        val stopping: Boolean = false,
    )

    private data class SessionKeyLease(
        val ticket: LocalSessionKeyTicket,
        val key: LocalDatabaseKey,
        val rootRevision: Long,
        val rootIdentity: ByteArray,
        val recordRevision: ByteArray,
        val startupPending: Boolean = false,
        val startupSubmitted: Boolean = false,
        val createGranted: Boolean = false,
    ) {
        override fun toString(): String = "SessionKeyLease[redacted]"
    }

    private class QueuedSecrets(
        private val password: CharArray?,
        private val confirmation: CharArray?,
        private val proof: LocalDatabaseKey?,
    ) {
        fun clear() {
            password?.fill('\u0000')
            confirmation?.fill('\u0000')
            proof?.close()
        }
    }

    private class Capture(
        val id: Long,
        val operation: Operation,
        val phase: LocalLockPhase,
        val epoch: Long,
        val managementRevision: Long,
        val rootRevision: Long,
        val identity: ByteArray,
        val expected: LocalStoredEnvelope?,
        val proof: LocalDatabaseKey?,
    )

    private sealed interface Outcome {
        class Created(val value: LocalCryptoResult<WrappedDatabaseKey>) : Outcome
        class Skipped(val value: LocalCryptoResult<WrappedDatabaseKey>) : Outcome
        class Opened(val value: LocalCryptoResult<LocalDatabaseKey>) : Outcome
        class Changed(val value: LocalCryptoResult<ByteArray>) : Outcome
        class Reenabled(val value: LocalCryptoResult<ByteArray>) : Outcome
        class Removed(val value: LocalCryptoResult<ByteArray>) : Outcome
        data object SamePassword : Outcome
        data object Failed : Outcome
    }

    /** Owns and eventually clears both arrays, even when work is rejected or canceled. */
    private fun submit(operation: Operation, password: CharArray, confirmation: CharArray? = null) {
        // Reserve before any root/read call. Cancel either precedes this reservation
        // or revokes this exact slot; it can never become a newly sampled baseline.
        val reserved = synchronized(admissionLock) {
            val current = mutableState.value
            val owner = control
            val issue = when {
                owner.stopping -> LocalLockIssue.STALE
                owner.flight != null -> LocalLockIssue.BUSY
                owner.sequence == Long.MAX_VALUE -> LocalLockIssue.RESOURCE_UNAVAILABLE
                else -> LocalLockIssue.NONE
            }
            if (issue != LocalLockIssue.NONE) {
                password.fill('\u0000')
                confirmation?.fill('\u0000')
                publishSnapshotLocked(current.copy(issue = issue, operationRevision = next(current.operationRevision)))
                return
            }
            val flight = Flight(next(owner.sequence), operation, current.phase, owner.witness.epoch,
                owner.managementRevision, password = password, confirmation = confirmation)
            control = owner.copy(sequence = flight.id, flight = flight)
            publishSnapshotLocked(current.copy(busy = true, issue = LocalLockIssue.NONE,
                operationRevision = next(current.operationRevision)))
            flight
        }
        val capture: Capture
        synchronized(lock) {
            if (!active(reserved.id, reserved.epoch)) {
                abandonReservation(reserved.id, LocalLockIssue.STALE)
                return
            }
            val waiting = remaining(retryDeadline)
            if (managementKey != null && remaining(managementDeadline) == 0L) clearManagementLocked()
            if (operation == Operation.VERIFY) clearManagementLocked()
            val current = mutableState.value
            val validPhase = when (operation) {
                Operation.CREATE -> current.canCreate && (current.phase == LocalLockPhase.NEW_ROOT_SETUP ||
                    (current.phase == LocalLockPhase.UNPROTECTED && record?.kind == LocalStoredKind.OPEN))
                Operation.SKIP -> current.phase == LocalLockPhase.NEW_ROOT_SETUP
                Operation.UNLOCK -> current.phase == LocalLockPhase.COLD_LOCKED || current.phase == LocalLockPhase.HOT_LOCKED
                Operation.VERIFY -> current.phase == LocalLockPhase.UNLOCKED && current.enabled
                Operation.CHANGE -> current.phase == LocalLockPhase.UNLOCKED && current.canChange && managementKey != null
                Operation.REMOVE -> current.phase == LocalLockPhase.UNLOCKED && current.canRemove && managementKey != null
            }
            val issue = when {
                stopping || !validPhase -> LocalLockIssue.STALE
                isManagementOperation(operation, current.phase) && control.sessionLease?.startupPending == true -> LocalLockIssue.BUSY
                waiting > 0 -> LocalLockIssue.WAITING
                (operation == Operation.VERIFY || operation == Operation.CHANGE || operation == Operation.REMOVE ||
                    (operation == Operation.CREATE && current.phase == LocalLockPhase.UNPROTECTED)) &&
                    (witness.blocked || witness.epoch != current.epoch) -> LocalLockIssue.STALE
                operation != Operation.REMOVE && operation != Operation.SKIP &&
                    (password.isEmpty() || password.size > LocalEnvelopeFormat.MAX_PASSWORD_BYTES) -> LocalLockIssue.INVALID_PASSWORD
                confirmation != null && !password.contentEquals(confirmation) -> LocalLockIssue.PASSWORD_MISMATCH
                !safeRoot() -> LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE
                (operation == Operation.CREATE || operation == Operation.SKIP) && current.phase == LocalLockPhase.NEW_ROOT_SETUP &&
                    (!safeFresh() || !safeDurability()) -> LocalLockIssue.IO_FAILURE
                else -> LocalLockIssue.NONE
            }
            if (issue != LocalLockIssue.NONE) {
                if (operation == Operation.CHANGE || operation == Operation.REMOVE) clearManagementLocked()
                abandonReservation(reserved.id, issue)
                return
            }
            val identity = safeIdentity()
            val rootRevision = safeRevision()
            if (identity == null || rootRevision == null) {
                abandonReservation(reserved.id, LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
                return
            }
            if (confirmation !== password) confirmation?.fill('\u0000')
            val proof = when {
                operation == Operation.CHANGE || operation == Operation.REMOVE -> {
                    if (remaining(managementDeadline) == 0L) clearManagementLocked()
                    val key = transferManagementProof(reserved)
                    if (key == null) {
                        abandonReservation(reserved.id, LocalLockIssue.STALE)
                        return
                    }
                    key
                }
                operation == Operation.CREATE && current.phase == LocalLockPhase.UNPROTECTED -> {
                    val existing = safeRead()
                    val key = if (existing is LocalRecordRead.Present && existing.envelope.kind == LocalStoredKind.OPEN &&
                        record?.revision?.let { MessageDigest.isEqual(it, existing.envelope.revision) } == true) {
                        existing.takeOpenKey()
                    } else null
                    if (existing is LocalRecordRead.Present) existing.close()
                    if (key == null) {
                        abandonReservation(reserved.id, LocalLockIssue.STALE)
                        return
                    }
                    key
                }
                else -> null
            }
            if (proof != null) managementDeadline = null
            capture = synchronized(admissionLock) {
                val owner = control
                val flight = owner.flight
                if (flight == null || flight.id != reserved.id || flight.canceled || owner.stopping ||
                    owner.witness.epoch != reserved.epoch || (isManagementOperation(operation, current.phase) &&
                        owner.managementRevision != reserved.managementRevision)) {
                    proof?.close()
                    abandonReservation(reserved.id, LocalLockIssue.STALE)
                    return
                }
                control = owner.copy(flight = flight.copy(phase = current.phase, stage = FlightStage.QUEUED,
                    confirmation = null, proof = proof))
                val latest = mutableState.value
                publishSnapshotLocked(latest.copy(busy = true, issue = LocalLockIssue.NONE,
                    managementAuthorized = if (proof != null) false else latest.managementAuthorized,
                    canChange = if (proof != null) false else latest.canChange, canRemove = false))
                Capture(flight.id, operation, current.phase, reserved.epoch, reserved.managementRevision,
                    rootRevision, identity, record, proof)
            }
        }
        try {
            worker.execute {
                if (!startWorker(capture)) {
                    password.fill('\u0000')
                    capture.proof?.close()
                    finish(capture, Outcome.Failed)
                    return@execute
                }
                val outcome = try {
                    runOperation(capture, password)
                } catch (_: Throwable) {
                    Outcome.Failed
                } finally {
                    password.fill('\u0000')
                }
                finish(capture, outcome)
            }
        } catch (_: RuntimeException) {
            password.fill('\u0000')
            capture.proof?.close()
            finish(capture, Outcome.Failed)
        }
    }

    private fun runOperation(capture: Capture, password: CharArray): Outcome {
        return when (capture.operation) {
            Operation.SKIP -> {
                val bytes = ByteArray(LocalEnvelopeFormat.KEY_BYTES)
                var key: LocalDatabaseKey? = null
                try {
                    entropy.fill(bytes)
                    val generated = LocalDatabaseKey.copyOf(bytes)
                    key = generated
                    when (val encoded = LocalOpenWrap.encode(generated, capture.identity, 1)) {
                        is LocalCryptoResult.Success -> {
                            var transferred = false
                            try {
                                val inspected = inspectOpenWrap(encoded.value, capture.identity)
                                val checked = if (inspected is LocalRecordRead.Present) inspected.takeOpenKey() else null
                                if (inspected is LocalRecordRead.Present) inspected.close()
                                val valid = try { checked != null && keysEqual(checked, generated) } finally { checked?.close() }
                                if (!valid) Outcome.Skipped(LocalCryptoResult.CryptoUnavailable) else {
                                    Outcome.Skipped(LocalCryptoResult.Success(WrappedDatabaseKey(encoded.value, generated))).also {
                                        transferred = true
                                        key = null
                                    }
                                }
                            } finally {
                                if (!transferred) encoded.value.fill(0)
                            }
                        }
                        else -> Outcome.Skipped(encoded.failure())
                    }
                } finally {
                    bytes.fill(0)
                    key?.close()
                }
            }
            Operation.CREATE -> {
                val existing = capture.expected
                val proof = capture.proof
                if (existing == null) {
                    val created = crypto.create(password, capture.identity, 1)
                    if (created is LocalCryptoResult.Success && !safeVerifyWrapped(password, created.value.record,
                            capture.identity, 1, created.value.key)) {
                        created.value.key.close()
                        Outcome.Created(LocalCryptoResult.CryptoUnavailable)
                    } else Outcome.Created(created)
                } else {
                    if (existing.kind != LocalStoredKind.OPEN || proof == null || existing.generation == Long.MAX_VALUE) return Outcome.Failed
                    val wrapped = crypto.rewrap(password, proof, capture.identity, existing.generation + 1)
                    if (wrapped is LocalCryptoResult.Success && !safeVerifyWrapped(password, wrapped.value,
                            capture.identity, existing.generation + 1, proof)) {
                        Outcome.Reenabled(LocalCryptoResult.CryptoUnavailable)
                    } else Outcome.Reenabled(wrapped)
                }
            }
            Operation.UNLOCK, Operation.VERIFY -> {
                val existing = capture.expected ?: return Outcome.Failed
                if (existing.kind != LocalStoredKind.PASSWORD) return Outcome.Failed
                Outcome.Opened(crypto.open(password, existing.bytes, capture.identity, existing.generation))
            }
            Operation.CHANGE -> {
                val existing = capture.expected ?: return Outcome.Failed
                val proof = capture.proof ?: return Outcome.Failed
                if (existing.kind != LocalStoredKind.PASSWORD || existing.generation == Long.MAX_VALUE) return Outcome.Failed
                when (val same = crypto.open(password, existing.bytes, capture.identity, existing.generation)) {
                    is LocalCryptoResult.Success -> {
                        same.value.close()
                        Outcome.SamePassword
                    }
                    LocalCryptoResult.AuthenticationFailed -> {
                        val wrapped = crypto.rewrap(password, proof, capture.identity, existing.generation + 1)
                        if (wrapped is LocalCryptoResult.Success && !safeVerifyWrapped(password, wrapped.value,
                                capture.identity, existing.generation + 1, proof)) {
                            Outcome.Changed(LocalCryptoResult.CryptoUnavailable)
                        } else Outcome.Changed(wrapped)
                    }
                    else -> Outcome.Changed(same.failure())
                }
            }
            Operation.REMOVE -> {
                val existing = capture.expected ?: return Outcome.Failed
                val proof = capture.proof ?: return Outcome.Failed
                if (existing.kind != LocalStoredKind.PASSWORD || existing.generation == Long.MAX_VALUE) return Outcome.Failed
                val opened = LocalOpenWrap.encode(proof, capture.identity, existing.generation + 1)
                if (opened is LocalCryptoResult.Success) {
                    var keep = false
                    try {
                        val inspected = inspectOpenWrap(opened.value, capture.identity)
                        val check = if (inspected is LocalRecordRead.Present) inspected.takeOpenKey() else null
                        if (inspected is LocalRecordRead.Present) inspected.close()
                        val verified = try { check != null && keysEqual(check, proof) } finally { check?.close() }
                        if (!verified) Outcome.Removed(LocalCryptoResult.CryptoUnavailable) else {
                            keep = true
                            Outcome.Removed(opened)
                        }
                    } finally {
                        if (!keep) opened.value.fill(0)
                    }
                } else Outcome.Removed(opened)
            }
        }
    }

    private fun verifyWrapped(password: CharArray, bytes: ByteArray, identity: ByteArray, generation: Long, expected: LocalDatabaseKey): Boolean {
        val opened = crypto.open(password, bytes, identity, generation)
        if (opened !is LocalCryptoResult.Success) return false
        return try { keysEqual(opened.value, expected) } finally { opened.value.close() }
    }

    private fun safeVerifyWrapped(password: CharArray, bytes: ByteArray, identity: ByteArray, generation: Long, expected: LocalDatabaseKey): Boolean =
        try { verifyWrapped(password, bytes, identity, generation, expected) } catch (_: Throwable) { false }

    private fun keysEqual(first: LocalDatabaseKey, second: LocalDatabaseKey): Boolean {
        val result = first.borrow { left ->
            second.borrow { right -> LocalCryptoResult.Success(MessageDigest.isEqual(left, right)) }
        }
        return result is LocalCryptoResult.Success && result.value
    }

    private class CommitCompletion(val result: LocalCommitResult, val envelope: LocalStoredEnvelope?)

    /** Slow work keeps the physical slot, but never holds the admission monitor. */
    private fun prepareCommit(capture: Capture, outcome: Outcome): CommitCompletion? {
        val candidate = when (outcome) {
            is Outcome.Created -> (outcome.value as? LocalCryptoResult.Success)?.value?.record
            is Outcome.Skipped -> (outcome.value as? LocalCryptoResult.Success)?.value?.record
            is Outcome.Changed -> (outcome.value as? LocalCryptoResult.Success)?.value
            is Outcome.Reenabled -> (outcome.value as? LocalCryptoResult.Success)?.value
            is Outcome.Removed -> (outcome.value as? LocalCryptoResult.Success)?.value
            else -> null
        } ?: return null
        val fresh = capture.expected == null
        val verifyFresh = if (fresh) ({ readBackCandidate(capture, outcome, candidate) != null }) else null
        val result = if (!fresh || safeFresh()) commitVerified(candidate, capture.expected, capture, verifyFresh) else LocalCommitResult.CONFLICT
        if (result != LocalCommitResult.COMMITTED) return CommitCompletion(result, null)
        val envelope = readBackCandidate(capture, outcome, candidate)
        return CommitCompletion(if (envelope != null) result else LocalCommitResult.INDETERMINATE, envelope)
    }

    private fun readBackCandidate(capture: Capture, outcome: Outcome, candidate: ByteArray): LocalStoredEnvelope? {
        val expectedKind = if (outcome is Outcome.Removed || outcome is Outcome.Skipped) LocalStoredKind.OPEN else LocalStoredKind.PASSWORD
        val reread = safeRead()
        if (reread !is LocalRecordRead.Present) return null
        return try {
            val revision = recordDigest(candidate)
            val generation = capture.expected?.generation?.let { it + 1 } ?: 1L
            val keyMatches = if (expectedKind == LocalStoredKind.OPEN) {
                val actual = reread.takeOpenKey()
                var expected: LocalDatabaseKey? = null
                try {
                    val inspected = inspectOpenWrap(candidate, capture.identity)
                    expected = if (inspected is LocalRecordRead.Present) inspected.takeOpenKey() else null
                    if (inspected is LocalRecordRead.Present) inspected.close()
                    actual != null && expected != null && keysEqual(actual, expected) &&
                        (outcome !is Outcome.Skipped || keysEqual(actual,
                            (outcome.value as LocalCryptoResult.Success<WrappedDatabaseKey>).value.key))
                } finally {
                    actual?.close()
                    expected?.close()
                }
            } else reread.envelope.bytes.contentEquals(candidate)
            // OPEN intentionally retains no D-bearing bytes in the envelope.
            if (reread.envelope.kind == expectedKind && reread.envelope.generation == generation &&
                MessageDigest.isEqual(reread.envelope.revision, revision) && keyMatches && sameOwner(capture)) {
                reread.envelope
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            reread.close()
        }
    }

    private fun finish(capture: Capture, outcome: Outcome) {
        try {
            finishOwned(capture, outcome)
        } finally {
            clearEncodedOutcome(outcome)
            var changed: LocalLockWitness? = null
            var idle: (() -> Unit)? = null
            synchronized(lock) {
                synchronized(admissionLock) {
                    // An unexpected completion exception cannot strand the physical
                    // slot or leave a half-transferred key authorized for later use.
                    if (control.flight?.id == capture.id) {
                        try {
                            dispose(outcome)
                            capture.proof?.close()
                            changed = recoveryLocked(LocalLockIssue.IO_FAILURE)
                        } finally {
                            control = control.copy(flight = null)
                            val snapshot = mutableState.value
                            publishSnapshotLocked(snapshot.copy(operationRevision = next(snapshot.operationRevision)))
                        }
                        if (stopping) {
                            clearKeysLocked()
                            idle = onStopped
                            onStopped = null
                        }
                    }
                }
            }
            changed?.let { try { onWitnessChanged(it) } catch (_: RuntimeException) { } }
            idle?.invoke()
        }
    }

    private fun finishOwned(capture: Capture, outcome: Outcome) {
        var changed: LocalLockWitness? = null
        var start: Pair<Long, Boolean>? = null
        var idle: (() -> Unit)? = null
        var armManagement = false
        var armRetry = false
        synchronized(lock) {
            val initiallyActive = active(capture.id, capture.epoch)
            val ownerMatches = initiallyActive && sameOwner(capture) && sameRecord(capture)
            val completion = if (ownerMatches && active(capture.id, capture.epoch)) prepareCommit(capture, outcome) else null
            // Capability checks can be platform calls, so finish them before admission.
            val durable = ownerMatches && active(capture.id, capture.epoch) && safeDurability()
            synchronized(admissionLock) {
                val snapshot = mutableState.value
                val current = control
                val flight = current.flight
                val canceled = !activeLocked(capture.id, capture.epoch) || operationCanceled(capture)
                if (canceled) {
                    dispose(outcome)
                    if (completion != null) {
                        // Stage/finalize may have changed disk. Retain all recovery material
                        // and never expose a stale in-memory envelope as authoritative.
                        changed = recoveryLocked(LocalLockIssue.STALE)
                    }
                } else if (!ownerMatches) {
                    dispose(outcome)
                    changed = recoveryLocked(LocalLockIssue.STALE)
                } else if (completion != null) {
                    val committed = completion.envelope
                    if (committed == null) {
                        dispose(outcome)
                        changed = recoveryLocked(if (completion.result == LocalCommitResult.CONFLICT) LocalLockIssue.STALE else LocalLockIssue.IO_FAILURE)
                    } else {
                        record = committed
                        when (outcome) {
                            is Outcome.Created, is Outcome.Skipped -> {
                                val wrapped = if (outcome is Outcome.Created) {
                                    (outcome.value as LocalCryptoResult.Success<WrappedDatabaseKey>).value
                                } else ((outcome as Outcome.Skipped).value as LocalCryptoResult.Success<WrappedDatabaseKey>).value
                                startupKey = wrapped.key
                                startupRootRevision = capture.rootRevision
                                startupRootIdentity = capture.identity.copyOf()
                                startupSubmitted = false
                                publishSnapshotLocked(snapshot.copy(phase = LocalLockPhase.UNLOCKING,
                                    enabled = committed.kind == LocalStoredKind.PASSWORD, canCreate = false,
                                    issue = LocalLockIssue.NONE))
                                start = current.witness.epoch to true
                            }
                            is Outcome.Changed -> {
                                changed = transitionLocked(LocalLockPhase.UNLOCKED, enabled = true,
                                    forceEpoch = true, expectedEpoch = capture.epoch)
                                if (!refreshSessionLeaseLocked(capture)) changed = recoveryLocked(LocalLockIssue.CRYPTO_UNAVAILABLE)
                                publishSnapshotLocked(mutableState.value.copy(issue = if (mutableState.value.phase == LocalLockPhase.RECOVERY) mutableState.value.issue else LocalLockIssue.NONE,
                                    managementAuthorized = false, canChange = false, canRemove = false))
                            }
                            is Outcome.Reenabled -> {
                                changed = transitionLocked(LocalLockPhase.UNLOCKED, enabled = true,
                                    forceEpoch = true, expectedEpoch = capture.epoch)
                                if (!refreshSessionLeaseLocked(capture)) changed = recoveryLocked(LocalLockIssue.CRYPTO_UNAVAILABLE)
                                publishSnapshotLocked(mutableState.value.copy(issue = if (mutableState.value.phase == LocalLockPhase.RECOVERY) mutableState.value.issue else LocalLockIssue.NONE,
                                    managementAuthorized = false, canChange = false, canRemove = false))
                            }
                            is Outcome.Removed -> {
                                changed = transitionLocked(LocalLockPhase.UNPROTECTED, enabled = false,
                                    expectedEpoch = capture.epoch, allowCreate = durable)
                                if (!refreshSessionLeaseLocked(capture)) changed = recoveryLocked(LocalLockIssue.CRYPTO_UNAVAILABLE)
                                publishSnapshotLocked(mutableState.value.copy(issue = if (mutableState.value.phase == LocalLockPhase.RECOVERY) mutableState.value.issue else LocalLockIssue.NONE))
                            }
                            else -> Unit
                        }
                    }
                } else {
                    when (outcome) {
                        is Outcome.Created -> armRetry = failCryptoLocked(outcome.value, snapshot)
                        is Outcome.Skipped -> armRetry = failCryptoLocked(outcome.value, snapshot)
                        is Outcome.Opened -> when (val result = outcome.value) {
                            is LocalCryptoResult.Success -> {
                                failureCount = 0
                                retryDeadline = null
                                if (capture.operation == Operation.VERIFY) {
                                    managementKey?.close()
                                    control = control.copy(managementKey = result.value)
                                    managementDeadline = deadline(60_000)
                                    publishSnapshotLocked(snapshot.copy(issue = LocalLockIssue.NONE,
                                        retryAfterMillis = 0, managementAuthorized = true,
                                        canChange = durable, canRemove = durable))
                                    armManagement = true
                                } else if (capture.phase == LocalLockPhase.COLD_LOCKED) {
                                    startupKey = result.value
                                    startupRootRevision = capture.rootRevision
                                    startupRootIdentity = capture.identity.copyOf()
                                    startupSubmitted = false
                                    publishSnapshotLocked(snapshot.copy(phase = LocalLockPhase.UNLOCKING,
                                        issue = LocalLockIssue.NONE, retryAfterMillis = 0))
                                    start = current.witness.epoch to true
                                } else {
                                    changed = transitionLocked(LocalLockPhase.UNLOCKED, enabled = true, expectedEpoch = capture.epoch)
                                    val expected = capture.expected
                                    if (expected == null || !installSessionLeaseLocked(result.value, capture.rootRevision, capture.identity, expected.revision)) {
                                        result.value.close()
                                        changed = recoveryLocked(LocalLockIssue.CRYPTO_UNAVAILABLE)
                                    }
                                    publishSnapshotLocked(mutableState.value.copy(issue = if (mutableState.value.phase == LocalLockPhase.RECOVERY) mutableState.value.issue else LocalLockIssue.NONE,
                                        retryAfterMillis = 0))
                                }
                            }
                            else -> armRetry = failCryptoLocked(result, snapshot)
                        }
                        is Outcome.Changed -> armRetry = failCryptoLocked(outcome.value, snapshot)
                        is Outcome.Reenabled -> armRetry = failCryptoLocked(outcome.value, snapshot)
                        is Outcome.Removed -> armRetry = failCryptoLocked(outcome.value, snapshot)
                        Outcome.SamePassword -> publishSnapshotLocked(snapshot.copy(issue = LocalLockIssue.PASSWORD_UNCHANGED))
                        Outcome.Failed -> armRetry = failCryptoLocked(LocalCryptoResult.CryptoUnavailable, snapshot)
                    }
                }
                // Release only after the real worker and every disk call above returned.
                // A canceled reservation/queued runnable still owns this slot until here.
                capture.proof?.close()
                if (flight?.id == capture.id) control = control.copy(flight = null)
                val latest = mutableState.value
                publishSnapshotLocked(latest.copy(busy = control.flight != null, operationRevision = next(latest.operationRevision)))
            }
            if (armManagement) armManagementTimerLocked()
            if (armRetry && !armRetryTimerLocked()) updateSnapshot { it.copy(issue = LocalLockIssue.CRYPTO_UNAVAILABLE,
                retryAfterMillis = remaining(retryDeadline)) }
            if (stopping) {
                clearKeysLocked()
                idle = onStopped
                onStopped = null
            }
        }
        // Open-wrap bytes contain D; the stored envelope retains only its revision.
        clearEncodedOutcome(outcome)
        changed?.let(onWitnessChanged)
        start?.let { requestStart(it.first, it.second) }
        idle?.invoke()
    }

    /** Only the cold-start actor may call this; the callback must synchronously submit the bytes. */
    fun borrowStartupKey(epoch: Long, submit: (ByteArray) -> Unit): Boolean {
        var changed: LocalLockWitness? = null
        val success = synchronized(lock) {
            if (stopping || epoch != witness.epoch || mutableState.value.phase != LocalLockPhase.UNLOCKING ||
                startupSubmitted || !safeRoot() || safeRevision() != startupRootRevision ||
                startupRootIdentity?.let { expected -> safeIdentity()?.let { MessageDigest.isEqual(expected, it) } } != true ||
                !currentRecordMatches()) return false
            val key = startupKey ?: return false
            try {
                val borrowed = key.borrow {
                    submit(it)
                    LocalCryptoResult.Success(Unit)
                }
                if (borrowed is LocalCryptoResult.Success) {
                    startupSubmitted = true
                    true
                } else false
            } catch (_: Exception) {
                changed = recoveryLocked(LocalLockIssue.IO_FAILURE)
                false
            }
        }
        changed?.let(onWitnessChanged)
        return success
    }

    fun nativeStartupResult(
        epoch: Long,
        success: Boolean,
        mismatch401: Boolean,
        engineCurrent: () -> Boolean = { true },
    ) {
        var changed: LocalLockWitness? = null
        synchronized(lock) {
            val lease = synchronized(admissionLock) {
                control.sessionLease?.takeIf { it.ticket.epoch == epoch && it.startupPending }
            }
            if (lease != null) {
                val ownerMatches = if (lease.createGranted && lease.startupSubmitted) {
                    sessionOwnerMatches(lease, lease.rootRevision, engineCurrent)
                } else OwnerMatch.MISMATCH
                synchronized(admissionLock) {
                    if (!ownerReadCurrentLocked(lease, engineCurrent) || ownerMatches == OwnerMatch.REVOKED) return
                    if (ownerMatches == OwnerMatch.MISMATCH) changed = recoveryLocked(LocalLockIssue.STALE) else if (success && !mismatch401) {
                        control = control.copy(sessionLease = lease.copy(startupPending = false, startupSubmitted = false, createGranted = false))
                        updateSnapshot { it.copy(issue = LocalLockIssue.NONE, operationRevision = next(it.operationRevision)) }
                    } else changed = recoveryLocked(if (mismatch401) LocalLockIssue.DATABASE_MISMATCH else LocalLockIssue.IO_FAILURE)
                }
            } else {
                if (stopping || epoch != witness.epoch || mutableState.value.phase != LocalLockPhase.UNLOCKING) return
                val identity = startupRootIdentity
                val envelope = record
                val ownerMatches = startupSubmitted && identity != null && envelope != null && safeRoot() &&
                    safeRevision() == startupRootRevision && safeIdentity()?.let { MessageDigest.isEqual(it, identity) } == true &&
                    currentRecordMatches(envelope)
                val durable = ownerMatches && safeDurability()
                synchronized(admissionLock) {
                    if (stopping || epoch != witness.epoch || mutableState.value.phase != LocalLockPhase.UNLOCKING) return
                    if (!ownerMatches || identity == null || envelope == null) changed = recoveryLocked(LocalLockIssue.STALE) else if (success && !mismatch401) {
                        val key = startupKey
                        startupKey = null
                        startupRootIdentity = null
                        if (key == null) changed = recoveryLocked(LocalLockIssue.CRYPTO_UNAVAILABLE) else {
                            var transferred = false
                            try {
                                val protected = envelope.kind == LocalStoredKind.PASSWORD
                                changed = transitionLocked(if (protected) LocalLockPhase.UNLOCKED else LocalLockPhase.UNPROTECTED,
                                    enabled = protected, expectedEpoch = epoch, allowCreate = durable)
                                transferred = installSessionLeaseLocked(key, startupRootRevision, identity, envelope.revision)
                            } catch (_: Throwable) {
                                changed = recoveryLocked(LocalLockIssue.IO_FAILURE)
                            } finally {
                                if (!transferred) key.close()
                            }
                        }
                    } else changed = recoveryLocked(if (mismatch401) LocalLockIssue.DATABASE_MISMATCH else LocalLockIssue.IO_FAILURE)
                }
            }
        }
        changed?.let(onWitnessChanged)
    }
    private fun lockNow() {
        val revoked = synchronized(admissionLock) {
            val snapshot = mutableState.value
            val owner = control
            if (owner.stopping || !snapshot.enabled || snapshot.phase != LocalLockPhase.UNLOCKED || owner.witness.blocked) return
            val blocked = LocalLockWitness(next(owner.witness.epoch), true)
            control = owner.copy(witness = blocked, managementKey = null,
                managementRevision = next(owner.managementRevision), sessionLease = null,
                lastRevokedSessionTicket = owner.sessionLease?.ticket ?: owner.lastRevokedSessionTicket)
            val secrets = revokeFlightLocked()
            // The visible state and witness share the same short linearization point.
            // Finalize may already own the OS call, but cannot publish over this lock.
            publishSnapshotLocked(snapshot.copy(phase = LocalLockPhase.HOT_LOCKED, epoch = blocked.epoch,
                managementAuthorized = false, canManage = false, canChange = false, canRemove = false,
                canAutoLock = false, canLockNow = false, operationRevision = next(snapshot.operationRevision)))
            Triple(blocked, secrets, owner.managementKey to owner.sessionLease?.key)
        }
        revoked.second?.clear()
        revoked.third.first?.close()
        revoked.third.second?.close()
        try { onWitnessChanged(revoked.first) } catch (_: RuntimeException) { }
    }

    private fun cancel() {
        val secrets = synchronized(admissionLock) {
            val queued = revokeFlightLocked()
            val snapshot = mutableState.value
            publishSnapshotLocked(snapshot.copy(issue = LocalLockIssue.STALE, operationRevision = next(snapshot.operationRevision)))
            queued
        }
        secrets?.clear()
    }

    private fun closeManagement() {
        val revoked = synchronized(admissionLock) {
            val owner = control
            control = owner.copy(managementRevision = next(owner.managementRevision), managementKey = null)
            val secrets = revokeFlightLocked(managementOnly = true)
            val snapshot = mutableState.value
            publishSnapshotLocked(snapshot.copy(managementAuthorized = false, canChange = false, canRemove = false,
                operationRevision = next(snapshot.operationRevision)))
            secrets to owner.managementKey
        }
        revoked.first?.clear()
        revoked.second?.close()
    }

    private fun managementActivity() = synchronized(lock) {
        if (managementKey != null && remaining(managementDeadline) == 0L) clearManagementLocked()
        if (managementKey != null && !workerBusy) {
            managementDeadline = deadline(60_000)
            armManagementTimerLocked()
        }
    }

    private fun removePassword() = submit(Operation.REMOVE, CharArray(0))

    private fun setAutoLockSeconds(seconds: Long) = synchronized(lock) {
        if (managementKey != null && remaining(managementDeadline) == 0L) clearManagementLocked()
        val snapshot = mutableState.value
        val issue = when {
            stopping || snapshot.phase != LocalLockPhase.UNLOCKED || managementKey == null -> LocalLockIssue.STALE
            workerBusy -> LocalLockIssue.BUSY
            !validAutoLockSeconds(seconds) -> LocalLockIssue.INVALID_AUTO_LOCK
            !safeRoot() -> LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE
            else -> LocalLockIssue.NONE
        }
        if (issue != LocalLockIssue.NONE) {
            rejectLocked(issue)
        } else {
            val saved = try { preferences.write(seconds) } catch (_: RuntimeException) { false }
            if (!saved) rejectLocked(LocalLockIssue.IO_FAILURE) else {
                managementDeadline = deadline(60_000)
                armManagementTimerLocked()
                updateSnapshot { latest -> latest.copy(autoLockSeconds = seconds,
                    issue = if (managementKey == null) LocalLockIssue.CRYPTO_UNAVAILABLE else LocalLockIssue.NONE,
                    operationRevision = next(latest.operationRevision)) }
            }
        }
    }

    /** Call only after native Closed. Keep the root lease until the callback fires. */
    fun stop(onIdle: () -> Unit) {
        val revoked = synchronized(admissionLock) {
            val owner = control
            if (owner.stopping) return
            val blocked = LocalLockWitness(next(owner.witness.epoch), true)
            control = owner.copy(stopping = true, witness = blocked, managementKey = null,
                managementRevision = next(owner.managementRevision), sessionLease = null, lastRevokedSessionTicket = null)
            val secrets = revokeFlightLocked()
            val snapshot = mutableState.value
            publishSnapshotLocked(snapshot.copy(phase = LocalLockPhase.RECOVERY, canCreate = false,
                issue = if (snapshot.phase == LocalLockPhase.RECOVERY) snapshot.issue else LocalLockIssue.STALE,
                managementAuthorized = false, canManage = false, canChange = false,
                canRemove = false, canAutoLock = false, canLockNow = false))
            Triple(blocked, secrets, owner.managementKey to owner.sessionLease?.key)
        }
        revoked.second?.clear()
        revoked.third.first?.close()
        revoked.third.second?.close()
        try { onWitnessChanged(revoked.first) } catch (_: RuntimeException) { }
        var callNow = false
        synchronized(lock) {
            if (workerBusy) onStopped = onIdle else {
                clearKeysLocked()
                callNow = true
            }
        }
        if (callNow) onIdle()
    }

    private fun failCryptoLocked(result: LocalCryptoResult<*>, snapshot: LocalLockSnapshot): Boolean {
        val issue = when (result) {
            LocalCryptoResult.AuthenticationFailed -> {
                failureCount = (failureCount + 1).coerceAtMost(9)
                val wait = ((failureCount - 3).coerceAtLeast(0) * 5_000L).coerceAtMost(30_000L)
                retryDeadline = deadline(wait)
                LocalLockIssue.WRONG_PASSWORD
            }
            LocalCryptoResult.InvalidPassword -> LocalLockIssue.INVALID_PASSWORD
            LocalCryptoResult.CorruptRecord -> LocalLockIssue.CORRUPT_RECORD
            LocalCryptoResult.UnsupportedFormat, LocalCryptoResult.UnsupportedParameters -> LocalLockIssue.UNSUPPORTED_RECORD
            LocalCryptoResult.BindingMismatch -> LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE
            LocalCryptoResult.ResourceUnavailable -> LocalLockIssue.RESOURCE_UNAVAILABLE
            else -> LocalLockIssue.CRYPTO_UNAVAILABLE
        }
        publishSnapshotLocked(snapshot.copy(issue = issue, retryAfterMillis = remaining(retryDeadline)))
        return result === LocalCryptoResult.AuthenticationFailed && remaining(retryDeadline) > 0
    }

    private fun armRetryTimerLocked(): Boolean {
        if (retryTimerArmed) return true
        retryTimerArmed = true
        return try {
            schedule(remaining(retryDeadline).coerceAtLeast(1)) { retryTimer() }
            true
        } catch (_: RuntimeException) {
            retryTimerArmed = false
            // Do not silently remove the throttle when its timer is unavailable.
            retryDeadline = Long.MAX_VALUE
            false
        }
    }

    private fun retryTimer() = synchronized(lock) {
        retryTimerArmed = false
        if (stopping) return@synchronized
        val wait = remaining(retryDeadline)
        if (wait > 0) {
            if (!armRetryTimerLocked()) updateSnapshot { it.copy(
                issue = LocalLockIssue.CRYPTO_UNAVAILABLE, retryAfterMillis = remaining(retryDeadline),
            ) }
        } else {
            retryDeadline = null
            updateSnapshot { it.copy(retryAfterMillis = 0) }
        }
    }

    private fun armManagementTimerLocked() {
        if (managementTimerArmed || managementKey == null) return
        managementTimerArmed = true
        try {
            schedule(remaining(managementDeadline).coerceAtLeast(1)) { managementTimer() }
        } catch (_: RuntimeException) {
            managementTimerArmed = false
            clearManagementLocked()
            updateSnapshot { it.copy(issue = LocalLockIssue.CRYPTO_UNAVAILABLE) }
        }
    }

    private fun managementTimer() = synchronized(lock) {
        managementTimerArmed = false
        if (stopping || managementKey == null) return@synchronized
        if (remaining(managementDeadline) > 0) armManagementTimerLocked() else clearManagementLocked()
    }

    private fun clearManagementLocked() {
        val key = synchronized(admissionLock) {
            val previous = control.managementKey
            control = control.copy(managementKey = null)
            publishSnapshotLocked(mutableState.value.copy(managementAuthorized = false, canChange = false, canRemove = false))
            previous
        }
        key?.close()
        managementDeadline = null
    }

    private fun clearKeysLocked() {
        clearManagementLocked()
        val sessionKey = synchronized(admissionLock) {
            val lease = control.sessionLease
            control = control.copy(sessionLease = null, lastRevokedSessionTicket = null)
            lease?.key
        }
        sessionKey?.close()
        startupKey?.close()
        startupKey = null
        startupRootIdentity?.fill(0)
        startupRootIdentity = null
        startupSubmitted = false
    }

    private fun currentSessionLeaseLocked(ticket: LocalSessionKeyTicket): SessionKeyLease? {
        val owner = control
        val lease = owner.sessionLease ?: return null
        return lease.takeIf { !owner.stopping && !owner.witness.blocked && it.ticket === ticket && ticket.epoch == owner.witness.epoch }
    }

    private enum class OwnerMatch { MATCHED, REVOKED, MISMATCH }

    /** Pure atomic Engine checks only; no callback may read files or call native. */
    private fun ownerReadCurrentLocked(lease: SessionKeyLease, engineCurrent: () -> Boolean): Boolean {
        if (currentSessionLeaseLocked(lease.ticket) !== lease) return false
        val current = try { engineCurrent() } catch (_: Throwable) { false }
        return current && currentSessionLeaseLocked(lease.ticket) === lease
    }

    private fun ownerReadCurrent(lease: SessionKeyLease, engineCurrent: () -> Boolean): Boolean =
        synchronized(admissionLock) { ownerReadCurrentLocked(lease, engineCurrent) }

    /** Each bounded read is separate; revocation admits no following read. */
    private fun sessionOwnerMatches(
        lease: SessionKeyLease,
        revision: Long,
        engineCurrent: () -> Boolean,
    ): OwnerMatch {
        fun admitted() = ownerReadCurrent(lease, engineCurrent)
        return try {
            if (!admitted()) return OwnerMatch.REVOKED
            val rootCurrent = safeRoot()
            if (!admitted()) return OwnerMatch.REVOKED
            if (!rootCurrent) return OwnerMatch.MISMATCH
            val rootRevision = safeRevision()
            if (!admitted()) return OwnerMatch.REVOKED
            if (rootRevision != revision) return OwnerMatch.MISMATCH
            val identity = safeIdentity()
            if (!admitted()) return OwnerMatch.REVOKED
            if (identity == null || !MessageDigest.isEqual(identity, lease.rootIdentity)) return OwnerMatch.MISMATCH
            val current = safeRead()
            try {
                if (!admitted()) OwnerMatch.REVOKED else if (current !is LocalRecordRead.Present) OwnerMatch.MISMATCH else {
                    if (MessageDigest.isEqual(current.envelope.revision, lease.recordRevision)) OwnerMatch.MATCHED else OwnerMatch.MISMATCH
                }
            } finally {
                if (current is LocalRecordRead.Present) current.close()
            }
        } catch (_: Throwable) {
            if (admitted()) OwnerMatch.MISMATCH else OwnerMatch.REVOKED
        }
    }

    /** Takes key ownership only after a successful opening/verified same-D update. */
    private fun installSessionLeaseLocked(
        key: LocalDatabaseKey,
        rootRevision: Long,
        rootIdentity: ByteArray,
        recordRevision: ByteArray,
    ): Boolean {
        if (control.stopping || control.witness.blocked) return false
        val previous = control.sessionLease
        val ticket = LocalSessionKeyTicket(control.witness.epoch)
        control = control.copy(sessionLease = SessionKeyLease(ticket, key, rootRevision, rootIdentity.copyOf(), recordRevision.copyOf()),
            lastRevokedSessionTicket = null)
        if (previous?.key !== key) previous?.key?.close()
        return true
    }

    private fun refreshSessionLeaseLocked(capture: Capture): Boolean {
        val lease = control.sessionLease ?: return false
        val envelope = record ?: return false
        return installSessionLeaseLocked(lease.key, capture.rootRevision, capture.identity, envelope.revision)
    }

    private fun recoveryLocked(issue: LocalLockIssue): LocalLockWitness? {
        clearKeysLocked()
        val changed = transitionLocked(LocalLockPhase.RECOVERY, enabled = mutableState.value.enabled)
        updateSnapshot { it.copy(issue = issue, canCreate = false) }
        return changed
    }

    private fun transitionLocked(
        phase: LocalLockPhase,
        enabled: Boolean,
        forceEpoch: Boolean = false,
        expectedEpoch: Long? = null,
        allowCreate: Boolean = false,
    ): LocalLockWitness? = synchronized(admissionLock) {
        val old = control.witness
        if (expectedEpoch != null && old.epoch != expectedEpoch) return recoveryLocked(LocalLockIssue.STALE)
        val blocked = phase != LocalLockPhase.UNPROTECTED && phase != LocalLockPhase.UNLOCKED
        if (control.stopping && !blocked) return recoveryLocked(LocalLockIssue.STALE)
        val advancing = forceEpoch || old.blocked != blocked || mutableState.value.phase != phase || phase == LocalLockPhase.RECOVERY
        if (advancing && old.epoch == Long.MAX_VALUE) {
            val terminal = LocalLockWitness(Long.MAX_VALUE, true)
            control = control.copy(witness = terminal)
            clearKeysLocked()
            publishSnapshotLocked(mutableState.value.copy(phase = LocalLockPhase.RECOVERY, issue = LocalLockIssue.RESOURCE_UNAVAILABLE,
                epoch = terminal.epoch, canCreate = false, canManage = false, canChange = false,
                canRemove = false, canAutoLock = false, canLockNow = false))
            return terminal.takeIf { it != old }
        }
        val nextWitness = if (advancing) LocalLockWitness(next(old.epoch), blocked) else old
        control = control.copy(witness = nextWitness)
        publishSnapshotLocked(mutableState.value.copy(phase = phase, enabled = enabled, epoch = nextWitness.epoch,
            canCreate = phase == LocalLockPhase.UNPROTECTED && record?.kind == LocalStoredKind.OPEN && allowCreate,
            canManage = phase == LocalLockPhase.UNLOCKED && enabled,
            canLockNow = phase == LocalLockPhase.UNLOCKED && enabled,
            canAutoLock = phase == LocalLockPhase.UNLOCKED && enabled))
        nextWitness.takeIf { it != old }
    }

    private fun updateSnapshot(change: (LocalLockSnapshot) -> LocalLockSnapshot) = synchronized(admissionLock) {
        publishSnapshotLocked(change(mutableState.value))
    }

    /** All snapshot writes use the same monitor as witness/slot revocation. */
    private fun publishSnapshotLocked(value: LocalLockSnapshot) {
        val owner = control
        val open = !owner.witness.blocked && !owner.stopping
        val invalidOpen = value.phase == LocalLockPhase.UNLOCKED || value.phase == LocalLockPhase.UNPROTECTED
        val phase = when {
            owner.stopping -> LocalLockPhase.RECOVERY
            !open && invalidOpen -> if (value.enabled) LocalLockPhase.HOT_LOCKED else LocalLockPhase.RECOVERY
            else -> value.phase
        }
        val proof = open && owner.managementKey != null
        mutableState.value = value.copy(phase = phase, epoch = owner.witness.epoch, busy = owner.flight != null,
            issue = if (!open && invalidOpen) LocalLockIssue.STALE else value.issue,
            operationRevision = maxOf(value.operationRevision, mutableState.value.operationRevision),
            managementAuthorized = value.managementAuthorized && proof,
            canCreate = value.canCreate && !owner.stopping && (phase == LocalLockPhase.NEW_ROOT_SETUP || phase == LocalLockPhase.UNPROTECTED),
            canManage = value.canManage && open, canChange = value.canChange && proof, canRemove = value.canRemove && proof,
            canAutoLock = value.canAutoLock && open, canLockNow = value.canLockNow && open)
    }

    private fun rejectLocked(issue: LocalLockIssue) = updateSnapshot {
        it.copy(issue = issue, retryAfterMillis = remaining(retryDeadline), operationRevision = next(it.operationRevision))
    }

    private fun activeLocked(id: Long, epoch: Long): Boolean {
        val owner = control
        val flight = owner.flight ?: return false
        return !owner.stopping && flight.id == id && flight.epoch == epoch && !flight.canceled && owner.witness.epoch == epoch &&
            (!isManagementOperation(flight.operation, flight.phase) || flight.managementRevision == owner.managementRevision)
    }

    private fun active(id: Long, epoch: Long): Boolean = synchronized(admissionLock) { activeLocked(id, epoch) }

    /** Revocation takes queued ownership before clearing it outside the monitor. */
    private fun revokeFlightLocked(managementOnly: Boolean = false): QueuedSecrets? {
        val flight = control.flight ?: return null
        if (flight.canceled || (managementOnly && !isManagementOperation(flight.operation, flight.phase))) return null
        val queued = flight.stage == FlightStage.RESERVING || flight.stage == FlightStage.QUEUED
        control = control.copy(flight = if (queued) {
            flight.copy(canceled = true, password = null, confirmation = null, proof = null)
        } else flight.copy(canceled = true))
        return if (queued) QueuedSecrets(flight.password, flight.confirmation, flight.proof) else null
    }

    private fun abandonReservation(id: Long, issue: LocalLockIssue) {
        val secrets = synchronized(admissionLock) {
            val flight = control.flight
            if (flight == null || flight.id != id || flight.stage != FlightStage.RESERVING) return
            control = control.copy(flight = null)
            val snapshot = mutableState.value
            publishSnapshotLocked(snapshot.copy(issue = if (flight.canceled) LocalLockIssue.STALE else issue,
                operationRevision = next(snapshot.operationRevision)))
            QueuedSecrets(flight.password, flight.confirmation, flight.proof)
        }
        secrets.clear()
    }

    private fun transferManagementProof(reserved: Flight): LocalDatabaseKey? = synchronized(admissionLock) {
        if (!activeLocked(reserved.id, reserved.epoch)) return null
        val owner = control
        val flight = owner.flight ?: return null
        val key = owner.managementKey ?: return null
        control = owner.copy(managementKey = null, flight = flight.copy(proof = key))
        publishSnapshotLocked(mutableState.value.copy(managementAuthorized = false, canChange = false, canRemove = false))
        key
    }

    private fun startWorker(capture: Capture): Boolean = synchronized(admissionLock) {
        val flight = control.flight ?: return false
        if (!activeLocked(capture.id, capture.epoch) || flight.stage != FlightStage.QUEUED) return false
        control = control.copy(flight = flight.copy(stage = FlightStage.RUNNING, password = null, confirmation = null, proof = null))
        true
    }

    private fun admitFinalize(capture: Capture): Boolean = synchronized(admissionLock) {
        val flight = control.flight ?: return false
        if (!activeLocked(capture.id, capture.epoch) || flight.stage != FlightStage.RUNNING) return false
        // This is the sole point after which an OS finalize is allowed to finish.
        // A later revocation remains recorded and forbids result publication.
        control = control.copy(flight = flight.copy(stage = FlightStage.ADMITTED))
        true
    }

    private fun operationCanceled(capture: Capture): Boolean {
        val owner = control
        val flight = owner.flight
        return owner.stopping || flight == null || flight.id != capture.id || flight.canceled ||
            (isManagementOperation(capture.operation, capture.phase) && capture.managementRevision != owner.managementRevision)
    }

    private fun isManagementOperation(operation: Operation, phase: LocalLockPhase): Boolean =
        operation == Operation.VERIFY || operation == Operation.CHANGE || operation == Operation.REMOVE ||
            (operation == Operation.CREATE && phase == LocalLockPhase.UNPROTECTED)

    private fun sameOwner(capture: Capture): Boolean = safeRoot() && safeRevision() == capture.rootRevision &&
        safeIdentity()?.let { MessageDigest.isEqual(it, capture.identity) } == true

    private fun sameRecord(capture: Capture): Boolean = if (capture.expected == null) {
        val current = safeRead()
        if (current is LocalRecordRead.Present) current.close()
        current === LocalRecordRead.Absent
    } else currentRecordMatches(capture.expected)

    private fun currentRecordMatches(expected: LocalStoredEnvelope? = record): Boolean {
        val old = expected ?: return false
        val current = safeRead()
        if (current !is LocalRecordRead.Present) return false
        return try { MessageDigest.isEqual(old.revision, current.envelope.revision) } finally { current.close() }
    }

    private fun commitVerified(
        bytes: ByteArray,
        expected: LocalStoredEnvelope?,
        capture: Capture,
        verifyFresh: (() -> Boolean)? = null,
    ): LocalCommitResult {
        return try {
            if (!active(capture.id, capture.epoch)) return LocalCommitResult.INDETERMINATE
            val staged = persistence.commit(bytes, expected)
            if (staged != LocalCommitResult.COMMITTED) return staged
            // A first record has no previous file, so normal read can independently
            // confirm it before finalize. Replacements retain the old wrap until
            // admission and rely on the store's bounded stage readback contract.
            if (verifyFresh != null && !verifyFresh()) return LocalCommitResult.INDETERMINATE
            // Cancel/lock/close and finalize admission share the exact same slot.
            // If revocation wins, never delete the old wrap left by stage.
            if (!sameOwner(capture) || !admitFinalize(capture)) {
                return LocalCommitResult.INDETERMINATE
            }
            val finalized = persistence.finalizeCommit(bytes, expected)
            if (operationCanceled(capture) || capture.epoch != witness.epoch) LocalCommitResult.INDETERMINATE else finalized
        } catch (_: Exception) {
            LocalCommitResult.INDETERMINATE
        }
    }

    private fun safeRead(): LocalRecordRead = try { persistence.read() } catch (_: Exception) {
        LocalRecordRead.Failed(LocalLockIssue.IO_FAILURE)
    }

    private fun safePreferencesRead(): LocalLockPreferenceRead = try { preferences.read() } catch (_: Exception) {
        LocalLockPreferenceRead.Failure
    }

    private fun safeRoot(): Boolean = try { root.isCurrent() } catch (_: Exception) { false }
    private fun safeFresh(): Boolean = try { root.freshEligible() } catch (_: Exception) { false }
    private fun safeDurability(): Boolean = try { root.durableCommitAvailable() } catch (_: Exception) { false }
    private fun safeRevision(): Long? = try { root.revision() } catch (_: Exception) { null }
    private fun safeIdentity(): ByteArray? = try { root.identity()?.takeIf { it.size == 32 }?.copyOf() } catch (_: Exception) { null }
    private fun deadline(delay: Long): Long? {
        if (delay <= 0) return null
        val now = nowMillis()
        return if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
    private fun remaining(deadline: Long?): Long {
        deadline ?: return 0
        val now = nowMillis()
        if (now >= deadline) return 0
        return if (now < 0 && deadline > Long.MAX_VALUE + now) Long.MAX_VALUE else deadline - now
    }
    private fun next(value: Long): Long = if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1

    private fun requestStart(epoch: Long, protected: Boolean) {
        val allowed = synchronized(admissionLock) {
            val current = control
            val phaseAllows = if (protected) {
                mutableState.value.phase == LocalLockPhase.UNLOCKING
            } else !current.witness.blocked && mutableState.value.phase == LocalLockPhase.UNPROTECTED
            !current.stopping && current.witness.epoch == epoch && phaseAllows
        }
        if (!allowed) return
        try {
            onStartRequested(epoch, protected)
        } catch (_: RuntimeException) {
            val changed = synchronized(lock) {
                if (witness.epoch == epoch) recoveryLocked(LocalLockIssue.IO_FAILURE) else null
            }
            changed?.let(onWitnessChanged)
        }
    }

    private fun dispose(outcome: Outcome) {
        when (outcome) {
            is Outcome.Created -> (outcome.value as? LocalCryptoResult.Success)?.value?.key?.close()
            is Outcome.Skipped -> (outcome.value as? LocalCryptoResult.Success)?.value?.let { it.key.close(); it.record.fill(0) }
            is Outcome.Opened -> (outcome.value as? LocalCryptoResult.Success)?.value?.close()
            is Outcome.Removed -> (outcome.value as? LocalCryptoResult.Success)?.value?.fill(0)
            else -> Unit
        }
    }

    private fun clearEncodedOutcome(outcome: Outcome) {
        when (outcome) {
            is Outcome.Skipped -> (outcome.value as? LocalCryptoResult.Success)?.value?.record?.fill(0)
            is Outcome.Removed -> (outcome.value as? LocalCryptoResult.Success)?.value?.fill(0)
            else -> Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> LocalCryptoResult<*>.failure(): LocalCryptoResult<T> = this as LocalCryptoResult<T>
}
