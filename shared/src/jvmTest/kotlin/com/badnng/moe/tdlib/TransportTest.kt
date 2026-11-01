package com.badnng.moe.tdlib

import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.LoginCodeDelivery
import com.badnng.moe.status.LoginError
import com.badnng.moe.status.LoginOtherStep
import com.badnng.moe.status.LoginRequestKind
import com.badnng.moe.status.LoginResult
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class TransportTest {
    @Test
    fun nativeAuthorizationStatesMapToTheExpectedLoginStep() {
        val states = listOf(
            TdApi.AuthorizationStateWaitTdlibParameters() to AuthorizationPhase.WAITING_PARAMETERS,
            TdApi.AuthorizationStateWaitPhoneNumber() to AuthorizationPhase.WAITING_LOGIN,
            TdApi.AuthorizationStateWaitCode() to AuthorizationPhase.WAITING_CODE,
            TdApi.AuthorizationStateWaitPassword() to AuthorizationPhase.WAITING_PASSWORD,
            TdApi.AuthorizationStateReady() to AuthorizationPhase.READY,
            TdApi.AuthorizationStateClosing() to AuthorizationPhase.CLOSING,
            TdApi.AuthorizationStateLoggingOut() to AuthorizationPhase.CLOSING,
            TdApi.AuthorizationStateClosed() to AuthorizationPhase.CLOSED,
        )
        for ((native, expected) in states) assertEquals(expected, mapAuthorizationDetails(native).phase)
        assertEquals(AuthorizationPhase.OTHER_STEP, mapAuthorizationDetails(null).phase)
        assertEquals(AuthorizationPhase.OTHER_STEP, mapAuthorizationDetails(object : TdApi.AuthorizationState() {
            override fun getConstructor() = Int.MIN_VALUE
        }).phase)
    }

    @Test
    fun codeMetadataIncludesDeliveryAndLengthWithoutRetainingPrivateNativeFields() {
        val types = listOf(
            TdApi.AuthenticationCodeTypeTelegramMessage(5) to LoginCodeDelivery.TELEGRAM,
            TdApi.AuthenticationCodeTypeSms(5) to LoginCodeDelivery.SMS,
            TdApi.AuthenticationCodeTypeCall(5) to LoginCodeDelivery.CALL,
            TdApi.AuthenticationCodeTypeMissedCall("private-prefix", 5) to LoginCodeDelivery.MISSED_CALL,
            TdApi.AuthenticationCodeTypeFragment("https://private-link.invalid", 5) to LoginCodeDelivery.FRAGMENT,
            TdApi.AuthenticationCodeTypeFirebaseAndroid(null, 5) to LoginCodeDelivery.FIREBASE,
            TdApi.AuthenticationCodeTypeFirebaseIos("private-receipt", 10, 5) to LoginCodeDelivery.FIREBASE,
            TdApi.AuthenticationCodeTypeFlashCall("private-pattern") to LoginCodeDelivery.FLASH_CALL,
            TdApi.AuthenticationCodeTypeSmsWord("private-letter") to LoginCodeDelivery.SMS_WORD,
            TdApi.AuthenticationCodeTypeSmsPhrase("private-word") to LoginCodeDelivery.SMS_PHRASE,
        )
        for ((type, delivery) in types) {
            val native = TdApi.AuthorizationStateWaitCode(TdApi.AuthenticationCodeInfo(
                "private-phone", type, TdApi.AuthenticationCodeTypeSms(6), 30,
            ))
            val mapped = mapAuthorizationDetails(native)
            assertEquals(AuthorizationPhase.WAITING_CODE, mapped.phase)
            assertEquals(delivery, mapped.codeDelivery)
            assertEquals(if (delivery in setOf(LoginCodeDelivery.FLASH_CALL, LoginCodeDelivery.SMS_WORD, LoginCodeDelivery.SMS_PHRASE)) null else 5, mapped.codeLength)
            assertFalse(mapped.toString().contains("private"))
        }
        val invalidLength = TdApi.AuthorizationStateWaitCode(TdApi.AuthenticationCodeInfo(
            "private-phone", TdApi.AuthenticationCodeTypeSms(Int.MAX_VALUE), null, 0,
        ))
        assertNull(mapAuthorizationDetails(invalidLength).codeLength)
        assertEquals(LoginCodeDelivery.UNKNOWN, mapAuthorizationDetails(TdApi.AuthorizationStateWaitCode()).codeDelivery)
    }

    @Test
    fun unsupportedAuthorizationStepsRemainDistinguishableWithoutLeakingDetails() {
        val states = listOf(
            TdApi.AuthorizationStateWaitEmailAddress() to LoginOtherStep.EMAIL_ADDRESS,
            TdApi.AuthorizationStateWaitEmailCode() to LoginOtherStep.EMAIL_CODE,
            TdApi.AuthorizationStateWaitRegistration() to LoginOtherStep.REGISTRATION,
            TdApi.AuthorizationStateWaitOtherDeviceConfirmation("private-link") to LoginOtherStep.DEVICE_CONFIRMATION,
            TdApi.AuthorizationStateWaitPremiumPurchase("private-product", 1, "private-email", "private-subject") to LoginOtherStep.PREMIUM_PURCHASE,
        )
        for ((native, expected) in states) {
            val mapped = mapAuthorizationDetails(native)
            assertEquals(AuthorizationPhase.OTHER_STEP, mapped.phase)
            assertEquals(expected, mapped.otherStep)
            assertFalse(mapped.toString().contains("private"))
        }
        val password = mapAuthorizationDetails(TdApi.AuthorizationStateWaitPassword("private-hint", true, true, "private-email"))
        assertEquals(AuthorizationPhase.WAITING_PASSWORD, password.phase)
        assertFalse(password.toString().contains("private"))
    }

    @Test
    fun phoneSubmissionUsesTheExactPhoneAndDisablesUnsupportedMobileAuthenticationCapabilities() {
        val harness = Harness()
        val results = mutableListOf<LoginResult>()
        harness.session.submitLogin(LoginRequestKind.PHONE, "+1234567890", results::add)
        val request = assertIs<TdApi.SetAuthenticationPhoneNumber>(harness.requests.single().request)
        assertEquals("+1234567890", request.phoneNumber)
        assertFalse(request.settings.allowFlashCall)
        assertFalse(request.settings.allowMissedCall)
        assertFalse(request.settings.isCurrentPhoneNumber)
        assertFalse(request.settings.hasUnknownPhoneNumber)
        assertFalse(request.settings.allowSmsRetrieverApi)
        assertNull(request.settings.firebaseAuthenticationSettings)
        assertTrue(request.settings.authenticationTokens.isEmpty())
        assertTrue(results.isEmpty())
        harness.requests.single().result(TdApi.Ok())
        assertEquals(listOf(LoginResult()), results)
    }

    @Test
    fun codeAndPasswordRequestsPreserveWordsWhitespaceAndUnicode() {
        val harness = Harness()
        val results = mutableListOf<LoginResult>()
        val code = "a phrase with words"
        val password = "  合成密码 \t  "
        harness.session.submitLogin(LoginRequestKind.CODE, code, results::add)
        harness.session.submitLogin(LoginRequestKind.PASSWORD, password, results::add)
        assertEquals(code, assertIs<TdApi.CheckAuthenticationCode>(harness.requests[0].request).code)
        assertEquals(password, assertIs<TdApi.CheckAuthenticationPassword>(harness.requests[1].request).password)
        harness.requests.forEach { it.result(TdApi.Ok()) }
        assertEquals(listOf(LoginResult(), LoginResult()), results)
    }

    @Test
    fun loginErrorsAreMappedFromKnownTokensWithoutForwardingNativeText() {
        val cases = listOf(
            Triple(400, "PHONE_NUMBER_INVALID", LoginError.INVALID_PHONE),
            Triple(400, "PHONE_CODE_INVALID", LoginError.INVALID_CODE),
            Triple(400, "PHONE_CODE_EMPTY", LoginError.INVALID_CODE),
            Triple(400, "PHONE_CODE_EXPIRED", LoginError.EXPIRED_CODE),
            Triple(400, "PASSWORD_HASH_INVALID", LoginError.INVALID_PASSWORD),
            Triple(400, "PHONE_NUMBER_BANNED", LoginError.PHONE_BANNED),
            Triple(420, "FLOOD_WAIT_30", LoginError.TOO_MANY_ATTEMPTS),
            Triple(420, "FLOOD_PREMIUM_WAIT_60", LoginError.TOO_MANY_ATTEMPTS),
            Triple(429, "Too Many Requests: retry after 10 private", LoginError.TOO_MANY_ATTEMPTS),
            Triple(400, "PHONE_CODE_INVALID private text", LoginError.REQUEST_FAILED),
            Triple(500, "private phone password and response", LoginError.REQUEST_FAILED),
        )
        for ((code, message, expected) in cases) {
            val harness = Harness()
            val results = mutableListOf<LoginResult>()
            harness.session.submitLogin(LoginRequestKind.CODE, "synthetic-code", results::add)
            harness.requests.single().result(TdApi.Error(code, message))
            assertEquals(listOf(LoginResult(expected, code)), results)
            assertFalse(results.toString().contains("private"))
            assertFalse(results.toString().contains("synthetic-code"))
        }
    }

    @Test
    fun loginRequestCallbacksCompleteExactlyOnceAfterSuccessErrorOrUnexpectedResponse() {
        for (first in listOf(TdApi.Ok(), TdApi.Error(400, "PHONE_CODE_INVALID"), TdApi.OptionValueString("private unexpected response"))) {
            val harness = Harness()
            val results = mutableListOf<LoginResult>()
            harness.session.submitLogin(LoginRequestKind.CODE, "synthetic-code", results::add)
            val request = harness.requests.single()
            request.result(first)
            request.result(TdApi.Ok())
            request.error()
            assertEquals(1, results.size)
            assertEquals(when (first) {
                is TdApi.Ok -> LoginResult()
                is TdApi.Error -> LoginResult(LoginError.INVALID_CODE, 400)
                else -> LoginResult(LoginError.REQUEST_FAILED, -1)
            }, results.single())
            assertFalse(results.toString().contains("private"))
        }
    }

    @Test
    fun asynchronousAndSynchronousLoginExceptionsAreSanitized() {
        val harness = Harness()
        val callbackResults = mutableListOf<LoginResult>()
        harness.session.submitLogin(LoginRequestKind.PASSWORD, "private-password", callbackResults::add)
        harness.requests.single().error()
        harness.requests.single().result(TdApi.Ok())
        assertEquals(listOf(LoginResult(LoginError.REQUEST_FAILED, -1)), callbackResults)
        val dispatchResults = mutableListOf<LoginResult>()
        val session = OfficialTdSession(false) { _, _, _ -> throw IllegalStateException("private-password") }
        session.submitLogin(LoginRequestKind.PASSWORD, "private-password", dispatchResults::add)
        assertEquals(callbackResults, dispatchResults)
        assertFalse(dispatchResults.toString().contains("private"))
    }

    @Test
    fun nativeConnectionStatesMapIndependentlyAndUnknownValuesStayUnknown() {
        val states = listOf(
            TdApi.ConnectionStateWaitingForNetwork() to ConnectionPhase.WAITING_NETWORK,
            TdApi.ConnectionStateConnectingToProxy() to ConnectionPhase.CONNECTING_PROXY,
            TdApi.ConnectionStateConnecting() to ConnectionPhase.CONNECTING,
            TdApi.ConnectionStateUpdating() to ConnectionPhase.UPDATING,
            TdApi.ConnectionStateReady() to ConnectionPhase.READY,
        )
        for ((native, expected) in states) assertEquals(expected, mapConnectionState(native))
        assertEquals(ConnectionPhase.UNKNOWN, mapConnectionState(null))
        assertEquals(ConnectionPhase.UNKNOWN, mapConnectionState(object : TdApi.ConnectionState() {
            override fun getConstructor() = Int.MIN_VALUE
        }))
    }

    @Test
    fun initializationGatesNetworkingBeforeParametersAndCompletesOnlyAfterBothResults() {
        val harness = Harness()
        val root = Files.createTempDirectory("parameters-gate-")
        val results = mutableListOf<Int?>()
        DataDirectoryLease.acquire(root).use { lease ->
            harness.session.setParameters(TdConfiguration(12345, "a".repeat(32), root), lease, results::add)
            assertIs<TdApi.NetworkTypeNone>(assertIs<TdApi.SetNetworkType>(harness.requests[0].request).type)
            val parameters = assertIs<TdApi.SetTdlibParameters>(harness.requests[1].request)
            assertEquals(lease.database.toString(), parameters.databaseDirectory)
            harness.requests[1].result(TdApi.Ok())
            assertTrue(results.isEmpty())
            harness.requests[0].result(TdApi.Ok())
            assertEquals(listOf<Int?>(null), results)
            harness.requests[0].error()
            assertEquals(1, results.size)
        }
    }

    @Test
    fun parameterOrNetworkGateFailureProducesOnlyOneSanitizedFailure() {
        val harness = Harness()
        val root = Files.createTempDirectory("parameters-error-")
        val results = mutableListOf<Int?>()
        DataDirectoryLease.acquire(root).use { lease ->
            harness.session.setParameters(TdConfiguration(12345, "a".repeat(32), root), lease, results::add)
            harness.requests[0].result(TdApi.Error(500, "private native details"))
            harness.requests[1].result(TdApi.Ok())
            harness.requests[1].error()
            assertEquals(listOf<Int?>(500), results)
        }
    }

    @Test
    fun rejectedParametersDoNotWaitForTheNetworkRequestStillQueuedByTdlib() {
        val harness = Harness()
        val root = Files.createTempDirectory("parameters-rejected-")
        val results = mutableListOf<Int?>()
        DataDirectoryLease.acquire(root).use { lease ->
            harness.session.setParameters(TdConfiguration(12345, "a".repeat(32), root), lease, results::add)
            harness.requests[1].result(TdApi.Error(400, "private rejected request"))
            assertEquals(listOf<Int?>(400), results)
            harness.requests[0].result(TdApi.Error(500, "client closed later"))
            assertEquals(listOf<Int?>(400), results)
        }
    }

    @Test
    fun directModeDisablesThePersistedProxyWithoutDeletingItsHistoryBeforeEnablingNetworking() {
        val harness = Harness()
        val results = mutableListOf<Int?>()
        harness.session.configureProxy(null, results::add)
        assertIs<TdApi.DisableProxy>(harness.requests.single().request)
        harness.requests[0].result(TdApi.Ok())
        assertIs<TdApi.NetworkTypeOther>(assertIs<TdApi.SetNetworkType>(harness.requests[1].request).type)
        assertTrue(results.isEmpty())
        harness.requests[1].result(TdApi.Ok())
        assertEquals(listOf<Int?>(null), results)
        assertFalse(harness.requests.any { it.request is TdApi.RemoveProxy })
    }

    @Test
    fun socks5CredentialsAreSentOnlyInTheProxyRequestAndNetworkingWaitsForSuccess() {
        val harness = Harness()
        val results = mutableListOf<Int?>()
        harness.session.configureProxy(ProxyConfiguration(ProxyType.SOCKS5, "localhost", 1080, "test-user", "test-password"), results::add)
        val request = assertIs<TdApi.AddProxy>(harness.requests.single().request)
        val proxyType = assertIs<TdApi.ProxyTypeSocks5>(request.proxy.type)
        assertTrue(request.enable)
        assertEquals("localhost", request.proxy.server)
        assertEquals(1080, request.proxy.port)
        assertEquals("test-user", proxyType.username)
        assertEquals("test-password", proxyType.password)
        assertTrue(results.isEmpty())
        harness.requests[0].result(TdApi.AddedProxy())
        assertIs<TdApi.NetworkTypeOther>(assertIs<TdApi.SetNetworkType>(harness.requests[1].request).type)
        harness.requests[1].result(TdApi.Ok())
        assertEquals(listOf<Int?>(null), results)
    }

    @Test
    fun httpProxyUsesConnectAndAnOfflineFixtureNeverEnablesNetworking() {
        val harness = Harness(offline = true)
        val results = mutableListOf<Int?>()
        harness.session.configureProxy(ProxyConfiguration(ProxyType.HTTP, "localhost", 8080), results::add)
        val request = assertIs<TdApi.AddProxy>(harness.requests.single().request)
        assertFalse(assertIs<TdApi.ProxyTypeHttp>(request.proxy.type).httpOnly)
        harness.requests[0].result(TdApi.AddedProxy())
        assertEquals(listOf<Int?>(null), results)
        assertEquals(1, harness.requests.size)
    }

    @Test
    fun rejectedProxyKeepsNetworkingGatedAndDoesNotExposeNativeErrorText() {
        val harness = Harness()
        val root = Files.createTempDirectory("proxy-gate-")
        DataDirectoryLease.acquire(root).use { lease ->
            harness.session.setParameters(TdConfiguration(12345, "a".repeat(32), root), lease) {}
            harness.requests[0].result(TdApi.Ok())
            harness.requests[1].result(TdApi.Ok())
            val results = mutableListOf<Int?>()
            harness.session.configureProxy(ProxyConfiguration(ProxyType.SOCKS5, "localhost", 1080), results::add)
            harness.requests[2].result(TdApi.Error(400, "private username and password"))
            harness.requests[2].error()
            assertEquals(listOf<Int?>(400), results)
            assertEquals(3, harness.requests.size)
            assertTrue(harness.requests.map { it.request }.filterIsInstance<TdApi.SetNetworkType>().all { it.type is TdApi.NetworkTypeNone })
        }
    }

    @Test
    fun proxyCallbackExceptionsAlsoCompleteOnceWithoutEnablingNetworking() {
        val harness = Harness()
        val results = mutableListOf<Int?>()
        harness.session.configureProxy(null, results::add)
        harness.requests.single().error()
        harness.requests.single().result(TdApi.Ok())
        assertEquals(listOf<Int?>(-1), results)
        assertEquals(1, harness.requests.size)
    }

    @Test
    fun synchronousProxyDispatchExceptionsRemainSanitizedAndDoNotEnableNetworking() {
        var sends = 0
        val session = OfficialTdSession(false) { _, _, _ ->
            sends++
            throw IllegalStateException("private dispatch details")
        }
        val results = mutableListOf<Int?>()
        session.configureProxy(null, results::add)
        assertEquals(listOf<Int?>(-1), results)
        assertEquals(1, sends)
    }

    private class Harness(offline: Boolean = false) {
        val requests = mutableListOf<Request>()
        val session = OfficialTdSession(offline) { request, result, error ->
            requests += Request(request, result, error)
        }
    }

    private class Request(val request: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val error: () -> Unit)
}
