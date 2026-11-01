package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** One page-owned permit covers serial storage and proxy reads, including late physical callbacks. */
internal class OfficialTdSettingsProxyChecksRead(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun read(lease: SettingsProxyProbeLease, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsAdvancedData>) -> Unit,
    ) {
        if (!lease.beginProbe()) { runCatching { result(SettingsResult(error = SettingsError.CONFLICT)) }; return }
        val finished = AtomicBoolean()
        fun current() = runCatching(isCurrent).getOrDefault(false) && lease.current()
        fun finish(value: SettingsResult<SettingsAdvancedData>) {
            if (finished.compareAndSet(false, true)) {
                lease.finishProbe()
                val output = if (current()) value else SettingsResult(error = SettingsError.NOT_READY)
                runCatching { result(output) }
            }
        }
        if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }

        val trackedSend: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { command, receive, failed ->
            val once = AtomicBoolean()
            val stage = lease.beginStage()
            if (stage == null) {
                finish(SettingsResult(error = SettingsError.NOT_READY))
            } else {
                try {
                    val submitted = lease.dispatch {
                        send(command, { native ->
                            lease.deliver {
                                // Release physical ownership before interpreting or deduplicating the response.
                                lease.endStage(stage)
                                if (once.compareAndSet(false, true) && !finished.get()) {
                                    runCatching { receive(native) }.onFailure {
                                        finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
                                    }
                                }
                            }
                        }, {
                            lease.deliver {
                                lease.endStage(stage)
                                if (once.compareAndSet(false, true) && !finished.get()) {
                                    runCatching { failed() }.onFailure {
                                        finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
                                    }
                                }
                            }
                        })
                    }
                    if (!submitted) {
                        lease.endStage(stage)
                        if (once.compareAndSet(false, true)) finish(SettingsResult(error = SettingsError.NOT_READY))
                    }
                } catch (_: Throwable) {
                    // A throw can follow physical submission. Do not start another stage or release this one.
                    if (once.compareAndSet(false, true)) finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
                }
            }
        }
        val advanced = OfficialTdSettingsAdvanced(trackedSend)
        advanced.storage storage@{ first ->
            if (finished.get()) return@storage
            if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@storage }
            // The existing advanced read tries both parts; serialized order makes a later proxy error win.
            advanced.proxies proxies@{ second ->
                if (finished.get()) return@proxies
                if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@proxies }
                val error = second.error ?: first.error
                if (error != null) finish(SettingsResult(error = error))
                else finish(SettingsResult(SettingsAdvancedData(first.value, second.value.orEmpty())))
            }
        }
    }
}
