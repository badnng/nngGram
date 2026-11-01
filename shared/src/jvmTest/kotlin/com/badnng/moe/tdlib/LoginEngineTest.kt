package com.badnng.moe.tdlib

import com.badnng.moe.status.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LoginEngineTest {
    @Test
    fun repeatedIdenticalLocalPhoneRejectionsRetireEachFormRevisionWithoutNativeSubmission() = runTest {
        withEngine { (engine, transport) ->
            var previous = engine.state.value.loginToken
            repeat(3) {
                assertTrue(engine.submitLogin(previous, LoginRequestKind.PHONE, "1"))
                runCurrent()
                assertEquals(LoginError.INVALID_PHONE, engine.state.value.login.error)
                assertFalse(engine.state.value.login.pending)
                assertFalse(engine.state.value.login.awaitingAuthorization)
                assertNotEquals(previous, engine.state.value.loginToken)
                previous = engine.state.value.loginToken
            }
            assertTrue(transport.requests.isEmpty())
            assertTrue(engine.submitLogin(previous, LoginRequestKind.PHONE, "+19995550123"))
            runCurrent()
            assertEquals(1, transport.requests.size)
            assertTrue(engine.state.value.login.pending)
        }
    }

    @Test
    fun cancellingQrWaitsForClosedThenPersistsANewPhoneAttemptWithoutReleasingTheRootLease() = runTest {
        withEngine(AuthorizationPhase.OTHER_STEP) { (engine, transport) ->
            transport.authorization(qrChallenge())
            runCurrent()
            val token = engine.state.value.loginToken
            val root = Path.of(assertNotNull(engine.state.value.dataPath))
            transport.autoClose = false
            assertTrue(engine.cancelQrLogin(token))
            runCurrent()
            assertEquals(1, transport.sessions.size)
            assertEquals(AuthenticationDatabaseChoice(), AuthenticationDatabaseSelectionStore(root).read())
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
            transport.authorization(AuthorizationPhase.CLOSED)
            runCurrent()
            assertEquals(2, transport.sessions.size)
            assertTrue(engine.state.value.generation > token.generation)
            assertTrue(engine.state.value.loginInitialPhoneMode)
            val nextPath = Path.of(assertNotNull(engine.state.value.dataPath))
            assertTrue(nextPath.startsWith(root.resolve("authentication-attempts")))
            assertEquals(nextPath, AuthenticationDatabaseSelectionStore(root).directory(AuthenticationDatabaseSelectionStore(root).read()))
            assertTrue(Files.isDirectory(root.resolve("database")), "The original database is preserved.")
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
            assertTrue(transport.requests.isEmpty(), "QR cancellation uses Close, never LogOut or an auth submission.")
            assertFalse(engine.cancelQrLogin(token))
            transport.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            transport.authorization(AuthorizationPhase.WAITING_LOGIN)
            runCurrent()
            assertTrue(engine.state.value.canSubmitLogin(LoginRequestKind.PHONE))
            transport.autoClose = true
            val stopping = async { engine.stop() }
            runCurrent()
            assertTrue(stopping.await())
            DataDirectoryLease.acquire(root).close()
            engine.start()
            runCurrent()
            assertEquals(nextPath.toString(), engine.state.value.dataPath)
            assertTrue(engine.state.value.loginInitialPhoneMode)
        }
    }

    @Test
    fun stoppingBeforeQrCloseCompletesPreventsThePhoneAttemptAndAutomaticRestart() = runTest {
        withEngine(AuthorizationPhase.OTHER_STEP) { (engine, transport) ->
            transport.authorization(qrChallenge()); runCurrent()
            val root = Path.of(assertNotNull(engine.state.value.dataPath))
            transport.autoClose = false
            assertTrue(engine.cancelQrLogin(engine.state.value.loginToken)); runCurrent()
            val stopping = async { engine.stop() }
            runCurrent()
            transport.authorization(AuthorizationPhase.CLOSED); runCurrent()
            assertTrue(stopping.await())
            assertEquals(1, transport.sessions.size)
            assertEquals(AuthenticationDatabaseChoice(), AuthenticationDatabaseSelectionStore(root).read())
            assertFalse(Files.exists(root.resolve("authentication-attempts")))
            DataDirectoryLease.acquire(root).close()
        }
    }

    @Test
    fun aConcurrentReadyProofPreservesTheOriginalDatabaseAndOldReadyCannotAuthorizeTheNextGeneration() = runTest {
        withEngine(AuthorizationPhase.OTHER_STEP) { (engine, transport) ->
            transport.authorization(qrChallenge()); runCurrent()
            val old = transport.sessions.last()
            val root = Path.of(assertNotNull(engine.state.value.dataPath))
            transport.autoClose = false
            assertTrue(engine.cancelQrLogin(engine.state.value.loginToken)); runCurrent()
            old.authorization(AuthorizationDetails(AuthorizationPhase.READY))
            old.authorization(AuthorizationDetails(AuthorizationPhase.CLOSED))
            runCurrent()
            assertEquals(2, transport.sessions.size)
            assertEquals(root.toString(), engine.state.value.dataPath)
            assertFalse(engine.state.value.loginInitialPhoneMode)
            assertEquals(AuthenticationDatabaseChoice(), AuthenticationDatabaseSelectionStore(root).read())
            val generation = engine.state.value.generation
            old.authorization(AuthorizationDetails(AuthorizationPhase.READY)); runCurrent()
            assertEquals(generation, engine.state.value.generation)
            assertNotEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun resendAndRecoveryRequestsAcceptEmptyArgumentsAndReleaseTheirOwnCompletionRevision() = runTest {
        withEngine(AuthorizationPhase.WAITING_CODE) { (engine, transport) ->
            transport.authorization(AuthorizationDetails(AuthorizationPhase.WAITING_CODE,
                LoginCodeDelivery.TELEGRAM, 5, nextCodeDelivery = LoginCodeDelivery.SMS))
            runCurrent()
            val first = engine.state.value.loginToken
            assertTrue(engine.submitLogin(first, LoginRequestKind.RESEND_CODE, "")); runCurrent()
            transport.requests.single().result(LoginResult()); runCurrent()
            assertFalse(engine.state.value.login.pending)
            assertFalse(engine.state.value.login.awaitingAuthorization)
            assertNotEquals(first, engine.state.value.loginToken)
            assertTrue(engine.state.value.canSubmitLogin(LoginRequestKind.CODE))
            transport.authorization(AuthorizationDetails(AuthorizationPhase.WAITING_PASSWORD, hasRecoveryEmail = true)); runCurrent()
            val recovery = engine.state.value.loginToken
            assertTrue(engine.submitLogin(recovery, LoginRequestKind.PASSWORD_RECOVERY_REQUEST, "")); runCurrent()
            transport.requests.last().result(LoginResult()); runCurrent()
            assertFalse(engine.state.value.login.awaitingAuthorization)
            assertNotEquals(recovery, engine.state.value.loginToken)
            assertTrue(engine.state.value.canSubmitLogin(LoginRequestKind.PASSWORD_RECOVERY_CODE))
        }
    }

    private fun qrChallenge() = AuthorizationDetails(AuthorizationPhase.OTHER_STEP,
        otherStep = LoginOtherStep.DEVICE_CONFIRMATION,
        presentation = LoginPresentation(qrLink = "tg://login?token=synthetic-qr-token"))

    @Test
    fun threeStepLoginAdvancesOnlyOnAuthorizationUpdates() = runTest {
        withEngine { (engine, transport) ->
            val token = engine.state.value.loginToken
            repeat(20) { engine.submitLogin(token, LoginRequestKind.PHONE, "+1 (999) 555-0123") }
            runCurrent()
            val phone = transport.requests.single()
            assertEquals(LoginRequestKind.PHONE, phone.kind)
            assertEquals("+19995550123", phone.value)
            assertTrue(engine.state.value.login.pending)
            assertFalse(engine.state.value.canSubmitLogin(LoginRequestKind.PHONE))

            phone.result(LoginResult())
            runCurrent()
            assertEquals(AuthorizationPhase.WAITING_LOGIN, engine.state.value.status.authorization)
            assertTrue(engine.state.value.login.awaitingAuthorization)
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, "+19995550123")
            runCurrent()
            assertEquals(1, transport.requests.size)

            transport.authorization(AuthorizationPhase.WAITING_CODE)
            runCurrent()
            assertFalse(engine.state.value.login.pending)
            assertFalse(engine.state.value.login.awaitingAuthorization)
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.CODE, "  12345  ")
            runCurrent()
            assertEquals("12345", transport.requests.last().value)
            transport.requests.last().result(LoginResult())
            runCurrent()
            assertEquals(AuthorizationPhase.WAITING_CODE, engine.state.value.status.authorization)

            transport.authorization(AuthorizationPhase.WAITING_PASSWORD)
            runCurrent()
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PASSWORD, "synthetic password")
            runCurrent()
            transport.requests.last().result(LoginResult())
            runCurrent()
            assertEquals(AuthorizationPhase.WAITING_PASSWORD, engine.state.value.status.authorization)
            transport.authorization(AuthorizationPhase.READY)
            runCurrent()
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.state.value.login.pending)
            assertFalse(engine.state.value.login.awaitingAuthorization)
            assertNull(engine.state.value.login.error)
            assertEquals(3, transport.requests.size)
        }
    }

    @Test
    fun repeatedIdenticalAuthorizationUpdatesDoNotUnlockPendingRequests() = runTest {
        withEngine { (engine, transport) ->
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, "+19995550123")
            runCurrent()
            val token = engine.state.value.loginToken
            repeat(5) { transport.authorization(AuthorizationPhase.WAITING_LOGIN) }
            runCurrent()
            assertEquals(token, engine.state.value.loginToken)
            assertTrue(engine.state.value.login.pending)
            engine.submitLogin(token, LoginRequestKind.PHONE, "+19995550124")
            runCurrent()
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun changingPhoneRefreshesAnIdenticalCodeChallengeBeforeThePhoneResultArrives() = runTest {
        assertPhoneChangeRefreshesChallenge(
            AuthorizationDetails(AuthorizationPhase.WAITING_CODE, LoginCodeDelivery.TELEGRAM, 5),
            resultFirst = false,
            nextKind = LoginRequestKind.CODE,
        )
    }

    @Test
    fun changingPhoneRefreshesAnIdenticalCodeChallengeAfterThePhoneResultArrives() = runTest {
        assertPhoneChangeRefreshesChallenge(
            AuthorizationDetails(AuthorizationPhase.WAITING_CODE, LoginCodeDelivery.TELEGRAM, 5),
            resultFirst = true,
            nextKind = LoginRequestKind.CODE,
        )
    }

    @Test
    fun changingPhoneRefreshesIdenticalEmailChallengeMetadataInEitherCallbackOrder() = runTest {
        for (resultFirst in listOf(false, true)) {
            assertPhoneChangeRefreshesChallenge(
                AuthorizationDetails(AuthorizationPhase.OTHER_STEP, otherStep = LoginOtherStep.EMAIL_CODE),
                resultFirst = resultFirst,
                nextKind = LoginRequestKind.PHONE,
            )
        }
    }

    @Test
    fun malformedPhoneNumbersAndEmptyFieldsNeverReachTdlib() = runTest {
        withEngine { (engine, transport) ->
            for (phone in listOf("19995550123", "+012345", "+1234", "+1234567890123456", "+１２３４５")) {
                engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, phone)
                runCurrent()
                assertEquals(LoginError.INVALID_PHONE, engine.state.value.login.error)
            }
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, "  ")
            runCurrent()
            assertNotNull(engine.state.value.login.error)
            transport.authorization(AuthorizationPhase.WAITING_CODE)
            runCurrent()
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.CODE, " \n ")
            runCurrent()
            assertEquals(LoginError.EMPTY_INPUT, engine.state.value.login.error)
            transport.authorization(AuthorizationPhase.WAITING_PASSWORD)
            runCurrent()
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PASSWORD, "")
            runCurrent()
            assertEquals(LoginError.EMPTY_INPUT, engine.state.value.login.error)
            assertTrue(transport.requests.isEmpty())
            assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
        }
    }

    @Test
    fun phraseCodesKeepTheirWordsAndPasswordsKeepLeadingAndTrailingSpaces() = runTest {
        withEngine(AuthorizationPhase.WAITING_CODE) { (engine, transport) ->
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.CODE, "  amber river  sunset  ")
            runCurrent()
            assertEquals("amber river  sunset", transport.requests.single().value)
            transport.authorization(AuthorizationPhase.WAITING_PASSWORD)
            runCurrent()
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PASSWORD, "  synthetic secret  ")
            runCurrent()
            assertEquals("  synthetic secret  ", transport.requests.last().value)
        }
    }

    @Test
    fun rejectedLoginKeepsTheEngineActiveAndCanBeRetried() = runTest {
        withEngine(AuthorizationPhase.WAITING_CODE) { (engine, transport) ->
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.CODE, "12345")
            runCurrent()
            val previous = transport.requests.single()
            previous.result(LoginResult(LoginError.INVALID_CODE, 400))
            runCurrent()
            assertEquals(LoginError.INVALID_CODE, engine.state.value.login.error)
            assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
            assertEquals(AuthorizationPhase.WAITING_CODE, engine.state.value.status.authorization)
            assertNull(engine.state.value.status.failure)
            assertTrue(engine.state.value.clientActive)
            assertEquals(0, transport.closed)

            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.CODE, "67890")
            runCurrent()
            assertEquals(2, transport.requests.size)
            assertTrue(engine.state.value.login.pending)
            assertNull(engine.state.value.login.error)
            previous.result(LoginResult(LoginError.EXPIRED_CODE, 400))
            previous.result(LoginResult())
            runCurrent()
            assertTrue(engine.state.value.login.pending)
            assertFalse(engine.state.value.login.awaitingAuthorization)
            assertNull(engine.state.value.login.error)
        }
    }

    @Test
    fun authorizationArrivingBeforeTheRequestResultMakesTheOldResultHarmless() = runTest {
        withEngine { (engine, transport) ->
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, "+19995550123")
            runCurrent()
            val phone = transport.requests.single()
            transport.authorization(AuthorizationPhase.WAITING_CODE)
            runCurrent()
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.CODE, "12345")
            runCurrent()
            val code = transport.requests.last()
            phone.result(LoginResult(LoginError.PHONE_BANNED, 400))
            runCurrent()
            assertEquals(AuthorizationPhase.WAITING_CODE, engine.state.value.status.authorization)
            assertTrue(engine.state.value.login.pending)
            assertNull(engine.state.value.login.error)

            transport.authorization(AuthorizationPhase.READY)
            runCurrent()
            code.result(LoginResult(LoginError.INVALID_CODE, 400))
            runCurrent()
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.state.value.login.pending)
            assertNull(engine.state.value.login.error)
        }
    }

    @Test
    fun staleFormTokensAndRequestsForTheWrongStepAreIgnored() = runTest {
        withEngine { (engine, transport) ->
            val previousToken = engine.state.value.loginToken
            engine.submitLogin(previousToken, LoginRequestKind.CODE, "12345")
            engine.submitLogin(previousToken, LoginRequestKind.PASSWORD, "synthetic password")
            runCurrent()
            assertTrue(transport.requests.isEmpty())
            transport.authorization(AuthorizationPhase.WAITING_CODE)
            runCurrent()
            assertNotEquals(previousToken, engine.state.value.loginToken)
            engine.submitLogin(previousToken, LoginRequestKind.PHONE, "+19995550123")
            engine.submitLogin(previousToken, LoginRequestKind.CODE, "12345")
            runCurrent()
            assertTrue(transport.requests.isEmpty())
            transport.authorization(AuthorizationPhase.READY)
            runCurrent()
            for (kind in LoginRequestKind.entries) engine.submitLogin(engine.state.value.loginToken, kind, "12345")
            runCurrent()
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun aFailedProxyConfigurationPreventsLoginDispatchWithoutFailingInitialization() = runTest {
        withEngine(proxyResult = 407) { (engine, transport) ->
            assertEquals(ProxyStatus.FAILED, engine.state.value.proxy)
            assertFalse(engine.state.value.canSubmitLogin(LoginRequestKind.PHONE))
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, "+19995550123")
            runCurrent()
            assertTrue(transport.requests.isEmpty())
            assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
        }
    }

    @Test
    fun aPhoneNumberCanBeChangedOnlyInSupportedAuthorizationSteps() = runTest {
        withEngine { (engine, transport) ->
            for (phase in listOf(AuthorizationPhase.WAITING_CODE, AuthorizationPhase.WAITING_PASSWORD)) {
                transport.authorization(phase)
                runCurrent()
                engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, "+19995550123")
                runCurrent()
                assertEquals(LoginRequestKind.PHONE, transport.requests.last().kind)
                transport.requests.last().result(LoginResult(LoginError.INVALID_PHONE, 400))
                runCurrent()
            }
            for (other in listOf(LoginOtherStep.EMAIL_ADDRESS, LoginOtherStep.EMAIL_CODE,
                LoginOtherStep.REGISTRATION, LoginOtherStep.PREMIUM_PURCHASE)) {
                transport.authorization(AuthorizationDetails(AuthorizationPhase.OTHER_STEP, otherStep = other))
                runCurrent()
                assertTrue(engine.state.value.canSubmitLogin(LoginRequestKind.PHONE))
                engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, "+19995550123")
                runCurrent()
                transport.requests.last().result(LoginResult(LoginError.INVALID_PHONE, 400))
                runCurrent()
            }
            assertEquals(6, transport.requests.size)
            for (other in listOf(LoginOtherStep.UNKNOWN, LoginOtherStep.DEVICE_CONFIRMATION)) {
                transport.authorization(AuthorizationDetails(AuthorizationPhase.OTHER_STEP, otherStep = other))
                runCurrent()
                assertFalse(engine.state.value.canSubmitLogin(LoginRequestKind.PHONE))
                engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.PHONE, "+19995550123")
                runCurrent()
            }
            assertEquals(6, transport.requests.size)
        }
    }

    @Test
    fun mobileFirebaseVerificationIsNotSubmittedAsAnOrdinaryCode() = runTest {
        withEngine { (engine, transport) ->
            transport.authorization(AuthorizationDetails(AuthorizationPhase.WAITING_CODE, LoginCodeDelivery.FIREBASE, 6))
            runCurrent()
            assertFalse(engine.state.value.canSubmitLogin(LoginRequestKind.CODE))
            engine.submitLogin(engine.state.value.loginToken, LoginRequestKind.CODE, "123456")
            runCurrent()
            assertTrue(transport.requests.isEmpty())
            assertTrue(engine.state.value.canSubmitLogin(LoginRequestKind.PHONE))
        }
    }

    @Test
    fun stoppingDuringLoginClearsPendingStateAndIgnoresCallbacksFromTheOldGeneration() = runTest {
        withEngine { (engine, transport) ->
            val oldToken = engine.state.value.loginToken
            engine.submitLogin(oldToken, LoginRequestKind.PHONE, "+19995550123")
            runCurrent()
            val oldRequest = transport.requests.single()
            transport.autoClose = false
            val stopping = async { engine.stop() }
            runCurrent()
            assertFalse(engine.state.value.login.pending)
            assertFalse(engine.state.value.canSubmitLogin(LoginRequestKind.PHONE))
            oldRequest.result(LoginResult(LoginError.INVALID_PHONE, 400))
            runCurrent()
            assertNull(engine.state.value.login.error)
            transport.authorization(AuthorizationPhase.CLOSED)
            runCurrent()
            assertTrue(stopping.await())
            engine.start()
            runCurrent()
            transport.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            transport.authorization(AuthorizationPhase.WAITING_LOGIN)
            runCurrent()
            engine.submitLogin(oldToken, LoginRequestKind.PHONE, "+19995550124")
            oldRequest.result(LoginResult())
            runCurrent()
            assertEquals(1, transport.requests.size)
            assertFalse(engine.state.value.login.pending)
            assertFalse(engine.state.value.login.awaitingAuthorization)
            assertNull(engine.state.value.login.error)
            assertTrue(engine.state.value.canSubmitLogin(LoginRequestKind.PHONE))
        }
    }

    @Test
    fun loginInputsNeverAppearInRuntimeSnapshotsOrEventLogs() = runTest {
        withEngine { (engine, transport) ->
            val inputs = listOf(
                Triple(AuthorizationPhase.WAITING_LOGIN, LoginRequestKind.PHONE, "+19995550123"),
                Triple(AuthorizationPhase.WAITING_CODE, LoginRequestKind.CODE, "synthetic private code"),
                Triple(AuthorizationPhase.WAITING_PASSWORD, LoginRequestKind.PASSWORD, "private synthetic password"),
            )
            for ((phase, kind, value) in inputs) {
                transport.authorization(phase)
                runCurrent()
                engine.submitLogin(engine.state.value.loginToken, kind, value)
                runCurrent()
                assertFalse(engine.state.value.toString().contains(value))
                transport.requests.last().result(LoginResult(LoginError.REQUEST_FAILED, 500))
                runCurrent()
                assertFalse(engine.state.value.toString().contains(value))
            }
            val journal = Files.readString(Path.of(assertNotNull(engine.state.value.eventLogPath)))
            for ((_, _, value) in inputs) assertFalse(journal.contains(value))
            assertTrue(engine.state.value.events.isNotEmpty())
            assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
        }
    }

    private suspend fun TestScope.assertPhoneChangeRefreshesChallenge(
        challenge: AuthorizationDetails,
        resultFirst: Boolean,
        nextKind: LoginRequestKind,
    ) {
        withEngine(challenge.phase) { (engine, transport) ->
            transport.authorization(challenge)
            runCurrent()
            val previousToken = engine.state.value.loginToken
            engine.submitLogin(previousToken, LoginRequestKind.PHONE, "+19995550124")
            runCurrent()
            val phone = transport.requests.single()
            assertTrue(engine.state.value.login.pending)
            if (resultFirst) {
                phone.result(LoginResult())
                runCurrent()
                assertTrue(engine.state.value.login.awaitingAuthorization)
                assertEquals(previousToken, engine.state.value.loginToken)
            }

            // The actual number changed, but phone numbers are deliberately
            // absent from AuthorizationDetails. Safe metadata can stay equal.
            transport.authorization(challenge)
            runCurrent()
            val refreshed = engine.state.value
            assertEquals(challenge, refreshed.login.challenge)
            assertNotEquals(previousToken, refreshed.loginToken)
            assertFalse(refreshed.login.pending)
            assertFalse(refreshed.login.awaitingAuthorization)
            assertNull(refreshed.login.error)
            assertTrue(refreshed.canSubmitLogin(nextKind))
            if (nextKind != LoginRequestKind.CODE) {
                assertFalse(refreshed.canSubmitLogin(LoginRequestKind.CODE))
            }

            // An obsolete form cannot submit against the new phone challenge.
            engine.submitLogin(previousToken, nextKind, "12345")
            runCurrent()
            assertEquals(1, transport.requests.size)
            val nextValue = if (nextKind == LoginRequestKind.PHONE) "+19995550125" else "12345"
            engine.submitLogin(refreshed.loginToken, nextKind, nextValue)
            runCurrent()
            assertEquals(2, transport.requests.size)
            assertTrue(engine.state.value.login.pending)
            phone.result(LoginResult(LoginError.INVALID_PHONE, 400))
            phone.result(LoginResult())
            runCurrent()
            assertEquals(refreshed.loginToken, engine.state.value.loginToken)
            assertTrue(engine.state.value.login.pending)
            assertFalse(engine.state.value.login.awaitingAuthorization)
            assertNull(engine.state.value.login.error)
        }
    }

    private suspend fun TestScope.withEngine(
        phase: AuthorizationPhase = AuthorizationPhase.WAITING_LOGIN,
        proxyResult: Int? = null,
        block: suspend TestScope.(Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("login-engine-test-")
        val path = root.resolve("tdlib.properties")
        Files.writeString(path, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        val transport = FakeTransport(proxyResult)
        val engine = syntheticOpenEngine(ConfigurationStore(path, root.resolve("data")), transport, StandardTestDispatcher(testScheduler))
        try {
            engine.start()
            runCurrent()
            transport.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            transport.authorization(phase)
            runCurrent()
            assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
            block(Fixture(engine, transport))
        } finally {
            transport.autoClose = true
            assertTrue(engine.close())
        }
    }

    private data class Fixture(val engine: TdEngine, val transport: FakeTransport)

    private class FakeTransport(private val proxyResult: Int?) : TdTransport {
        val sessions = mutableListOf<FakeSession>()
        val requests = mutableListOf<LoginRequest>()
        var autoClose = true
        var closed = 0

        fun authorization(phase: AuthorizationPhase) = authorization(AuthorizationDetails(phase))
        fun authorization(details: AuthorizationDetails) = sessions.last().authorization(details)
        override fun load() = NativeInfo("1.8.69", "0".repeat(40), "fixture-native-directory")
        override fun create(
            onAuthorization: (AuthorizationDetails) -> Unit,
            onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit,
            onChatEvent: (com.badnng.moe.chat.ChatEvent) -> Unit,
        ): TdSession = FakeSession(onAuthorization).also(sessions::add)

        inner class FakeSession(val authorization: (AuthorizationDetails) -> Unit) : SyntheticOpenKeySession {
            override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
            override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(proxyResult)
            override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) {
                requests += LoginRequest(kind, value, result)
            }
            override fun close() {
                closed++
                if (autoClose) authorization(AuthorizationDetails(AuthorizationPhase.CLOSED))
            }
        }
    }

    private class LoginRequest(val kind: LoginRequestKind, val value: String, val result: (LoginResult) -> Unit)
}
