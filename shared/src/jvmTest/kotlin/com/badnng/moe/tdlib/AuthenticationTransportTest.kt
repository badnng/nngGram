package com.badnng.moe.tdlib

import com.badnng.moe.status.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class AuthenticationTransportTest {
    @Test fun qrUsesNativeRequestAndChangingItsNativePayloadChangesTheChallengeWithoutLoggingIt() {
        val sent = mutableListOf<TdApi.Function<*>>()
        val session = OfficialTdSession(false) { request, done, _ -> sent += request; done(TdApi.Ok()) }
        session.submitLogin(LoginRequestKind.QR_CODE, "") { assertNull(it.error) }
        assertTrue(assertIs<TdApi.RequestQrCodeAuthentication>(sent.single()).otherUserIds.isEmpty())
        val first = mapAuthorizationDetails(TdApi.AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=fixture_1"))
        val second = mapAuthorizationDetails(TdApi.AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=fixture_2"))
        assertEquals(LoginOtherStep.DEVICE_CONFIRMATION, first.otherStep)
        assertEquals("tg://login?token=fixture_1", first.presentation.qrLink)
        assertNotEquals(first, second)
        assertFalse(first.toString().contains("fixture_1"))
        assertNull(mapAuthorizationDetails(TdApi.AuthorizationStateWaitOtherDeviceConfirmation("https://example.invalid/")).presentation.qrLink)
    }

    @Test fun qrCancellationClosesWithoutLoggingOutAnAccountThatMayHaveJustAuthorized() {
        val sent = mutableListOf<TdApi.Function<*>>()
        val session = OfficialTdSession(false) { request, done, _ -> sent += request; done(TdApi.Ok()) }
        session.submitLogin(LoginRequestKind.RESET_PHONE, "") { assertNull(it.error) }
        assertIs<TdApi.Close>(sent.single())
        assertFalse(sent.any { it is TdApi.LogOut || it is TdApi.Destroy })
    }

    @Test fun actualCodeChannelTimeoutEmailAndPasswordMetadataAreMappedTransiently() {
        val code = mapAuthorizationDetails(TdApi.AuthorizationStateWaitCode(TdApi.AuthenticationCodeInfo(
            "+19995550123", TdApi.AuthenticationCodeTypeTelegramMessage(5), TdApi.AuthenticationCodeTypeSms(5), 30)))
        assertEquals(LoginCodeDelivery.SMS, code.nextCodeDelivery)
        assertEquals(30, code.resendTimeoutSeconds)
        assertEquals("+19995550123", code.presentation.phoneNumber)
        val password = mapAuthorizationDetails(TdApi.AuthorizationStateWaitPassword("private-hint", true, false, "p***@example.invalid"))
        assertTrue(password.hasRecoveryEmail)
        assertEquals("private-hint", password.presentation.passwordHint)
        assertEquals("p***@example.invalid", password.presentation.recoveryEmailPattern)
        val native = TdApi.AuthorizationStateWaitEmailCode().apply {
            codeInfo = TdApi.EmailAddressAuthenticationCodeInfo("e***@example.invalid", 6)
        }
        val email = mapAuthorizationDetails(native)
        assertEquals(6, email.codeLength)
        assertEquals("e***@example.invalid", email.presentation.emailPattern)
        for (mapped in listOf(code, password, email)) {
            assertFalse(mapped.toString().contains("private-hint"))
            assertFalse(mapped.toString().contains("@example.invalid"))
            assertFalse(mapped.toString().contains("1999555"))
        }
    }

    @Test fun emailResendAndPasswordRecoveryUseDistinctNativeRequestsAndSanitizeFailures() {
        val requests = mutableListOf<TdApi.Function<*>>()
        val results = mutableListOf<LoginResult>()
        val session = OfficialTdSession(false) { request, done, error ->
            requests += request
            done(TdApi.Error(400, "EMAIL_INVALID")); done(TdApi.Ok()); error()
        }
        for (kind in listOf(LoginRequestKind.EMAIL_ADDRESS, LoginRequestKind.EMAIL_CODE,
            LoginRequestKind.RESEND_CODE, LoginRequestKind.PASSWORD_RECOVERY_REQUEST, LoginRequestKind.PASSWORD_RECOVERY_CODE))
            session.submitLogin(kind, "fixture-input", results::add)
        assertEquals("fixture-input", assertIs<TdApi.SetAuthenticationEmailAddress>(requests[0]).emailAddress)
        assertEquals("fixture-input", assertIs<TdApi.EmailAddressAuthenticationCode>(assertIs<TdApi.CheckAuthenticationEmailCode>(requests[1]).code).code)
        assertIs<TdApi.ResendAuthenticationCode>(requests[2])
        assertIs<TdApi.RequestAuthenticationPasswordRecovery>(requests[3])
        assertIs<TdApi.RecoverAuthenticationPassword>(requests[4]).let {
            assertEquals("fixture-input", it.recoveryCode); assertEquals("", it.newPassword); assertEquals("", it.newHint)
        }
        assertEquals(5, results.size)
        assertTrue(results.all { it.error == LoginError.INVALID_EMAIL })
        assertFalse(results.toString().contains("fixture-input"))
    }
}
