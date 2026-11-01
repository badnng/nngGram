package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.folders.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface TdFolderSettingsSession {
    fun retire() {}
    fun latestNativeState(current: () -> Boolean): FolderNativeState? = null
    fun capabilities(current: () -> Boolean, result: (SettingsResult<FolderCapabilities>) -> Unit)
    fun recommendations(current: () -> Boolean, result: (SettingsResult<List<FolderRecommendation>>) -> Unit)
    fun createRecommended(key: Long, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<ChatFolderInfo>) -> Unit)
    fun remove(id: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
    fun reorder(ids: List<Int>, mainPosition: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
    fun toggleTags(enabled: Boolean, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
    fun color(folder: ChatFolderInfo, colorId: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
}

/** Native acceptance queues TDLib synchronization; it does not establish cloud completion. */
internal class OfficialTdFolderSettings(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdFolderSettingsSession {
    private val active = AtomicBoolean(true)
    // Each update is a new view even when visible metadata compares equal: hidden rules may
    // have changed, so a Get -> write chain must not reacquire an older source's permission.
    private class NativeView(val state: FolderNativeState)
    private val native = AtomicReference<NativeView?>(null)
    private val nativeRevision = AtomicLong()
    private val keys = AtomicLong()
    private val guard = Any()
    private var suggestions = emptyMap<Long, TdApi.ChatFolder>()

    fun accept(update: TdApi.UpdateChatFolders) {
        if (!active.get()) return
        nativeRevision.incrementAndGet()
        val folders = update.chatFolders.orEmpty()
        native.set(if (folders.size <= 100 && folders.all { it != null && it.id >= 2 } &&
            folders.map { it.id }.distinct().size == folders.size && update.mainChatListPosition in 0..folders.size)
            NativeView(FolderNativeState(folders.map(::mapFolderInfo), update.mainChatListPosition, update.areTagsEnabled)) else null)
    }

    override fun latestNativeState(current: () -> Boolean): FolderNativeState? {
        val captured = native.get() ?: return null
        return captured.state.takeIf { active.get() && current() && native.get() === captured }
    }

    override fun retire() { active.set(false); nativeRevision.incrementAndGet(); native.set(null); synchronized(guard) { suggestions = emptyMap() } }

    override fun capabilities(current: () -> Boolean, result: (SettingsResult<FolderCapabilities>) -> Unit) {
        request(TdApi.GetMe(), current, { (it as? TdApi.User)?.takeIf { user -> user.id > 0 } }) me@ { me ->
            val user = me.value ?: run { result(SettingsResult(error = me.error)); return@me }
            option("chat_folder_count_max", 0..100, current) count@ { count ->
                val maximum = count.value ?: run { result(SettingsResult(error = count.error)); return@count }
                option("chat_folder_chosen_chat_count_max", 1..10_000, current) chosen@ { chosen ->
                    val limit = chosen.value ?: run { result(SettingsResult(error = chosen.error)); return@chosen }
                    result(SettingsResult(FolderCapabilities(user.isPremium, maximum, limit)))
                }
            }
        }
    }

    override fun recommendations(current: () -> Boolean, result: (SettingsResult<List<FolderRecommendation>>) -> Unit) {
        request(TdApi.GetRecommendedChatFolders(), current, { it as? TdApi.RecommendedChatFolders }) received@ { response ->
            val values = response.value?.chatFolders ?: run { result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@received }
            if (!active.get() || !current() || values.size > 64 || values.any { it?.folder?.name?.text?.text.isNullOrBlank() ||
                    it.description.orEmpty().length > 4096 || it.folder.isShareable }) {
                result(SettingsResult(error = if (current()) SettingsError.UNSUPPORTED else SettingsError.NOT_READY)); return@received
            }
            val payload = linkedMapOf<Long, TdApi.ChatFolder>()
            val mapped = values.map { value ->
                val key = keys.incrementAndGet()
                payload[key] = value.folder
                FolderRecommendation(key, value.folder.name.text.text, value.description.orEmpty())
            }
            val before = native.get()
            if (before == null) { result(SettingsResult(error = SettingsError.NOT_READY)); return@received }
            val scoped = viewCurrent(current, before)
            val candidates = before.state.folders.filter { known -> payload.values.any {
                it.name.text.text == known.name && it.colorId == known.colorId
            } }
            val existing = mutableListOf<TdApi.ChatFolder>()
            fun next(index: Int) {
                if (index == candidates.size) {
                    if (!active.get() || !scoped()) { result(SettingsResult(error = SettingsError.CONFLICT)); return }
                    val visible = mapped.filter { recommendation -> existing.none { sameRecommendation(it, payload.getValue(recommendation.key)) } }
                    synchronized(guard) { suggestions = payload.filterKeys { key -> visible.any { it.key == key } } }
                    result(SettingsResult(visible)); return
                }
                request(TdApi.GetChatFolder(candidates[index].id), scoped, { it as? TdApi.ChatFolder }) detail@ { detail ->
                    val value = detail.value ?: run { result(SettingsResult(error = detail.error)); return@detail }
                    existing += value; next(index + 1)
                }
            }
            next(0)
        }
    }

    override fun createRecommended(key: Long, expected: FolderNativeState, current: () -> Boolean,
        result: (SettingsResult<ChatFolderInfo>) -> Unit,
    ) {
        val folder = synchronized(guard) { suggestions[key] }
        if (folder == null) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
        val scoped = mutationCurrent(current, expected)
        capabilities(scoped) ready@ { response ->
            val capability = response.value ?: run { result(SettingsResult(error = response.error)); return@ready }
            if (expected.folders.size >= capability.folderLimit) { result(SettingsResult(error = SettingsError.FORBIDDEN)); return@ready }
            // A previously read key may outlive another device's Create, or an earlier identical
            // suggestion in this batch. TDLib itself permits duplicate definitions, so re-read
            // matching native definitions before the write instead of guessing from their name.
            hasEquivalentRecommendation(folder, expected, scoped) equivalent@ { response ->
                val duplicate = response.value ?: run { result(SettingsResult(error = response.error)); return@equivalent }
                if (duplicate) {
                    synchronized(guard) { suggestions = suggestions - key }
                    result(SettingsResult(error = SettingsError.CONFLICT)); return@equivalent
                }
                request(TdApi.CreateChatFolder(folder), scoped, {
                    (it as? TdApi.ChatFolderInfo)?.takeIf { value -> value.id >= 2 }?.let(::mapFolderInfo)
                }) { accepted ->
                    if (accepted.value != null) synchronized(guard) { suggestions = suggestions - key }
                    result(accepted)
                }
            }
        }
    }

    private fun hasEquivalentRecommendation(folder: TdApi.ChatFolder, expected: FolderNativeState,
        current: () -> Boolean, result: (SettingsResult<Boolean>) -> Unit,
    ) {
        val candidates = expected.folders.filter { it.name == folder.name.text.text && it.colorId == folder.colorId }
        fun next(index: Int) {
            if (!active.get() || !current()) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
            if (index == candidates.size) { result(SettingsResult(false)); return }
            request(TdApi.GetChatFolder(candidates[index].id), current, { it as? TdApi.ChatFolder }) received@ { response ->
                val value = response.value ?: run { result(SettingsResult(error = response.error)); return@received }
                if (!active.get() || !current()) { result(SettingsResult(error = SettingsError.NOT_READY)); return@received }
                if (sameRecommendation(value, folder)) result(SettingsResult(true)) else next(index + 1)
            }
        }
        next(0)
    }

    override fun remove(id: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        val folder = expected.folders.singleOrNull { it.id == id }
        if (folder == null || folder.isShareable) { result(SettingsResult(error = SettingsError.UNSUPPORTED)); return }
        // Never leave a group, channel, or shared chat as a side effect of folder management.
        request(TdApi.DeleteChatFolder(id, longArrayOf()), mutationCurrent(current, expected), ::ok, result)
    }

    override fun reorder(ids: List<Int>, mainPosition: Int, expected: FolderNativeState, current: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        if (ids.size > 100 || ids.size != ids.distinct().size || ids.toSet() != expected.folders.map { it.id }.toSet() ||
            mainPosition !in 0..ids.size) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        val scoped = mutationCurrent(current, expected)
        request(TdApi.GetMe(), scoped, { it as? TdApi.User }) ready@ { response ->
            val user = response.value ?: run { result(SettingsResult(error = response.error)); return@ready }
            if (!user.isPremium && mainPosition != 0) { result(SettingsResult(error = SettingsError.FORBIDDEN)); return@ready }
            request(TdApi.ReorderChatFolders(ids.toIntArray(), mainPosition), scoped, ::ok, result)
        }
    }

    override fun toggleTags(enabled: Boolean, expected: FolderNativeState, current: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        val scoped = mutationCurrent(current, expected)
        request(TdApi.GetMe(), scoped, { it as? TdApi.User }) ready@ { response ->
            val user = response.value ?: run { result(SettingsResult(error = response.error)); return@ready }
            if (!user.isPremium && enabled) { result(SettingsResult(error = SettingsError.FORBIDDEN)); return@ready }
            request(TdApi.ToggleChatFolderTags(enabled), scoped, ::ok, result)
        }
    }

    override fun color(folder: ChatFolderInfo, colorId: Int, expected: FolderNativeState, current: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        if (colorId !in -1..6 || folder.isShareable || expected.folders.singleOrNull { it.id == folder.id } != folder) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        val scoped = mutationCurrent(current, expected)
        request(TdApi.GetMe(), scoped, { it as? TdApi.User }) me@ { response ->
            val user = response.value ?: run { result(SettingsResult(error = response.error)); return@me }
            if (!user.isPremium || !expected.tagsEnabled) { result(SettingsResult(error = SettingsError.FORBIDDEN)); return@me }
            request(TdApi.GetChatFolder(folder.id), scoped, { it as? TdApi.ChatFolder }) fresh@ { response ->
                val value = response.value ?: run { result(SettingsResult(error = response.error)); return@fresh }
                if (value.isShareable || value.colorId != folder.colorId || value.name?.text?.text != folder.name) {
                    result(SettingsResult(error = SettingsError.CONFLICT)); return@fresh
                }
                val replacement = TdApi.ChatFolder(value.name, value.icon, colorId, value.isShareable,
                    value.pinnedChatIds, value.includedChatIds, value.excludedChatIds,
                    value.excludeMuted, value.excludeRead, value.excludeArchived, value.includeContacts,
                    value.includeNonContacts, value.includeBots, value.includeGroups, value.includeChannels)
                fun save(icon: String?) {
                    if (icon != folder.iconName) { result(SettingsResult(error = SettingsError.CONFLICT)); return }
                    request(TdApi.EditChatFolder(folder.id, replacement), scoped, {
                        if (it is TdApi.ChatFolderInfo && it.id == folder.id && it.colorId == colorId) Unit else null
                    }, result)
                }
                if (value.icon == null && folder.iconName != null) request(TdApi.GetChatFolderDefaultIconName(value),
                    scoped, { (it as? TdApi.ChatFolderIcon)?.name }) icon@ { icon ->
                    if (icon.value == null) result(SettingsResult(error = icon.error)) else save(icon.value)
                } else save(value.icon?.name)
            }
        }
    }

    private fun mutationCurrent(current: () -> Boolean, expected: FolderNativeState): () -> Boolean =
        viewCurrent(current, native.get()?.takeIf { it.state == expected })
    private fun viewCurrent(current: () -> Boolean, expected: NativeView?): () -> Boolean =
        { current() && expected != null && native.get() === expected }
    internal fun captureEditorView(current: () -> Boolean): () -> Boolean {
        val revision = nativeRevision.get()
        return { active.get() && current() && nativeRevision.get() == revision }
    }
    private fun sameRecommendation(a: TdApi.ChatFolder, b: TdApi.ChatFolder): Boolean {
        fun title(value: TdApi.ChatFolder) = Triple(value.name?.text?.text, value.name?.animateCustomEmoji,
            value.name?.text?.entities.orEmpty().map { entity -> Triple(entity.offset, entity.length,
                (entity.type as? TdApi.TextEntityTypeCustomEmoji)?.customEmojiId) })
        fun included(value: TdApi.ChatFolder) = ((value.pinnedChatIds ?: longArrayOf()).toList() +
            (value.includedChatIds ?: longArrayOf()).toList()).toSet()
        return title(a) == title(b) && a.icon?.name == b.icon?.name && a.colorId == b.colorId && a.isShareable == b.isShareable &&
            included(a) == included(b) && (a.excludedChatIds ?: longArrayOf()).toSet() == (b.excludedChatIds ?: longArrayOf()).toSet() &&
            a.excludeMuted == b.excludeMuted && a.excludeRead == b.excludeRead && a.excludeArchived == b.excludeArchived &&
            a.includeContacts == b.includeContacts && a.includeNonContacts == b.includeNonContacts && a.includeBots == b.includeBots &&
            a.includeGroups == b.includeGroups && a.includeChannels == b.includeChannels
    }
    private fun option(name: String, range: IntRange, current: () -> Boolean, result: (SettingsResult<Int>) -> Unit) =
        request(TdApi.GetOption(name), current, { (it as? TdApi.OptionValueInteger)?.value?.takeIf { value -> value in range.first.toLong()..range.last.toLong() }?.toInt() }, result)
    private fun ok(value: TdApi.Object) = if (value is TdApi.Ok) Unit else null

    private fun <T> request(command: TdApi.Function<*>, current: () -> Boolean, map: (TdApi.Object) -> T?,
        result: (SettingsResult<T>) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun finish(value: SettingsResult<T>) { if (once.compareAndSet(false, true)) result(value) }
        if (!active.get() || !current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        try { send(command, { response ->
            val value = runCatching { map(response) }.getOrNull()
            finish(if (value != null) SettingsResult(value) else SettingsResult(error = settingsError(response)))
        }, { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
}
