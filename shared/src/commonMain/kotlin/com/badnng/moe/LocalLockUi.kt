package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.settings.LocalLockActions
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.settings.LocalLockSnapshot
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Passive real-input observation: no frame loop, focus heuristic or event consumption. */
@Composable
internal fun Modifier.localLockUserActivity(onActivity: () -> Unit): Modifier {
    val latest = rememberUpdatedState(onActivity)
    return onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown) latest.value()
        false
    }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Move || event.type == PointerEventType.Scroll ||
                    event.changes.any { it.pressed && !it.previousPressed }) {
                    latest.value()
                }
            }
        }
    }
}

internal enum class LocalLockCredentialKind { CREATE, UNLOCK, VERIFY, CHANGE }

/** Kept only by this mounted form. Never Saver-backed and never passed into snapshots. */
internal class LocalLockSecretDraft {
    var password by mutableStateOf(TextFieldValue())
    var confirmation by mutableStateOf(TextFieldValue())
    var pendingRevision by mutableStateOf<Long?>(null)
    var issue by mutableStateOf<LocalLockIssue?>(null)
    var editedSinceResult by mutableStateOf(false)
    var focusRevision by mutableLongStateOf(0L)

    fun clear() {
        password = TextFieldValue()
        confirmation = TextFieldValue()
        pendingRevision = null
        issue = null
        editedSinceResult = false
    }

    override fun toString() = "LocalLockSecretDraft(redacted)"
}

/** Common masked input for setup, root unlock and fresh management verification. */
@Composable
internal fun LocalLockCredentialForm(
    snapshot: LocalLockSnapshot,
    actions: LocalLockActions,
    kind: LocalLockCredentialKind,
    active: Boolean,
    windowFocused: Boolean,
    modifier: Modifier = Modifier,
    onAccepted: () -> Unit = {},
) {
    val form = remember(kind, snapshot.epoch) { LocalLockSecretDraft() }
    val latestSnapshot = rememberUpdatedState(snapshot)
    val latestActions = rememberUpdatedState(actions)
    val latestActive = rememberUpdatedState(active)
    val latestAccepted = rememberUpdatedState(onAccepted)
    val passwordFocus = remember { FocusRequester() }
    val confirmationFocus = remember { FocusRequester() }
    val twoFields = kind == LocalLockCredentialKind.CREATE || kind == LocalLockCredentialKind.CHANGE
    fun allowed(value: LocalLockSnapshot): Boolean = when (kind) {
        LocalLockCredentialKind.CREATE -> value.canCreate
        LocalLockCredentialKind.UNLOCK -> value.phase == LocalLockPhase.COLD_LOCKED || value.phase == LocalLockPhase.HOT_LOCKED
        LocalLockCredentialKind.VERIFY -> value.canManage
        LocalLockCredentialKind.CHANGE -> value.canChange && value.managementAuthorized
    }
    val enabled = active && allowed(snapshot) && !snapshot.busy && snapshot.retryAfterMillis <= 0 && form.pendingRevision == null

    fun submit() {
        val current = latestSnapshot.value
        if (!latestActive.value || !allowed(current) || current.busy || current.retryAfterMillis > 0 || form.pendingRevision != null) return
        if (form.password.composition != null || (twoFields && form.confirmation.composition != null)) return
        if (form.password.text.isEmpty()) {
            form.issue = LocalLockIssue.INVALID_PASSWORD
            form.focusRevision++
            return
        }
        if (twoFields && form.password.text != form.confirmation.text) {
            form.issue = LocalLockIssue.PASSWORD_MISMATCH
            return
        }
        val password = form.password.text.toCharArray()
        val confirmation = if (twoFields) form.confirmation.text.toCharArray() else null
        form.password = TextFieldValue()
        form.confirmation = TextFieldValue()
        form.issue = null
        form.editedSinceResult = false
        form.pendingRevision = current.operationRevision
        // The action takes array ownership, including every rejection/cancellation path.
        when (kind) {
            LocalLockCredentialKind.CREATE -> latestActions.value.create(password, checkNotNull(confirmation))
            LocalLockCredentialKind.UNLOCK -> latestActions.value.unlock(password)
            LocalLockCredentialKind.VERIFY -> latestActions.value.verifyManagement(password)
            LocalLockCredentialKind.CHANGE -> latestActions.value.change(password, checkNotNull(confirmation))
        }
    }

    DisposableEffect(form) { onDispose { form.clear() } }
    LaunchedEffect(active) { if (!active) form.clear() }
    LaunchedEffect(snapshot.operationRevision, snapshot.busy) {
        val submitted = form.pendingRevision
        if (submitted != null && snapshot.operationRevision != submitted && !snapshot.busy) {
            form.pendingRevision = null
            form.editedSinceResult = false
            if (snapshot.issue == LocalLockIssue.NONE) latestAccepted.value()
            else form.focusRevision++
        }
    }
    LaunchedEffect(windowFocused, enabled, form.focusRevision) {
        if (windowFocused && enabled) passwordFocus.requestFocus()
    }
    val label = if (twoFields) "新本地密码" else "本地密码"
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(form.password, {
            form.password = it
            form.issue = null
            form.editedSinceResult = true
            latestActions.value.managementActivity()
        }, label = label, enabled = enabled, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                imeAction = if (twoFields) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onNext = { if (enabled) confirmationFocus.requestFocus() }, onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).onPreviewKeyEvent {
                if (!twoFields && (it.key == Key.Enter || it.key == Key.NumPadEnter) && form.password.composition == null) {
                    if (it.type == KeyEventType.KeyDown) submit()
                    true
                } else false
            })
        if (twoFields) TextField(form.confirmation, {
            form.confirmation = it
            form.issue = null
            form.editedSinceResult = true
            latestActions.value.managementActivity()
        }, label = "再次输入本地密码", enabled = enabled, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(confirmationFocus).onPreviewKeyEvent {
                if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && form.confirmation.composition == null) {
                    if (it.type == KeyEventType.KeyDown) submit()
                    true
                } else false
            })
        val issue = form.issue ?: snapshot.issue.takeUnless { it == LocalLockIssue.NONE || form.editedSinceResult }
        if (form.pendingRevision != null || snapshot.busy) SettingsNote("正在处理……")
        else if (snapshot.issue == LocalLockIssue.CRYPTO_UNAVAILABLE) SettingsNote(localLockIssueText(snapshot.issue), error = true)
        else if (snapshot.retryAfterMillis > 0) SettingsNote(localLockWaitText(snapshot.retryAfterMillis))
        else issue?.let { SettingsNote(localLockIssueText(it), error = it != LocalLockIssue.MIGRATION_UNAVAILABLE) }
        ChatButton(when (kind) {
            LocalLockCredentialKind.CREATE -> "创建本地密码"
            LocalLockCredentialKind.UNLOCK -> "解锁"
            LocalLockCredentialKind.VERIFY -> "验证密码"
            LocalLockCredentialKind.CHANGE -> "保存新密码"
        }, Modifier.fillMaxWidth(), enabled = enabled && form.password.text.isNotEmpty() &&
            (!twoFields || form.confirmation.text.isNotEmpty()), primary = true, onClick = ::submit)
    }
}

