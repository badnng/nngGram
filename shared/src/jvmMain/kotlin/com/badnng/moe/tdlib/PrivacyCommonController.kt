package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** One lazy visible privacy-page owner. Native option updates are independent of blocked-list invalidation. */
internal class PrivacyCommonController(
    private val generation: Long, private val session: TdPrivacyCommonSession,
    private val enqueue: (() -> Unit) -> Unit, private val publish: (PrivacyCommonSnapshot) -> Unit,
    private val terminal: SettingsSpecialMutationBoundary,
    private val isAllowed: () -> Boolean = { true },
) {
    constructor(generation: Long, session: TdPrivacyCommonSession, enqueue: (() -> Unit) -> Unit,
        publish: (PrivacyCommonSnapshot) -> Unit,
    ) : this(generation, session, enqueue, publish, SettingsSpecialMutationBoundary(enqueue))
    private var snapshot = PrivacyCommonSnapshot()
    private var nextOwner = 0L
    private var request = 0L
    private var revision = 0L
    private var owner = AtomicBoolean(false)
    private var pageLease: () -> Boolean = { false }
    @Volatile private var retired = false
    private var mutationInFlight = false
    private var acceptedWriteRevision = 0L

    init { session.observe { update -> enqueue { accept(update) } } }
    fun accepts(token: PrivacyCommonToken) = !retired && owner.get() && pageLease() && isAllowed() && snapshot.opened && snapshot.token == token
    fun open(lease: () -> Boolean) {
        if (retired || !lease() || !isAllowed()) return
        if (owner.get() && pageLease()) return
        owner.set(false); owner = AtomicBoolean(true); pageLease = lease
        snapshot = PrivacyCommonSnapshot(PrivacyCommonToken(generation, ++nextOwner), opened = true,
            loading = !mutationInFlight, busy = mutationInFlight, acceptedRevision = acceptedWriteRevision)
        emit()
        if (!mutationInFlight) read()
    }
    fun close(token: PrivacyCommonToken) {
        // A UI lease can be closed synchronously before its actor close command arrives.
        if (retired || snapshot.token != token || !owner.get()) return
        owner.set(false); request++
        snapshot = PrivacyCommonSnapshot(PrivacyCommonToken(generation, ++nextOwner)); emit()
    }
    fun deactivate() {
        retired = true; owner.set(false); request++; session.observe(null)
        snapshot = PrivacyCommonSnapshot(PrivacyCommonToken(generation, ++nextOwner))
    }
    fun refresh(token: PrivacyCommonToken) {
        if (!accepts(token) || snapshot.loading || snapshot.busy || mutationInFlight) return
        snapshot = snapshot.copy(loading = true); emit(); read()
    }
    fun topPeers(token: PrivacyCommonToken, expected: Boolean, desired: Boolean) {
        if (!canSave(token, PrivacyCommonField.TOP_PEERS)) return
        if (snapshot.data.topPeersEnabled != expected) { conflict(PrivacyCommonField.TOP_PEERS); return }
        mutate(PrivacyCommonField.TOP_PEERS) { lease, done -> session.setTopPeers(expected, desired, lease, done) }
    }
    fun archive(token: PrivacyCommonToken, expected: PrivacyArchiveSettings, desired: PrivacyArchiveSettings) {
        if (!canSave(token, PrivacyCommonField.ARCHIVE)) return
        if (snapshot.data.archive != expected) { conflict(PrivacyCommonField.ARCHIVE); return }
        if (!expected.archiveAndMuteNewChats && desired.archiveAndMuteNewChats &&
            snapshot.data.archiveCapability != PrivacyArchiveCapability.ALLOWED) {
            fail(PrivacyCommonField.ARCHIVE, SettingsError.FORBIDDEN); return
        }
        mutate(PrivacyCommonField.ARCHIVE) { lease, done -> session.setArchive(expected, desired, lease, done) }
    }
    fun accountTtl(token: PrivacyCommonToken, expected: Int, desired: Int) {
        if (!canSave(token, PrivacyCommonField.ACCOUNT_TTL)) return
        if (snapshot.data.accountTtlDays != expected) { conflict(PrivacyCommonField.ACCOUNT_TTL); return }
        if (expected !in 30..730 || desired !in 30..730) { fail(PrivacyCommonField.ACCOUNT_TTL, SettingsError.INVALID_VALUE); return }
        mutate(PrivacyCommonField.ACCOUNT_TTL) { lease, done -> session.setAccountTtl(expected, desired, lease, done) }
    }
    private fun canSave(token: PrivacyCommonToken, field: PrivacyCommonField) = accepts(token) && !snapshot.loading &&
        !snapshot.busy && !mutationInFlight && field in snapshot.loaded && field !in snapshot.refreshRequired
    private fun conflict(field: PrivacyCommonField) {
        snapshot = snapshot.copy(operation = field, errors = snapshot.errors + (field to SettingsError.CONFLICT),
            refreshRequired = snapshot.refreshRequired + field, failedRevision = ++revision); emit()
    }
    private fun fail(field: PrivacyCommonField, error: SettingsError) {
        snapshot = snapshot.copy(operation = field, errors = snapshot.errors + (field to error), failedRevision = ++revision); emit()
    }
    private fun mutate(field: PrivacyCommonField,
        submit: (() -> Boolean, (SettingsResult<Unit>) -> Unit) -> Unit,
    ) {
        mutationInFlight = true
        snapshot = snapshot.copy(busy = true, operation = field, errors = snapshot.errors - field); emit()
        val token = snapshot.token
        val active = owner
        val visible = pageLease
        val once = AtomicBoolean()
        val authority = terminal.captureAuthority()
        val current = { !retired && active.get() && visible() && authority() }
        fun finish(response: SettingsResult<Unit>) {
            val receipt = SettingsSpecialMutationContext.receipt()
            if (receipt?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) return
            if (!once.compareAndSet(false, true)) return
            terminal.enqueue settle@ {
                mutationInFlight = false
                if (response.value != null) acceptedWriteRevision++
                if (retired) return@settle
                if (!accepts(token) || !current()) {
                    val opened = snapshot.token
                    terminal.readback({ (opened != token || current()) && accepts(opened) }) {
                        snapshot = snapshot.copy(busy = false, loading = true); emit(); read()
                    }
                    return@settle
                }
                if (response.value == null) {
                    snapshot = snapshot.copy(busy = false, errors = snapshot.errors + (field to (response.error ?: SettingsError.REQUEST_FAILED)),
                        refreshRequired = if (response.error == SettingsError.CONFLICT) snapshot.refreshRequired + field else snapshot.refreshRequired,
                        failedRevision = ++revision); emit()
                } else {
                    snapshot = snapshot.copy(busy = false, loading = true, acceptedRevision = acceptedWriteRevision,
                        refreshRequired = snapshot.refreshRequired + field)
                    emit(); read()
                }
            }
        }
        try { submit(current, ::finish) } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
    private fun read() {
        val token = snapshot.token
        val active = owner
        val visible = pageLease
        val id = ++request
        val current = { !retired && active.get() && visible() }
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<PrivacyCommonRead>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!accepts(token) || !current() || request != id) return@enqueue
                val read = response.value
                if (read == null) {
                    snapshot = snapshot.copy(loading = false, errors = PrivacyCommonField.entries.associateWith {
                        response.error ?: SettingsError.REQUEST_FAILED }, refreshRequired = PrivacyCommonField.entries.toSet())
                } else {
                    val loaded = PrivacyCommonField.entries.toSet() - read.errors.keys
                    val previous = snapshot.data
                    snapshot = snapshot.copy(loading = false, data = PrivacyCommonData(
                        read.data.topPeersEnabled.takeIf { PrivacyCommonField.TOP_PEERS in loaded } ?: previous.topPeersEnabled,
                        read.data.archive.takeIf { PrivacyCommonField.ARCHIVE in loaded } ?: previous.archive,
                        if (PrivacyCommonField.ARCHIVE_CAPABILITY in loaded) read.data.archiveCapability else previous.archiveCapability,
                        read.data.accountTtlDays.takeIf { PrivacyCommonField.ACCOUNT_TTL in loaded } ?: previous.accountTtlDays),
                        loaded = snapshot.loaded + loaded, errors = read.errors, refreshRequired = read.errors.keys)
                }
                emit()
            }
        }
        try { session.load(current, ::finish) } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
    private fun accept(update: PrivacyCommonUpdate) {
        if (retired || !snapshot.opened || !owner.get() || !pageLease() || !isAllowed()) return
        val data = when (update.field) {
            PrivacyCommonField.TOP_PEERS -> snapshot.data.copy(topPeersEnabled = update.booleanValue)
            PrivacyCommonField.ARCHIVE_CAPABILITY -> snapshot.data.copy(archiveCapability = when (update.booleanValue) {
                true -> PrivacyArchiveCapability.ALLOWED
                false -> PrivacyArchiveCapability.DENIED
                null -> PrivacyArchiveCapability.UNKNOWN
            })
            else -> return
        }
        snapshot = snapshot.copy(data = data,
            loaded = if (update.booleanValue == null) snapshot.loaded - update.field else snapshot.loaded + update.field,
            refreshRequired = if (update.booleanValue == null) snapshot.refreshRequired + update.field else snapshot.refreshRequired,
            errors = if (update.booleanValue == null) snapshot.errors + (update.field to SettingsError.NOT_READY) else snapshot.errors - update.field)
        emit()
        // A result assembled before this native update must not replace the new value.
        if (snapshot.loading && !mutationInFlight) read()
    }
    private fun emit() = publish(snapshot)
}
