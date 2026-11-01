package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Session metadata only. GetMe is a TDLib cache read, never a server-side compare-and-swap. */
internal class OfficialTdSettingsProfileColors(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdSettingsProfileColorsSession {
    private val lock = Any()
    private var retired = false
    private var observer: (() -> Unit)? = null
    private var colors = SettingsProfileColorsCatalog()
    private var self: SettingsProfileColorsData? = null
    private var access: Boolean? = null
    private var regular: Boolean? = null
    private var mutation: Operation<SettingsProfileColorMutationResult>? = null
    private val operations = LinkedHashSet<Operation<*>>()
    private val knownEmojis = LinkedHashMap<Long, SettingsBackgroundEmoji>()

    override fun observe(observer: (() -> Unit)?) {
        synchronized(lock) { if (!retired) this.observer = observer }
    }

    override fun catalog(): SettingsProfileColorsCatalog = synchronized(lock) { colors }

    internal fun accept(update: TdApi.Object) {
        val notify = synchronized(lock) {
            if (retired) return
            when (update) {
                is TdApi.UpdateAccentColors -> {
                    val mapped = runCatching { nameCatalog(update) }.getOrNull()
                    colors = if (mapped == null) colors.copy(revision = colors.revision + 1,
                        nameError = SettingsError.INVALID_VALUE)
                    else colors.copy(nameColors = mapped.first, availableNameColorIds = mapped.second,
                        revision = colors.revision + 1, nameError = null)
                }
                is TdApi.UpdateProfileAccentColors -> {
                    val mapped = runCatching { profileCatalog(update) }.getOrNull()
                    colors = if (mapped == null) colors.copy(revision = colors.revision + 1,
                        profileError = SettingsError.INVALID_VALUE)
                    else colors.copy(profileColors = mapped.first, availableProfileColorIds = mapped.second,
                        revision = colors.revision + 1, profileError = null)
                }
                is TdApi.UpdateUser -> {
                    val user = update.user ?: return
                    if (self?.userId != user.id) return
                    updateSelfLocked(user)
                }
                else -> return
            }
            self = self?.copy(catalog = colors)
            observer
        }
        notifySafely(notify)
    }

    override fun load(userId: Long, active: () -> Boolean, result: (SettingsResult<SettingsProfileColorsData>) -> Unit) {
        val op = operation(active, result, SettingsResult<SettingsProfileColorsData>(error = SettingsError.NOT_READY)) ?: return
        if (userId <= 0) { op.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        readSelf(op, userId) { data, error -> op.finish(SettingsResult(data, error)) }
    }

    override fun save(expected: SettingsProfileColorsData, part: SettingsProfileColorPart,
        desired: SettingsProfileColorValue, replaceGift: Boolean, active: () -> Boolean,
        result: (SettingsProfileColorMutationResult) -> Unit,
    ) {
        val op = operation(active, result, SettingsProfileColorMutationResult(error = SettingsError.NOT_READY)) ?: return
        val acquired = synchronized(lock) {
            if (mutation != null || retired) false else { mutation = op; true }
        }
        if (!acquired) { op.finish(SettingsProfileColorMutationResult(error = SettingsError.NOT_READY)); return }
        if (expected.userId <= 0) { op.finish(SettingsProfileColorMutationResult(error = SettingsError.INVALID_VALUE)); return }
        readSelf(op, expected.userId) read@ { fresh, error ->
            if (fresh == null) { op.finish(SettingsProfileColorMutationResult(error = error)); return@read }
            val invalid = synchronized(lock) { validateSaveLocked(expected, fresh, part, desired, replaceGift) }
            if (invalid != null) { op.finish(SettingsProfileColorMutationResult(error = invalid)); return@read }
            if (!op.current()) { op.finish(SettingsProfileColorMutationResult(error = SettingsError.NOT_READY)); return@read }
            // Recheck after the foreign lease callback, which can reenter accept()/retire().
            val ready = synchronized(lock) {
                if (retired || op.done.get()) SettingsError.NOT_READY
                else validateSaveLocked(expected, self ?: fresh, part, desired, replaceGift).also {
                    if (it == null) op.writing = true
                }
            }
            if (ready != null) { op.finish(SettingsProfileColorMutationResult(error = ready)); return@read }
            val command = if (part == SettingsProfileColorPart.NAME)
                TdApi.SetAccentColor(desired.colorId, desired.backgroundCustomEmojiId)
            else TdApi.SetProfileAccentColor(desired.colorId, desired.backgroundCustomEmojiId)
            request(command, finalWrite = true) response@ { response ->
                if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                    op.uncertain(SettingsProfileColorMutationResult(error = SettingsError.REQUEST_FAILED))
                    return@response
                }
                if (response !is TdApi.Ok) {
                    op.finish(SettingsProfileColorMutationResult(error = errorOf(response))); return@response
                }
                synchronized(lock) {
                    op.writing = false
                    op.cancellation = SettingsProfileColorMutationResult(locallyAccepted = true, error = SettingsError.NOT_READY)
                }
                // Ok belongs to the original operation, even after navigation or session retirement.
                if (!op.current()) {
                    op.finish(SettingsProfileColorMutationResult(locallyAccepted = true, error = SettingsError.NOT_READY))
                    return@response
                }
                readSelf(op, expected.userId) { actual, readError ->
                    val mismatch = actual?.values?.value(part) != desired ||
                        (part == SettingsProfileColorPart.NAME && actual?.values?.gift != null)
                    op.finish(SettingsProfileColorMutationResult(true, actual,
                        readError ?: if (mismatch) SettingsError.CONFLICT else null))
                }
            }
        }
    }

    override fun backgroundEmojis(active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) =
        emojis(null, active, result)

    override fun resolveEmojis(ids: List<Long>, active: () -> Boolean,
        result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit,
    ) {
        // Signed int64 identities are valid. Zero is the remove-pattern action, not metadata.
        if (ids.size > 200 || ids.any { it == 0L }) {
            runCatching { result(SettingsResult(error = SettingsError.INVALID_VALUE)) }; return
        }
        emojis(immutable(ids.distinct()), active, result)
    }

    private fun emojis(ids: List<Long>?, active: () -> Boolean,
        result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit,
    ) {
        val op = operation(active, result, SettingsResult<List<SettingsBackgroundEmoji>>(error = SettingsError.NOT_READY)) ?: return
        if (!op.current()) { op.finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        if (ids?.isEmpty() == true) { op.finish(SettingsResult(emptyList())); return }
        request(if (ids == null) TdApi.GetDefaultBackgroundCustomEmojiStickers() else TdApi.GetCustomEmojiStickers(ids.toLongArray())) response@ { response ->
            if (!op.current()) { op.finish(SettingsResult(error = SettingsError.NOT_READY)); return@response }
            val stickers = (response as? TdApi.Stickers)?.stickers
            if (stickers == null) { op.finish(SettingsResult(error = errorOf(response))); return@response }
            if (stickers.size > (if (ids == null) 4096 else 200)) {
                op.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return@response
            }
            val requested = ids?.toSet()
            val mapped = runCatching { stickers.mapNotNull { sticker ->
                val fullType = sticker?.fullType as? TdApi.StickerFullTypeCustomEmoji
                if (fullType == null || fullType.customEmojiId == 0L ||
                    (requested != null && fullType.customEmojiId !in requested)) null
                else mapSticker(sticker)?.let { SettingsBackgroundEmoji(fullType.customEmojiId, it, fullType.needsRepainting) }
            }.distinctBy { it.customEmojiId } }.getOrNull()
            if (mapped == null) { op.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return@response }
            val live = synchronized(lock) {
                if (retired || op.done.get()) false else {
                    mapped.forEach { knownEmojis[it.customEmojiId] = it }
                    while (knownEmojis.size > 4096) knownEmojis.remove(knownEmojis.keys.first())
                    true
                }
            }
            op.finish(if (live) SettingsResult(immutable(mapped)) else SettingsResult(error = SettingsError.NOT_READY))
        }
    }

    override fun retire() {
        val pending = synchronized(lock) {
            if (retired) return
            retired = true; observer = null; self = null; access = null; regular = null
            colors = SettingsProfileColorsCatalog(); knownEmojis.clear()
            operations.filterNot { it.writing }
        }
        pending.forEach { it.cancel() }
    }

    private fun validateSaveLocked(expected: SettingsProfileColorsData, fresh: SettingsProfileColorsData,
        part: SettingsProfileColorPart, desired: SettingsProfileColorValue, replaceGift: Boolean,
    ): SettingsError? {
        if (retired || self?.userId != expected.userId || access != true || regular != true) return SettingsError.NOT_READY
        if (!fresh.premium) return SettingsError.FORBIDDEN
        if (fresh.nativeRevision != expected.nativeRevision || fresh.premium != expected.premium ||
            fresh.values.value(part) != expected.values.value(part) || colors.revision != expected.catalog.revision ||
            (part == SettingsProfileColorPart.NAME && fresh.values.gift != expected.values.gift)) return SettingsError.CONFLICT
        if ((if (part == SettingsProfileColorPart.NAME) colors.nameError else colors.profileError) != null)
            return SettingsError.CONFLICT
        val old = fresh.values.value(part)
        val reset = if (part == SettingsProfileColorPart.NAME) SettingsProfileColorValue((fresh.userId % 7).toInt())
            else SettingsProfileColorValue(-1)
        if (desired.colorId != old.colorId && desired != reset) {
            val available = if (part == SettingsProfileColorPart.NAME) colors.availableNameColorIds else colors.availableProfileColorIds
            if (available == null || desired.colorId !in available) return SettingsError.INVALID_VALUE
        }
        if (desired.colorId < (if (part == SettingsProfileColorPart.NAME) 0 else -1)) return SettingsError.INVALID_VALUE
        if (desired.backgroundCustomEmojiId != 0L && desired.backgroundCustomEmojiId != old.backgroundCustomEmojiId &&
            desired.backgroundCustomEmojiId !in knownEmojis) return SettingsError.INVALID_VALUE
        if (part == SettingsProfileColorPart.NAME && fresh.values.gift != null && !replaceGift) return SettingsError.CONFLICT
        return null
    }

    private fun <T> readSelf(op: Operation<T>, userId: Long,
        result: (SettingsProfileColorsData?, SettingsError?) -> Unit,
    ) {
        if (!op.current()) { result(null, SettingsError.NOT_READY); return }
        val start = synchronized(lock) { self?.nativeRevision }
        request(TdApi.GetMe()) response@ { response ->
            if (!op.current()) { result(null, SettingsError.NOT_READY); return@response }
            val user = response as? TdApi.User
            if (user == null) { result(null, errorOf(response)); return@response }
            if (user.id != userId || user.type !is TdApi.UserTypeRegular) {
                result(null, SettingsError.NOT_READY); return@response
            }
            val outcome = synchronized(lock) {
                if (retired || (self != null && self?.userId != userId)) null
                else {
                    // A newer real UpdateUser wins over an older in-flight GetMe cache response.
                    if (self?.nativeRevision == start) updateSelfLocked(user)
                    self?.copy(catalog = colors)
                }
            }
            result(outcome, if (outcome == null) SettingsError.NOT_READY else null)
        }
    }

    private fun updateSelfLocked(user: TdApi.User) {
        val values = SettingsProfileColorsValues(
            SettingsProfileColorValue(user.accentColorId, user.backgroundCustomEmojiId),
            SettingsProfileColorValue(user.profileAccentColorId, user.profileBackgroundCustomEmojiId),
            user.upgradedGiftColors?.let { SettingsUpgradedGiftColors(it.id, it.modelCustomEmojiId, it.symbolCustomEmojiId,
                it.lightThemeAccentColor, immutable(it.lightThemeColors?.toList().orEmpty()),
                it.darkThemeAccentColor, immutable(it.darkThemeColors?.toList().orEmpty())) })
        val isRegular = user.type is TdApi.UserTypeRegular
        val changed = self?.let { it.values != values || it.premium != user.isPremium ||
            access != user.haveAccess || regular != isRegular } ?: false
        self = SettingsProfileColorsData(user.id, user.isPremium, values, colors,
            (self?.nativeRevision ?: 0) + if (changed) 1 else 0)
        access = user.haveAccess; regular = isRegular
    }

    private inner class Operation<T>(val active: () -> Boolean, val callback: (T) -> Unit, cancelled: T) {
        val done = AtomicBoolean()
        var cancellation = cancelled // guarded by lock
        var writing = false // guarded by lock; retains the actual Set result through retirement
        fun current(): Boolean {
            if (done.get() || synchronized(lock) { retired }) return false
            return runCatching { active() }.getOrDefault(false) && !done.get() && synchronized(lock) { !retired }
        }
        fun finish(value: T) {
            if (!done.compareAndSet(false, true)) return
            synchronized(lock) { operations.remove(this); if (mutation === this) mutation = null }
            runCatching { callback(value) }
        }
        fun uncertain(value: T) {
            if (!done.get()) runCatching { callback(value) }
        }
        fun cancel() = finish(synchronized(lock) { cancellation })
    }

    private fun <T> operation(active: () -> Boolean, result: (T) -> Unit, cancelled: T): Operation<T>? {
        val op = Operation(active, result, cancelled)
        val accepted = synchronized(lock) { if (retired || operations.size >= 32) false else operations.add(op) }
        if (!accepted || !op.current()) { op.cancel(); return null }
        return op
    }

    private fun request(command: TdApi.Function<*>, finalWrite: Boolean = false, callback: (TdApi.Object?) -> Unit) {
        val once = AtomicBoolean()
        fun finish(value: TdApi.Object?) { if (once.compareAndSet(false, true)) callback(value) }
        if (finalWrite) {
            // The boundary owns dispatch/unknown classification; never fabricate a native Error here.
            val uncertaintyReported = AtomicBoolean()
            SettingsSpecialMutationContext.write(command, send, { value ->
                if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                    if (!once.get() && uncertaintyReported.compareAndSet(false, true)) callback(value)
                } else finish(value)
            }, {
                if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                    if (!once.get() && uncertaintyReported.compareAndSet(false, true)) callback(null)
                } else finish(null)
            })
        } else {
            try { send(command, { finish(it) }, { finish(null) }) }
            catch (_: Exception) { finish(TdApi.Error(500, "request failed")) }
        }
    }

    private fun errorOf(value: TdApi.Object?): SettingsError = if (value == null) SettingsError.NOT_READY else settingsError(value)
    private fun notifySafely(callback: (() -> Unit)?) { if (callback != null) runCatching { callback() } }

    private fun nameCatalog(update: TdApi.UpdateAccentColors): Pair<List<SettingsNameAccentColor>, List<Int>> {
        val native = requireNotNull(update.colors); val ids = requireNotNull(update.availableAccentColorIds)
        require(native.size <= 4096 && ids.size <= 4096)
        val mapped = native.map { color ->
            require(color.id >= 7 && color.builtInAccentColorId in 0..6 && color.minChannelChatBoostLevel >= 0)
            SettingsNameAccentColor(color.id, color.builtInAccentColorId, rgb(color.lightThemeColors, 1..3),
                rgb(color.darkThemeColors, 1..3), color.minChannelChatBoostLevel)
        }
        val keys = mapped.map { it.id }.toSet()
        require(keys.size == mapped.size && ids.distinct().size == ids.size && ids.all { it in 0..6 || it in keys })
        return immutable(mapped) to immutable(ids.toList())
    }

    private fun profileCatalog(update: TdApi.UpdateProfileAccentColors): Pair<List<SettingsProfileAccentColor>, List<Int>> {
        val native = requireNotNull(update.colors); val ids = requireNotNull(update.availableAccentColorIds)
        require(native.size <= 4096 && ids.size <= 4096)
        fun palette(value: TdApi.ProfileAccentColors?): SettingsProfileAccentPalette {
            val p = requireNotNull(value)
            return SettingsProfileAccentPalette(rgb(p.paletteColors, 1..2), rgb(p.backgroundColors, 1..2), rgb(p.storyColors, 2..2))
        }
        val mapped = native.map { color ->
            require(color.id >= 0 && color.minSupergroupChatBoostLevel >= 0 && color.minChannelChatBoostLevel >= 0)
            SettingsProfileAccentColor(color.id, palette(color.lightThemeColors), palette(color.darkThemeColors),
                color.minSupergroupChatBoostLevel, color.minChannelChatBoostLevel)
        }
        val keys = mapped.map { it.id }.toSet()
        require(keys.size == mapped.size && ids.distinct().size == ids.size && ids.all { it in keys })
        return immutable(mapped) to immutable(ids.toList())
    }

    private fun rgb(values: IntArray?, count: IntRange): List<Int> {
        requireNotNull(values); require(values.size in count && values.all { it in 0..0xffffff })
        return immutable(values.toList())
    }

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