/** Direct root branch: its caller must remove the protected subtree without an exit animation. */
@Composable
internal fun LocalLockScreen(
    snapshot: LocalLockSnapshot,
    actions: LocalLockActions,
    windowFocused: Boolean,
    modifier: Modifier = Modifier,
    awaitingLock: Boolean = false,
    onStop: () -> Unit = {},
    onExit: () -> Unit = {},
    active: Boolean = true,
    onRecheckProtection: (() -> Unit)? = null,
) {
    var confirmSkip by remember(snapshot.epoch) { mutableStateOf(false) }
    var skipRevision by remember(snapshot.epoch) { mutableStateOf<Long?>(null) }
    val latestSnapshot = rememberUpdatedState(snapshot)
    val latestActions = rememberUpdatedState(actions)
    LaunchedEffect(snapshot.operationRevision, snapshot.busy) {
        if (skipRevision != null && snapshot.operationRevision != skipRevision && !snapshot.busy) skipRevision = null
    }
    Box(modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface.copy(alpha = 1f)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val setup = snapshot.phase == LocalLockPhase.NEW_ROOT_SETUP
            Text(if (setup) "设置本地密码" else if (snapshot.phase == LocalLockPhase.CHECKING) "本机保护" else "nngGram 已锁定",
                Modifier.semantics { heading() },
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
            when {
                awaitingLock && !snapshot.rootBlocked -> SettingsNote("正在锁定……")
                setup -> {
                    SettingsNote("此密码保护本机应用和数据库密钥，无法通过云端找回。下载媒体不在其加密范围内。")
                    if (!confirmSkip) {
                        LocalLockCredentialForm(snapshot, actions, LocalLockCredentialKind.CREATE,
                            active = active && skipRevision == null, windowFocused = windowFocused)
                        SettingsAction("暂不设置", enabled = active && !snapshot.busy && skipRevision == null) { confirmSkip = true }
                    } else {
                        SettingsNote("继续后将不使用本地密码。当前版本尚不能为既有数据安全迁移设置密码。")
                        ChatButton("返回设置密码", Modifier.fillMaxWidth(), enabled = active && !snapshot.busy && skipRevision == null) { confirmSkip = false }
                        ChatButton("确认暂不设置", Modifier.fillMaxWidth(), enabled = active && !snapshot.busy && skipRevision == null) {
                            val current = latestSnapshot.value
                            if (active && current.phase == LocalLockPhase.NEW_ROOT_SETUP && !current.busy && skipRevision == null) {
                                skipRevision = current.operationRevision
                                latestActions.value.skipSetup()
                            }
                        }
                    }
                }
                snapshot.phase == LocalLockPhase.COLD_LOCKED || snapshot.phase == LocalLockPhase.HOT_LOCKED -> {
                    SettingsNote("请输入本地密码继续。此密码与 Telegram 两步验证密码分别设置。")
                    LocalLockCredentialForm(snapshot, actions, LocalLockCredentialKind.UNLOCK, active, windowFocused)
                }
                snapshot.phase == LocalLockPhase.RECOVERY -> {
                    SettingsNote(localLockIssueText(snapshot.issue), error = true)
                    SettingsNote(if (snapshot.issue == LocalLockIssue.STALE && onRecheckProtection != null)
                        "客户端已停止。请重新检查本机保护状态后继续。"
                    else "为保留现有数据，应用已停止打开受保护内容。请保留数据目录并检查本机保护记录。")
                    if (onRecheckProtection != null) ChatButton("重新检查保护状态", Modifier.fillMaxWidth(),
                        enabled = active && !snapshot.busy, onClick = onRecheckProtection)
                }
                snapshot.phase == LocalLockPhase.UNLOCKING -> SettingsNote("正在验证本机数据……")
                else -> SettingsNote("正在检查本机保护状态……")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("停止", Modifier.weight(1f), onClick = onStop)
                ChatButton("退出", Modifier.weight(1f), onClick = onExit)
            }
        }
    }
}

