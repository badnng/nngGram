package com.badnng.moe.chat

/** Desktop a7f4536 data_auto_download; these are local account rules, not network presets. */
enum class AutoMediaSource(val label: String) { USER("私聊"), GROUP("群组"), CHANNEL("频道") }
enum class AutoMediaType { PHOTO, VIDEO, VOICE, VIDEO_NOTE, MUSIC, GIF, FILE }
enum class AutoMediaOverride { ALLOW, DENY }

data class AutoMediaSourceRules(val limits: Map<AutoMediaType, Long>) {
    fun limit(type: AutoMediaType): Long = limits[type] ?: 0L
    val enabled: Boolean get() = AutoMediaType.entries.any { it !in STREAMED && limit(it) > 0 }
    fun withLimit(type: AutoMediaType, bytes: Long) = copy(limits = limits + (type to bytes))
    companion object {
        val STREAMED = setOf(AutoMediaType.VOICE, AutoMediaType.MUSIC)
        fun defaults(source: AutoMediaSource) = AutoMediaSourceRules(AutoMediaType.entries.associateWith {
            when (it) {
                AutoMediaType.VIDEO, AutoMediaType.GIF, AutoMediaType.VIDEO_NOTE -> 50L * MIB
                AutoMediaType.FILE, AutoMediaType.MUSIC -> if (source == AutoMediaSource.CHANNEL) 0L else 8L * MIB
                else -> 8L * MIB
            }
        })
        fun disabled() = AutoMediaSourceRules(AutoMediaType.entries.associateWith { 0L })
    }
}

data class AutoMediaPolicy(
    val sources: Map<AutoMediaSource, AutoMediaSourceRules> = AutoMediaSource.entries.associateWith(AutoMediaSourceRules::defaults),
    /** Actual TDLib chat IDs are account scoped and preserve user/group/channel identity. */
    val peers: Map<Long, AutoMediaOverride> = emptyMap(),
) {
    fun rules(source: AutoMediaSource) = sources[source] ?: AutoMediaSourceRules.defaults(source)
    fun withSource(source: AutoMediaSource, rules: AutoMediaSourceRules) = copy(sources = sources + (source to rules))
    fun sourceEnabled(source: AutoMediaSource, enabled: Boolean) = withSource(source,
        if (enabled) AutoMediaSourceRules.defaults(source) else AutoMediaSourceRules.disabled())
    fun allows(source: AutoMediaSource, chatId: Long, type: AutoMediaType, size: Long): Boolean {
        if (size < 0 || type in AutoMediaSourceRules.STREAMED || peers[chatId] == AutoMediaOverride.DENY) return false
        val configured = rules(source).limit(type)
        val limit = if (peers[chatId] == AutoMediaOverride.ALLOW && configured == 0L)
            AutoMediaSourceRules.defaults(source).limit(type) else configured
        // Native size 0 is unknown and follows the official numeric predicate.
        return limit > 0 && size <= limit
    }
    fun valid() = sources.keys == AutoMediaSource.entries.toSet() && sources.values.all { rules ->
        rules.limits.keys == AutoMediaType.entries.toSet() && rules.limits.values.all { it in 0..MAX_AUTO_MEDIA_BYTES }
    } && peers.size <= 10_000 && peers.keys.none { it == 0L }
    override fun toString() = "AutoMediaPolicy(<redacted>)"
}

const val MIB = 1_048_576L
const val MAX_AUTO_MEDIA_BYTES = 4_000L * MIB

/** The shared official 100-position discrete size scale; opening a draft never rounds its value. */
fun autoMediaSizeByIndex(index: Int): Long {
    require(index in 0..99)
    val i = index + 1
    val mb = when {
        i <= 10 -> i
        i <= 30 -> 10 + (i - 10) * 2
        i <= 40 -> 50 + (i - 30) * 5
        i <= 60 -> 100 + (i - 40) * 10
        i <= 70 -> 300 + (i - 60) * 20
        i <= 80 -> 500 + (i - 70) * 50
        i <= 90 -> 1000 + (i - 80) * 100
        else -> 2000 + (i - 90) * 200
    }
    return mb * MIB
}

fun ChatItem.autoMediaSource(): AutoMediaSource? = when {
    privateUserId != null -> AutoMediaSource.USER
    basicGroupId != null || supergroupId != null && !isChannel -> AutoMediaSource.GROUP
    supergroupId != null && isChannel -> AutoMediaSource.CHANNEL
    else -> null
}

data class AutoMediaSettings(
    val accountUserId: Long? = null,
    val revision: Long = 0,
    val policy: AutoMediaPolicy = AutoMediaPolicy(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: Boolean = false,
    val copyError: Boolean = false,
    val copyNotice: AutoMediaCopyNotice? = null,
)

enum class AutoMediaCopyNotice { FAILED, RECORD_PENDING, USER_CHANGED_TARGET, RECOVERY_AMBIGUOUS }

/** A page-owned lease never grants access to a different message or file. */
data class MediaDownloadOwner(
    val token: ChatToken,
    val messageId: Long,
    val purpose: MediaDownloadPurpose,
    /** Separates a disposed reader from a new reader for the same message. */
    val leaseId: Long = 0,
)
enum class MediaDownloadPurpose { AUTO, STREAM, PROBE, ARTWORK, USER, SOUND, PROFILE_EMOJI, PROFILE_PHOTO_HISTORY }

/** Cache/stream permission never implies permission to create a user-visible saved file. */
data class AutoMediaDecision(
    val autoCacheAllowed: Boolean = false,
    val autoStreamAllowed: Boolean = false,
    val externalCopyAllowed: Boolean = false,
    val cacheReason: AutoMediaDecisionReason = AutoMediaDecisionReason.UNSUPPORTED,
    val streamReason: AutoMediaDecisionReason = AutoMediaDecisionReason.UNSUPPORTED,
    val copyReason: AutoMediaDecisionReason = AutoMediaDecisionReason.UNSUPPORTED,
)

enum class AutoMediaDecisionReason {
    ALLOWED, UNSUPPORTED, INVALID_CONTEXT, PEER_DENIED, SIZE_OR_DISABLED,
    EXECUTABLE, ASK_LOCATION, DESTINATION_UNKNOWN, SAVE_FORBIDDEN, NOT_COMPLETED, NATIVE_UNAVAILABLE,
}

fun ChatSnapshot.autoPlayAllowed(message: ChatMessage): Boolean {
    if (!autoMediaSettings.loaded) return false
    val chat = selected?.takeIf { it.id == message.chatId } ?: return false
    val source = chat.autoMediaSource() ?: return false
    val media = message.media ?: return false
    val type = when (media.format) { MediaFormat.VIDEO -> AutoMediaType.VIDEO; MediaFormat.ANIMATION -> AutoMediaType.GIF; else -> return false }
    val file = mediaFiles[media.file.id] ?: media.file
    return autoMediaSettings.policy.allows(source, chat.id, type, file.size)
}
