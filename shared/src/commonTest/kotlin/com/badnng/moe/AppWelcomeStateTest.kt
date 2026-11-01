package com.badnng.moe

import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.EngineFailure
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.FailureCategory
import com.badnng.moe.status.ProxyStatus
import com.badnng.moe.status.RuntimeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppWelcomeStateTest {
    @Test fun anInitializedEngineAloneDoesNotSkipRealAuthorizationAndProxyPreparation() {
        val runtime = runtime(EnginePhase.INITIALIZED).copy(clientActive = true)
        assertEquals(AppWelcomePhase.INITIALIZING, runtime.appWelcomeState()?.phase)
        assertEquals(AppWelcomePhase.INITIALIZING, runtime.copy(proxy = ProxyStatus.DIRECT).appWelcomeState()?.phase)
        assertEquals(AppWelcomePhase.INITIALIZING, runtime.copy(status = runtime.status.copy(
            authorization = AuthorizationPhase.WAITING_LOGIN)).appWelcomeState()?.phase)
    }

    @Test fun actualQrPhoneCodePasswordAndExistingAuthorizationHandOffWithoutAnotherWelcome() {
        for (authorization in listOf(AuthorizationPhase.WAITING_LOGIN, AuthorizationPhase.WAITING_CODE,
            AuthorizationPhase.WAITING_PASSWORD, AuthorizationPhase.OTHER_STEP, AuthorizationPhase.READY)) {
            val runtime = runtime(EnginePhase.INITIALIZED).copy(clientActive = true, proxy = ProxyStatus.DIRECT,
                status = ClientStatus(EnginePhase.INITIALIZED, authorization))
            assertNull(runtime.appWelcomeState())
            assertNull(runtime.copy(proxy = ProxyStatus.ENABLED).appWelcomeState())
        }
    }

    @Test fun missingConfigurationAndRealInitializationExposeDifferentActions() {
        assertTrue(assertNotNull(runtime(EnginePhase.NOT_STARTED).appWelcomeState()).canContinue)
        val configuration = assertNotNull(runtime(EnginePhase.WAITING_CONFIGURATION).appWelcomeState())
        assertEquals(AppWelcomePhase.CONFIGURATION, configuration.phase)
        assertTrue(configuration.canContinue)
        val pending = assertNotNull(runtime(EnginePhase.STARTING).copy(clientActive = true, proxy = ProxyStatus.FAILED).appWelcomeState())
        assertEquals(AppWelcomePhase.INITIALIZING, pending.phase)
        assertFalse(pending.canContinue)
    }

    @Test fun failureAndConfigurationOpenErrorKeepTheirActualSourceWithoutInventingSuccess() {
        val failure = EngineFailure("无法准备客户端", "检查本地设置后重试。", FailureCategory.CONFIGURATION)
        val runtime = runtime(EnginePhase.FAILED).copy(generation = 8, status = ClientStatus(EnginePhase.FAILED, failure = failure))
        val state = assertNotNull(runtime.appWelcomeState(active = false, configurationOpenError = "配置文件暂时无法打开。"))
        assertEquals(AppWelcomePhase.FAILED, state.phase)
        assertSame(failure, state.failure)
        assertEquals(8L, state.generation)
        assertFalse(state.active)
        assertEquals("配置文件暂时无法打开。", state.configurationOpenError)
        assertTrue(state.canContinue)
    }

    @Test fun anActiveProxyFailureOffersOnlyAnExplicitRetryAndClosingNeverDoes() {
        val failed = runtime(EnginePhase.INITIALIZED).copy(clientActive = true, proxy = ProxyStatus.FAILED)
        assertEquals(AppWelcomePhase.FAILED, failed.appWelcomeState()?.phase)
        assertTrue(assertNotNull(failed.appWelcomeState()).canContinue)
        for (closed in listOf(runtime(EnginePhase.CLOSING), failed.copy(closeTimedOut = true), failed.copy(terminated = true))) {
            val state = assertNotNull(closed.appWelcomeState())
            assertEquals(AppWelcomePhase.CLOSING, state.phase)
            assertFalse(state.canContinue)
        }
    }

    private fun runtime(engine: EnginePhase) = RuntimeSnapshot(configPath = "synthetic-config", status = ClientStatus(engine))
}
