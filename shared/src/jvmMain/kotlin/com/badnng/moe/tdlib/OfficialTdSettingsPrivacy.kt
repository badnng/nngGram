package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class OfficialTdSettingsPrivacy(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    private val lock = Any()
    private val loadedRules = mutableMapOf<PrivacyKey, PrivacyRules>()

    fun load(result: (SettingsResult<Pair<Map<PrivacyKey, PrivacyRules>, Map<PrivacyKey, SettingsError>>>) -> Unit) {
        val values = mutableMapOf<PrivacyKey, PrivacyRules>()
        val errors = mutableMapOf<PrivacyKey, SettingsError>()
        val remaining = AtomicInteger(PrivacyKey.entries.size)
        for (key in PrivacyKey.entries) get(key) { response ->
            synchronized(lock) {
                response.value?.let { values[key] = it; loadedRules[key] = it }
                response.error?.let { errors[key] = it; loadedRules.remove(key) }
            }
            if (remaining.decrementAndGet() == 0) synchronized(lock) {
                result(SettingsResult(values.toMap() to errors.toMap()))
            }
        }
    }

    fun save(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) {
        if (key == PrivacyKey.FIND_BY_PHONE_NUMBER && base == PrivacyBase.NOBODY) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        val expected = synchronized(lock) { loadedRules[key] }
        if (expected == null || !expected.editable) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
        // Refresh immediately before writing. A remote edit requires an explicit reload rather than
        // overwriting the other client's exceptions from an old settings form.
        get(key) { current ->
            val value = current.value
            if (value == null) { result(SettingsResult(error = current.error ?: SettingsError.REQUEST_FAILED)); return@get }
            if (value != expected) { result(SettingsResult(error = SettingsError.NOT_READY)); return@get }
            val replacement = replacePrivacyBase(value, base)
            if (replacement == null) { result(SettingsResult(error = SettingsError.UNSUPPORTED)); return@get }
            val rules = replacement.rules.map(::nativePrivacyRule).toTypedArray()
            request(TdApi.SetUserPrivacySettingRules(key.native(), TdApi.UserPrivacySettingRules(rules)), {
                if (it is TdApi.Ok) Unit else null
            }) { response ->
                if (response.value != null) synchronized(lock) { loadedRules[key] = replacement }
                result(response)
            }
        }
    }

    fun saveDraft(key: PrivacyKey, draft: PrivacyDraft, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        val replacement = draft.replacement(key)
        if (replacement == null) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        val expected = synchronized(lock) { loadedRules[key] }
        if (!isCurrent() || expected != draft.source || !draft.source.editable) {
            result(SettingsResult(error = SettingsError.NOT_READY)); return
        }
        val guarded: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { command, receive, failed ->
            if (isCurrent()) send(command, receive, failed) else failed()
        }
        // Use this boundary for every Get and Set in the operation as well as peer lookups.
        // A callback's earlier ownership check does not authorize a later native send.
        val scoped = OfficialTdSettingsPrivacy(guarded)
        scoped.get(key) fresh@ { response ->
            if (!isCurrent()) { result(SettingsResult(error = SettingsError.NOT_READY)); return@fresh }
            val current = response.value
            if (current == null) { result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@fresh }
            if (current != draft.source) { result(SettingsResult(error = SettingsError.CONFLICT)); return@fresh }
            val old = PrivacyDraft.from(draft.source)
            val original = (old?.selected(PrivacyExceptionTarget.ALLOW).orEmpty() + old?.selected(PrivacyExceptionTarget.DENY).orEmpty()).toSet()
            val added = (draft.selected(PrivacyExceptionTarget.ALLOW) + draft.selected(PrivacyExceptionTarget.DENY)).filter { it !in original }
            OfficialTdSettingsPrivacyPeers(guarded).validate(key, added) validated@ { validation ->
                if (!isCurrent()) { result(SettingsResult(error = SettingsError.NOT_READY)); return@validated }
                if (validation.value == null) { result(SettingsResult(error = validation.error)); return@validated }
                // Identity resolution can take time. Recheck the complete first-match sequence
                // immediately before writing, rather than trusting the earlier read across that work.
                scoped.get(key) finalRead@ { final ->
                    if (!isCurrent() || final.value != draft.source) {
                        result(SettingsResult(error = final.error ?: if (isCurrent()) SettingsError.CONFLICT else SettingsError.NOT_READY)); return@finalRead
                    }
                    scoped.request(TdApi.SetUserPrivacySettingRules(key.native(),
                        TdApi.UserPrivacySettingRules(replacement.rules.map(::nativePrivacyRule).toTypedArray())), {
                        if (it is TdApi.Ok) Unit else null
                    }) { accepted ->
                        if (accepted.value != null) synchronized(lock) { loadedRules[key] = replacement }
                        result(accepted)
                    }
                }
            }
        }
    }

    private fun get(key: PrivacyKey, result: (SettingsResult<PrivacyRules>) -> Unit) =
        request(TdApi.GetUserPrivacySettingRules(key.native()), { (it as? TdApi.UserPrivacySettingRules)?.let(::mapPrivacyRules) }, result)

    private fun <T> request(function: TdApi.Function<*>, map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit) {
        val completed = AtomicBoolean()
        fun finish(value: SettingsResult<T>) { if (completed.compareAndSet(false, true)) result(value) }
        try { send(function, { response ->
            val value = runCatching { map(response) }.getOrNull()
            if (value == null) finish(SettingsResult(error = settingsError(response))) else finish(SettingsResult(value))
        }, { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
}

internal fun replacePrivacyBase(source: PrivacyRules, base: PrivacyBase): PrivacyRules? {
    if (!source.editable || source.base == null) return null
    if (source.base == base) return source
    val exceptions = source.rules.filter { it.kind !in setOf(PrivacyRuleKind.ALLOW_ALL,
        PrivacyRuleKind.ALLOW_CONTACTS, PrivacyRuleKind.RESTRICT_ALL) }
    val baseline = when (base) {
        PrivacyBase.EVERYBODY -> listOf(PrivacyRule(PrivacyRuleKind.ALLOW_ALL))
        PrivacyBase.CONTACTS -> listOf(PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS), PrivacyRule(PrivacyRuleKind.RESTRICT_ALL))
        PrivacyBase.NOBODY -> listOf(PrivacyRule(PrivacyRuleKind.RESTRICT_ALL))
    }
    return PrivacyRules(base, exceptions + baseline)
}

internal fun mapPrivacyRules(value: TdApi.UserPrivacySettingRules): PrivacyRules {
    val native = value.rules.orEmpty()
    // Refuse editing rather than truncating a server rule or an unexpectedly large exception list.
    if (native.size > 128) return PrivacyRules(null, emptyList(), editable = false)
    val ids = native.sumOf { rule -> when (rule) {
        is TdApi.UserPrivacySettingRuleAllowUsers -> rule.userIds?.size?.toLong() ?: 0L
        is TdApi.UserPrivacySettingRuleAllowChatMembers -> rule.chatIds?.size?.toLong() ?: 0L
        is TdApi.UserPrivacySettingRuleRestrictUsers -> rule.userIds?.size?.toLong() ?: 0L
        is TdApi.UserPrivacySettingRuleRestrictChatMembers -> rule.chatIds?.size?.toLong() ?: 0L
        else -> 0L
    } }
    if (ids > 10_000L) return PrivacyRules(null, emptyList(), editable = false)
    val mapped = native.mapNotNull { rule -> when (rule) {
        is TdApi.UserPrivacySettingRuleAllowAll -> PrivacyRule(PrivacyRuleKind.ALLOW_ALL)
        is TdApi.UserPrivacySettingRuleAllowContacts -> PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS)
        is TdApi.UserPrivacySettingRuleAllowBots -> PrivacyRule(PrivacyRuleKind.ALLOW_BOTS)
        is TdApi.UserPrivacySettingRuleAllowPremiumUsers -> PrivacyRule(PrivacyRuleKind.ALLOW_PREMIUM)
        is TdApi.UserPrivacySettingRuleAllowUsers -> PrivacyRule(PrivacyRuleKind.ALLOW_USERS, (rule.userIds ?: longArrayOf()).toList())
        is TdApi.UserPrivacySettingRuleAllowChatMembers -> PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, (rule.chatIds ?: longArrayOf()).toList())
        is TdApi.UserPrivacySettingRuleRestrictAll -> PrivacyRule(PrivacyRuleKind.RESTRICT_ALL)
        is TdApi.UserPrivacySettingRuleRestrictContacts -> PrivacyRule(PrivacyRuleKind.RESTRICT_CONTACTS)
        is TdApi.UserPrivacySettingRuleRestrictBots -> PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS)
        is TdApi.UserPrivacySettingRuleRestrictUsers -> PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, (rule.userIds ?: longArrayOf()).toList())
        is TdApi.UserPrivacySettingRuleRestrictChatMembers -> PrivacyRule(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, (rule.chatIds ?: longArrayOf()).toList())
        else -> null
    } }
    val base = mapped.firstNotNullOfOrNull { when (it.kind) {
        PrivacyRuleKind.ALLOW_ALL -> PrivacyBase.EVERYBODY
        PrivacyRuleKind.ALLOW_CONTACTS -> PrivacyBase.CONTACTS
        PrivacyRuleKind.RESTRICT_ALL -> PrivacyBase.NOBODY
        else -> null
    } } ?: PrivacyBase.NOBODY // TDLib removes terminal RestrictAll; an unmatched sender is denied.
    val broad = setOf(PrivacyRuleKind.ALLOW_ALL, PrivacyRuleKind.ALLOW_CONTACTS, PrivacyRuleKind.RESTRICT_ALL)
    val firstBase = mapped.indexOfFirst { it.kind in broad }.let { if (it < 0) mapped.size else it }
    val suffix = mapped.drop(firstBase).map { it.kind }
    val canonical = suffix in listOf(emptyList(), listOf(PrivacyRuleKind.ALLOW_ALL),
        listOf(PrivacyRuleKind.ALLOW_CONTACTS), listOf(PrivacyRuleKind.RESTRICT_ALL),
        listOf(PrivacyRuleKind.ALLOW_CONTACTS, PrivacyRuleKind.RESTRICT_ALL))
    val prefixKinds = mapped.take(firstBase).map { it.kind }
    // Native rules use first-match semantics. A baseline preceding another rule cannot be
    // moved past it just to fit a three-choice editor; expose that order without permitting edits.
    // Split exception kinds cannot be flattened into a single editable list: a special rule
    // between two segments can deny an identity that would become allowed if moved to the first.
    return PrivacyRules(base, mapped, mapped.size == native.size && canonical && prefixKinds.distinct().size == prefixKinds.size)
}

