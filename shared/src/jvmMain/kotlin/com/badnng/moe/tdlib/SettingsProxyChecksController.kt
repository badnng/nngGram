package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actor-owned page/list state. Workers see only revocable witnesses; every result returns
 * to the actor, while the shared physical budget is released directly by native onDrained.
 */
internal class SettingsProxyChecksController(
    private val source: () -> SettingsSnapshot,
    private val advancedRevision: () -> Long?,
    private val startProbe: (SettingsProxy, SettingsProxyProbeLease, (SettingsResult<SettingsProxyProbe>) -> Unit) -> Unit,
    private val startAdvancedRead: (SettingsProxyProbeLease, (SettingsResult<SettingsAdvancedData>) -> Unit) -> Unit,
    private val budget: SettingsProxyProbeBudget,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (SettingsProxyChecksSnapshot) -> Unit,
    private val requestAdvancedLoad: (SettingsToken, SettingsProxyChecksPageLease) -> Unit,
    private val advancedReadResult: (SettingsToken, Long, SettingsResult<SettingsAdvancedData>) -> Unit,
    private val advancedReadCancelled: (SettingsToken, Long) -> Unit,
    private val startShare: (SettingsProxyShareTarget, Boolean, SettingsProxyProbeLease, (SettingsResult<List<String>>) -> Unit) -> Unit =
        { _, _, lease, result ->
            if (!lease.beginProbe()) result(SettingsResult(error = SettingsError.CONFLICT))
            else { lease.finishProbe(); result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        },
) {
    private val wakeOwner = Any()
    private var retired = false
    private var nextOwner = 0L
    private var nextEpoch = 0L
    private var page: Page? = null
    private var epoch: Epoch? = null
    private var read: ManagedRead? = null
    private var share: ManagedShare? = null
    private var snapshot = SettingsProxyChecksSnapshot()
    private var pumping = false
    private var pumpAgain = false

    init { budget.attach(wakeOwner) { enqueue { pump() } } }

    fun open(parent: SettingsToken, pageLease: SettingsProxyChecksPageLease,
        opened: (SettingsProxyChecksToken?) -> Unit,
    ) {
        val value = source()
        if (retired || !value.enabled || value.token != parent || !pageLease.current()) {
            runCatching { opened(null) }
            return
        }
        val previous = page
        if (previous != null && previous.parent == parent && previous.lease === pageLease) {
            runCatching { opened(previous.token) }
            return
        }
        if (previous != null) closeActive()
        // A publish during close can re-enter and establish a different page.
        if (page != null || !pageLease.current()) { runCatching { opened(null) }; return }
        val current = Page(parent, pageLease, SettingsProxyChecksToken(parent, ++nextOwner))
        page = current
        snapshot = SettingsProxyChecksSnapshot(token = current.token)
        emit()
        runCatching { opened(current.token) }
        // A running section read or write already owns the refresh; do not stack reads
        // when a user rapidly leaves and re-enters an in-flight page.
        val latest = source()
        if (page === current && pageLease.current() && latest.enabled && latest.token == parent &&
            SettingsSection.ADVANCED !in latest.loading && SettingsSection.ADVANCED !in latest.saving) {
            requestAdvancedLoad(parent, pageLease)
        }
    }

    /** Exact identity avoids a delayed close retiring a newer page with the same parent. */
    fun close(parent: SettingsToken, pageLease: SettingsProxyChecksPageLease) {
        val current = page ?: return
        if (current.parent != parent || current.lease !== pageLease) return
        closeActive()
    }

    fun matches(parent: SettingsToken, pageLease: SettingsProxyChecksPageLease): Boolean {
        val current = page ?: return false
        return !retired && current.parent == parent && current.lease === pageLease && pageLease.current()
    }

    fun activePage(parent: SettingsToken): SettingsProxyChecksPageLease? =
        page?.takeIf { !retired && it.parent == parent && it.lease.current() }?.lease

    /** A queued user write can precede cleanup even though close synchronously revoked its page. */
    fun registeredPage(parent: SettingsToken): SettingsProxyChecksPageLease? =
        page?.takeIf { !retired && it.parent == parent }?.lease

    fun refresh(parent: SettingsToken, pageLease: SettingsProxyChecksPageLease) {
        if (!matches(parent, pageLease)) return
        val latest = source()
        if (latest.enabled && latest.token == parent && SettingsSection.ADVANCED !in latest.loading &&
            SettingsSection.ADVANCED !in latest.saving) requestAdvancedLoad(parent, pageLease)
    }

    /** The input must still identify the exact loaded page, row(s), and source epoch. */
    fun share(parent: SettingsToken, pageLease: SettingsProxyChecksPageLease,
        request: SettingsProxyShareRequest, result: (SettingsResult<SettingsProxyShareContent>) -> Unit,
        suppliedOwner: SettingsProxyProbeOwner? = null,
    ) {
        val current = epoch
        if (current == null || !matches(parent, pageLease) || !isEpochCurrent(current) ||
            request.token != current.page.token || request.listEpoch != current.number ||
            (suppliedOwner != null && !suppliedOwner.current())) {
            runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
            return
        }
        val target = when (val input = request.target) {
            is SettingsProxyShareTarget.One -> {
                if (current.rows.getOrNull(input.index) != input.proxy ||
                    current.rows.count { it.id == input.proxy.id } != 1) {
                    runCatching { result(SettingsResult(error = SettingsError.CONFLICT)) }
                    return
                }
                input
            }
            is SettingsProxyShareTarget.All -> {
                if (request.format != SettingsProxyShareFormat.PUBLIC || input.rows != current.rows) {
                    runCatching { result(SettingsResult(error = SettingsError.CONFLICT)) }
                    return
                }
                SettingsProxyShareTarget.All(input.rows.toList())
            }
        }
        // A newer explicit request supersedes the old result but never frees its raw native slot.
        cancelShare()
        if (share != null || !isEpochCurrent(current) || !matches(parent, pageLease) ||
            (suppliedOwner != null && !suppliedOwner.current())) {
            runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
            return
        }
        val alive = AtomicBoolean(true)
        val owner = suppliedOwner ?: SettingsProxyProbeOwner { alive.get() && current.alive.get() && pageLease.current() }
        if (!owner.current() || !pageLease.attach(owner) || !owner.current()) {
            owner.revoke()
            pageLease.detach(owner)
            runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
            return
        }
        share = ManagedShare(current, target, request.format, alive, owner, result)
        pump()
    }

    fun closeActive() {
        val previous = page
        page = null
        previous?.lease?.revoke()
        invalidateAdvanced()
    }

    /** Called before ADVANCED load and before any admitted ADVANCED mutation. */
    fun invalidateAdvanced() {
        val previousRead = read
        val old = epoch
        read = null
        epoch = null
        // Do not invoke a foreign completion until this invalidation has finished;
        // its callback can synchronously open a different page or load a new epoch.
        val cancelledShare = takeShareCancellation()
        if (previousRead != null) {
            previousRead.alive.set(false)
            previousRead.owner.revoke()
            previousRead.page.lease.detach(previousRead.owner)
        }
        if (old != null) {
            old.alive.set(false)
            old.owner.revoke()
            old.page.lease.detach(old.owner)
        }
        snapshot = SettingsProxyChecksSnapshot(token = page?.token)
        emit()
        // Foreign publication can re-enter and start a newer read. The parent only clears
        // the detached read if its revision is still current.
        if (previousRead != null) {
            advancedReadCancelled(previousRead.page.parent, previousRead.revision)
            // A reentrant open may have seen the old read as loading while close was publishing.
            // Once its old revision is cleared, let the distinct new page request its own read.
            if (page != null && page !== previousRead.page) refreshIfOpen()
        }
        cancelledShare?.invoke()
    }

    /** Queue one page-owned serial metadata chain alongside the row probes. */
    fun queueAdvancedRead(parent: SettingsToken, pageLease: SettingsProxyChecksPageLease, revision: Long): Boolean {
        val current = page ?: return false
        val latest = source()
        if (!matches(parent, pageLease) || advancedRevision() != revision || !latest.enabled || latest.token != parent ||
            (SettingsSection.ADVANCED !in latest.loading && SettingsSection.ADVANCED !in latest.saving) || read != null) return false
        val alive = AtomicBoolean(true)
        val owner = SettingsProxyProbeOwner { alive.get() && pageLease.current() }
        if (!pageLease.attach(owner)) return false
        val value = ManagedRead(current, revision, alive, owner)
        read = value
        pump()
        return true
    }

    /** Called only after the current section read has succeeded and its flags have been cleared. */
    fun advancedLoaded(revision: Long) {
        val current = page ?: return
        val value = source()
        if (!pageAllowed(current, value) || advancedRevision() != revision ||
            current.startedRevision == revision) return
        invalidateAdvanced()
        val refreshed = source()
        if (!pageAllowed(current, refreshed) || advancedRevision() != revision) return
        val rows = refreshed.proxies.toList()
        val alive = AtomicBoolean(true)
        val owner = SettingsProxyProbeOwner { alive.get() && current.lease.current() }
        if (!current.lease.attach(owner)) return
        val duplicateIds = rows.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val queue = ArrayDeque<Int>()
        val states = rows.mapIndexed { index, proxy ->
            val invalid = proxy.id <= 0 || proxy.id in duplicateIds
            val status = when {
                proxy.type == SettingsProxyType.WEB -> SettingsProxyCheckStatus.NotTested
                invalid -> SettingsProxyCheckStatus.NotTested
                else -> SettingsProxyCheckStatus.Checking.also { queue.addLast(index) }
            }
            SettingsProxyCheckRow(index, proxy, status,
                if (invalid && proxy.type != SettingsProxyType.WEB) SettingsError.CONFLICT else null)
        }
        val latest = source()
        if (!pageAllowed(current, latest) || !owner.current() || advancedRevision() != revision || latest.proxies != rows) {
            alive.set(false)
            owner.revoke()
            current.lease.detach(owner)
            return
        }
        current.startedRevision = revision
        val next = Epoch(current, ++nextEpoch, revision, rows, queue, alive, owner)
        epoch = next
        snapshot = SettingsProxyChecksSnapshot(current.token, next.number, states)
        emit()
        pump()
    }

    /** An error cannot reauthorize cached loaded rows from before the failed operation. */
    fun advancedFailed() = invalidateAdvanced()

    /** A different ADVANCED write may have finished without its usual section readback. */
    fun refreshIfOpen() {
        val current = page ?: return
        val latest = source()
        if (!retired && current.lease.current() && latest.enabled && latest.token == current.parent &&
            SettingsSection.ADVANCED !in latest.loading && SettingsSection.ADVANCED !in latest.saving) {
            requestAdvancedLoad(current.parent, current.lease)
        }
    }

    fun retire() {
        if (retired) return
        retired = true
        closeActive()
        budget.detach(wakeOwner)
    }

    private fun pageAllowed(current: Page, value: SettingsSnapshot): Boolean =
        !retired && page === current && current.lease.current() && value.enabled && value.token == current.parent &&
            SettingsSection.ADVANCED in value.loaded && SettingsSection.ADVANCED !in value.loading &&
            SettingsSection.ADVANCED !in value.saving && value.errors[SettingsSection.ADVANCED] == null

    private fun isEpochCurrent(value: Epoch): Boolean {
        val latest = source()
        return epoch === value && value.alive.get() && value.owner.current() &&
            pageAllowed(value.page, latest) && advancedRevision() == value.revision && latest.proxies == value.rows
    }

    private fun isReadCurrent(value: ManagedRead): Boolean {
        val latest = source()
        return read === value && !retired && page === value.page && value.alive.get() && value.owner.current() &&
            value.page.lease.current() && latest.enabled && latest.token == value.page.parent &&
            (SettingsSection.ADVANCED in latest.loading || SettingsSection.ADVANCED in latest.saving) &&
            advancedRevision() == value.revision
    }

    private fun isShareCurrent(value: ManagedShare): Boolean =
        share === value && value.alive.get() && value.owner.current() && isEpochCurrent(value.epoch)

    private fun cancelShare() { takeShareCancellation()?.invoke() }

    private fun takeShareCancellation(): (() -> Unit)? {
        val old = share ?: return null
        share = null
        old.alive.set(false)
        old.owner.revoke()
        old.content?.invalidate()
        old.epoch.page.lease.detach(old.owner)
        if (!old.completed) {
            old.completed = true
            return { runCatching { old.result(SettingsResult(error = SettingsError.NOT_READY)) }; Unit }
        }
        return null
    }

    private fun pump() {
        if (pumping) { pumpAgain = true; return }
        pumping = true
        try {
            do {
                pumpAgain = false
                while (true) {
                    val queuedRead = read
                    if (queuedRead != null && !queuedRead.started) {
                        if (!isReadCurrent(queuedRead)) { invalidateAdvanced(); break }
                        val permit = budget.tryAcquire() ?: break
                        val lease = try { queuedRead.owner.newLease { permit.release() } } catch (_: Exception) {
                            permit.release()
                            finishRead(queuedRead, SettingsResult(error = SettingsError.REQUEST_FAILED))
                            continue
                        }
                        queuedRead.started = true
                        val replied = AtomicBoolean()
                        fun result(value: SettingsResult<SettingsAdvancedData>) {
                            if (replied.compareAndSet(false, true)) enqueue { finishRead(queuedRead, value) }
                        }
                        try { startAdvancedRead(lease, ::result) }
                        catch (_: Exception) {
                            // Submission may have happened; only onDrained releases the permit.
                            result(SettingsResult(error = SettingsError.REQUEST_FAILED))
                        }
                        continue
                    }
                    // A user's explicit request is admitted before the remaining automatic checks.
                    val queuedShare = share
                    if (queuedShare != null && !queuedShare.started) {
                        if (!isShareCurrent(queuedShare)) { cancelShare(); continue }
                        val permit = budget.tryAcquire() ?: break
                        val lease = try { queuedShare.owner.newLease { permit.release() } } catch (_: Exception) {
                            permit.release()
                            finishShare(queuedShare, SettingsResult(error = SettingsError.REQUEST_FAILED))
                            continue
                        }
                        queuedShare.started = true
                        val replied = AtomicBoolean()
                        fun result(value: SettingsResult<List<String>>) {
                            if (replied.compareAndSet(false, true)) enqueue { finishShare(queuedShare, value) }
                        }
                        try { startShare(queuedShare.target, queuedShare.format == SettingsProxyShareFormat.PUBLIC, lease, ::result) }
                        catch (_: Exception) {
                            // Entering a sender can submit before throwing; only raw drain releases the permit.
                            result(SettingsResult(error = SettingsError.REQUEST_FAILED))
                        }
                        continue
                    }
                    val current = epoch ?: break
                    if (!isEpochCurrent(current)) { invalidateAdvanced(); break }
                    if (current.queue.isEmpty()) break
                    val permit = budget.tryAcquire() ?: break
                    val index = current.queue.removeFirst()
                    val row = current.rows[index]
                    val lease = try { current.owner.newLease { permit.release() } } catch (_: Exception) {
                        // No native facade was entered, so the permit is known to be unused.
                        permit.release()
                        finish(current, index, SettingsResult(error = SettingsError.REQUEST_FAILED))
                        continue
                    }
                    val replied = AtomicBoolean()
                    fun result(value: SettingsResult<SettingsProxyProbe>) {
                        if (replied.compareAndSet(false, true)) enqueue { finish(current, index, value) }
                    }
                    try { startProbe(row, lease, ::result) }
                    catch (_: Exception) {
                        // Entered a foreign sender: submission may have happened. Only the lease's
                        // physical onDrained callback is permitted to release this slot.
                        result(SettingsResult(error = SettingsError.REQUEST_FAILED))
                    }
                }
            } while (pumpAgain)
        } finally {
            pumping = false
        }
    }

    private fun finishRead(current: ManagedRead, result: SettingsResult<SettingsAdvancedData>) {
        if (!isReadCurrent(current)) {
            if (read === current) invalidateAdvanced()
            return
        }
        read = null
        current.alive.set(false)
        current.owner.revoke()
        current.page.lease.detach(current.owner)
        advancedReadResult(current.page.parent, current.revision, result)
    }

    private fun finishShare(current: ManagedShare, result: SettingsResult<List<String>>) {
        if (share !== current || current.completed) return
        if (!isShareCurrent(current)) { cancelShare(); return }
        val links = result.value
        if (result.error != null || links.isNullOrEmpty() || links.any { it.isBlank() } ||
            (current.target is SettingsProxyShareTarget.One && links.size != 1)) {
            val error = result.error ?: SettingsError.REQUEST_FAILED
            current.completed = true
            cancelShare()
            runCatching { current.result(SettingsResult(error = error)) }
            return
        }
        val text = links.joinToString("\n\n")
        val content = ShareContent(text, current.owner) {
            runCatching { enqueue { if (share === current) cancelShare() } }
        }
        current.content = content
        current.completed = true
        runCatching { current.result(SettingsResult(content)) }
    }

    private fun finish(current: Epoch, index: Int, result: SettingsResult<SettingsProxyProbe>) {
        if (!isEpochCurrent(current) || index !in current.rows.indices || snapshot.token != current.page.token ||
            snapshot.listEpoch != current.number || snapshot.rows.getOrNull(index)?.proxy != current.rows[index]) return
        val value = result.value
        val (status, error) = when {
            result.error != null -> SettingsProxyCheckStatus.NotTested to result.error
            value is SettingsProxyProbe.Available && value.milliseconds >= 0 ->
                SettingsProxyCheckStatus.Available(value.milliseconds) to null
            value == SettingsProxyProbe.Unavailable -> SettingsProxyCheckStatus.Unavailable to null
            else -> SettingsProxyCheckStatus.NotTested to SettingsError.REQUEST_FAILED
        }
        val changed = snapshot.rows.toMutableList()
        changed[index] = changed[index].copy(status = status, error = error)
        snapshot = snapshot.copy(rows = changed)
        emit()
    }

    private fun emit() = publish(snapshot)

    private class Page(val parent: SettingsToken, val lease: SettingsProxyChecksPageLease, val token: SettingsProxyChecksToken) {
        var startedRevision: Long? = null
    }

    private class Epoch(
        val page: Page, val number: Long, val revision: Long, val rows: List<SettingsProxy>,
        val queue: ArrayDeque<Int>, val alive: AtomicBoolean, val owner: SettingsProxyProbeOwner,
    )

    private class ManagedRead(
        val page: Page, val revision: Long, val alive: AtomicBoolean, val owner: SettingsProxyProbeOwner,
        var started: Boolean = false,
    )

    private class ManagedShare(
        val epoch: Epoch, val target: SettingsProxyShareTarget, val format: SettingsProxyShareFormat,
        val alive: AtomicBoolean, val owner: SettingsProxyProbeOwner,
        val result: (SettingsResult<SettingsProxyShareContent>) -> Unit,
        var started: Boolean = false, var completed: Boolean = false, var content: ShareContent? = null,
    )

    /** Serializes the actual synchronous commit against owner revocation. */
    private class ShareContent(text: String, private val owner: SettingsProxyProbeOwner,
        private val closed: () -> Unit,
    ) : SettingsProxyShareContent {
        private var value: String? = text
        override fun textIfCurrent(): String? = synchronized(owner.lock) {
            if (owner.currentLocked()) value else { value = null; null }
        }
        override fun useIfCurrent(commit: (String) -> Unit): Boolean = synchronized(owner.lock) {
            val current = value
            if (current == null || !owner.currentLocked()) { value = null; false }
            else { runCatching { commit(current) }; true }
        }
        override fun close() {
            synchronized(owner.lock) { value = null; owner.revoke() }
            closed()
        }
        fun invalidate() = synchronized(owner.lock) { value = null; owner.revoke() }
        override fun toString() = "SettingsProxyShareContent(<redacted>)"
    }
}
