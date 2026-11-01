package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badnng.moe.status.*

@Composable
internal fun LoginPanel(
    runtime: RuntimeSnapshot,
    exitRequested: Boolean,
    onSubmit: (LoginToken, LoginRequestKind, String) -> Unit,
    onCancelQr: (LoginToken) -> Unit = {},
    initialPhoneMode: Boolean = false,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    if (!runtime.clientActive || runtime.status.engine != EnginePhase.INITIALIZED || exitRequested) return
    if (runtime.login.challenge.phase !in setOf(AuthorizationPhase.WAITING_LOGIN, AuthorizationPhase.WAITING_CODE,
            AuthorizationPhase.WAITING_PASSWORD, AuthorizationPhase.OTHER_STEP)) return
    AuthenticationLoginPage(runtime.login, runtime.loginToken, runtime::canSubmitLogin, onSubmit,
        onCancelQr, initialPhoneMode, modifier, active, qrPhoneSwitchAvailable = runtime.loginQrPhoneSwitchAvailable)
}
