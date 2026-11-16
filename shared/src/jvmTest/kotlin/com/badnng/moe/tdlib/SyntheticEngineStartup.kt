package com.badnng.moe.tdlib

import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.status.EnginePhase
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun requireSyntheticConfiguration(engine: TdEngine) {
    val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    val configuration = Path.of(engine.state.value.configPath).toAbsolutePath().normalize()
    val childRoot = temporary.parent
    val isolatedChild = temporary.fileName.toString() == "tmp" &&
        childRoot?.fileName?.toString()?.startsWith("native-acceptance-") == true &&
        configuration == childRoot.resolve("fixture.properties")
    require(configuration.startsWith(temporary) || isolatedChild) { "Only an isolated synthetic configuration may choose first setup." }
}

/** Ordinary-account fixtures explicitly make their first-user choice before native callbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.completeSyntheticLocalSetup(engine: TdEngine) {
    runCurrent()
    if (engine.localLockState.value.phase != LocalLockPhase.NEW_ROOT_SETUP) return
    requireSyntheticConfiguration(engine)
    assertFalse(engine.state.value.clientActive)
    engine.localLockActions.skipSetup()
    runCurrent()
    assertNotEquals(LocalLockPhase.NEW_ROOT_SETUP, engine.localLockState.value.phase,
        "The fixture's real OPEN commit must finish before native initialization.")
}

/** Real offline JVM fixtures wait for a new generation, without skipping any native assertions. */
internal suspend fun startSyntheticNativeEngine(engine: TdEngine, requests: Int = 1) {
    requireSyntheticConfiguration(engine)
    val previous = engine.state.value.generation
    assertTrue(engine.start())
    repeat(requests - 1) { engine.start() }
    val gate = withTimeout(40_000) {
        engine.state.first {
            it.generation > previous &&
                ((it.localLock.phase == LocalLockPhase.NEW_ROOT_SETUP && !it.localLock.busy) ||
                    it.status.engine in setOf(EnginePhase.FAILED, EnginePhase.WAITING_CONFIGURATION) ||
                    it.clientActive || it.localLock.phase == LocalLockPhase.RECOVERY)
        }
    }
    if (gate.localLock.phase == LocalLockPhase.NEW_ROOT_SETUP) {
        assertFalse(gate.clientActive)
        engine.localLockActions.skipSetup()
    }
}
