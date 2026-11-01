package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatReportSpamState
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class SpamConfirmation(
    val token: ChatToken,
    val chatId: Long,
    val channel: Boolean,
    val historyRemoval: Boolean,
)
private enum class SpamDispatchKind { REPORT_AND_LEAVE, DISMISS }
private data class SpamDispatch(val revision: Long, val kind: SpamDispatchKind)
private const val REPORT_SPAM_BAR_TINT_ALPHA = .94f

/** A naturally measured header child. Only the actor's current native eligibility exposes the action. */
@Composable
internal fun ChatReportSpamPanel(
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    pageInteractive: Boolean,
    modifier: Modifier = Modifier,
) {
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestInteractive by rememberUpdatedState(pageInteractive)
    val latestFeatures by rememberUpdatedState(features)
    var confirmation by remember { mutableStateOf<SpamConfirmation?>(null) }
    var requested by remember { mutableStateOf(false) }
    var dispatch by remember(snapshot.token) { mutableStateOf<SpamDispatch?>(null) }
    var dispatchFailed by remember(snapshot.token) { mutableStateOf(false) }
    val state = currentSpamState(snapshot)
    val localPending = dispatch != null
    val busy = state?.pending == true || localPending
    LaunchedEffect(snapshot.token, state?.revision, state?.pending) {
        if (dispatch != null && state?.pending != true && state?.revision != dispatch?.revision) dispatch = null
    }
    val target = confirmation
    val currentConfirmation = target?.takeIf {
        pageInteractive && snapshot.token == it.token && snapshot.selected?.id == it.chatId &&
            state?.chatId == it.chatId && state.visible
    }
    val dialogShown = requested && currentConfirmation != null
    LaunchedEffect(dialogShown) { if (requested && currentConfirmation == null) requested = false }
    DisposableEffect(snapshot.token) { onDispose { requested = false } }

    fun currentTarget(expected: SpamConfirmation? = null): ChatReportSpamState? {
        if (!latestInteractive) return null
        val current = latestSnapshot
        if (expected != null && (current.token != expected.token || current.selected?.id != expected.chatId)) return null
        return currentSpamState(current)?.takeIf { it.visible && !it.pending && dispatch == null }
    }
    fun submit(kind: SpamDispatchKind, expected: SpamConfirmation? = null) {
        val current = currentTarget(expected) ?: return
        if (kind == SpamDispatchKind.REPORT_AND_LEAVE && !current.available && !current.reported) return
        if (kind == SpamDispatchKind.DISMISS && current.reported) return
        val token = latestSnapshot.token
        dispatchFailed = false
        dispatch = SpamDispatch(current.revision, kind)
        try {
            if (kind == SpamDispatchKind.DISMISS) latestFeatures.dismissSpamBar(token)
            else latestFeatures.reportSpamAndLeave(token)
        } catch (_: Exception) {
            dispatch = null
            dispatchFailed = true
        }
    }

    val colors = MiuixTheme.colorScheme
    val tint = if (colors.surface.luminance() < .5f) colors.surfaceContainer else Color.White
    val danger = readableSpamForeground(colors.error, colors.onSurface, colors.surfaceContainer, tint)
    if (state?.visible == true) {
        Column(modifier.fillMaxWidth().chatGlassSurface(15.dp, shadow = false, tintAlpha = REPORT_SPAM_BAR_TINT_ALPHA)) {
            Box(Modifier.fillMaxWidth()) {
                val actionEnabled = pageInteractive && !busy && (state.available || state.reported)
                val clearingHistory = state.left && state.needsHistoryRemoval && !state.cleared
                val label = when {
                    state.dismissPending || dispatch?.kind == SpamDispatchKind.DISMISS -> "正在关闭举报提示……"
                    state.clearPending || clearingHistory && busy -> "正在清除聊天记录……"
                    state.leavePending || state.reported && busy -> "正在离开聊天……"
                    state.reportPending || busy -> "正在提交举报……"
                    clearingHistory -> "已举报并离开 · 重试清除聊天记录"
                    state.reported -> "已举报 · 重试离开"
                    !state.available -> "举报未完成"
                    else -> "举报垃圾信息并离开"
                }
                Column(Modifier.fillMaxWidth().heightIn(min = 42.dp)
                    .chatSquircleSurface(Color.Transparent, 15.dp)
                    .clickable(enabled = actionEnabled, role = Role.Button) {
                        val current = currentTarget() ?: return@clickable
                        if (!current.available && !current.reported) return@clickable
                        val chat = latestSnapshot.selected ?: return@clickable
                        confirmation = SpamConfirmation(latestSnapshot.token, chat.id, chat.isChannel,
                            chat.basicGroupId != null || current.needsHistoryRemoval)
                        dispatchFailed = false
                        requested = true
                    }.padding(horizontal = if (state.reported) 14.dp else 44.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!state.reported) Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
                    ComposerActionIcon(MiuixIcons.Regular.Close, "关闭举报提示",
                        enabled = pageInteractive && !busy && !requested, onClick = { submit(SpamDispatchKind.DISMISS) })
                }
            }
            if (!dialogShown) {
                if (state.error != null) SpamFailureText(state, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
                if (dispatchFailed) Text("操作未提交，请重试。", fontSize = 13.sp, color = colors.onSurface,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
            }
        }
    }

    if (confirmation != null) ContextMenuBackdropRegistration(dialogShown)
    val clearingHistory = state?.let { it.left && it.needsHistoryRemoval && !it.cleared } == true
    OverlayDialog(show = dialogShown, title = when {
        clearingHistory -> "清除聊天记录"
        state?.reported == true -> "离开聊天"
        else -> "举报垃圾信息并离开"
    },
        maxWidth = 480.dp, onDismissRequest = { if (!busy) requested = false },
        onDismissFinished = { if (!requested) confirmation = null }) {
        confirmation?.let { frozen ->
            val noun = if (frozen.channel) "频道" else "群组"
            val reported = state?.reported == true
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    clearingHistory -> "垃圾信息已举报并已离开群组，清除你自己的聊天记录尚未完成。"
                    reported -> "垃圾信息已举报，离开${noun}尚未完成。"
                    frozen.historyRemoval -> "确定向 Telegram 举报这个群组的垃圾信息、退出群组，并清除你自己的聊天记录吗？其他成员的聊天记录不会被删除。"
                    else -> "确定向 Telegram 举报这个${noun}的垃圾信息并离开吗？"
                },
                    fontSize = 14.sp, color = colors.onSurface)
                if (reported) Text(when {
                    clearingHistory -> "此次仅重试清除你自己的聊天记录，不会再次举报或离开群组。"
                    frozen.historyRemoval -> "举报不会重复提交。离开后将清除你自己的聊天记录，不会删除其他成员的记录。"
                    else -> "此次将仅重试离开。"
                }, fontSize = 13.sp, color = colors.onSurfaceSecondary)
                state?.takeIf { it.error != null }?.let { SpamFailureText(it) }
                if (dispatchFailed) Text("操作未提交，请重试。", fontSize = 13.sp, color = colors.onSurface)
                if (state?.pending == true || localPending) Text(
                    when {
                        state?.clearPending == true || clearingHistory -> "正在清除聊天记录……"
                        state?.leavePending == true || reported -> "正在离开${noun}……"
                        else -> "正在提交举报……"
                    },
                    fontSize = 13.sp, color = colors.onSurface)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpamDialogButton("取消", Modifier.weight(1f), enabled = dialogShown && !busy,
                        onClick = { requested = false })
                    SpamDialogButton(when {
                        clearingHistory -> "重试清除聊天记录"
                        reported -> "重试离开"
                        else -> "举报并离开"
                    }, Modifier.weight(1f),
                        enabled = dialogShown && !busy && state?.let { it.available || it.reported } == true,
                        primary = true, onClick = { submit(SpamDispatchKind.REPORT_AND_LEAVE, frozen) })
                }
            }
        }
    }
}

