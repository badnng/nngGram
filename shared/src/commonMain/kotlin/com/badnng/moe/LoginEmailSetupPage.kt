package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.settings.*
import kotlinx.coroutines.delay
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

internal interface LoginEmailPageLease { fun active(): Boolean; fun close() }
internal expect fun loginEmailPageLease(): LoginEmailPageLease

private class LoginEmailPageOwner(val generation: Long, val kind: LoginEmailEntryKind, val epoch: Long) {
    val openingId = Random.nextLong(1, Long.MAX_VALUE)
    val lease = loginEmailPageLease()
    var token: LoginEmailSetupToken? = null

    fun owns(snapshot: LoginEmailSnapshot): Boolean {
        val candidate = snapshot.token ?: return false
        return lease.active() && snapshot.generation == generation && snapshot.openingId == openingId &&
            snapshot.suggestions.contains(kind, epoch) &&
            candidate.generation == generation && candidate.kind == kind && candidate.suggestionEpoch == epoch &&
            (token == null || token == candidate)
    }
}

/** Secrets are private to this composition, including when its parent has a SaveableStateHolder. */
private class LoginEmailPageDraft {
    var email by mutableStateOf(TextFieldValue())
    var code by mutableStateOf(TextFieldValue())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<LoginEmailIssue?>(null)
    var activity by mutableStateOf(0L)
    var revision = 0L
    private var request = 0L

    fun editEmail(value: TextFieldValue) {
        if (email.text != value.text) { revision++; error = null }
        email = value; activity++
    }
    fun editCode(value: TextFieldValue) {
        if (code.text != value.text) { revision++; error = null }
        code = value; activity++
    }
    fun begin(): Pair<Long, Long> {
        busy = true; error = null; activity++
        return revision to ++request
    }
    fun accepts(identity: Pair<Long, Long>): Boolean = revision == identity.first && request == identity.second
    fun clear() {
        revision++; request++; email = TextFieldValue(); code = TextFieldValue()
        busy = false; error = null; activity++
    }
}

/**
 * An independent no-2FA setup owner. In a bounded viewport the body scrolls itself; inside an
 * existing settings scroller it keeps its natural height rather than nesting an unbounded scroller.
 * The host must select MANDATORY from the original runtime, outside ordinary route animations.
 */
