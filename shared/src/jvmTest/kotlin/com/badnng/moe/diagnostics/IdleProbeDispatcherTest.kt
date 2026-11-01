package com.badnng.moe.diagnostics

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class IdleProbeDispatcherTest {
    @Test
    fun deadlineAndReportFinishWhileTheSceneDispatcherIsPaused() = runBlocking {
        val scene = PausableSceneDispatcher()
        val scope = CoroutineScope(SupervisorJob() + scene)
        val reported = CompletableDeferred<IdleProbeMeasurement>()
        val recorder = IdleProbeRecorder()
        try {
            scope.launch {
                runIdleProbeOffScene {
                    recorder.begin()
                    delay(50)
                    // The real probe also writes its report before this block returns.
                    reported.complete(recorder.finish())
                }
            }
            scene.runNext() // Mount once, then simulate the minimized scene's stopped drain.
            val result = withTimeout(5_000) { reported.await() }
            assertTrue(result.elapsedMillis >= 40)
            assertEquals(0L, result.activeMillis)
        } finally {
            scope.cancel()
            while (scene.runNext()) Unit
        }
    }

    private class PausableSceneDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runNext(): Boolean {
            val task = queue.poll() ?: return false
            task.run()
            return true
        }
    }
}
