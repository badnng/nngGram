package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class SetupProxyMode { SYSTEM, DIRECT, SOCKS5, HTTP }

/** The local configuration values the guide edits; nothing here leaves this device. */
data class SetupDraft(
    val apiId: String = "",
    val apiHash: String = "",
    val proxyMode: SetupProxyMode = SetupProxyMode.SYSTEM,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
) {
    override fun toString() = "SetupDraft(<redacted>)"
}

data class SetupSnapshot(
    val draft: SetupDraft = SetupDraft(),
    /** The build already carries an application identity, so the identity step is optional. */
    val bundledIdentity: Boolean = false,
    val configPath: String = "",
    /** The fixed proxy currently reported by the operating system, such as `SOCKS5 127.0.0.1:7897`. */
    val systemProxy: String? = null,
)

/** Host-owned local file access; [save] returns an error text, or null once the file is written. */
class SetupActions(
    val load: () -> SetupSnapshot? = { null },
    val detectSystemProxy: () -> String? = { null },
    val save: (SetupDraft) -> String? = { "当前环境不支持保存配置。" },
)

/**
 * Two steps: application identity, then connection. The host decides what happens after a
 * successful save (normally a client restart); this screen only edits the local file.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupGuideScreen(
    initial: SetupSnapshot,
    actions: SetupActions,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    var draft by remember(initial) { mutableStateOf(initial.draft) }
    var step by remember(initial) { mutableIntStateOf(0) }
    var systemProxy by remember(initial) { mutableStateOf(initial.systemProxy) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    val colors = MiuixTheme.colorScheme
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().chatSquircleBackground(colors.surface, 0.dp)) {
        val compact = constraints.maxDpSize(density).height < 600.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = if (compact) 24.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("配置引导", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("第 ${step + 1} 步，共 2 步 · ${if (step == 0) "应用身份" else "连接方式"}", fontSize = 14.sp,
                    color = colors.onSurfaceVariantSummary)
                Card(Modifier.fillMaxWidth(), cornerRadius = 20.dp, insideMargin = PaddingValues(20.dp)) {
                    if (step == 0) IdentityStep(initial.bundledIdentity, draft) { draft = it; error = null }
                    else ConnectionStep(draft, systemProxy, onDraft = { draft = it; error = null },
                        onDetect = { if (active) systemProxy = actions.detectSystemProxy() })
                }
                error?.let { Text(it, fontSize = 14.sp, color = colors.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GuideButton(if (step == 0) "取消" else "上一步", primary = false, enabled = active, modifier = Modifier.weight(1f)) {
                        error = null
                        if (step == 0) onCancel() else step = 0
                    }
                    GuideButton(if (step == 0) "下一步" else "保存并连接", primary = true, enabled = active, modifier = Modifier.weight(1f)) {
                        if (step == 0) {
                            error = identityError(draft, initial.bundledIdentity)
                            if (error == null) step = 1
                        } else {
                            error = actions.save(draft)
                            if (error == null) onDone()
                        }
                    }
                }
                if (initial.configPath.isNotEmpty()) Text("配置保存在本机：${initial.configPath}", fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary)
            }
        }
    }
}

/** Mirrors the startup parser so an incomplete pair is reported on the step that owns it. */
internal fun identityError(draft: SetupDraft, bundledIdentity: Boolean): String? {
    val id = draft.apiId.trim()
    val hash = draft.apiHash.trim()
    return when {
        id.isEmpty() && hash.isEmpty() -> if (bundledIdentity) null else "请填写 api_id 和 api_hash。"
        id.isEmpty() || hash.isEmpty() -> "api_id 和 api_hash 需要成对填写。"
        id.toIntOrNull()?.let { it > 0 } != true -> "api_id 应为正整数。"
        !hash.matches(Regex("[a-fA-F0-9]{32}")) -> "api_hash 应为 32 位十六进制字符。"
        else -> null
    }
}

@Composable
private fun IdentityStep(bundledIdentity: Boolean, draft: SetupDraft, onDraft: (SetupDraft) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Text("Telegram 应用身份", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(if (bundledIdentity) "此版本已内置应用身份，可以直接进入下一步。只有想改用自己申请的身份时才需要填写。"
        else "在 my.telegram.org 的 API development tools 中申请后，把 api_id 和 api_hash 填到这里。",
        fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
    Spacer(Modifier.height(16.dp))
    TextField(draft.apiId, { value -> onDraft(draft.copy(apiId = value.filter(Char::isDigit).take(12))) },
        label = "api_id", singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    Spacer(Modifier.height(12.dp))
    TextField(draft.apiHash, { value -> onDraft(draft.copy(apiHash = value.trim().take(32))) },
        label = "api_hash", singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionStep(draft: SetupDraft, systemProxy: String?, onDraft: (SetupDraft) -> Unit, onDetect: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Text("连接方式", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text("nngGram 不会自动使用系统代理软件，需要在这里选择一种连接方式。", fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(SetupProxyMode.SYSTEM to "跟随系统代理", SetupProxyMode.DIRECT to "直接连接",
            SetupProxyMode.SOCKS5 to "SOCKS5", SetupProxyMode.HTTP to "HTTP").forEach { (mode, name) ->
            GuideButton(name, primary = draft.proxyMode == mode) { onDraft(draft.copy(proxyMode = mode)) }
        }
    }
    Spacer(Modifier.height(16.dp))
    when (draft.proxyMode) {
        SetupProxyMode.SYSTEM -> {
            Text(systemProxy?.let { "检测到系统代理：$it" } ?: "当前没有检测到系统代理，将直接连接。", fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("每次启动时读取一次；不支持 PAC 自动配置脚本。切换系统代理后请重新启动客户端。", fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.height(12.dp))
            GuideButton("重新检测", primary = false, onClick = onDetect)
        }
        SetupProxyMode.DIRECT -> Text("不经过代理。网络无法直接访问 Telegram 时会一直停在“连接中”。", fontSize = 14.sp)
        SetupProxyMode.SOCKS5, SetupProxyMode.HTTP -> {
            TextField(draft.proxyHost, { onDraft(draft.copy(proxyHost = it.trim().take(255))) },
                label = "代理地址，例如 127.0.0.1", singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            TextField(draft.proxyPort, { value -> onDraft(draft.copy(proxyPort = value.filter(Char::isDigit).take(5))) },
                label = "端口，例如 7890", singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.height(12.dp))
            TextField(draft.proxyUsername, { onDraft(draft.copy(proxyUsername = it.take(255))) },
                label = "用户名（可选）", singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            TextField(draft.proxyPassword, { onDraft(draft.copy(proxyPassword = it.take(255))) },
                label = "密码（可选）", singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation())
        }
    }
}

@Composable
private fun GuideButton(text: String, primary: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 44.dp), enabled = enabled,
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        minWidth = 0.dp, cornerRadius = 14.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, fontSize = 14.sp)
    }
}
