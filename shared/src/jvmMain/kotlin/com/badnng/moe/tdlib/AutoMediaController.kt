package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class AutoMediaContext(val token: ChatToken, val chat: ChatItem?, val messages: Map<Long, ChatMessage>, val files: Map<Int, MediaFile>)

internal fun autoMediaCopyNotice(copied: AutoMediaCopyResult): AutoMediaCopyNotice? {
    val issue = when (copied) {
        is AutoMediaCopyResult.Failed -> copied.issue
        is AutoMediaCopyResult.Pending -> copied.issue
        else -> return null
    }
    if (copied is AutoMediaCopyResult.Pending && copied.publicFileMayExist &&
        issue in setOf(AutoMediaCopyIssue.TARGET_UNSAFE, AutoMediaCopyIssue.TARGET_UNKNOWN,
            AutoMediaCopyIssue.COMMIT_UNAVAILABLE)) return AutoMediaCopyNotice.RECOVERY_AMBIGUOUS
    return when (issue) {
        AutoMediaCopyIssue.PUBLIC_WRITTEN_RECORD_PENDING -> AutoMediaCopyNotice.RECORD_PENDING
        AutoMediaCopyIssue.USER_CHANGED_TARGET -> AutoMediaCopyNotice.USER_CHANGED_TARGET
        AutoMediaCopyIssue.RECOVERY_AMBIGUOUS -> AutoMediaCopyNotice.RECOVERY_AMBIGUOUS
        else -> AutoMediaCopyNotice.FAILED
    }
}

