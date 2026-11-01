package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** An opening source is immutable, bounded and private to its native session. */
internal class OfficialTdFolderEditor(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    private val captureView: (() -> Boolean) -> (() -> Boolean),
) {
    private data class Source(val raw: TdApi.ChatFolder, val model: ChatFolder)
    private val sources = linkedMapOf<Long, Source>()
    private val lock = Any()

    fun get(id: Int, current: () -> Boolean, result: (ChatResult<ChatFolder>) -> Unit) {
        if (id < 2) { result(ChatResult(error = ChatError.NOT_READY)); return }
        val scoped = captureView(current)
        request(TdApi.GetChatFolder(id), scoped, { it as? TdApi.ChatFolder }) received@ { response ->
            val raw = response.value ?: run { result(failure(response)); return@received }
            if (!scoped()) { result(ChatResult(error = ChatError.NOT_READY)); return@received }
            if (!bounded(raw)) { result(ChatResult(error = ChatError.UNSUPPORTED)); return@received }
            val key = ids.incrementAndGet()
            val frozen = copy(raw)
            val model = mapChatFolder(id, frozen).copy(sourceId = key)
            synchronized(lock) {
                sources[key] = Source(frozen, model)
                while (sources.size > 2) sources.remove(sources.keys.first())
            }
            result(ChatResult(model))
        }
    }

    fun save(desired: ChatFolder, current: () -> Boolean, result: (ChatResult<ChatFolderInfo>) -> Unit) {
        if (!validName(desired) || !bounded(desired)) { result(ChatResult(error = ChatError.UNSUPPORTED)); return }
        val scoped = captureView(current)
        if (desired.id == 0) {
            if (desired.sourceId != 0L || desired.isShareable) { result(ChatResult(error = ChatError.UNSUPPORTED)); return }
            val raw = nativeNew(desired)
            request(TdApi.CreateChatFolder(raw), scoped, { (it as? TdApi.ChatFolderInfo)?.takeIf { info -> info.id >= 2 } }) {
                result(if (it.value != null) ChatResult(mapFolderInfo(it.value)) else failure(it))
            }
            return
        }
        val source = synchronized(lock) { sources[desired.sourceId] }?.takeIf { it.model.id == desired.id }
        if (source == null) { result(ChatResult(error = ChatError.NOT_READY)); return }
        if (source.raw.isShareable || desired.isShareable) { result(ChatResult(error = ChatError.UNSUPPORTED)); return }
        request(TdApi.GetChatFolder(desired.id), scoped, { it as? TdApi.ChatFolder }) fresh@ { response ->
            val fresh = response.value ?: run { result(failure(response)); return@fresh }
            if (!scoped()) { result(ChatResult(error = ChatError.NOT_READY)); return@fresh }
            if (!bounded(fresh)) { result(ChatResult(error = ChatError.UNSUPPORTED)); return@fresh }
            if (fresh.isShareable) { result(ChatResult(error = ChatError.UNSUPPORTED)); return@fresh }
            val replacement = try { merge(source, desired, fresh) }
            catch (_: Conflict) { result(ChatResult(error = ChatError.NOT_READY)); return@fresh }
            catch (_: Unsupported) { result(ChatResult(error = ChatError.UNSUPPORTED)); return@fresh }
            request(TdApi.EditChatFolder(desired.id, replacement), scoped, {
                (it as? TdApi.ChatFolderInfo)?.takeIf { info -> info.id == desired.id }
            }) { accepted ->
                if (accepted.value != null) synchronized(lock) { sources.remove(desired.sourceId) }
                result(if (accepted.value != null) ChatResult(mapFolderInfo(accepted.value)) else failure(accepted))
            }
        }
    }

    private fun merge(source: Source, desired: ChatFolder, fresh: TdApi.ChatFolder): TdApi.ChatFolder {
        val base = source.model
        val nameChanged = desired.name != base.name || desired.nameEntities != base.nameEntities
        val animationChanged = desired.animateCustomEmoji != base.animateCustomEmoji
        var name = fresh.name
        if (nameChanged) {
            if (!base.nameEditable || !desired.nameEditable) throw Unsupported()
            if (title(source.raw.name) != title(fresh.name)) throw Conflict()
            name = TdApi.ChatFolderName(RichMessageText(desired.name, desired.nameEntities).toNative(), fresh.name.animateCustomEmoji)
        }
        if (animationChanged) {
            if (!base.nameEditable || source.raw.name.animateCustomEmoji != fresh.name.animateCustomEmoji) throw Conflict()
            name = TdApi.ChatFolderName(name.text, desired.animateCustomEmoji)
        }
        val lists = mergePeers(base, desired, fresh)
        val icon = dirty(base.iconName, desired.iconName, fresh.icon?.name)?.let { TdApi.ChatFolderIcon(it) }
        return TdApi.ChatFolder(name, icon, dirty(base.colorId, desired.colorId, fresh.colorId), fresh.isShareable,
            lists.first, lists.second, lists.third,
            dirty(base.excludeMuted, desired.excludeMuted, fresh.excludeMuted),
            dirty(base.excludeRead, desired.excludeRead, fresh.excludeRead),
            dirty(base.excludeArchived, desired.excludeArchived, fresh.excludeArchived),
            dirty(base.includeContacts, desired.includeContacts, fresh.includeContacts),
            dirty(base.includeNonContacts, desired.includeNonContacts, fresh.includeNonContacts),
            dirty(base.includeBots, desired.includeBots, fresh.includeBots),
            dirty(base.includeGroups, desired.includeGroups, fresh.includeGroups),
            dirty(base.includeChannels, desired.includeChannels, fresh.includeChannels))
    }

    /** Only explicitly touched peers move lists; unrelated native additions and pin order survive. */
    private fun mergePeers(base: ChatFolder, desired: ChatFolder, fresh: TdApi.ChatFolder): Triple<LongArray, LongArray, LongArray> {
        val original = listOf(base.pinnedChatIds, base.includedChatIds, base.excludedChatIds)
        val chosen = listOf(desired.pinnedChatIds, desired.includedChatIds, desired.excludedChatIds)
        val latest = listOf(fresh.pinnedChatIds.toList(), fresh.includedChatIds.toList(), fresh.excludedChatIds.toList())
        fun membership(lists: List<List<Long>>) = buildMap<Long, Int> {
            lists.forEachIndexed { index, values -> values.forEach { put(it, index) } }
        }
        val originalState = membership(original)
        val chosenState = membership(chosen)
        val latestState = membership(latest)
        val touched = (originalState.keys + chosenState.keys).filter { originalState[it] != chosenState[it] }.toSet()
        if (touched.any { originalState[it] != latestState[it] }) throw Conflict()
        val originalPinned = base.pinnedChatIds.toSet()
        val desiredPinned = desired.pinnedChatIds.toSet()
        val pinnedOrderChanged = base.pinnedChatIds.filter { it in desiredPinned } != desired.pinnedChatIds.filter { it in originalPinned }
        if (pinnedOrderChanged && latest[0] != base.pinnedChatIds) throw Conflict()
        val merged = latest.mapIndexed { index, list -> list.filterNot { it in touched } + chosen[index].filter { it in touched } }.toMutableList()
        if (pinnedOrderChanged) merged[0] = desired.pinnedChatIds
        return Triple(merged[0].toLongArray(), merged[1].toLongArray(), merged[2].toLongArray())
    }

    private fun <T> dirty(expected: T, desired: T, latest: T): T {
        if (desired == expected) return latest
        if (latest != expected) throw Conflict()
        return desired
    }
    private fun title(name: TdApi.ChatFolderName) = Pair(name.text.text, name.text.entities.orEmpty().map {
        Triple(it.offset, it.length, Pair(it.type?.constructor, (it.type as? TdApi.TextEntityTypeCustomEmoji)?.customEmojiId))
    })
    private fun validName(folder: ChatFolder): Boolean {
        val rich = RichMessageText(folder.name, folder.nameEntities)
        return folder.name.isNotBlank() && '\n' !in folder.name && '\r' !in folder.name &&
            folder.name.codePointCount(0, folder.name.length) <= 12 &&
            rich.validEntities().size == folder.nameEntities.size &&
            (!folder.nameEditable && folder.id > 0 || folder.nameEntities.all { it.kind == MessageEntityKind.CUSTOM_EMOJI && it.id > 0 })
    }
    private fun bounded(folder: ChatFolder) = folder.colorId in -1..6 && validLists(listOf(folder.pinnedChatIds, folder.includedChatIds, folder.excludedChatIds))
    private fun bounded(folder: TdApi.ChatFolder): Boolean {
        val text = folder.name?.text?.text ?: return false
        val entities = folder.name.text.entities.orEmpty()
        return text.isNotBlank() && '\n' !in text && '\r' !in text && text.codePointCount(0, text.length) <= 12 &&
            entities.size <= 12 && entities.all { it != null && it.type != null && it.offset >= 0 && it.length > 0 &&
                it.offset <= text.length && it.length <= text.length - it.offset &&
                text.isUtf16Boundary(it.offset) && text.isUtf16Boundary(it.offset + it.length) &&
                ((it.type as? TdApi.TextEntityTypeCustomEmoji)?.customEmojiId?.let { id -> id > 0 } != false) } &&
            folder.colorId in -1..6 && folder.pinnedChatIds != null && folder.includedChatIds != null && folder.excludedChatIds != null &&
            validLists(listOf(folder.pinnedChatIds.toList(), folder.includedChatIds.toList(), folder.excludedChatIds.toList()))
    }
    private fun validLists(lists: List<List<Long>>) = lists.all { it.size <= 20_000 && it.all { id -> id != 0L } } &&
        lists.flatten().let { it.size == it.distinct().size }
    private fun nativeNew(folder: ChatFolder) = TdApi.ChatFolder(
        TdApi.ChatFolderName(RichMessageText(folder.name, folder.nameEntities).toNative(), folder.animateCustomEmoji),
        folder.iconName?.let { TdApi.ChatFolderIcon(it) }, folder.colorId, false,
        folder.pinnedChatIds.toLongArray(), folder.includedChatIds.toLongArray(), folder.excludedChatIds.toLongArray(),
        folder.excludeMuted, folder.excludeRead, folder.excludeArchived, folder.includeContacts,
        folder.includeNonContacts, folder.includeBots, folder.includeGroups, folder.includeChannels)
    private fun copy(folder: TdApi.ChatFolder): TdApi.ChatFolder {
        val name = TdApi.ChatFolderName(TdApi.FormattedText(folder.name.text.text, folder.name.text.entities.orEmpty().map {
            TdApi.TextEntity(it.offset, it.length, (it.type as? TdApi.TextEntityTypeCustomEmoji)?.let { type -> TdApi.TextEntityTypeCustomEmoji(type.customEmojiId) } ?: it.type)
        }.toTypedArray()), folder.name.animateCustomEmoji)
        return TdApi.ChatFolder(name, folder.icon?.let { TdApi.ChatFolderIcon(it.name) }, folder.colorId, folder.isShareable,
            folder.pinnedChatIds.copyOf(), folder.includedChatIds.copyOf(), folder.excludedChatIds.copyOf(),
            folder.excludeMuted, folder.excludeRead, folder.excludeArchived, folder.includeContacts,
            folder.includeNonContacts, folder.includeBots, folder.includeGroups, folder.includeChannels)
    }
    private fun <T> failure(response: ChatResult<*>) = ChatResult<T>(error = response.error ?: ChatError.REQUEST_FAILED, code = response.code)
    private fun <T> request(command: TdApi.Function<*>, current: () -> Boolean, map: (TdApi.Object) -> T?, result: (ChatResult<T>) -> Unit) {
        val once = AtomicBoolean()
        fun complete(value: ChatResult<T>) { if (once.compareAndSet(false, true)) result(value) }
        if (!current()) { complete(ChatResult(error = ChatError.NOT_READY)); return }
        try { send(command, { response ->
            val value = runCatching { map(response) }.getOrNull()
            complete(if (value != null) ChatResult(value) else if (response is TdApi.Error)
                ChatResult(error = classifyChatError(response), code = response.code) else ChatResult(error = ChatError.REQUEST_FAILED))
        }, { complete(ChatResult(error = ChatError.REQUEST_FAILED)) }) }
        catch (_: Exception) { complete(ChatResult(error = ChatError.REQUEST_FAILED)) }
    }
    private class Conflict : Exception()
    private class Unsupported : Exception()
    private companion object { val ids = AtomicLong() }
}
