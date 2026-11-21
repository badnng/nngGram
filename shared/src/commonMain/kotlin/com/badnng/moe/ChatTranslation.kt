package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.MessageDelivery
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.MessageKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ChatTranslationProvider { OFFLINE, LIBRE_TRANSLATE }

internal data class ChatTranslationPreferences(
    val server: String = "",
    val source: String = "auto",
    val target: String = "zh",
    val showOriginal: Boolean = false,
    val provider: ChatTranslationProvider = ChatTranslationProvider.OFFLINE,
    val automatic: Boolean = false,
    val offlineSource: String = "auto",
) {
    val identity: String get() = if (provider == ChatTranslationProvider.OFFLINE) "offline" else server
    val effectiveSource: String get() = if (provider == ChatTranslationProvider.OFFLINE) offlineSource else source
    val effectiveTarget: String get() = if (provider == ChatTranslationProvider.OFFLINE) "zh" else target

    fun enabledForGroup(groupOverride: Boolean?): Boolean = identity.isNotEmpty() && (groupOverride ?: automatic)
}

internal val chatTranslationLanguages = linkedMapOf(
    "auto" to "自动识别", "zh" to "简体中文", "zt" to "繁体中文", "en" to "英语",
    "ja" to "日语", "ko" to "韩语", "es" to "西班牙语", "fr" to "法语", "de" to "德语",
    "ru" to "俄语", "pt" to "葡萄牙语", "it" to "意大利语", "ar" to "阿拉伯语",
    "hi" to "印地语", "id" to "印度尼西亚语", "vi" to "越南语", "tr" to "土耳其语",
    "uk" to "乌克兰语", "pl" to "波兰语", "nl" to "荷兰语",
)

private val translationLanguagePattern = Regex("[a-zA-Z]{2,8}(?:-[a-zA-Z0-9]{1,8})*")

internal fun normalizeTranslationLanguage(input: String, allowAuto: Boolean): String? {
    val code = input.trim()
    if (code.equals("auto", ignoreCase = true)) return if (allowAuto) "auto" else null
    return code.takeIf { it.length <= 35 && translationLanguagePattern.matches(it) }
}

internal expect fun normalizeChatTranslationServer(input: String): String?

internal sealed interface ChatTranslationResult {
    data class Translated(val text: String) : ChatTranslationResult {
        override fun toString() = "Translated(<redacted>)"
    }
    data object Original : ChatTranslationResult
    data object Unsupported : ChatTranslationResult
    enum class Failure(val label: String) : ChatTranslationResult {
        CONNECTION("翻译服务连接失败"), RATE_LIMIT("翻译服务已限流"), ACCESS("翻译服务拒绝访问"),
        RESPONSE("翻译服务返回异常"),
        MODEL_DOWNLOAD("离线模型下载失败"),
        OFFLINE_ENGINE("离线翻译程序不可用"),
    }
}

internal expect suspend fun translateChatText(
    server: String, source: String, target: String, text: String,
): ChatTranslationResult

internal expect suspend fun translateOfflineChatText(source: String, text: String): ChatTranslationResult

internal fun ChatItem.supportsTranslation(): Boolean =
    !isChannel && !hasProtectedContent && (basicGroupId != null || supergroupId != null)

private val translatableKinds = setOf(MessageKind.TEXT, MessageKind.PHOTO, MessageKind.VIDEO,
    MessageKind.ANIMATION, MessageKind.FILE, MessageKind.VOICE)

internal data class TranslationMessage(val id: Long, val text: String) {
    override fun toString() = "TranslationMessage(<redacted>)"
}

internal fun ChatMessage.translationMessage(): TranslationMessage? =
    if (id > 0 && delivery == MessageDelivery.SENT && canBeSaved && kind in translatableKinds &&
        text.length <= 4096 && text.any(Char::isLetter) &&
        entities.none { it.kind == MessageEntityKind.SPOILER }) TranslationMessage(id, text) else null

internal class ConversationTranslation {
    val requestLock = Mutex()
    private val results = mutableStateMapOf<TranslationMessage, ChatTranslationResult>()
    private val insertionOrder = ArrayDeque<TranslationMessage>()
    var pending by mutableStateOf(false)
        internal set
    var failure by mutableStateOf<ChatTranslationResult.Failure?>(null)
        internal set
    var retryRevision by mutableIntStateOf(0)
        private set

    fun result(message: TranslationMessage?) = message?.let(results::get)

    fun record(message: TranslationMessage, result: ChatTranslationResult) {
        results[message] = result
        insertionOrder.addLast(message)
        if (insertionOrder.size > 256) results.remove(insertionOrder.removeFirst())
    }

    fun retry() {
        failure = null
        retryRevision++
    }
}

@Composable
internal fun rememberConversationTranslation(
    settings: ChatTranslationPreferences,
    enabled: Boolean,
    canRequest: Boolean,
    visibleMessages: () -> List<TranslationMessage>,
): ConversationTranslation {
    val state = remember(settings.identity, settings.effectiveSource, settings.effectiveTarget) { ConversationTranslation() }
    val currentMessages by rememberUpdatedState(visibleMessages)
    LaunchedEffect(state, enabled, canRequest, state.retryRevision) {
        if (!enabled || !canRequest || settings.effectiveSource == settings.effectiveTarget || state.failure != null) return@LaunchedEffect
        while (true) {
            val next = snapshotFlow { currentMessages().firstOrNull { state.result(it) == null } }
                .filterNotNull().first()
            // A brief dwell avoids sending messages that pass through during a fast scroll.
            delay(180)
            val result = state.requestLock.withLock {
                if (next !in currentMessages()) return@withLock null
                state.pending = true
                try {
                    if (settings.provider == ChatTranslationProvider.OFFLINE) translateOfflineChatText(settings.offlineSource, next.text)
                    else translateChatText(settings.server, settings.source, settings.target, next.text)
                } finally {
                    state.pending = false
                }
            } ?: continue
            if (result is ChatTranslationResult.Failure) {
                state.failure = result
                break
            }
            state.record(next, result)
        }
    }
    return state
}