/** Account-local policy commits and current-message leases share the existing engine actor. */
internal class AutoMediaController(
    private val session: TdChatSession,
    private val access: AutoMediaPreferencesAccess?,
    private val enqueue: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val context: () -> AutoMediaContext,
    private val publish: (AutoMediaSettings) -> Unit,
    private val downloadError: (Int, ChatError) -> Unit = { _, _ -> },
) {
    private data class Entry(val owner: MediaDownloadOwner, val fileId: Int, val live: AtomicBoolean = AtomicBoolean(true),
        var acquired: Boolean = false, var copied: Boolean = false, var failed: Boolean = false)
    private var settings = AutoMediaSettings()
    private var accountEpoch = AtomicBoolean(true)
    private var destination: AutoMediaDestination? = null
    private var destinationPending = false
    private var destinationTick = false
    @Volatile private var closed = false
    private var copiesRunning = 0
    private val copyFiles = mutableSetOf<Int>()
    private val entries = linkedMapOf<Long, Entry>()
    private val copyJournal = AutoMediaCopyJournal()

    fun account(userId: Long) {
        if (closed || userId <= 0 || settings.accountUserId == userId) return
        accountEpoch.set(false)
        accountEpoch = AtomicBoolean(true)
        entries.values.forEach(::release)
        entries.clear()
        destination = null
        destinationPending = false
        settings = AutoMediaSettings(accountUserId = userId)
        publish(settings)
        val epoch = accountEpoch
        if (access == null) { settings = settings.copy(loaded = true); publish(settings); return }
        access.read(userId) { loaded -> enqueue {
            if (closed || !epoch.get()) return@enqueue
            settings = settings.copy(revision = loaded?.revision ?: 0, policy = loaded?.policy ?: AutoMediaPolicy(),
                loaded = loaded != null, error = loaded == null)
            publish(settings)
            reconcile()
        } }
    }

    fun save(expectedRevision: Long, policy: AutoMediaPolicy, completed: (Boolean) -> Unit = {}): Boolean {
        val id = settings.accountUserId ?: return false
        if (closed || access == null || !settings.loaded || settings.saving || expectedRevision != settings.revision || !policy.valid()) return false
        val epoch = accountEpoch
        val expected = AutoMediaStored(settings.revision, settings.policy)
        val answered = AtomicBoolean()
        settings = settings.copy(saving = true, error = false)
        publish(settings)
        access.write(id, expected, policy, { !closed && epoch.get() }) { result ->
            if (!answered.compareAndSet(false, true)) return@write
            enqueue {
                if (closed || !epoch.get()) return@enqueue
                settings = if (result == AutoMediaWriteResult.SAVED) settings.copy(revision = expected.revision + 1,
                    policy = policy, saving = false, error = false) else settings.copy(saving = false, error = true)
                publish(settings)
                // A stable draft owner can be unmounted through this entire write.
                // Deliver this request's outcome instead of requiring it to observe
                // an intermediate saving snapshot that StateFlow may conflate.
                completed(result == AutoMediaWriteResult.SAVED)
                // A policy commit revokes only AUTO; manual/artwork/stream owners remain independent.
                reconcile()
            }
        }
        return true
    }

    fun visible(token: ChatToken, messageId: Long, active: Boolean) {
        if (closed) return
        val old = entries[messageId]
        if (!active) {
            if (old?.owner?.token == token) { release(old); entries.remove(messageId) }
            return
        }
        val state = context()
        val message = state.messages[messageId] ?: return
        val media = message.media ?: return
        if (state.token != token || message.chatId != token.chatId || state.chat?.id != token.chatId || message.delivery != MessageDelivery.SENT) return
        if (old != null && old.owner.token == token && old.fileId == media.file.id) { reconcile(); return }
        old?.let(::release)
        entries[messageId] = Entry(MediaDownloadOwner(token, messageId, MediaDownloadPurpose.AUTO), media.file.id)
        refreshDestination()
        reconcile()
    }

    fun reconcile() {
        if (closed) return
        val state = context()
        entries.toMap().forEach { (id, original) ->
            var entry = original
            val message = state.messages[id]
            val media = message?.media
            val chat = state.chat
            val file = state.files[entry.fileId] ?: media?.file
            if (entry.owner.token != state.token || message?.chatId != state.token.chatId || media?.file?.id != entry.fileId || chat == null || file == null) {
                release(entry); entries.remove(id); return@forEach
            }
            val actualMessage = checkNotNull(message)
            val decision = automaticMediaDecision(settings.policy, chat, actualMessage, file,
                destination?.askEveryTime ?: true, destination?.directory != null)
            val allowed = settings.loaded && decision.autoCacheAllowed
            if (!allowed) {
                entry.live.set(false)
                if (entry.acquired) session.releaseDownload(entry.owner, entry.fileId)
                entry.acquired = false
                if (!settings.loaded || !decision.externalCopyAllowed) return@forEach
            }
            // Each authorization revision gets a fresh copy permit. Old workers retain false.
            if (!entry.live.get()) {
                release(entry)
                entry = Entry(entry.owner, entry.fileId)
                entries[id] = entry
            }
            if (!file.downloaded && file.canDownload && !entry.acquired && !entry.failed) {
                entry.acquired = true
                val acquired = entry
                session.acquireDownload(entry.owner, file) { result -> enqueue {
                    if (closed || entries[id] !== acquired || !acquired.live.get()) return@enqueue
                    acquired.acquired = false
                    if (result.error != null) {
                        acquired.failed = true
                        downloadError(acquired.fileId, result.error)
                    }
                } }
            }
            if (decision.externalCopyAllowed) startCopy(entry, actualMessage, chat, file)
        }
        if (entries.isNotEmpty()) queueDestinationRefresh()
    }

    private fun startCopy(entry: Entry, message: ChatMessage, chat: ChatItem, file: MediaFile) {
        val target = destination ?: return
        val directory = target.directory ?: return
        val source = file.localPath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        val accountId = settings.accountUserId ?: return
        val root = access?.dataRoot ?: return
        val media = message.media ?: return
        if (entry.copied || copiesRunning >= 2 || file.id in copyFiles || target.askEveryTime || !message.canBeSaved || chat.hasProtectedContent || !file.downloaded || file.size < 0) return
        entry.copied = true
        copiesRunning++
        copyFiles += file.id
        val epoch = accountEpoch
        val policy = settings.policy
        val destinationFingerprint = target.fingerprint
        val request = AutoMediaCopyRequest(AutoMediaCopyAccount(accountId, root), file.id, file.uniqueId.takeIf { it.isNotEmpty() },
            source, file.size, directory, media.fileName.ifBlank { "Telegram-file" })
        fun active() = !closed && epoch.get() && entry.live.get()
        fun currentAuthorization(): Boolean {
            if (!active()) return false
            val state = context()
            val current = state.messages[message.id] ?: return false
            val currentMedia = current.media ?: return false
            val currentFile = state.files[file.id] ?: currentMedia.file
            val currentChat = state.chat ?: return false
            return state.token == entry.owner.token && current.chatId == message.chatId && currentMedia.file.id == file.id &&
                currentMedia.fileName == media.fileName && currentFile.downloaded && currentFile.localPath == file.localPath &&
                currentFile.size == file.size && currentFile.uniqueId == file.uniqueId && current.canBeSaved && !currentChat.hasProtectedContent &&
                settings.policy == policy && destination?.fingerprint == destinationFingerprint &&
                automaticMediaDecision(settings.policy, currentChat, current, currentFile, false, true).externalCopyAllowed
        }
        copyWorkers.execute {
            val copied = copyJournal.copy(request, ::active, authorizeCommit = {
                if (!active()) false else {
                    val latch = CountDownLatch(1)
                    val granted = AtomicBoolean()
                    enqueue { granted.set(currentAuthorization()); latch.countDown() }
                    // This waits only for the live actor's current permission, never for transfer
                    // progress. Retiring an owner always unblocks without a public write.
                    while (active() && !latch.await(100, TimeUnit.MILLISECONDS)) { /* owner cancellation */ }
                    active() && granted.get()
                }
            })
            enqueue {
                copiesRunning--
                copyFiles -= file.id
                if (!closed && epoch.get()) {
                    autoMediaCopyNotice(copied)?.let { notice ->
                        settings = settings.copy(copyError = true, copyNotice = notice)
                        publish(settings)
                    }
                    reconcile()
                }
            }
        }
    }

    private fun refreshDestination() {
        val userId = settings.accountUserId ?: return
        val store = access ?: return
        if (destinationPending || closed) return
        destinationPending = true
        val epoch = accountEpoch
        store.destination(userId) { value -> enqueue {
            if (closed || !epoch.get()) return@enqueue
            destinationPending = false
            if (value?.targetAvailable == false) {
                settings = settings.copy(copyError = true, copyNotice = AutoMediaCopyNotice.FAILED)
                publish(settings)
            }
            if (destination?.fingerprint != value?.fingerprint) {
                entries.values.forEach { it.live.set(false) }
                destination = value
            }
            reconcile()
        } }
    }

    private fun queueDestinationRefresh() {
        if (destinationTick || closed) return
        destinationTick = true
        schedule(2000) { enqueue {
            destinationTick = false
            if (!closed && entries.isNotEmpty()) refreshDestination()
        } }
    }
    private fun release(entry: Entry) {
        entry.live.set(false)
        if (entry.acquired) session.releaseDownload(entry.owner, entry.fileId)
    }
    fun clearPage() {
        entries.values.forEach(::release)
        entries.clear()
    }
    fun close() {
        closed = true
        accountEpoch.set(false)
        entries.values.forEach(::release)
        entries.clear()
    }
    companion object {
        private val copyWorkers = Executors.newFixedThreadPool(2) { task -> Thread(task, "auto-media-copy").apply { isDaemon = true } }
    }
}
