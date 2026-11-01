package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.status.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Official intro layout/choices, with Miuix controls and transient Compose-owned input. */
@Composable
internal fun AuthenticationLoginPage(
    login: LoginState,
    token: LoginToken,
    canSubmit: (LoginRequestKind) -> Boolean,
    onSubmit: (LoginToken, LoginRequestKind, String) -> Unit,
    onCancelQr: (LoginToken) -> Unit,
    initialPhoneMode: Boolean = false,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    qrPhoneSwitchAvailable: Boolean = true,
) {
    val challenge = login.challenge
    var phoneMode by remember(token.generation) { mutableStateOf(initialPhoneMode) }
    var countryPicker by remember(token.generation) { mutableStateOf(false) }
    var country by remember(token.generation) { mutableStateOf(loginCountries.first { it.iso == "US" }) }
    var countryCode by remember(token.generation) { mutableStateOf("+1") }
    var phone by remember(token.generation) { mutableStateOf(TextFieldValue()) }
    var recovery by remember(token.generation) { mutableStateOf(false) }
    var showPassword by remember(token) { mutableStateOf(false) }
    var confirmRecovery by remember(token) { mutableStateOf(false) }
    var input by remember(token) { mutableStateOf(TextFieldValue()) }
    var submitted by remember(token) { mutableStateOf(false) }
    var qrRequested by remember(token.generation) { mutableStateOf(false) }
    val busy = !active || login.pending || login.awaitingAuthorization || submitted
    val deviceConfirmation = challenge.otherStep == LoginOtherStep.DEVICE_CONFIRMATION
    val qr = (challenge.phase == AuthorizationPhase.WAITING_LOGIN && !phoneMode) || deviceConfirmation
    val currentSubmit by rememberUpdatedState(onSubmit)
    val currentCanSubmit by rememberUpdatedState(canSubmit)
    val currentActive by rememberUpdatedState(active)
    val currentQrPhoneSwitchAvailable by rememberUpdatedState(qrPhoneSwitchAvailable)
    val qrAllowed = canSubmit(LoginRequestKind.QR_CODE)

    LaunchedEffect(active, token.generation, challenge.phase, phoneMode, login.error, login.pending, qrAllowed) {
        if (active && challenge.phase == AuthorizationPhase.WAITING_LOGIN && !phoneMode && !qrRequested &&
            currentCanSubmit(LoginRequestKind.QR_CODE)) {
            qrRequested = true
            currentSubmit(token, LoginRequestKind.QR_CODE, "")
        }
    }
    LaunchedEffect(login.error, login.pending, login.awaitingAuthorization) {
        if (login.error != null && !login.pending) submitted = false
        if (login.awaitingAuthorization) input = TextFieldValue()
    }
    LaunchedEffect(challenge.presentation.recoveryEmailPattern) {
        if (!challenge.presentation.recoveryEmailPattern.isNullOrEmpty()) recovery = true
    }
    LaunchedEffect(token) {
        if (challenge.phase != AuthorizationPhase.WAITING_LOGIN && !deviceConfirmation) phoneMode = false
    }
    DisposableEffect(token.generation, active) {
        onDispose { phone = TextFieldValue(); input = TextFieldValue(); confirmRecovery = false }
    }
    SideEffect {
        if (!active) { phone = TextFieldValue(); input = TextFieldValue(); countryPicker = false; confirmRecovery = false; submitted = false }
    }
    LaunchedEffect(input.text, phone.text) {
        delay(10 * 60 * 1000L)
        input = TextFieldValue(); phone = TextFieldValue(); confirmRecovery = false
    }

    var resendLeft by remember(token) { mutableStateOf(challenge.resendTimeoutSeconds) }
    LaunchedEffect(token) { while (resendLeft > 0) { delay(1000); resendLeft-- } }
    val kind = when {
        qr -> null
        phoneMode || challenge.phase == AuthorizationPhase.WAITING_LOGIN -> LoginRequestKind.PHONE
        challenge.phase == AuthorizationPhase.WAITING_CODE && challenge.codeDelivery != LoginCodeDelivery.FIREBASE -> LoginRequestKind.CODE
        challenge.phase == AuthorizationPhase.WAITING_PASSWORD -> if (recovery) LoginRequestKind.PASSWORD_RECOVERY_CODE else LoginRequestKind.PASSWORD
        challenge.otherStep == LoginOtherStep.EMAIL_ADDRESS -> LoginRequestKind.EMAIL_ADDRESS
        challenge.otherStep == LoginOtherStep.EMAIL_CODE -> LoginRequestKind.EMAIL_CODE
        else -> null
    }
    fun send(kindToSend: LoginRequestKind, value: String = "") {
        if (currentActive && !submitted && !login.pending && !login.awaitingAuthorization && currentCanSubmit(kindToSend)) {
            submitted = true
            if (kindToSend == LoginRequestKind.PASSWORD) input = TextFieldValue()
            currentSubmit(token, kindToSend, value)
        }
    }
    val submit = {
        if (kind != null) {
            val value = if (kind == LoginRequestKind.PHONE) countryCode + phone.text else input.text
            if (value.isNotBlank() || (kind == LoginRequestKind.PASSWORD && value.isNotEmpty())) {
                if (kind == LoginRequestKind.PASSWORD_RECOVERY_CODE) confirmRecovery = true else send(kind, value)
            }
        }
    }

    if (countryPicker && active) {
        LoginCountryPicker(country, onChoose = {
            if (currentActive) { country = it; countryCode = "+${it.callingCode}"; countryPicker = false }
        }, onBack = { countryPicker = false }, modifier)
        return
    }
    BoxWithConstraints(modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        val density = LocalDensity.current
        val viewportHeight = with(density) { constraints.maxHeight.toDp() }
        val top = ((viewportHeight - 430.dp) / 2).coerceAtLeast(24.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(top = top, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 300.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (qr) {
                    LoginQrContent(challenge.presentation.qrLink, loading = login.pending || login.awaitingAuthorization || login.error == null)
                    Spacer(Modifier.height(20.dp))
                    LoginTitle("登录 Telegram", qr = true)
                    Spacer(Modifier.height(20.dp))
                    LoginQrStep(1, "在手机上打开 Telegram")
                    LoginQrStep(2, "进入设置 > 设备 > 连接桌面设备")
                    LoginQrStep(3, "将手机对准此屏幕以确认登录")
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = {
                        if (currentActive && !submitted && deviceConfirmation && currentQrPhoneSwitchAvailable &&
                            currentCanSubmit(LoginRequestKind.RESET_PHONE)) { submitted = true; onCancelQr(token) }
                        else if (!busy && !deviceConfirmation) phoneMode = true
                    }, enabled = !busy && (!deviceConfirmation || qrPhoneSwitchAvailable)) { Text("使用手机号登录") }
                    if (deviceConfirmation && !qrPhoneSwitchAvailable) {
                        Spacer(Modifier.height(10.dp))
                        Text("本机保护已启用，当前不能安全切换到新的手机号登录目录。可继续扫码登录。",
                            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (login.error != null && !deviceConfirmation) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { qrRequested = true; send(LoginRequestKind.QR_CODE) },
                            enabled = !busy && canSubmit(LoginRequestKind.QR_CODE)) { Text("重新获取二维码") }
                    }
                } else {
                    LoginTitle(when (kind) {
                        LoginRequestKind.PHONE -> "你的手机号码"
                        LoginRequestKind.CODE -> challenge.presentation.phoneNumber ?: "验证码"
                        LoginRequestKind.PASSWORD -> "二步验证"
                        LoginRequestKind.PASSWORD_RECOVERY_CODE -> "恢复密码"
                        LoginRequestKind.EMAIL_ADDRESS -> "登录邮箱"
                        LoginRequestKind.EMAIL_CODE -> "检查你的邮箱"
                        else -> "继续登录"
                    })
                    Spacer(Modifier.height(14.dp))
                    Text(when (kind) {
                        LoginRequestKind.PHONE -> "请选择国家或地区，并输入你的手机号码。"
                        LoginRequestKind.CODE -> loginCodeInstructions(challenge)
                        LoginRequestKind.PASSWORD -> "你的账号已启用二步验证。请输入密码。"
                        LoginRequestKind.PASSWORD_RECOVERY_CODE -> "请输入发送到 ${challenge.presentation.recoveryEmailPattern.orEmpty()} 的恢复码。"
                        LoginRequestKind.EMAIL_ADDRESS -> "添加你可以访问的邮箱，Telegram 将向这个邮箱发送登录验证码。"
                        LoginRequestKind.EMAIL_CODE -> challenge.presentation.emailPattern?.let { "请输入发送到 $it 的验证码。" }
                            ?: "邮箱验证信息暂不可用，请返回手机号重新开始。"
                        else -> loginUnsupportedInstructions(challenge)
                    }, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(30.dp))
                    if (kind == LoginRequestKind.PHONE) {
                        Button(onClick = { countryPicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("${country.name}  ▾", modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextField(value = countryCode, onValueChange = { raw ->
                                countryCode = "+" + raw.filter(Char::isDigit).take(4)
                                loginCountries.firstOrNull { "+${it.callingCode}" == countryCode }?.let { country = it }
                            }, label = "代码", enabled = !busy, singleLine = true, modifier = Modifier.width(84.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                            TextField(value = phone, onValueChange = { value ->
                                if (value.text.startsWith("+")) {
                                    val digits = value.text.drop(1).filter(Char::isDigit)
                                    val match = loginCountries.filter { digits.startsWith(it.callingCode) }.maxByOrNull { it.callingCode.length }
                                    if (match != null) { country = match; countryCode = "+${match.callingCode}"; phone = TextFieldValue(digits.drop(match.callingCode.length)) }
                                    else phone = value.copy(text = value.text.take(32))
                                } else phone = value.copy(text = value.text.take(32))
                            }, label = "手机号码", singleLine = true, enabled = !busy, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }))
                        }
                    } else if (kind != null) {
                        TextField(value = input, onValueChange = { value ->
                            val changed = input.text != value.text
                            val limit = if (kind == LoginRequestKind.PASSWORD) 1024 else 320
                            input = if (value.text.length <= limit) value else input
                            val length = challenge.codeLength
                            if (changed && value.composition == null && kind in setOf(LoginRequestKind.CODE, LoginRequestKind.EMAIL_CODE) &&
                                length != null && value.text.length == length && value.text.all(Char::isDigit)) send(kind, value.text)
                        }, label = when (kind) {
                            LoginRequestKind.PASSWORD -> "密码"
                            LoginRequestKind.EMAIL_ADDRESS -> "邮箱地址"
                            LoginRequestKind.PASSWORD_RECOVERY_CODE -> "恢复码"
                            else -> "验证码"
                        }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy && canSubmit(kind) &&
                            (kind != LoginRequestKind.EMAIL_CODE || challenge.presentation.emailPattern != null),
                            keyboardOptions = KeyboardOptions(keyboardType = when (kind) {
                                LoginRequestKind.PASSWORD -> KeyboardType.Password
                                LoginRequestKind.EMAIL_ADDRESS -> KeyboardType.Email
                                else -> KeyboardType.Text
                            }, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            visualTransformation = if (kind == LoginRequestKind.PASSWORD && !showPassword) PasswordVisualTransformation() else VisualTransformation.None)
                    }
                    if (kind == LoginRequestKind.PASSWORD) {
                        challenge.presentation.passwordHint?.takeIf(String::isNotBlank)?.let {
                            Spacer(Modifier.height(10.dp)); Text("密码提示：$it", fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showPassword = !showPassword }, enabled = !busy) { Text(if (showPassword) "隐藏密码" else "显示密码") }
                            Button(onClick = { if (challenge.hasRecoveryEmail) send(LoginRequestKind.PASSWORD_RECOVERY_REQUEST) else recovery = true }, enabled = !busy) { Text("忘记密码？") }
                        }
                    }
                    if (kind == LoginRequestKind.PASSWORD_RECOVERY_CODE) {
                        if (!challenge.hasRecoveryEmail) {
                            Spacer(Modifier.height(8.dp)); Text("这个账号没有设置恢复邮箱。请在已登录的 Telegram 设备上管理二步验证。", fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { recovery = false; input = TextFieldValue() }, enabled = !busy) { Text("返回密码登录") }
                    }
                    Spacer(Modifier.height(24.dp))
                    if (kind != null && (kind != LoginRequestKind.PASSWORD_RECOVERY_CODE || challenge.hasRecoveryEmail)) {
                        Button(onClick = { submit() }, enabled = !busy && canSubmit(kind) &&
                            (kind != LoginRequestKind.EMAIL_CODE || challenge.presentation.emailPattern != null) &&
                            (if (kind == LoginRequestKind.PHONE) phone.text.isNotBlank() else input.text.isNotEmpty()),
                            colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)) { Text("下一步") }
                    }
                    if (kind in setOf(LoginRequestKind.CODE, LoginRequestKind.EMAIL_CODE)) {
                        val canResend = kind == LoginRequestKind.EMAIL_CODE || challenge.nextCodeDelivery != LoginCodeDelivery.UNKNOWN
                        if (canResend) {
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { send(LoginRequestKind.RESEND_CODE) }, enabled = !busy && resendLeft == 0 && canSubmit(LoginRequestKind.RESEND_CODE)) {
                                Text(if (resendLeft > 0) "重新发送（${resendLeft}秒）" else "重新发送验证码")
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    if (kind == LoginRequestKind.PHONE || canSubmit(LoginRequestKind.QR_CODE)) {
                        Button(onClick = { phoneMode = false; input = TextFieldValue(); if (challenge.phase != AuthorizationPhase.WAITING_LOGIN) send(LoginRequestKind.QR_CODE) }, enabled = !busy) { Text("使用二维码登录") }
                    }
                    if (kind != LoginRequestKind.PHONE && canSubmit(LoginRequestKind.PHONE)) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { phoneMode = true; input = TextFieldValue() }, enabled = !busy) { Text("更换手机号码") }
                    }
                }
                login.error?.let { Spacer(Modifier.height(14.dp)); Text(it.loginMessage(), fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
                if (login.pending || login.awaitingAuthorization) {
                    Spacer(Modifier.height(14.dp))
                    Text(if (login.pending) "正在连接……" else "正在等待 Telegram 验证状态……", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
    if (active && confirmRecovery && kind == LoginRequestKind.PASSWORD_RECOVERY_CODE) {
        WindowDialog(show = true, title = "恢复账号", summary = "恢复后会移除当前二步验证密码。登录后请重新设置二步验证。", onDismissRequest = { confirmRecovery = false }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { confirmRecovery = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { confirmRecovery = false; send(LoginRequestKind.PASSWORD_RECOVERY_CODE, input.text) },
                    enabled = !busy, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f)) { Text("恢复并登录") }
            }
        }
    }
}

@Composable private fun LoginTitle(value: String, qr: Boolean = false) {
    Text(value, fontSize = if (qr) 20.sp else 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().semantics { heading() })
}

@Composable private fun LoginQrStep(number: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$number.", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(value, fontSize = 14.sp)
    }
}

@Composable private fun LoginQrContent(link: String?, loading: Boolean) {
    val matrix = remember(link) { link?.let(LoginQrMatrix::encode) }
    Box(Modifier.size(204.dp).background(Color.White), contentAlignment = Alignment.Center) {
        if (matrix != null) Canvas(Modifier.size(204.dp).semantics { contentDescription = "使用手机 Telegram 扫描此二维码登录" }) {
            val cell = kotlin.math.floor(size.minDimension / (matrix.size + 8))
            val start = (size.minDimension - matrix.size * cell) / 2
            for (y in 0 until matrix.size) for (x in 0 until matrix.size) if (matrix[x, y])
                drawRect(Color.Black, Offset(start + x * cell, start + y * cell), Size(cell, cell))
        } else if (loading && link == null) MediaLoadingIndicator(true, "正在获取登录二维码")
        else Text("二维码暂不可用", color = Color.Black, fontSize = 13.sp)
    }
}

@Composable private fun LoginCountryPicker(selected: LoginCountry, onChoose: (LoginCountry) -> Unit,
    onBack: () -> Unit, modifier: Modifier) {
    var search by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth()) {
            Button(onClick = onBack) { Text("返回手机号") }
            Spacer(Modifier.height(18.dp)); LoginTitle("国家或地区")
            Spacer(Modifier.height(18.dp))
            TextField(search, { search = it.take(100) }, label = "搜索国家、地区或代码", singleLine = true)
            Spacer(Modifier.height(12.dp))
            val matches = remember(search) { loginCountries.filter { search.isBlank() || it.name.contains(search, true) || it.iso.equals(search, true) || it.callingCode.startsWith(search.removePrefix("+")) } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(matches, key = { it.iso }) { country ->
                    Button(onClick = { onChoose(country) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (country == selected) "✓ " else ""}${country.name}", modifier = Modifier.weight(1f))
                        Text("+${country.callingCode}")
                    }
                }
            }
        }
    }
}

internal fun loginCodeInstructions(challenge: AuthorizationDetails): String = when (challenge.codeDelivery) {
    LoginCodeDelivery.TELEGRAM -> "我们已将验证码发送到你其他设备上的 Telegram。"
    LoginCodeDelivery.SMS -> "我们已通过短信向你发送验证码。"
    LoginCodeDelivery.CALL -> "请接听 Telegram 来电，并输入听到的验证码。"
    LoginCodeDelivery.FLASH_CALL -> "请根据 Telegram 验证来电的号码输入验证码。"
    LoginCodeDelivery.MISSED_CALL -> "请输入 Telegram 验证未接来电号码中的验证码。"
    LoginCodeDelivery.FRAGMENT -> "请前往 Fragment 查看 Telegram 验证码。"
    LoginCodeDelivery.SMS_WORD -> "请输入 Telegram 短信中的验证单词。"
    LoginCodeDelivery.SMS_PHRASE -> "请输入 Telegram 短信中的完整验证短语，保留单词间空格。"
    LoginCodeDelivery.FIREBASE -> "当前验证需要官方手机客户端，请先在手机端完成验证。"
    LoginCodeDelivery.UNKNOWN -> "请输入 Telegram 发送给你的验证码。"
}

private fun loginUnsupportedInstructions(challenge: AuthorizationDetails): String = when {
    challenge.phase == AuthorizationPhase.WAITING_CODE -> loginCodeInstructions(challenge)
    challenge.otherStep == LoginOtherStep.REGISTRATION -> "这个手机号码尚未注册。请使用官方 Telegram 完成注册后登录。"
    challenge.otherStep == LoginOtherStep.PREMIUM_PURCHASE -> "Telegram 要求额外的 Premium 验证。请在官方客户端处理后继续。"
    else -> "Telegram 要求当前版本尚未支持的额外验证。"
}

internal fun LoginError.loginMessage(): String = when (this) {
    LoginError.INVALID_PHONE -> "手机号码无效，请检查国家代码与号码。"
    LoginError.INVALID_CODE -> "验证码不正确，请重试。"
    LoginError.EXPIRED_CODE -> "验证码已过期，请重新获取。"
    LoginError.INVALID_PASSWORD -> "密码不正确，请重试。"
    LoginError.PHONE_BANNED -> "此号码暂时无法登录，请联系 Telegram 支持。"
    LoginError.TOO_MANY_ATTEMPTS -> "尝试次数过多，请稍后再试。"
    LoginError.REQUEST_FAILED -> "连接未完成，请检查网络后重试。"
    LoginError.EMPTY_INPUT -> "请填写当前步骤所需的内容。"
    LoginError.INVALID_EMAIL -> "邮箱地址无效，请检查后重试。"
    LoginError.EMAIL_NOT_ALLOWED -> "Telegram 不允许使用这个邮箱地址。"
    LoginError.RECOVERY_UNAVAILABLE -> "当前无法通过邮箱恢复，请使用密码登录。"
}
