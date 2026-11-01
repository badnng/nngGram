package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/** The default is a native Miuix dialog; offline tests may render the same body without opening an OS window. */
internal interface SettingsSecurityConfirmationHost {
    @Composable
    fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
        onDismissRequest: () -> Unit, content: @Composable () -> Unit)
}

private object NativeSettingsSecurityConfirmationHost : SettingsSecurityConfirmationHost {
    @Composable
    override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
        onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        WindowDialog(show, title = title, summary = summary, maxWidth = maxWidth,
            onDismissRequest = onDismissRequest, content = content)
    }
}

internal val LocalSettingsSecurityConfirmationHost = staticCompositionLocalOf<SettingsSecurityConfirmationHost> {
    NativeSettingsSecurityConfirmationHost
}

@Composable
internal fun SecurityConfirmation(show: Boolean, title: String?, summary: String? = null, maxWidth: Dp,
    onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    LocalSettingsSecurityConfirmationHost.current.Content(show, title, summary, maxWidth, onDismissRequest, content)
}

private enum class PasswordPageStep { INTRO, CURRENT_PASSWORD, MANAGE, NEW_PASSWORD, HINT, EMAIL, EMAIL_CODE, RECOVERY_CODE }
private enum class PasswordPageMode { CREATE, CHANGE, EMAIL, RECOVER, LOGIN_EMAIL }
private enum class PasswordConfirmation { DISABLE, REMOVE_EMAIL, SKIP_EMAIL, CANCEL_EMAIL, RESET, CANCEL_RESET, RECOVER_REMOVE }

/** Secrets never use a Saver or enter an account/runtime snapshot. */
private class PasswordPageDraft {
    var initialized by mutableStateOf(false)
    var step by mutableStateOf(PasswordPageStep.INTRO)
    var mode by mutableStateOf(PasswordPageMode.CREATE)
    var currentPassword by mutableStateOf(TextFieldValue())
    var newPassword by mutableStateOf(TextFieldValue())
    var confirmation by mutableStateOf(TextFieldValue())
    var hint by mutableStateOf(TextFieldValue())
    var email by mutableStateOf(TextFieldValue())
    var code by mutableStateOf(TextFieldValue())
    var codePattern by mutableStateOf("")
    var codeLength by mutableStateOf(0)
    var resetDate by mutableStateOf(0)
    var resetDeclined by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var confirmationAction by mutableStateOf<PasswordConfirmation?>(null)
    var activity by mutableStateOf(0L)
    private var epoch = 0L
    private var request = 0L

    fun touch() { activity++ }
    fun move(next: PasswordPageStep) {
        epoch++
        step = next
        error = null
        confirmationAction = null
        touch()
    }
    fun begin(): Pair<Long, Long> {
        busy = true
        error = null
        touch()
        return epoch to ++request
    }
    fun accepts(identity: Pair<Long, Long>) = identity.first == epoch && identity.second == request
    fun clear(message: String? = null) {
        epoch++
        request++
        currentPassword = TextFieldValue()
        newPassword = TextFieldValue()
        confirmation = TextFieldValue()
        hint = TextFieldValue()
        email = TextFieldValue()
        code = TextFieldValue()
        codePattern = ""
        codeLength = 0
        resetDate = 0
        resetDeclined = false
        busy = false
        error = null
        notice = message
        confirmationAction = null
        initialized = false
        step = PasswordPageStep.INTRO
        touch()
    }
}

@Composable
internal fun SettingsSecurityPage(snapshot: SettingsSnapshot, actions: SettingsActions) {
    val security = snapshot.security
    val section = security.section ?: return
    val active = LocalSettingsPageActive.current && snapshot.enabled
    val writesAllowed = security.error != SettingsError.RESULT_UNKNOWN
    val currentActive by rememberUpdatedState(active)
    val currentToken by rememberUpdatedState(security.token)
    val latestActions by rememberUpdatedState(actions)
    val deviceNameHost = LocalSettingsDeviceNameHost.current
    val top = remember(security.token) { BringIntoViewRequester() }
    DisposableEffect(security.token) {
        onDispose { latestActions.closeSecurity(security.token) }
    }
    LaunchedEffect(active, security.token) {
        if (!active) latestActions.closeSecurity(security.token)
        else top.bringIntoView()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(top)) {
            SettingsAction("返回隐私与安全", enabled = active) {
                deviceNameHost?.retireSecurity?.invoke()
                actions.closeSecurity(security.token)
            }
        }
        Text(when (section) {
            SettingsSecuritySection.PASSWORD -> "两步验证"
            SettingsSecuritySection.WEBSITES -> "已连接网站"
            SettingsSecuritySection.BLOCKED -> "黑名单"
            SettingsSecuritySection.SESSIONS -> "已登录设备"
            SettingsSecuritySection.AUTO_DELETE -> "新聊天自动删除消息"
        }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (security.loading) SettingsNote("正在读取……")
        if (security.busy) SettingsNote("正在提交……")
        security.error?.let { SettingsNote(settingsErrorText(it), error = true) }
        when (section) {
            SettingsSecuritySection.PASSWORD -> SettingsPasswordPage(snapshot, actions, writesAllowed)
            SettingsSecuritySection.WEBSITES -> SettingsWebsitesPage(security, actions, active, writesAllowed)
            SettingsSecuritySection.BLOCKED -> SettingsBlockedPage(security, actions, active, writesAllowed)
            SettingsSecuritySection.SESSIONS -> SettingsSessionsPage(security, actions, active, writesAllowed)
            SettingsSecuritySection.AUTO_DELETE -> SettingsDefaultAutoDeletePage(security, actions, active, writesAllowed)
        }
        if (section != SettingsSecuritySection.PASSWORD) SettingsAction("刷新列表",
            enabled = active && !security.loading && !security.busy && !security.blockedLoadingMore) {
            if (currentActive && currentToken == security.token) actions.refreshSecurity(security.token)
        }
    }
}