internal fun localLockWaitText(milliseconds: Long): String {
    val value = milliseconds.coerceAtLeast(1)
    val seconds = value / 1000 + if (value % 1000 == 0L) 0 else 1
    return "尝试次数较多，请等待 $seconds 秒后重试。"
}

internal fun localLockIssueText(issue: LocalLockIssue): String = when (issue) {
    LocalLockIssue.NONE -> "本机保护状态尚未就绪。"
    LocalLockIssue.WRONG_PASSWORD -> "本地密码不正确，请重试。"
    LocalLockIssue.INVALID_PASSWORD -> "请输入非空且长度有效的本地密码。空格会保留。"
    LocalLockIssue.INVALID_AUTO_LOCK -> "请输入有效且非零的自动锁时长，分钟为 0–59。"
    LocalLockIssue.PASSWORD_MISMATCH -> "两次输入的本地密码不一致。"
    LocalLockIssue.PASSWORD_UNCHANGED -> "新本地密码与原密码相同，请选择不同的密码。"
    LocalLockIssue.BUSY -> "另一项本机保护操作仍在处理，请稍后重试。"
    LocalLockIssue.WAITING -> "请等待后重试。"
    LocalLockIssue.MIGRATION_UNAVAILABLE -> "既有数据的安全迁移尚未具备验证证据，暂不能启用本地密码。"
    LocalLockIssue.CORRUPT_RECORD -> "本机保护记录损坏，无法安全打开数据。"
    LocalLockIssue.UNSUPPORTED_RECORD -> "本机保护记录版本或算法暂不支持。"
    LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE -> "无法确认本机数据目录的保护身份。"
    LocalLockIssue.IO_FAILURE -> "本机保护记录未能可靠读取或保存，请重试。"
    LocalLockIssue.RESOURCE_UNAVAILABLE -> "本机密码验证所需资源暂不可用。"
    LocalLockIssue.CRYPTO_UNAVAILABLE -> "本机密码验证或重试计时组件暂不可用，请退出并重新启动应用后检查。"
    LocalLockIssue.DATABASE_MISMATCH -> "保护密钥与现有数据库不匹配，需要恢复处理。"
    LocalLockIssue.STALE -> "本机保护状态已变化，请重新验证。"
}