internal fun nativePrivacyRule(value: PrivacyRule): TdApi.UserPrivacySettingRule = when (value.kind) {
    PrivacyRuleKind.ALLOW_ALL -> TdApi.UserPrivacySettingRuleAllowAll()
    PrivacyRuleKind.ALLOW_CONTACTS -> TdApi.UserPrivacySettingRuleAllowContacts()
    PrivacyRuleKind.ALLOW_BOTS -> TdApi.UserPrivacySettingRuleAllowBots()
    PrivacyRuleKind.ALLOW_PREMIUM -> TdApi.UserPrivacySettingRuleAllowPremiumUsers()
    PrivacyRuleKind.ALLOW_USERS -> TdApi.UserPrivacySettingRuleAllowUsers(value.ids.toLongArray())
    PrivacyRuleKind.ALLOW_CHAT_MEMBERS -> TdApi.UserPrivacySettingRuleAllowChatMembers(value.ids.toLongArray())
    PrivacyRuleKind.RESTRICT_ALL -> TdApi.UserPrivacySettingRuleRestrictAll()
    PrivacyRuleKind.RESTRICT_CONTACTS -> TdApi.UserPrivacySettingRuleRestrictContacts()
    PrivacyRuleKind.RESTRICT_BOTS -> TdApi.UserPrivacySettingRuleRestrictBots()
    PrivacyRuleKind.RESTRICT_USERS -> TdApi.UserPrivacySettingRuleRestrictUsers(value.ids.toLongArray())
    PrivacyRuleKind.RESTRICT_CHAT_MEMBERS -> TdApi.UserPrivacySettingRuleRestrictChatMembers(value.ids.toLongArray())
}

