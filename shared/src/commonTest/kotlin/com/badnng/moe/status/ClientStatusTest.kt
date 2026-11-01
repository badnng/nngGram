package com.badnng.moe.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ClientStatusTest {
    @Test
    fun initializedEngineDoesNotInventLoginOrConnection() {
        val state = ClientStatus().reduce(StatusEvent.EngineChanged(EnginePhase.INITIALIZED))
        assertEquals(AuthorizationPhase.UNKNOWN, state.authorization)
        assertEquals(ConnectionPhase.UNKNOWN, state.connection)
    }

    @Test
    fun connectionUpdatesPreserveAuthorizationAndEngineFailure() {
        val state = PreviewScenario.FAILURE.snapshot().copy(authorization = AuthorizationPhase.WAITING_LOGIN)
        for (phase in ConnectionPhase.entries) {
            val changed = state.reduce(StatusEvent.ConnectionChanged(phase))
            assertEquals(state.engine, changed.engine)
            assertEquals(state.failure, changed.failure)
            assertEquals(state.authorization, changed.authorization)
            assertEquals(phase, changed.connection)
        }
    }

    @Test
    fun authorizationUpdatesDoNotOverwriteNetwork() {
        val state = ClientStatus(connection = ConnectionPhase.WAITING_NETWORK)
        val changed = state.reduce(StatusEvent.AuthorizationChanged(AuthorizationPhase.READY))
        assertEquals(ConnectionPhase.WAITING_NETWORK, changed.connection)
        assertEquals(EnginePhase.NOT_STARTED, changed.engine)
    }

    @Test
    fun restartingEngineClearsItsPreviousErrorOnly() {
        val state = PreviewScenario.FAILURE.snapshot().copy(connection = ConnectionPhase.CONNECTING)
        assertNotNull(state.failure)
        val changed = state.reduce(StatusEvent.EngineChanged(EnginePhase.STARTING))
        assertNull(changed.failure)
        assertEquals(state.connection, changed.connection)
    }

    @Test
    fun waitingForLoginPreviewDoesNotClaimConnected() {
        val state = PreviewScenario.LOGIN.snapshot()
        assertEquals(EnginePhase.INITIALIZED, state.engine)
        assertEquals(AuthorizationPhase.WAITING_LOGIN, state.authorization)
        assertEquals("尚未获知", state.connection.presentation().label)
    }
}