@Composable
internal fun LoginEmailSetupPage(
    snapshot: LoginEmailSnapshot,
    actions: LoginEmailActions,
    kind: LoginEmailEntryKind,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onClose: () -> Unit = {},
    onStop: () -> Unit = {},
    onExit: () -> Unit = {},
    ready: Boolean = true,
) {
    var restart by remember(snapshot.generation, kind) { mutableStateOf(0L) }
    var notice by remember(snapshot.generation, kind) { mutableStateOf<String?>(null) }
    val epoch = snapshot.suggestions.epoch(kind)
    val page = remember(snapshot.generation, kind, epoch, active, ready, restart) {
        LoginEmailPageOwner(snapshot.generation, kind, epoch)
    }
    val form = remember(page) { LoginEmailPageDraft() }
    val latestActions by rememberUpdatedState(actions)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestPage by rememberUpdatedState(page)
    val latestActive by rememberUpdatedState(active)
    val latestReady by rememberUpdatedState(ready)
    val owned = active && ready && page.owns(snapshot)
    val token = snapshot.token.takeIf { owned }
    val phase = snapshot.phase.takeIf { owned } ?: LoginEmailPhase.EMAIL
    val challenge = snapshot.challenge?.takeIf { owned && it.owner == token }
    val nativeBusy = owned && snapshot.busy
    val enabled = owned && snapshot.availability == LoginEmailAvailability.ALLOWED && !nativeBusy && !form.busy
    val currentActions = actions

    SideEffect { if (owned && page.token == null) page.token = token }
    DisposableEffect(page) {
        if (active && ready) currentActions.open(page.generation, page.openingId, page.kind, page.lease::active)
        else page.lease.close()
        onDispose {
            page.lease.close()
            form.clear()
            page.token?.let { latestActions.close(it) }
        }
    }

    fun current(owner: LoginEmailSetupToken): Boolean = latestActive && latestReady && latestPage === page &&
        page.token == owner && page.owns(latestSnapshot) && latestSnapshot.token == owner

    fun retire() {
        page.lease.close()
        form.clear()
        page.token?.let { latestActions.close(it) }
    }
    fun restartEmail(message: String? = null) {
        retire(); notice = message; restart++
    }
    fun request(
        submit: ((LoginEmailOutcome) -> Unit) -> Unit,
        accepted: (LoginEmailOutcome) -> Unit = {},
    ) {
        val owner = token ?: return
        if (!enabled || !current(owner) || latestSnapshot.busy || form.busy || latestSnapshot.phase != phase ||
            latestSnapshot.availability != LoginEmailAvailability.ALLOWED) return
        val requestPhase = phase
        val identity = form.begin()
        submit reply@{ outcome ->
            if (!current(owner) || !form.accepts(identity)) return@reply
            // A successful response may already have advanced the native snapshot. A failed
            // response can only affect the original phase, never a newer native challenge.
            if (outcome.issue != null && latestSnapshot.phase != requestPhase) return@reply
            form.busy = false
            form.error = outcome.issue
            if (outcome.issue == null) accepted(outcome)
        }
    }
    fun submitEmail() {
        val owner = token ?: return
        if (phase != LoginEmailPhase.EMAIL || form.email.composition != null) return
        val address = form.email.text.trim()
        if (!validLoginEmailAddress(address)) { form.error = LoginEmailIssue.EMAIL_INVALID; return }
        request({ done -> latestActions.submitEmail(owner, address, done) })
    }
    fun submitCode() {
        val codeOwner = challenge ?: return
        if (phase != LoginEmailPhase.CODE || latestSnapshot.challenge != codeOwner || latestSnapshot.codeInfo == null ||
            form.code.composition != null || form.code.text.isBlank()) return
        val value = form.code.text
        request({ done -> latestActions.check(codeOwner, value, done) }) { outcome ->
            if (outcome.verificationAccepted || outcome.completed) {
                form.email = TextFieldValue(); form.code = TextFieldValue(); form.revision++
            }
        }
    }

    LaunchedEffect(page, form.activity, active) {
        if (active && (form.email.text.isNotEmpty() || form.code.text.isNotEmpty() || form.busy)) {
            delay(10 * 60 * 1000L)
            if (latestPage === page && page.lease.active())
                restartEmail("输入已因长时间未操作而清空，请重新开始。")
        }
    }
    LaunchedEffect(page, phase) {
        if (owned && (phase == LoginEmailPhase.PROOF_PENDING || phase == LoginEmailPhase.COMPLETE)) {
            form.email = TextFieldValue(); form.code = TextFieldValue(); form.revision++
            form.busy = false
        }
    }

    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scrolling = if (constraints.hasBoundedHeight) Modifier.verticalScroll(scroll) else Modifier
        Column(Modifier.fillMaxWidth().then(scrolling).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置登录邮箱", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            SettingsNote(if (kind == LoginEmailEntryKind.MANDATORY)
                "Telegram 要求先设置登录邮箱，才能继续使用。" else "使用可以访问的邮箱接收 Telegram 登录验证码。")
            notice?.let { SettingsNote(it) }
            when {
                !ready -> SettingsNote("正在连接 Telegram……")
                !owned -> SettingsNote("正在读取登录邮箱设置……")
                phase == LoginEmailPhase.COMPLETE -> {
                    Text(if (snapshot.fulfilledElsewhere) "原生邮箱要求已满足" else "登录邮箱已设置", fontWeight = FontWeight.SemiBold)
                }
                phase == LoginEmailPhase.PROOF_PENDING -> {
                    SettingsNote(if (snapshot.verificationAccepted) "验证码已接受，正在核对实际登录邮箱状态。"
                        else "正在核对实际登录邮箱状态。")
                    SettingsNote("状态确认前，不能再次提交验证码。")
                    ChatButton("刷新验证状态", enabled = !nativeBusy && active) {
                        token?.takeIf(::current)?.let { latestActions.refreshProof(it) }
                    }
                }
                snapshot.availability == LoginEmailAvailability.ALLOWED -> when (phase) {
                    LoginEmailPhase.EMAIL -> {
                        TextField(form.email, { value ->
                            if (value.text.length <= LOGIN_EMAIL_ADDRESS_LIMIT) form.editEmail(value)
                        }, label = "邮箱地址", enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitEmail() }))
                        ChatButton("发送验证码", Modifier.fillMaxWidth(), enabled = enabled && form.email.text.isNotBlank(),
                            primary = true, onClick = ::submitEmail)
                    }
                    LoginEmailPhase.CODE -> {
                        val info = snapshot.codeInfo.takeIf { challenge != null }
                        SettingsNote(if (info != null) "验证码已发送至 ${info.pattern.ifBlank { "你的邮箱" }}。"
                            else "验证码信息暂不可用，请返回邮箱地址重新开始。")
                        if (info?.length == 0) SettingsNote("填写邮件中的验证码后，点击验证。")
                        TextField(form.code, { value ->
                            if (value.text.length <= LOGIN_EMAIL_CODE_LIMIT) {
                                val before = form.code
                                form.editCode(value)
                                if ((value.text != before.text || before.composition != null) && value.composition == null &&
                                    info != null && info.length > 0 && value.text.length == info.length) submitCode()
                            }
                        }, label = "验证码", enabled = enabled && challenge != null && info != null, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitCode() }))
                        ChatButton("验证", Modifier.fillMaxWidth(), enabled = enabled && challenge != null && info != null && form.code.text.isNotBlank(),
                            primary = true, onClick = ::submitCode)
                        ChatButton("重新发送验证码", enabled = enabled && challenge != null) {
                            val codeOwner = challenge ?: return@ChatButton
                            if (latestSnapshot.challenge != codeOwner) return@ChatButton
                            request({ done -> latestActions.resend(codeOwner, done) }) { outcome ->
                                if (outcome.challenge?.owner == token && outcome.codeInfo != null) {
                                    form.code = TextFieldValue(); form.revision++
                                    notice = "验证码已重新发送。"
                                }
                            }
                        }
                        SettingsAction("返回邮箱地址", enabled = active && owned) { restartEmail() }
                    }
                    else -> Unit
                }
                snapshot.availability == LoginEmailAvailability.NOT_REQUIRED -> {
                    SettingsNote(if (kind == LoginEmailEntryKind.MANDATORY) "原生要求已变化，仍需核对实际登录邮箱状态。"
                        else "当前不需要设置登录邮箱。")
                    ChatButton("刷新验证状态", enabled = active && !nativeBusy) {
                        token?.takeIf(::current)?.let { latestActions.refreshProof(it) }
                    }
                }
                snapshot.availability == LoginEmailAvailability.FAILED -> {
                    SettingsNote("无法确认登录邮箱设置是否可用，请检查连接后重试。", error = true)
                    ChatButton("重试读取邮箱设置", enabled = active && !nativeBusy) {
                        token?.takeIf(::current)?.let { latestActions.refreshProof(it) }
                    }
                }
                else -> SettingsNote("正在核对登录邮箱设置条件……")
            }
            if (owned && (nativeBusy || form.busy)) SettingsNote("正在提交……")
            (form.error ?: snapshot.issue.takeIf { owned })?.let { SettingsNote(loginEmailIssueText(it), error = true) }
            if (kind == LoginEmailEntryKind.OPTIONAL) {
                SettingsAction("关闭邮箱设置", enabled = active) { retire(); onClose() }
                if (snapshot.suggestions.optionalPresent && !snapshot.suggestions.requiredPresent)
                    SettingsAction("稍后提醒", enabled = active && !nativeBusy && !form.busy) {
                        if (latestActive && latestPage === page && latestSnapshot.generation == page.generation &&
                            latestSnapshot.suggestions.contains(LoginEmailEntryKind.OPTIONAL, page.epoch) &&
                            !latestSnapshot.suggestions.requiredPresent) {
                            latestActions.hideOptional(page.generation, page.epoch)
                            notice = "已请求稍后提醒，等待原生确认。"
                        }
                    }
            } else {
                SettingsAction("停止连接", enabled = active) { retire(); onStop() }
                SettingsAction("退出应用", enabled = active) { retire(); onExit() }
            }
        }
    }
}

