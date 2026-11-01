package com.badnng.moe.settings

enum class PrivacyExceptionTarget { ALLOW, DENY }
data class PrivacyPeerId(val id: Long, val isChat: Boolean) {
    override fun toString() = "PrivacyPeerId(<redacted>)"
}
data class SettingsPrivacyPeer(val identity: PrivacyPeerId, val title: String, val isGroup: Boolean = false) {
    override fun toString() = "SettingsPrivacyPeer(<redacted>)"
}
data class PrivacyEditorToken(val generation: Long, val owner: Long, val key: PrivacyKey)
enum class PrivacyPeerSource { CONTACTS, CHATS, SERVER, SELECTED }
data class PrivacyPeerQuery(
    val target: PrivacyExceptionTarget, val parentRevision: Long, val queryRevision: Long,
    val source: PrivacyPeerSource, val query: String = "", val limit: Int = 32,
    val selected: List<PrivacyPeerId> = emptyList(),
) { override fun toString() = "PrivacyPeerQuery($source, <redacted>)" }
data class SettingsPrivacyPeers(val entries: List<SettingsPrivacyPeer>, val mayHaveMore: Boolean) {
    override fun toString() = "SettingsPrivacyPeers(<redacted>)"
}

/** A child selector only replaces a list in this parent draft. Native writes belong to parent Save. */
data class PrivacyDraft(
    val source: PrivacyRules, val base: PrivacyBase,
    val allowUsers: List<Long>, val allowChats: List<Long>, val denyUsers: List<Long>, val denyChats: List<Long>,
    val allowPremium: Boolean, val revision: Long = 0,
) {
    fun selected(target: PrivacyExceptionTarget): List<PrivacyPeerId> = when (target) {
        PrivacyExceptionTarget.ALLOW -> allowUsers.map { PrivacyPeerId(it, false) } + allowChats.map { PrivacyPeerId(it, true) }
        PrivacyExceptionTarget.DENY -> denyUsers.map { PrivacyPeerId(it, false) } + denyChats.map { PrivacyPeerId(it, true) }
    }

    fun withSelection(target: PrivacyExceptionTarget, identities: List<PrivacyPeerId>, premium: Boolean = allowPremium): PrivacyDraft {
        val users = identities.filterNot { it.isChat }.map { it.id }.distinct()
        val chats = identities.filter { it.isChat }.map { it.id }.distinct()
        return when (target) {
            PrivacyExceptionTarget.ALLOW -> copy(allowUsers = users, allowChats = chats,
                denyUsers = denyUsers - users.toSet(), denyChats = denyChats - chats.toSet(),
                allowPremium = premium, revision = revision + 1)
            PrivacyExceptionTarget.DENY -> copy(denyUsers = users, denyChats = chats,
                allowUsers = allowUsers - users.toSet(), allowChats = allowChats - chats.toSet(), revision = revision + 1)
        }
    }

    /** Retain untouched native rules verbatim, including first-match special-rule ordering. */
    fun replacement(key: PrivacyKey): PrivacyRules? {
        // A split kind may straddle another first-match rule. A list editor cannot merge those
        // segments without moving an existing identity across that rule, even if source was forged editable.
        val prefixKinds = source.rules.takeWhile { it.kind !in BASE_KINDS }.map { it.kind }
        if (prefixKinds.distinct().size != prefixKinds.size) return null
        if (!source.editable || source.base == null || allowUsers.any { it <= 0 } || denyUsers.any { it <= 0 } ||
            allowChats.any { it == 0L } || denyChats.any { it == 0L } ||
            allowUsers.size + denyUsers.size > 1000 || allowChats.size + denyChats.size > 20 ||
            allowUsers.distinct().size != allowUsers.size || denyUsers.distinct().size != denyUsers.size ||
            allowChats.distinct().size != allowChats.size || denyChats.distinct().size != denyChats.size ||
            allowUsers.any { it in denyUsers } || allowChats.any { it in denyChats } ||
            allowPremium && key != PrivacyKey.CHAT_INVITES) return null
        if (key == PrivacyKey.FIND_BY_PHONE_NUMBER && (base == PrivacyBase.NOBODY ||
                allowUsers.isNotEmpty() || denyUsers.isNotEmpty() || allowChats.isNotEmpty() || denyChats.isNotEmpty() ||
                source.rules.any { it.kind !in BASE_KINDS })) return null
        val original = from(source) ?: return null
        val changed = linkedMapOf<PrivacyRuleKind, List<Long>>()
        fun change(kind: PrivacyRuleKind, before: List<Long>, after: List<Long>) { if (before != after) changed[kind] = after }
        change(PrivacyRuleKind.ALLOW_USERS, original.allowUsers, allowUsers)
        change(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, original.allowChats, allowChats)
        change(PrivacyRuleKind.RESTRICT_USERS, original.denyUsers, denyUsers)
        change(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, original.denyChats, denyChats)
        val premiumChanged = allowPremium != original.allowPremium
        if (changed.isEmpty() && !premiumChanged && base == source.base) return source
        val prefix = mutableListOf<PrivacyRule>()
        val inserted = mutableSetOf<PrivacyRuleKind>()
        for (rule in source.rules.takeWhile { it.kind !in BASE_KINDS }) {
            when {
                rule.kind in changed -> if (inserted.add(rule.kind) && changed.getValue(rule.kind).isNotEmpty())
                    prefix += rule.copy(ids = changed.getValue(rule.kind))
                rule.kind == PrivacyRuleKind.ALLOW_PREMIUM && premiumChanged -> if (allowPremium && inserted.add(rule.kind)) prefix += rule
                else -> prefix += rule
            }
        }
        for ((kind, ids) in changed) if (kind !in inserted && ids.isNotEmpty()) prefix += PrivacyRule(kind, ids)
        if (premiumChanged && allowPremium && source.rules.none { it.kind == PrivacyRuleKind.ALLOW_PREMIUM })
            prefix += PrivacyRule(PrivacyRuleKind.ALLOW_PREMIUM)
        val suffix = if (base == source.base) source.rules.dropWhile { it.kind !in BASE_KINDS } else when (base) {
            PrivacyBase.EVERYBODY -> listOf(PrivacyRule(PrivacyRuleKind.ALLOW_ALL))
            PrivacyBase.CONTACTS -> listOf(PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS), PrivacyRule(PrivacyRuleKind.RESTRICT_ALL))
            PrivacyBase.NOBODY -> listOf(PrivacyRule(PrivacyRuleKind.RESTRICT_ALL))
        }
        return PrivacyRules(base, prefix + suffix)
    }

    override fun toString() = "PrivacyDraft(<redacted>)"
    companion object {
        private val BASE_KINDS = setOf(PrivacyRuleKind.ALLOW_ALL, PrivacyRuleKind.ALLOW_CONTACTS, PrivacyRuleKind.RESTRICT_ALL)
        fun from(source: PrivacyRules): PrivacyDraft? {
            val base = source.base ?: return null
            fun ids(kind: PrivacyRuleKind) = source.rules.filter { it.kind == kind }.flatMap { it.ids }.distinct()
            return PrivacyDraft(source, base, ids(PrivacyRuleKind.ALLOW_USERS), ids(PrivacyRuleKind.ALLOW_CHAT_MEMBERS),
                ids(PrivacyRuleKind.RESTRICT_USERS), ids(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS),
                source.rules.any { it.kind == PrivacyRuleKind.ALLOW_PREMIUM })
        }
    }
}
