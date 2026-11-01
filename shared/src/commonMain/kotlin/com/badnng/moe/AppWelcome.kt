package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.EngineFailure
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.ProxyStatus
import com.badnng.moe.status.RuntimeSnapshot
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class AppWelcomePhase { START, CONFIGURATION, INITIALIZING, FAILED, CLOSING }

/** Only application-owned status text belongs here; credentials and account inputs do not. */
data class AppWelcomeState(
    val phase: AppWelcomePhase = AppWelcomePhase.START,
    val generation: Long = 0,
    val canContinue: Boolean = true,
    val active: Boolean = true,
    val failure: EngineFailure? = null,
    val configurationOpenError: String? = null,
)

/** A null result hands control to the actual authorization flow, including an existing session. */
fun RuntimeSnapshot.appWelcomeState(
    active: Boolean = true,
    configurationOpenError: String? = null,
): AppWelcomeState? {
    val preparedAuthorization = status.authorization in setOf(AuthorizationPhase.WAITING_LOGIN,
        AuthorizationPhase.WAITING_CODE, AuthorizationPhase.WAITING_PASSWORD, AuthorizationPhase.OTHER_STEP,
        AuthorizationPhase.READY)
    if (!terminated && clientActive && status.engine == EnginePhase.INITIALIZED &&
        proxy in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED) && preparedAuthorization) return null
    val phase = when {
        terminated || closeTimedOut || status.engine == EnginePhase.CLOSING -> AppWelcomePhase.CLOSING
        status.engine == EnginePhase.WAITING_CONFIGURATION -> AppWelcomePhase.CONFIGURATION
        status.engine == EnginePhase.STARTING -> AppWelcomePhase.INITIALIZING
        status.engine == EnginePhase.FAILED || proxy == ProxyStatus.FAILED -> AppWelcomePhase.FAILED
        clientActive -> AppWelcomePhase.INITIALIZING
        else -> AppWelcomePhase.START
    }
    val retryActiveFailure = clientActive && phase == AppWelcomePhase.FAILED && status.engine != EnginePhase.CLOSING
    val canContinue = phase !in setOf(AppWelcomePhase.INITIALIZING, AppWelcomePhase.CLOSING) && (canStart || retryActiveFailure)
    return AppWelcomeState(phase, generation, canContinue, active,
        status.failure ?: proxyFailure, configurationOpenError)
}

/**
 * Explicit Start -> real initialization -> authorization. The host owns the callback and the
 * handoff; this screen never starts a client, opens a file, or submits a login from composition.
 */
@Composable
fun AppWelcomeScreen(
    state: AppWelcomeState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var submitted by remember(state.generation, state.phase) { mutableStateOf(false) }
    val pending = state.phase in setOf(AppWelcomePhase.INITIALIZING, AppWelcomePhase.CLOSING)
    val actionEnabled = state.active && state.canContinue && !pending && !submitted
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().chatSquircleBackground(MiuixTheme.colorScheme.surface, 0.dp)) {
        val compact = constraints.maxDpSize(density).height < 600.dp
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = if (compact) 24.dp else 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Column(Modifier.widthIn(max = 368.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(if (compact) 88.dp else 112.dp)
                    .chatSquircleBackground(MiuixTheme.colorScheme.primary, 28.dp), contentAlignment = Alignment.Center) {
                    Icon(MiuixIcons.Regular.Send, contentDescription = null, modifier = Modifier.size(if (compact) 44.dp else 56.dp),
                        tint = MiuixTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.height(if (compact) 24.dp else 32.dp))
                Text("nngGram", fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() }, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("在桌面上，连接你的 Telegram 世界。", fontSize = 16.sp, textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(32.dp))
                when (state.phase) {
                    AppWelcomePhase.CONFIGURATION -> WelcomeNote("此版本尚未配置完成",
                        "请使用已配置的发行版本，或按构建文档重新构建。", error = true)
                    AppWelcomePhase.INITIALIZING -> {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            MediaLoadingIndicator(state.active, "正在准备 nngGram")
                        }
                        Spacer(Modifier.height(16.dp))
                        WelcomeNote("正在准备 nngGram", "准备完成后将进入账号登录。")
                    }
                    AppWelcomePhase.FAILED -> WelcomeNote(state.failure?.summary ?: "暂时无法启动",
                        state.failure?.recovery ?: "请检查本地配置和连接后重试。", error = true)
                    AppWelcomePhase.CLOSING -> WelcomeNote("正在等待客户端关闭", "关闭完成后才能重新开始。")
                    AppWelcomePhase.START -> Unit
                }
                if (state.phase != AppWelcomePhase.START) Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    if (actionEnabled) {
                        submitted = true
                        onContinue()
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = actionEnabled,
                    colors = ButtonDefaults.buttonColorsPrimary(), cornerRadius = 15.dp,
                    insideMargin = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(when (state.phase) {
                        AppWelcomePhase.START -> "开始使用"
                        AppWelcomePhase.CONFIGURATION -> "重试"
                        AppWelcomePhase.INITIALIZING -> "正在准备…"
                        AppWelcomePhase.FAILED -> "重试"
                        AppWelcomePhase.CLOSING -> "等待关闭"
                    }, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun WelcomeNote(title: String, description: String, error: Boolean = false) {
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))
    Text(description, fontSize = 14.sp, textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
