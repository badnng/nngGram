package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsSupportActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Existing pinned browser destinations; volunteer support uses a separate native confirmation path. */
internal const val SETTINGS_FAQ_URL = "https://telegram.org/faq#general-questions"
internal const val SETTINGS_FEATURES_URL = "https://t.me/TelegramTips"

@Composable
internal fun SettingsHelpPage(
    preferences: SettingsPreferences,
    openLink: (String) -> Boolean = ::openExternalChatLink,
    chatToken: ChatToken = ChatToken(0, 0, null),
    support: SettingsSupportActions = SettingsSupportActions(),
    onSupportOpened: (ChatToken) -> Unit = {},
) {
    val active = LocalSettingsPageActive.current
    val pageLease = remember(active, chatToken.generation) { SettingsHelpPageLease(active) }
    val scope = rememberCoroutineScope()
    val currentSupport = rememberUpdatedState(support)
    val currentOnSupportOpened = rememberUpdatedState(onSupportOpened)
    val currentGeneration = rememberUpdatedState(chatToken.generation)
    var request by remember(pageLease) { mutableStateOf<SettingsHelpRequest?>(null) }
    var confirmationToken by remember(pageLease) { mutableStateOf<ChatToken?>(null) }
    var failure by remember(pageLease) { mutableStateOf<SettingsError?>(null) }

    DisposableEffect(pageLease) {
        onDispose { pageLease.close() }
    }

    var failed by remember { mutableStateOf(false) }
    SettingsGroup("Telegram 帮助") {
        SettingsRow("Telegram 常见问题", summary = SETTINGS_FAQ_URL, enabled = active) {
            failed = !openLink(SETTINGS_FAQ_URL)
        }
        SettingsRow("Telegram 功能介绍", summary = SETTINGS_FEATURES_URL, enabled = active) {
            failed = !openLink(SETTINGS_FEATURES_URL)
        }
        SettingsRow("向志愿者提问", value = if (request != null) "正在打开…" else null,
            summary = "将打开志愿者私聊。回复可能需要时间；不会预填或发送消息。",
            enabled = active && request == null) {
            if (active && request == null) {
                failure = null
                confirmationToken = chatToken
            }
        }
        if (request != null) SettingsNote("正在打开与志愿者的私聊……")
        failure?.let { SettingsNote(settingsErrorText(it), error = true) }
    }
    if (failed) SettingsNote("未能打开浏览器，请重试。", error = true)
    SettingsGroup("键盘快捷键") {
        SettingsRow("发送消息", if (preferences.sendKey == SettingsSendKey.ENTER) "Enter" else "Ctrl+Enter")
        SettingsRow("换行", if (preferences.sendKey == SettingsSendKey.ENTER) "Shift+Enter" else "Enter 或 Shift+Enter")
        SettingsRow("发送消息（两种模式均可）", "Ctrl+Shift+Enter")
        SettingsRow("选择附件", "Ctrl+O", "在聊天输入框内使用。")
        SettingsRow("编辑上一条可编辑消息", "↑", "聊天输入框为空时使用。")
        SettingsRow("取消当前回复或编辑", "Esc", "在聊天输入框内使用。")
        SettingsRow("返回设置分类或聊天", "Esc", "设置页内使用。")
    }

    val sourceToken = confirmationToken
    SecurityConfirmation(show = active && sourceToken != null, title = "向 Telegram 志愿者提问？", maxWidth = 440.dp,
        onDismissRequest = { confirmationToken = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNote("这会打开 Telegram 志愿者的私聊。志愿者不提供实时客服，回复可能需要时间。")
            SettingsNote("不会自动填写或发送任何消息。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { confirmationToken = null })
                ChatButton("打开支持聊天", Modifier.weight(1f), enabled = active && request == null, primary = true) {
                    val token = sourceToken ?: return@ChatButton
                    confirmationToken = null
                    failure = null
                    val operation = SettingsHelpRequest(pageLease, token)
                    request = operation
                    scope.launch {
                        val admission = CompletableDeferred<Boolean>()
                        var admissionError = SettingsError.NOT_READY
                        val accepted = try {
                            currentSupport.value.open(token, operation::active) { result ->
                                scope.launch {
                                    if (!admission.await() || request !== operation || !operation.active() ||
                                        currentGeneration.value != token.generation || !operation.claimTerminal()) return@launch
                                    request = null
                                    operation.close()
                                    val destination = result.value
                                    if (result.error != null || destination == null || destination.chatId == null ||
                                        destination.generation != currentGeneration.value ||
                                        destination.generation != operation.sourceToken.generation) {
                                        failure = result.error ?: SettingsError.REQUEST_FAILED
                                    } else {
                                        failure = null
                                        currentOnSupportOpened.value(destination)
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            admissionError = SettingsError.REQUEST_FAILED
                            false
                        }
                        admission.complete(accepted)
                        if (!accepted && request === operation && operation.active() && currentGeneration.value == token.generation) {
                            request = null
                            operation.close()
                            failure = admissionError
                        }
                    }
                }
            }
        }
    }
}

/** StateFlow supplies a cross-thread atomic lease without exposing platform atomics to commonMain. */
private class SettingsHelpPageLease(initiallyActive: Boolean) {
    private val alive = MutableStateFlow(initiallyActive)
    fun active() = alive.value
    fun close() { alive.compareAndSet(true, false) }
}

private class SettingsHelpRequest(
    private val page: SettingsHelpPageLease,
    val sourceToken: ChatToken,
) {
    private val alive = MutableStateFlow(true)
    private val terminal = MutableStateFlow(false)
    fun active() = page.active() && alive.value
    fun close() { alive.compareAndSet(true, false) }
    fun claimTerminal() = active() && terminal.compareAndSet(false, true)
}