private fun currentSpamState(snapshot: ChatSnapshot): ChatReportSpamState? = snapshot.reportSpam.takeIf {
    snapshot.enabled && snapshot.token.chatId != null && snapshot.selected?.id == snapshot.token.chatId &&
        it.chatId == snapshot.token.chatId
}

@Composable
private fun SpamFailureText(state: ChatReportSpamState, modifier: Modifier = Modifier) {
    val message = when {
        state.left && state.needsHistoryRemoval && !state.cleared -> "已举报并离开，清除自己的聊天记录未完成。请重试清除聊天记录。"
        state.reported -> "垃圾信息已举报，离开未完成。请重试离开。"
        !state.available -> "举报未完成，当前举报入口已失效。可关闭此提示。"
        else -> "操作未完成，请重试或关闭提示。"
    }
    Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface, modifier = modifier)
}

@Composable
private fun SpamDialogButton(label: String, modifier: Modifier, enabled: Boolean, primary: Boolean = false,
    onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Button(onClick = onClick, modifier = modifier, enabled = enabled, cornerRadius = 15.dp, minHeight = 40.dp,
        minWidth = 0.dp, colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(
            color = colors.secondaryContainer.copy(alpha = 1f), contentColor = colors.onSurface)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Check the actual glass tint over both source extremes, plus the opaque no-backdrop fallback. */
private fun readableSpamForeground(error: Color, foreground: Color, surface: Color, glassTint: Color): Color {
    fun contrast(a: Color, b: Color): Float = (maxOf(a.luminance(), b.luminance()) + .05f) /
        (minOf(a.luminance(), b.luminance()) + .05f)
    val tint = glassTint.copy(alpha = REPORT_SPAM_BAR_TINT_ALPHA)
    val surfaces = listOf(surface, tint.compositeOver(Color.Black), tint.compositeOver(Color.White))
    for (step in 0..20) {
        val candidate = lerp(error, foreground, step / 20f).copy(alpha = 1f)
        // Leave room for 8-bit glyph rasterization beyond the rendered 4.5:1 acceptance.
        if (surfaces.all { contrast(candidate, it) >= 5f }) return candidate
    }
    return foreground.copy(alpha = 1f)
}