private const val LOGIN_EMAIL_ADDRESS_LIMIT = 320
private const val LOGIN_EMAIL_CODE_LIMIT = 100

private fun validLoginEmailAddress(value: String): Boolean {
    val at = value.indexOf('@')
    return value.length <= LOGIN_EMAIL_ADDRESS_LIMIT && at > 0 && at == value.lastIndexOf('@') &&
        value.indexOf('.', at + 1) > at + 1 && !value.endsWith('.') && value.none { it.isWhitespace() || it.isISOControl() }
}

private fun loginEmailIssueText(issue: LoginEmailIssue): String = when (issue) {
    LoginEmailIssue.NOT_AVAILABLE -> "当前无法设置登录邮箱，请刷新实际状态。"
    LoginEmailIssue.BUSY -> "另一项邮箱请求尚未完成，请稍后重试。"
    LoginEmailIssue.STATE_CHANGED -> "邮箱验证状态已变化，请返回邮箱地址后重新开始。"
    LoginEmailIssue.EMAIL_INVALID -> "邮箱地址无效，请检查后重试。"
    LoginEmailIssue.CODE_INVALID -> "验证码不正确，请重试。"
    LoginEmailIssue.CODE_EXPIRED -> "验证码已过期，请重新发送或返回邮箱地址。"
    LoginEmailIssue.TOO_MANY_ATTEMPTS -> "操作过于频繁，请稍后重试。"
    LoginEmailIssue.REQUEST_FAILED -> "请求未完成，输入已保留，请重试。"
    LoginEmailIssue.PROOF_UNCONFIRMED -> "验证已提交，但实际邮箱状态尚未确认，请刷新验证状态。"
}