@Composable
private fun SettingsPasswordPage(snapshot: SettingsSnapshot, actions: SettingsActions, writesAllowed: Boolean) {
    val security = snapshot.security
    val state = security.password
    val active = LocalSettingsPageActive.current && snapshot.enabled
    val currentActive by rememberUpdatedState(active)
    val currentSecurity by rememberUpdatedState(security)
    val form = remember(security.token) { PasswordPageDraft() }
    var now by remember(security.token) { mutableStateOf(Clock.System.now().epochSeconds) }
    val enabled = writesAllowed && active && state != null && !security.loading && !security.busy && !form.busy
    val confirmFocus = remember { FocusRequester() }

    fun initialize(value: SettingsPasswordState) {
        form.initialized = true
        form.resetDate = value.pendingResetDate
        val pending = value.recoveryEmailCodeInfo
        form.step = when {
            pending != null -> PasswordPageStep.EMAIL_CODE
            value.hasPassword -> PasswordPageStep.CURRENT_PASSWORD
            else -> PasswordPageStep.INTRO
        }
        if (pending != null) {
            form.mode = PasswordPageMode.EMAIL
            form.codePattern = pending.pattern
            form.codeLength = pending.length
        }
    }
    fun reset(value: SettingsPasswordState?, notice: String? = null) {
        form.clear(notice)
        value?.let(::initialize)
    }
    fun issueText(issue: SettingsPasswordIssue): String = when (issue) {
        SettingsPasswordIssue.PASSWORD_INVALID -> "密码不正确，请重试。"
        SettingsPasswordIssue.PASSWORD_CHANGED -> "密码已在其他地方改变，请重新进入两步验证。"
        SettingsPasswordIssue.CODE_INVALID -> "验证码不正确，请重试。"
        SettingsPasswordIssue.CODE_EXPIRED -> "验证码已过期，请重新获取。"
        SettingsPasswordIssue.EMAIL_INVALID -> "邮箱地址无效，请检查后重试。"
        SettingsPasswordIssue.RECOVERY_UNAVAILABLE -> "当前无法通过恢复邮箱找回密码。"
        SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED -> "邮箱尚未确认，请检查验证码和当前验证状态。"
        SettingsPasswordIssue.STATE_CHANGED -> "验证状态已变化，请重新进入两步验证。"
    }
    fun request(command: SettingsPasswordCommand, accepted: (SettingsPasswordOutcome) -> Unit) {
        if (!enabled || currentSecurity.error == SettingsError.RESULT_UNKNOWN || !currentActive || form.busy || currentSecurity.busy || currentSecurity.loading ||
            currentSecurity.token != security.token || currentSecurity.password == null) return
        val owner = security.token
        val step = form.step
        val identity = form.begin()
        actions.password(owner, command) { result ->
            if (!currentActive || currentSecurity.token != owner || !form.accepts(identity) || form.step != step) return@password
            form.busy = false
            val outcome = result.value
            val issue = outcome?.issue
            if (issue != null) {
                val endedEmailVerification = step == PasswordPageStep.EMAIL_CODE && form.mode != PasswordPageMode.LOGIN_EMAIL &&
                    outcome.state != null && outcome.state.recoveryEmailCodeInfo == null &&
                    issue in listOf(SettingsPasswordIssue.CODE_EXPIRED, SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED)
                if (endedEmailVerification) {
                    reset(outcome.state, "邮箱验证状态已变化，请重新开始。")
                } else if (issue == SettingsPasswordIssue.CODE_EXPIRED &&
                    (step == PasswordPageStep.RECOVERY_CODE || form.mode == PasswordPageMode.LOGIN_EMAIL)) {
                    reset(outcome.state ?: currentSecurity.password, issueText(issue))
                } else if ((issue == SettingsPasswordIssue.PASSWORD_INVALID && step != PasswordPageStep.CURRENT_PASSWORD) ||
                    issue == SettingsPasswordIssue.PASSWORD_CHANGED || issue == SettingsPasswordIssue.STATE_CHANGED) {
                    reset(outcome.state ?: currentSecurity.password, issueText(issue))
                } else form.error = issueText(issue)
            } else if (outcome != null && result.error == null) accepted(outcome)
            else form.error = result.error?.let(::settingsErrorText) ?: "操作未完成，请重试。"
        }
    }
    fun passwordChanged(outcome: SettingsPasswordOutcome) {
        val value = outcome.state ?: run {
            form.error = "未能读取实际两步验证状态，请清空输入并刷新。"
            return
        }
        val pending = value.recoveryEmailCodeInfo
        if (pending != null) {
            form.code = TextFieldValue()
            form.codePattern = pending.pattern
            form.codeLength = pending.length
            form.move(PasswordPageStep.EMAIL_CODE)
        } else reset(value, "两步验证设置已更新。")
    }
    fun savePassword(includeEmail: Boolean) {
        if (form.mode == PasswordPageMode.RECOVER) {
            request(SettingsPasswordCommand(SettingsPasswordOperation.RECOVER_PASSWORD,
                newPassword = form.newPassword.text, hint = form.hint.text, code = form.code.text), ::passwordChanged)
        } else request(SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD,
            currentPassword = form.currentPassword.text, newPassword = form.newPassword.text, hint = form.hint.text,
            email = if (includeEmail) form.email.text else "", setRecoveryEmail = includeEmail), ::passwordChanged)
    }
    fun saveEmail() {
        if (form.mode == PasswordPageMode.LOGIN_EMAIL) {
            request(SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL, email = form.email.text)) { outcome ->
                outcome.codeInfo?.let {
                    form.codePattern = it.pattern
                    form.codeLength = it.length
                    form.code = TextFieldValue()
                    form.move(PasswordPageStep.EMAIL_CODE)
                } ?: run { form.error = "登录邮箱验证未开始，请刷新后重试。" }
            }
        } else if (form.mode == PasswordPageMode.EMAIL) {
            request(SettingsPasswordCommand(SettingsPasswordOperation.SET_RECOVERY_EMAIL,
                currentPassword = form.currentPassword.text, email = form.email.text), ::passwordChanged)
        } else savePassword(includeEmail = true)
    }
    fun submitCode(text: String) {
        if (text.isBlank()) return
        if (form.step == PasswordPageStep.RECOVERY_CODE) {
            request(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_RECOVERY_CODE, code = text)) { outcome ->
                if (outcome.verified) {
                    form.mode = PasswordPageMode.RECOVER
                    form.newPassword = TextFieldValue()
                    form.confirmation = TextFieldValue()
                    form.move(PasswordPageStep.NEW_PASSWORD)
                } else form.error = "恢复码尚未通过验证。"
            }
        } else if (form.mode == PasswordPageMode.LOGIN_EMAIL) {
            request(SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL, code = text)) { outcome ->
                if (outcome.verified) reset(outcome.state ?: currentSecurity.password, "登录邮箱已更新。")
                else form.error = "登录邮箱尚未确认，请检查验证码。"
            }
        } else {
            val effectivePassword = if (form.mode == PasswordPageMode.CREATE || form.mode == PasswordPageMode.CHANGE)
                form.newPassword.text else form.currentPassword.text
            request(SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_RECOVERY_EMAIL,
                currentPassword = effectivePassword, email = form.email.text, code = text)) { outcome ->
                val value = outcome.state ?: currentSecurity.password
                if (value?.recoveryEmailCodeInfo != null) form.error = "验证码尚未被接受，请重试。"
                else if (outcome.verified) reset(value, "恢复邮箱已确认。")
                else reset(value, "验证状态已更新，请检查当前两步验证设置。")
            }
        }
    }
    fun requestRecovery() {
        request(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY)) { outcome ->
            val info = outcome.codeInfo
            if (info != null) {
                form.currentPassword = TextFieldValue()
                form.code = TextFieldValue()
                form.codePattern = info.pattern
                form.codeLength = info.length
                form.mode = PasswordPageMode.RECOVER
                form.move(PasswordPageStep.RECOVERY_CODE)
            } else form.error = "恢复码尚未发送，请重试。"
        }
    }
    fun applyReset(outcome: SettingsPasswordOutcome) {
        val resetResult = outcome.reset
        when (resetResult?.kind) {
            SettingsPasswordResetKind.OK -> reset(outcome.state ?: currentSecurity.password, "密码重置状态已更新。")
            SettingsPasswordResetKind.PENDING -> {
                form.resetDate = resetResult.date
                form.resetDeclined = false
                form.notice = "重置申请已提交。到期后仍需在此请求完成重置。"
                form.code = TextFieldValue()
                form.currentPassword = TextFieldValue()
                form.move(PasswordPageStep.CURRENT_PASSWORD)
            }
            SettingsPasswordResetKind.DECLINED -> {
                form.resetDate = resetResult.date
                form.resetDeclined = true
                form.notice = "暂不能重置密码，请在原生允许的时间后重试。"
                form.code = TextFieldValue()
                form.currentPassword = TextFieldValue()
                form.move(PasswordPageStep.CURRENT_PASSWORD)
            }
            null -> form.error = "重置状态尚未读取，请刷新。"
        }
    }

    DisposableEffect(form) { onDispose { form.clear() } }
    LaunchedEffect(active) { if (!active) form.clear() }
    LaunchedEffect(state, active) {
        if (active && state != null && !form.initialized) initialize(state)
        else if (active && state != null && !state.hasPassword && form.currentPassword.text.isNotEmpty() &&
            !form.busy && !security.busy && form.mode != PasswordPageMode.CREATE) {
            reset(state, "账号密码状态已变化，请重新进入。")
        }
    }
    LaunchedEffect(form.activity, active) {
        if (active && form.initialized) {
            delay(10 * 60 * 1000L)
            // Retiring the native owner also stops a delayed request chain from reusing its secret.
            form.clear()
            actions.closeSecurity(security.token)
        }
    }
    LaunchedEffect(form.resetDate, active) {
        if (active && form.resetDate > 0) {
            now = Clock.System.now().epochSeconds
            while (now < form.resetDate) {
                delay(minOf(60_000L, (form.resetDate.toLong() - now) * 1000L))
                now = Clock.System.now().epochSeconds
            }
        }
    }
    form.notice?.let { SettingsNote(it) }
    form.error?.let { SettingsNote(it, error = true) }
    if (state == null) {
        if (!security.loading) SettingsAction("读取两步验证状态", enabled = active && !security.busy) {
            actions.refreshSecurity(security.token)
        }
        return
    }
    SettingsGroup(when (form.step) {
        PasswordPageStep.INTRO -> "保护账号"
        PasswordPageStep.CURRENT_PASSWORD -> "输入当前密码"
        PasswordPageStep.MANAGE -> "管理两步验证"
        PasswordPageStep.NEW_PASSWORD -> if (form.mode == PasswordPageMode.RECOVER) "设置恢复后的密码" else "设置密码"
        PasswordPageStep.HINT -> "密码提示"
        PasswordPageStep.EMAIL -> if (form.mode == PasswordPageMode.LOGIN_EMAIL) "登录邮箱" else "恢复邮箱"
        PasswordPageStep.EMAIL_CODE -> if (form.mode == PasswordPageMode.LOGIN_EMAIL) "确认登录邮箱" else "确认恢复邮箱"
        PasswordPageStep.RECOVERY_CODE -> "密码恢复码"
    }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (form.step) {
                PasswordPageStep.INTRO -> {
                    SettingsNote("设置额外密码。此后在新设备登录时，除验证码外还需要输入该密码。")
                    SettingsAction("创建密码", enabled) {
                        form.mode = PasswordPageMode.CREATE
                        form.move(PasswordPageStep.NEW_PASSWORD)
                    }
                }
                PasswordPageStep.CURRENT_PASSWORD -> {
                    if (state.passwordHint.isNotBlank()) SettingsNote("密码提示：${state.passwordHint}")
                    SecurityPasswordField(form.currentPassword, { form.currentPassword = it; form.touch() }, "当前密码", enabled)
                    ChatButton("继续", Modifier.fillMaxWidth(), enabled && form.currentPassword.text.isNotEmpty(), primary = true) {
                        request(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_CURRENT,
                            currentPassword = form.currentPassword.text)) { outcome ->
                            if (outcome.verified) {
                                form.mode = PasswordPageMode.CHANGE
                                form.move(PasswordPageStep.MANAGE)
                            }
                            else form.error = "密码尚未通过验证。"
                        }
                    }
                    SettingsAction("忘记密码？", enabled) {
                        if (state.hasRecoveryEmail) requestRecovery()
                        else form.confirmationAction = PasswordConfirmation.RESET
                    }
                    if (state.hasRecoveryEmail) SettingsAction("无法使用恢复邮箱", enabled) {
                        form.confirmationAction = PasswordConfirmation.RESET
                    }
                    if (form.resetDate > 0) {
                        SettingsNote("${if (form.resetDeclined) "可重试时间" else "等待重置时间"}：${messageTime(form.resetDate)}")
                        SettingsAction("请求完成密码重置", enabled && now >= form.resetDate) { form.confirmationAction = PasswordConfirmation.RESET }
                        if (!form.resetDeclined) SettingsAction("取消密码重置申请", enabled) {
                            form.confirmationAction = PasswordConfirmation.CANCEL_RESET
                        }
                    }
                }
                PasswordPageStep.MANAGE -> {
                    SettingsRow("两步验证", "已开启")
                    SettingsRow("恢复邮箱", if (state.hasRecoveryEmail) "已设置" else "未设置")
                    SettingsAction("更改密码", enabled) {
                        form.mode = PasswordPageMode.CHANGE
                        form.newPassword = TextFieldValue()
                        form.confirmation = TextFieldValue()
                        form.hint = TextFieldValue()
                        form.move(PasswordPageStep.NEW_PASSWORD)
                    }
                    SettingsAction(if (state.hasRecoveryEmail) "更改恢复邮箱" else "添加恢复邮箱", enabled) {
                        form.mode = PasswordPageMode.EMAIL
                        form.email = TextFieldValue()
                        form.move(PasswordPageStep.EMAIL)
                    }
                    if (state.hasRecoveryEmail) SettingsAction("移除恢复邮箱", enabled, destructive = true) {
                        form.confirmationAction = PasswordConfirmation.REMOVE_EMAIL
                    }
                    if (state.loginEmailSupported) SettingsAction("更改登录邮箱", enabled) {
                        form.mode = PasswordPageMode.LOGIN_EMAIL
                        form.email = TextFieldValue()
                        form.move(PasswordPageStep.EMAIL)
                    }
                    SettingsAction("关闭两步验证", enabled, destructive = true) { form.confirmationAction = PasswordConfirmation.DISABLE }
                    SettingsAction("结束本次管理", enabled) { reset(state) }
                }
                PasswordPageStep.NEW_PASSWORD -> {
                    SecurityPasswordField(form.newPassword, { form.newPassword = it; form.touch() }, "新密码", enabled,
                        next = { confirmFocus.requestFocus() })
                    SecurityPasswordField(form.confirmation, { form.confirmation = it; form.touch() }, "再次输入新密码", enabled,
                        modifier = Modifier.focusRequester(confirmFocus))
                    val passwordsMatch = form.newPassword.text.isNotEmpty() && form.newPassword.text == form.confirmation.text
                    if (form.confirmation.text.isNotEmpty() && !passwordsMatch) SettingsNote("两次输入的密码不一致。", error = true)
                    SecurityPageActions(enabled, passwordsMatch, onBack = { reset(state) }, onContinue = {
                        form.confirmation = TextFieldValue()
                        form.move(PasswordPageStep.HINT)
                    })
                    if (form.mode == PasswordPageMode.RECOVER) SettingsAction("恢复并移除两步验证密码", enabled, destructive = true) {
                        form.confirmationAction = PasswordConfirmation.RECOVER_REMOVE
                    }
                }
                PasswordPageStep.HINT -> {
                    TextField(form.hint, { form.hint = it; form.touch() }, label = "密码提示", enabled = enabled,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    SettingsNote("选择能帮助你记起密码的提示，不要直接填写密码。")
                    fun afterHint() {
                        if (form.mode == PasswordPageMode.RECOVER) savePassword(includeEmail = false)
                        else {
                            form.email = TextFieldValue()
                            form.move(PasswordPageStep.EMAIL)
                        }
                    }
                    SecurityPageActions(enabled, form.hint.text.isNotBlank() && form.hint.text != form.newPassword.text,
                        onBack = { form.move(PasswordPageStep.NEW_PASSWORD) }, onContinue = { afterHint() })
                    SettingsAction("跳过提示", enabled) { form.hint = TextFieldValue(); afterHint() }
                }
                PasswordPageStep.EMAIL -> {
                    SettingsNote(if (form.mode == PasswordPageMode.LOGIN_EMAIL) "使用新的登录邮箱接收 Telegram 登录验证码。"
                        else "填写可以访问的邮箱，以便忘记密码时恢复账号。")
                    TextField(form.email, { form.email = it; form.touch() }, label = "邮箱地址", enabled = enabled,
                        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    SecurityPageActions(enabled, form.email.text.isNotBlank(), onBack = { reset(state) }, onContinue = { saveEmail() })
                    if (form.mode == PasswordPageMode.CREATE || form.mode == PasswordPageMode.CHANGE) {
                        SettingsAction(if (state.hasRecoveryEmail) "保留当前恢复邮箱" else "跳过恢复邮箱", enabled) {
                            if (state.hasRecoveryEmail) savePassword(includeEmail = false)
                            else form.confirmationAction = PasswordConfirmation.SKIP_EMAIL
                        }
                    }
                }
                PasswordPageStep.EMAIL_CODE, PasswordPageStep.RECOVERY_CODE -> {
                    SettingsNote("验证码已发送至 ${form.codePattern.ifBlank { "你的邮箱" }}。")
                    TextField(form.code, { value ->
                        val previousValue = form.code
                        form.code = value
                        form.touch()
                        if ((value.text != previousValue.text || previousValue.composition != null) && value.composition == null && form.codeLength > 0 &&
                            value.text.length == form.codeLength) submitCode(value.text)
                    }, label = if (form.step == PasswordPageStep.RECOVERY_CODE) "恢复码" else "验证码", enabled = enabled,
                        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    SecurityPageActions(enabled, form.code.text.isNotBlank(), continueLabel = "验证",
                        onBack = {
                            if (form.step == PasswordPageStep.EMAIL_CODE) actions.closeSecurity(security.token)
                            else reset(state)
                        }, onContinue = { submitCode(form.code.text) })
                    if (form.step == PasswordPageStep.RECOVERY_CODE) SettingsAction("无法访问恢复邮箱", enabled) {
                        form.confirmationAction = PasswordConfirmation.RESET
                    } else {
                        SettingsAction("重新发送验证码", enabled) {
                            val operation = if (form.mode == PasswordPageMode.LOGIN_EMAIL) SettingsPasswordOperation.RESEND_LOGIN_EMAIL
                                else SettingsPasswordOperation.RESEND_RECOVERY_EMAIL
                            request(SettingsPasswordCommand(operation)) { outcome ->
                                val info = outcome.codeInfo ?: outcome.state?.recoveryEmailCodeInfo
                                if (info != null) {
                                    form.code = TextFieldValue()
                                    form.codePattern = info.pattern
                                    form.codeLength = info.length
                                    form.notice = "验证码已重新发送。"
                                } else reset(outcome.state, "验证状态已变化，请重新开始。")
                            }
                        }
                        if (form.mode != PasswordPageMode.LOGIN_EMAIL) SettingsAction("取消恢复邮箱设置", enabled, destructive = true) {
                            form.confirmationAction = PasswordConfirmation.CANCEL_EMAIL
                        }
                    }
                }
            }
        }
    }
    SettingsAction("清空输入并刷新状态", enabled = active && !security.loading && !security.busy && !form.busy) {
        form.clear()
        actions.refreshSecurity(security.token)
    }
    val confirmation = form.confirmationAction
    SecurityConfirmation(show = active && confirmation != null, title = when (confirmation) {
        PasswordConfirmation.DISABLE -> "关闭两步验证？"
        PasswordConfirmation.REMOVE_EMAIL -> "移除恢复邮箱？"
        PasswordConfirmation.SKIP_EMAIL -> "跳过恢复邮箱？"
        PasswordConfirmation.CANCEL_EMAIL -> "取消恢复邮箱设置？"
        PasswordConfirmation.RESET -> "申请或完成密码重置？"
        PasswordConfirmation.CANCEL_RESET -> "取消密码重置？"
        PasswordConfirmation.RECOVER_REMOVE -> "恢复并移除密码？"
        null -> "确认操作"
    }, maxWidth = 420.dp, onDismissRequest = { form.confirmationAction = null }) {
        SecurityDialogBody {
            SettingsNote(when (confirmation) {
                PasswordConfirmation.DISABLE, PasswordConfirmation.RECOVER_REMOVE -> "关闭后，新设备登录将不再要求两步验证密码。"
                PasswordConfirmation.REMOVE_EMAIL, PasswordConfirmation.SKIP_EMAIL -> "没有恢复邮箱时，忘记密码可能需要等待重置，且可能无法恢复受密码保护的数据。"
                PasswordConfirmation.CANCEL_EMAIL -> "这将取消服务器正在等待的邮箱验证。普通返回不会取消它。"
                PasswordConfirmation.RESET -> "Telegram 将决定是否需要等待、允许重置或要求稍后重试。到期后仍需再次请求，不会在本机自动完成。"
                PasswordConfirmation.CANCEL_RESET -> "这将取消账号当前的密码重置申请。"
                null -> ""
            })
            if (state.hasPassportData && confirmation in listOf(PasswordConfirmation.DISABLE, PasswordConfirmation.RECOVER_REMOVE,
                    PasswordConfirmation.RESET)) SettingsNote("该账号含 Telegram Passport 数据；移除或重置密码可能使受保护数据无法恢复。")
            if (confirmation == PasswordConfirmation.RESET && form.resetDate > now)
                SettingsNote("可再次请求时间：${messageTime(form.resetDate)}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { form.confirmationAction = null })
                ChatButton("确认", Modifier.weight(1f), enabled = enabled && confirmation != null &&
                    (confirmation != PasswordConfirmation.RESET || form.resetDate <= now), primary = true) {
                    form.confirmationAction = null
                    when (confirmation) {
                        PasswordConfirmation.DISABLE -> request(SettingsPasswordCommand(SettingsPasswordOperation.REMOVE_PASSWORD,
                            currentPassword = form.currentPassword.text), ::passwordChanged)
                        PasswordConfirmation.REMOVE_EMAIL -> request(SettingsPasswordCommand(SettingsPasswordOperation.SET_RECOVERY_EMAIL,
                            currentPassword = form.currentPassword.text, email = ""), ::passwordChanged)
                        PasswordConfirmation.SKIP_EMAIL -> savePassword(includeEmail = false)
                        PasswordConfirmation.CANCEL_EMAIL -> request(SettingsPasswordCommand(SettingsPasswordOperation.CANCEL_RECOVERY_EMAIL)) {
                            reset(it.state, "恢复邮箱设置已取消。")
                        }
                        PasswordConfirmation.RESET -> request(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RESET), ::applyReset)
                        PasswordConfirmation.CANCEL_RESET -> request(SettingsPasswordCommand(SettingsPasswordOperation.CANCEL_RESET)) {
                            reset(it.state, "密码重置申请已取消。")
                        }
                        PasswordConfirmation.RECOVER_REMOVE -> request(SettingsPasswordCommand(SettingsPasswordOperation.RECOVER_PASSWORD,
                            code = form.code.text, newPassword = ""), ::passwordChanged)
                        null -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsWebsitesPage(security: SettingsSecuritySnapshot, actions: SettingsActions, active: Boolean, writesAllowed: Boolean) {
    var selected by remember(security.token) { mutableStateOf<SettingsWebsite?>(null) }
    var blockBot by remember(security.token) { mutableStateOf(false) }
    var confirmAll by remember(security.token) { mutableStateOf(false) }
    var submitting by remember(security.token) { mutableStateOf(false) }
    var notice by remember(security.token) { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(security)
    val currentlyActive by rememberUpdatedState(active)
    val enabled = writesAllowed && active && !security.loading && !security.busy && !submitting
    val target = selected?.takeIf { chosen -> security.websites.any {
        it.id == chosen.id && it.domain == chosen.domain && it.botUserId == chosen.botUserId
    } }
    LaunchedEffect(active, security.loading) {
        if (!active || security.loading) { selected = null; confirmAll = false; blockBot = false }
    }
    notice?.let { SettingsNote(it) }
    SettingsGroup("网站授权") {
        if (security.websites.isEmpty() && !security.loading && security.error == null) SettingsRow("尚未连接任何网站")
        security.websitesTotalCount?.let { SettingsRow("已连接网站", "$it 个") }
        if (security.websitesTotalCount != null && security.websitesTotalCount > security.websites.size)
            SettingsNote("当前显示 ${security.websites.size} 个网站，原生总数为 ${security.websitesTotalCount}。")
        security.websites.sortedByDescending { if (it.lastActiveDate > 0) it.lastActiveDate else it.logInDate }.forEach { website ->
            SettingsRow(website.domain, summary = listOf(
                listOf(website.browser, website.platform).filter(String::isNotBlank).joinToString(" · "),
                listOf(website.ipAddress, website.location).filter(String::isNotBlank).joinToString(" · "),
                (website.lastActiveDate.takeIf { it > 0 } ?: website.logInDate).takeIf { it > 0 }
                    ?.let { "最后活动：${messageTime(it)}" }.orEmpty(),
            ).filter(String::isNotBlank).joinToString("\n"))
            SettingsAction("断开 ${website.domain}", enabled, destructive = true) {
                selected = website
                blockBot = false
            }
        }
        if (security.websites.isNotEmpty()) SettingsAction("断开所有网站", enabled, destructive = true) { confirmAll = true }
    }
    SecurityConfirmation(show = active && target != null && !security.loading, title = "断开网站授权？", summary = target?.domain,
        maxWidth = 420.dp, onDismissRequest = { selected = null }) {
        SecurityDialogBody {
            SettingsNote("该网站将无法继续使用此次 Telegram 登录授权。")
            if (target != null && target.botUserId > 0) SettingsToggle("同时屏蔽该网站的机器人", blockBot, enabled) { blockBot = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { selected = null })
                ChatButton("断开授权", Modifier.weight(1f), enabled && target != null, primary = true) {
                    val chosen = target ?: return@ChatButton
                    if (!currentlyActive || latest.error == SettingsError.RESULT_UNKNOWN || submitting || latest.token != security.token || latest.busy || latest.loading ||
                        latest.websites.none { it.id == chosen.id && it.domain == chosen.domain && it.botUserId == chosen.botUserId }) return@ChatButton
                    submitting = true
                    val owner = security.token
                    actions.disconnectWebsite(owner, chosen, blockBot && chosen.botUserId > 0) { result ->
                        if (!currentlyActive || latest.token != owner) return@disconnectWebsite
                        submitting = false
                        notice = when {
                            result.value?.disconnected == true && result.value.botBlocked == false -> "网站授权已断开，机器人未被屏蔽。"
                            result.value?.disconnected == true && result.error != null -> "网站授权已断开，列表刷新失败，请刷新列表。"
                            result.value?.disconnected == true -> "网站授权已断开。"
                            else -> result.error?.let(::settingsErrorText) ?: "网站授权未能断开，请重试。"
                        }
                    }
                    selected = null
                }
            }
        }
    }
    SecurityConfirmation(show = active && confirmAll && security.websites.isNotEmpty() && !security.loading,
        title = "断开所有网站授权？", maxWidth = 420.dp, onDismissRequest = { confirmAll = false }) {
        SecurityDialogBody {
            SettingsNote("所有已连接网站都需要重新使用 Telegram 登录。此操作不会屏蔽任何机器人。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { confirmAll = false })
                ChatButton("全部断开", Modifier.weight(1f), enabled, primary = true) {
                    if (!currentlyActive || latest.error == SettingsError.RESULT_UNKNOWN || submitting || latest.token != security.token || latest.busy || latest.loading) return@ChatButton
                    submitting = true
                    val owner = security.token
                    actions.disconnectAllWebsites(owner) { result ->
                        if (!currentlyActive || latest.token != owner) return@disconnectAllWebsites
                        submitting = false
                        notice = when {
                            result.value != null && result.error == null -> "所有网站授权已断开。"
                            result.value != null -> "所有网站授权已断开，列表刷新失败，请刷新列表。"
                            else -> result.error?.let(::settingsErrorText) ?: "网站授权未能全部断开，请重试。"
                        }
                    }
                    confirmAll = false
                }
            }
        }
    }
}

@Composable
private fun SettingsBlockedPage(security: SettingsSecuritySnapshot, actions: SettingsActions, active: Boolean, writesAllowed: Boolean) {
    var selected by remember(security.token) { mutableStateOf<SettingsBlockedSender?>(null) }
    var submitting by remember(security.token) { mutableStateOf(false) }
    var notice by remember(security.token) { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(security)
    val currentlyActive by rememberUpdatedState(active)
    val latestWritesAllowed by rememberUpdatedState(writesAllowed)
    val pageOwner = remember(security.token) { SettingsBlockedUiOwner() }
    DisposableEffect(pageOwner) { onDispose { pageOwner.alive = false } }
    val enabled = active && !security.loading && !security.busy && !security.blockedLoadingMore && !submitting
    val mutationEnabled = writesAllowed && enabled
    val target = selected?.takeIf { chosen -> security.blocked.any { it.id == chosen.id && it.isChat == chosen.isChat } }
    LaunchedEffect(active, security.loading) { if (!active || security.loading) selected = null }
    notice?.let { SettingsNote(it) }
    SettingsGroup("黑名单") {
        SettingsAction("添加用户", mutationEnabled && security.blockedPicker == null) {
            if (!pageOwner.alive || !currentlyActive || !latestWritesAllowed || submitting ||
                latest.token != security.token || latest.busy || latest.loading || latest.blockedLoadingMore ||
                latest.error == SettingsError.RESULT_UNKNOWN || latest.blockedPicker != null) return@SettingsAction
            notice = null
            actions.openBlockedPicker(security.token)
        }
        security.blockedTotal?.let { SettingsRow("已屏蔽", "$it 个用户或聊天") }
        if (security.blocked.isEmpty() && !security.loading && security.error == null) SettingsRow("黑名单为空")
        security.blocked.forEach { sender ->
            SettingsRow(sender.title.ifBlank { if (sender.isChat) "聊天 ${sender.id}" else "用户 ${sender.id}" },
                if (sender.isChat) "聊天" else "用户")
            SettingsAction("解除屏蔽", mutationEnabled) { selected = sender }
        }
        if (security.blockedLoadingMore) SettingsNote("正在加载更多……")
        if (security.blockedLimitReached) SettingsNote("已达到本次列表容量，请刷新或使用官方客户端查看其余内容。")
        else if (!security.blockedEnd) SettingsAction("加载更多", enabled) {
            if (currentlyActive && latest.token == security.token && !latest.busy && !latest.loading && !latest.blockedLoadingMore)
                actions.loadMoreBlocked(security.token)
        }
    }
    security.blockedPicker?.let { picker ->
        SettingsBlockedPickerUi(picker, actions, active = active,
            enabled = mutationEnabled, onChoose = { chosen ->
                val currentPicker = latest.blockedPicker
                if (!pageOwner.alive || !currentlyActive || !latestWritesAllowed || submitting || latest.busy || latest.loading ||
                    latest.token != security.token || latest.error == SettingsError.RESULT_UNKNOWN ||
                    currentPicker == null || currentPicker.token != picker.token || currentPicker.query != picker.query || currentPicker.loading ||
                    currentPicker.entries.none { it.userId == chosen.userId && !it.blocked }) false
                else {
                    submitting = true
                    val owner = security.token
                    actions.blockPickerUser(picker.token, chosen.userId) { result ->
                        // Closing the picker retires only its reads. Its parent's admitted write may finish later.
                        if (pageOwner.alive && currentlyActive && latest.token == owner) {
                            submitting = false
                            notice = when {
                                result.value != null && result.error == null -> "已屏蔽用户。"
                                result.value != null -> "已屏蔽用户，列表刷新失败，请刷新列表。"
                                else -> result.error?.let(::settingsErrorText) ?: "屏蔽未完成，请重新添加用户。"
                            }
                        }
                    }
                    true
                }
            })
    }
    SecurityConfirmation(show = active && target != null && !security.loading, title = "解除屏蔽？",
        summary = target?.title?.takeIf(String::isNotBlank), maxWidth = 420.dp, onDismissRequest = { selected = null }) {
        SecurityDialogBody {
            SettingsNote("解除屏蔽后，对方将可以再次向你发送消息。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { selected = null })
                ChatButton("解除屏蔽", Modifier.weight(1f), mutationEnabled && target != null, primary = true) {
                    val chosen = target ?: return@ChatButton
                    if (!currentlyActive || latest.error == SettingsError.RESULT_UNKNOWN || submitting || latest.token != security.token || latest.busy || latest.loading ||
                        latest.blocked.none { it.id == chosen.id && it.isChat == chosen.isChat }) return@ChatButton
                    submitting = true
                    val owner = security.token
                    actions.unblockSecurity(owner, chosen) { result ->
                        if (!currentlyActive || latest.token != owner) return@unblockSecurity
                        submitting = false
                        notice = when {
                            result.value != null && result.error == null -> "已解除屏蔽。"
                            result.value != null -> "已解除屏蔽，列表刷新失败，请刷新列表。"
                            else -> result.error?.let(::settingsErrorText) ?: "解除屏蔽未完成，请重试。"
                        }
                    }
                    selected = null
                }
            }
        }
    }
}

/** Every open/confirmation is a distinct owner, even when the same session is selected again. */
private class SessionSelection(val session: SettingsSession)
private class SessionInteractionOwner { var alive = true }

/** Optional offline observer of the same application callbacks supplied to the controls. */
internal class SettingsSessionUiCallbacks(
    val openRows: Map<Long, () -> Unit>,
    val closeDetails: (() -> Unit)?,
    val terminateDetails: (() -> Unit)?,
    val confirmTermination: (() -> Unit)?,
)
internal val LocalSettingsSessionUiCallbacks = staticCompositionLocalOf<((SettingsSessionUiCallbacks) -> Unit)?> { null }

private class SessionsPageOwner {
    var details by mutableStateOf<SessionSelection?>(null)
    var confirmation by mutableStateOf<SessionSelection?>(null)
    var submitting by mutableStateOf(false)
    var submittedTarget by mutableStateOf<SettingsSession?>(null)
    var notice by mutableStateOf<String?>(null)
    private var revision = 0L
    private var pendingRequest: Long? = null

    fun showDetails(session: SettingsSession) {
        revision++
        details = SessionSelection(session)
        confirmation = null
        notice = null
    }

    fun confirm(session: SettingsSession) {
        revision++
        details = null
        confirmation = SessionSelection(session)
        notice = null
    }

    fun clearSelections() {
        if (details != null || confirmation != null) revision++
        details = null
        confirmation = null
    }

    fun begin(target: SettingsSession): Long {
        submitting = true
        submittedTarget = target
        return (++revision).also { pendingRequest = it }
    }

    /** A newer selection suppresses the old notice but must still release its own submission gate. */
    fun finish(request: Long): Boolean {
        if (pendingRequest != request) return false
        pendingRequest = null
        submitting = false
        submittedTarget = null
        return revision == request
    }

    fun invalidateRequest(request: Long) {
        if (pendingRequest == request) invalidateSubmission()
    }

    fun invalidateSubmission() {
        revision++
        pendingRequest = null
        submittedTarget = null
        submitting = false
    }

    fun retire() {
        revision++
        details = null
        confirmation = null
        pendingRequest = null
        submitting = false
        submittedTarget = null
        notice = null
    }
}

/** Last activity may advance normally; the rest is the frozen device and authorization identity. */
private fun SettingsSession.sameDetailTarget(other: SettingsSession): Boolean = matchesDevice(other) &&
    logInDate == other.logInDate && isPasswordPending == other.isPasswordPending && isUnconfirmed == other.isUnconfirmed

private fun SettingsSession.deviceName() = deviceModel.ifBlank { applicationName.ifBlank { "设备" } }
private fun SettingsSession.applicationDescription() =
    listOf(applicationName, applicationVersion).filter(String::isNotBlank).joinToString(" ").ifBlank { "未知" }
private fun SettingsSession.systemDescription() =
    listOf(platform, systemVersion).filter(String::isNotBlank).joinToString(" ").ifBlank { "未知" }
private fun SettingsSession.authorizationDescription() = buildList {
    add(if (isCurrent) "当前设备" else "其他设备")
    if (isPasswordPending) add("等待两步验证")
    if (isUnconfirmed) add("尚未从其他会话确认")
}.joinToString(" · ")

@Composable
private fun SettingsSessionsPage(security: SettingsSecuritySnapshot, actions: SettingsActions, active: Boolean, writesAllowed: Boolean) {
    val page = remember(security.token) { SessionsPageOwner() }
    val pageScope = rememberCoroutineScope()
    val interaction = remember(security.token, active) { SessionInteractionOwner() }
    val rowIdentity = security.sessions.map { it.copy(lastActiveDate = 0) }
    val rows = remember(interaction, security.loading, rowIdentity) { SessionInteractionOwner() }
    val latest by rememberUpdatedState(security)
    val currentlyActive by rememberUpdatedState(active)
    val selectable = active && !security.loading && !security.busy && !security.sessionsRefreshRequired
    val mutationEnabled = selectable && writesAllowed && !page.submitting
    val detailsSelection = page.details
    val details = detailsSelection?.let { chosen -> security.sessions.firstOrNull(chosen.session::sameDetailTarget) }
    val confirmationSelection = page.confirmation
    val target = confirmationSelection?.let { chosen ->
        security.sessions.firstOrNull { chosen.session.sameDetailTarget(it) && !it.isCurrent }
    }
    DisposableEffect(interaction) { onDispose { interaction.alive = false; page.retire() } }
    DisposableEffect(rows) { onDispose { rows.alive = false } }
    LaunchedEffect(active, security.loading, security.sessions) {
        if (!active || security.loading) page.clearSelections()
        else {
            if (page.details != null && details == null) page.clearSelections()
            if (page.confirmation != null && target == null) page.clearSelections()
            val submitted = page.submittedTarget
            val replacement = submitted?.let { chosen -> security.sessions.firstOrNull { it.id == chosen.id } }
            if (submitted != null && replacement != null &&
                (!submitted.sameDetailTarget(replacement) || replacement.isCurrent)) page.invalidateSubmission()
        }
    }
    val rowOpens = security.sessions.map { session ->
        val open: () -> Unit = {
            if (interaction.alive && rows.alive && currentlyActive && latest.token == security.token && !latest.loading && !latest.busy &&
                !latest.sessionsRefreshRequired && latest.sessions.any(session::sameDetailTarget)) page.showDetails(session)
        }
        session to open
    }
    val closeDetails: () -> Unit = {
        if (detailsSelection != null && interaction.alive && page.details === detailsSelection) page.clearSelections()
    }
    val terminateDetails: () -> Unit = {
        val shown = details
        if (shown != null && detailsSelection != null && interaction.alive && page.details === detailsSelection &&
            currentlyActive && latest.error != SettingsError.RESULT_UNKNOWN && latest.token == security.token && !latest.loading && !latest.busy &&
            !latest.sessionsRefreshRequired && !page.submitting &&
            latest.sessions.any { shown.sameDetailTarget(it) && !it.isCurrent }) page.confirm(shown)
    }
    val cancelTermination: () -> Unit = {
        if (confirmationSelection != null && interaction.alive && page.confirmation === confirmationSelection) page.clearSelections()
    }
    val confirmTermination: () -> Unit = confirm@ {
        val chosen = target ?: return@confirm
        if (!interaction.alive || confirmationSelection == null || page.confirmation !== confirmationSelection || !currentlyActive ||
            page.submitting || latest.error == SettingsError.RESULT_UNKNOWN || latest.token != security.token || latest.busy || latest.loading ||
            latest.sessionsRefreshRequired || latest.sessions.none { chosen.sameDetailTarget(it) && !it.isCurrent }) return@confirm
        val request = page.begin(chosen)
        val owner = security.token
        page.confirmation = null
        actions.terminateSecuritySession(owner, chosen.id) { result ->
            // TD callbacks may arrive on the engine actor; owner state is UI-confined.
            pageScope.launch {
                if (!interaction.alive || !currentlyActive || latest.token != owner) return@launch
                val replacement = latest.sessions.firstOrNull { it.id == chosen.id }
                if (replacement != null && (!chosen.sameDetailTarget(replacement) || replacement.isCurrent)) {
                    page.invalidateRequest(request)
                    return@launch
                }
                if (!page.finish(request)) return@launch
                page.notice = when {
                    result.value != null && result.error == null -> "会话已终止。"
                    result.value != null -> "会话已终止，列表刷新失败，请刷新列表。"
                    else -> result.error?.let(::settingsErrorText) ?: "终止会话未完成，请重试。"
                }
            }
        }
    }
    val callbackObserver = LocalSettingsSessionUiCallbacks.current
    val callbackPayload = if (callbackObserver == null) null else SettingsSessionUiCallbacks(
        rowOpens.associate { (session, open) -> session.id to open },
        closeDetails.takeIf { details != null && detailsSelection != null },
        terminateDetails.takeIf { writesAllowed && details != null && detailsSelection != null && !details.isCurrent },
        confirmTermination.takeIf { target != null && confirmationSelection != null },
    )
    SideEffect { if (callbackPayload != null) callbackObserver?.invoke(callbackPayload) }
    page.notice?.let { SettingsNote(it) }
    SettingsSessionCommonOptions(security, actions, active, page.submitting, writesAllowed)
    SettingsLocalDeviceNameControl(security, active)
    SettingsGroup("设备与会话") {
        if (security.sessions.isEmpty() && !security.loading && security.error == null) SettingsRow("没有其他已登录设备")
        rowOpens.forEach { (session, open) ->
            SettingsRow(session.deviceName(), if (session.isCurrent) "此设备" else null, summary = listOf(
                listOf(session.applicationName, session.applicationVersion).filter(String::isNotBlank).joinToString(" "),
                listOf(session.platform, session.systemVersion).filter(String::isNotBlank).joinToString(" "),
                listOf(session.ipAddress, session.location).filter(String::isNotBlank).joinToString(" · "),
                session.lastActiveDate.takeIf { it > 0 }?.let { "最后活动：${messageTime(it)}" }.orEmpty(),
            ).filter(String::isNotBlank).joinToString("\n"), enabled = selectable, onClick = open)
            if (!session.isCurrent) SettingsAction("终止此会话", mutationEnabled, destructive = true) {
                if (interaction.alive && rows.alive && currentlyActive && latest.error != SettingsError.RESULT_UNKNOWN && latest.token == security.token && !latest.loading && !latest.busy &&
                    !latest.sessionsRefreshRequired && !page.submitting &&
                    latest.sessions.any { session.sameDetailTarget(it) && !it.isCurrent }) page.confirm(session)
            }
        }
    }
    SecurityConfirmation(show = active && details != null && !security.loading, title = "会话详情",
        maxWidth = 420.dp, onDismissRequest = closeDetails) {
        val shown = details
        if (shown != null && detailsSelection != null)
            SettingsSessionDetailContent(shown, mutationEnabled, onClose = closeDetails, onTerminate = terminateDetails)
    }
    SecurityConfirmation(show = active && target != null && !security.loading, title = "终止此会话？",
        summary = target?.let { listOf(it.deviceModel, it.applicationName, it.location).filter(String::isNotBlank).joinToString(" · ") },
        maxWidth = 420.dp, onDismissRequest = cancelTermination) {
        SecurityDialogBody {
            SettingsNote("该设备将退出 Telegram，需要重新登录才能访问你的账号。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = cancelTermination)
                ChatButton("终止会话", Modifier.weight(1f), mutationEnabled && target != null, primary = true,
                    onClick = confirmTermination)
            }
        }
    }
}

/** Shared with the CPU-raster fixture; actions still pass through the page owner above. */
@Composable
internal fun SettingsSessionDetailContent(shown: SettingsSession, enabled: Boolean,
    onClose: () -> Unit, onTerminate: () -> Unit,
) {
    SecurityDialogBody {
        Text(shown.deviceName(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        SettingsNote(shown.authorizationDescription())
        SettingsRow("应用", summary = shown.applicationDescription())
        SettingsRow("系统", summary = shown.systemDescription())
        SettingsRow("登录时间", summary = messageTime(shown.logInDate).ifBlank { "未知" })
        SettingsRow("最后活动", summary = messageTime(shown.lastActiveDate).ifBlank { "未知" })
        SettingsRow("IP 地址", summary = shown.ipAddress.ifBlank { "未知" })
        SettingsRow("位置", summary = shown.location.ifBlank { "未知" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChatButton("关闭", Modifier.weight(1f), onClick = onClose)
            if (!shown.isCurrent) ChatButton("终止此会话", Modifier.weight(1f), enabled = enabled, onClick = onTerminate)
        }
    }
}

/** Miuix constrains the dialog window but does not scroll its caller-supplied content. */
@Composable
internal fun SecurityDialogBody(content: @Composable ColumnScope.() -> Unit) {
    val availableHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(Modifier.fillMaxWidth().heightIn(max = (availableHeight - 170.dp).coerceIn(96.dp, 420.dp))
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun SecurityPageActions(enabled: Boolean, canContinue: Boolean = true, continueLabel: String = "继续",
    onBack: () -> Unit, onContinue: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChatButton("返回", Modifier.weight(1f), enabled = enabled, onClick = onBack)
        ChatButton(continueLabel, Modifier.weight(1f), enabled = enabled && canContinue, primary = true, onClick = onContinue)
    }
}

@Composable
private fun SecurityPasswordField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String,
    enabled: Boolean, modifier: Modifier = Modifier, next: (() -> Unit)? = null) {
    TextField(value, onValueChange, label = label, enabled = enabled, singleLine = true,
        modifier = modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
            imeAction = if (next != null) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onNext = { next?.invoke() }))
}
