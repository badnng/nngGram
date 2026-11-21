package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.RichMessageText
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsChatTranslation(preferences: ChatTranslationPreferences, onChange: (ChatTranslationPreferences) -> Unit) {
    val active = LocalSettingsPageActive.current
    var server by remember(preferences.server) { mutableStateOf(TextFieldValue(preferences.server)) }
    var editingLanguage by remember { mutableStateOf<String?>(null) }
    val normalized = remember(server.text) { normalizeChatTranslationServer(server.text) }
    val offline = preferences.provider == ChatTranslationProvider.OFFLINE
    SettingsGroup("群组翻译") {
        SettingsRow("离线英译中", if (offline) "已选中" else null,
            "首次遇到英文时下载约 71 MB。消息在本机翻译。", enabled = active) {
            editingLanguage = null
            onChange(preferences.copy(provider = ChatTranslationProvider.OFFLINE))
        }
        SettingsRow("自定义在线接口", if (offline) null else "已选中",
            "将可见消息发送到你填写的地址。兼容 LibreTranslate。", enabled = active) {
            editingLanguage = null
            if (offline) onChange(preferences.copy(provider = ChatTranslationProvider.LIBRE_TRANSLATE, automatic = false))
        }
        if (!offline) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(server, { server = it }, label = "翻译接口地址", modifier = Modifier.fillMaxWidth(),
                    enabled = active, singleLine = true)
                if (normalized == null) SettingsNote("请输入 HTTPS 地址。本机实例也支持 HTTP。", error = true)
                else if (normalized != preferences.server) ChatButton("保存翻译地址", enabled = active, primary = true) {
                    onChange(preferences.copy(server = normalized, automatic = false))
                }
            }
        }
        SettingsToggle("进入群组时自动翻译", preferences.automatic, enabled = active && preferences.identity.isNotEmpty()) {
            onChange(preferences.copy(automatic = it))
        }
        listOf(true, false).forEach { isSource ->
            val label = if (isSource) "原文语言" else "目标语言"
            val selected = if (isSource) preferences.effectiveSource else preferences.effectiveTarget
            val selectable = !offline || isSource
            SettingsRow(label, chatTranslationLanguages[selected] ?: selected, enabled = active,
                onClick = if (selectable) ({ editingLanguage = label.takeUnless { editingLanguage == label } }) else null)
            if (selectable && editingLanguage == label) {
                Column(Modifier.padding(start = 16.dp)) {
                    if (!offline) {
                        var custom by remember(selected) { mutableStateOf(selected.takeUnless(chatTranslationLanguages::containsKey).orEmpty()) }
                        val language = normalizeTranslationLanguage(custom, allowAuto = isSource)
                        TextField(custom, { custom = it }, label = "自定义语言代码", modifier = Modifier.fillMaxWidth(),
                            enabled = active, singleLine = true)
                        if (custom.isNotBlank() && language == null) SettingsNote("请输入服务支持的语言代码，例如 zh-Hans。", error = true)
                        if (language != null && language != selected) ChatButton("使用此语言", enabled = active) {
                            editingLanguage = null
                            onChange(if (isSource) preferences.copy(source = language) else preferences.copy(target = language))
                        }
                    }
                    chatTranslationLanguages.forEach { (code, name) ->
                        val available = if (offline) code == "auto" || code == "en" else isSource || code != "auto"
                        if (available) SettingsRow(name, if (code == selected) "已选中" else null, enabled = active) {
                            editingLanguage = null
                            onChange(when {
                                offline -> preferences.copy(offlineSource = code)
                                isSource -> preferences.copy(source = code)
                                else -> preferences.copy(target = code)
                            })
                        }
                    }
                }
            }
        }
        SettingsToggle("同时显示原文", preferences.showOriginal, enabled = active) {
            onChange(preferences.copy(showOriginal = it))
        }
        if (preferences.effectiveSource == preferences.effectiveTarget) SettingsNote("请选择不同的原文语言和目标语言。")
    }
}

@Composable
internal fun ConversationTranslationBar(
    enabled: Boolean,
    interactive: Boolean,
    settings: ChatTranslationPreferences,
    state: ConversationTranslation,
    unavailable: Boolean,
    onToggle: () -> Unit,
) {
    if (!enabled) {
        ChatButton("翻译", modifier = Modifier.padding(top = 8.dp), enabled = interactive, onClick = onToggle)
        return
    }
    val failure = state.failure
    val status = when {
        failure != null -> failure.label
        settings.effectiveSource == settings.effectiveTarget -> "原文与目标语言相同"
        state.pending && settings.provider == ChatTranslationProvider.OFFLINE -> "正在准备模型或翻译…"
        state.pending -> "正在翻译…"
        unavailable -> "部分消息无法翻译"
        settings.provider == ChatTranslationProvider.OFFLINE -> "离线英译中"
        else -> "译为${chatTranslationLanguages[settings.effectiveTarget] ?: settings.effectiveTarget}"
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).chatSurface().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatButton("关闭翻译", enabled = interactive, onClick = onToggle)
        Text(status, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (failure != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (failure != null) ChatButton("重试", enabled = interactive, onClick = state::retry)
    }
}

@Composable
internal fun TranslatedMessageBody(
    translation: String?,
    showOriginal: Boolean,
    onTextLayout: (TextLayoutResult) -> Unit,
    selectionState: SelectionState,
    allowCopy: Boolean,
    canCopyNow: () -> Boolean,
    onTranslationBounds: (Rect) -> Unit,
    original: @Composable ((TextLayoutResult) -> Unit) -> Unit,
) {
    if (translation == null) {
        original(onTextLayout)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showOriginal) original { }
            Column(Modifier.onGloballyPositioned { onTranslationBounds(it.boundsInWindow(clipBounds = false)) },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("译文", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 11.sp)
                RichMessageBody(remember(translation) { RichMessageText(translation) },
                    selectionState = selectionState, allowCopy = allowCopy, canCopyNow = canCopyNow)
            }
        }
    }
}