internal fun PrivacyKey.native(): TdApi.UserPrivacySetting = when (this) {
    PrivacyKey.PHONE_NUMBER -> TdApi.UserPrivacySettingShowPhoneNumber()
    PrivacyKey.FIND_BY_PHONE_NUMBER -> TdApi.UserPrivacySettingAllowFindingByPhoneNumber()
    PrivacyKey.LAST_SEEN -> TdApi.UserPrivacySettingShowStatus()
    PrivacyKey.PROFILE_PHOTO -> TdApi.UserPrivacySettingShowProfilePhoto()
    PrivacyKey.FORWARDED_LINK -> TdApi.UserPrivacySettingShowLinkInForwardedMessages()
    PrivacyKey.CALLS -> TdApi.UserPrivacySettingAllowCalls()
    PrivacyKey.PEER_TO_PEER_CALLS -> TdApi.UserPrivacySettingAllowPeerToPeerCalls()
    PrivacyKey.VOICE_VIDEO_MESSAGES -> TdApi.UserPrivacySettingAllowPrivateVoiceAndVideoNoteMessages()
    PrivacyKey.BIO -> TdApi.UserPrivacySettingShowBio()
    PrivacyKey.BIRTHDATE -> TdApi.UserPrivacySettingShowBirthdate()
    PrivacyKey.CHAT_INVITES -> TdApi.UserPrivacySettingAllowChatInvites()
}
