package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsProxy
import com.badnng.moe.settings.SettingsProxyProbe
import com.badnng.moe.settings.SettingsProxyType
import com.badnng.moe.settings.SettingsResult
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/** One probe and its native source validation. No raw native data crosses this boundary. */
internal class OfficialTdSettingsProxyProbe(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun probe(expected: SettingsProxy, lease: SettingsProxyProbeLease, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsProxyProbe>) -> Unit,
    ) {
        if (!lease.beginProbe()) { runCatching { result(SettingsResult(error = SettingsError.CONFLICT)) }; return }
        val completed = AtomicBoolean()
        fun current() = runCatching(isCurrent).getOrDefault(false) && lease.current()
        fun finish(value: SettingsResult<SettingsProxyProbe>) {
            if (completed.compareAndSet(false, true)) {
                // Normal terminal paths release physical capacity before notifying the actor.
                lease.finishProbe()
                val output = if (current()) value else SettingsResult(error = SettingsError.NOT_READY)
                runCatching { result(output) }
            }
        }
        if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        if (expected.type == SettingsProxyType.WEB) { finish(SettingsResult(error = SettingsError.UNSUPPORTED)); return }
        if (expected.id <= 0 || expected.server.isBlank() || expected.port !in 1..65535) {
            finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }

        val unexpected = { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        read(lease, unexpected) firstRead@{ first ->
            if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@firstRead }
            val firstList = first.value
            if (firstList == null) { finish(SettingsResult(error = first.error ?: SettingsError.REQUEST_FAILED)); return@firstRead }
            val proxy = runCatching { find(firstList, expected)?.proxy }.getOrNull()
            val original = proxy?.let(::identity)
            if (proxy == null || original == null) { finish(SettingsResult(error = SettingsError.CONFLICT)); return@firstRead }

            request(lease, TdApi.PingProxy(proxy), ::measurement, unexpected) measured@{ measured ->
                if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@measured }
                if (measured.value == null) {
                    finish(SettingsResult(error = measured.error ?: SettingsError.REQUEST_FAILED)); return@measured
                }
                read(lease, unexpected) lastRead@{ last ->
                    if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@lastRead }
                    val latestList = last.value
                    if (latestList == null) { finish(SettingsResult(error = last.error ?: SettingsError.REQUEST_FAILED)); return@lastRead }
                    val latest = runCatching { find(latestList, expected)?.proxy }.getOrNull()
                    if (latest == null || identity(latest) != original) finish(SettingsResult(error = SettingsError.CONFLICT))
                    else finish(measured)
                }
            }
        }
    }

    private fun measurement(response: TdApi.Object): SettingsResult<SettingsProxyProbe> = when (response) {
        is TdApi.Seconds -> {
            val millis = response.seconds * 1000.0
            if (!response.seconds.isFinite() || response.seconds < 0.0 || !millis.isFinite() ||
                millis >= Long.MAX_VALUE.toDouble()) failure()
            else SettingsResult(SettingsProxyProbe.Available(millis.roundToLong()))
        }
        is TdApi.Error -> {
            val error = settingsError(response)
            // TDLib returns 400 for DNS, connect and handshake failures. Preserve known control errors.
            if (response.code == 400 && error == SettingsError.INVALID_VALUE) SettingsResult(SettingsProxyProbe.Unavailable)
            else SettingsResult(error = error)
        }
        else -> failure()
    }

    private fun read(lease: SettingsProxyProbeLease, unexpected: () -> Unit,
        result: (SettingsResult<TdApi.AddedProxies>) -> Unit,
    ) = request(lease, TdApi.GetProxies(), { response ->
        if (response is TdApi.AddedProxies && response.proxies != null) SettingsResult(response) else failure(response)
    }, unexpected, result)

    private fun find(list: TdApi.AddedProxies, expected: SettingsProxy): TdApi.AddedProxy? {
        var found: TdApi.AddedProxy? = null
        for (candidate in list.proxies.orEmpty()) {
            if (candidate.id != expected.id) continue
            if (found != null) return null
            found = candidate
        }
        val added = found ?: return null
        val proxy = added.proxy ?: return null
        val type = when (proxy.type) {
            is TdApi.ProxyTypeSocks5 -> SettingsProxyType.SOCKS5
            is TdApi.ProxyTypeHttp -> SettingsProxyType.HTTP
            is TdApi.ProxyTypeMtproto -> SettingsProxyType.MTPROTO
            else -> return null
        }
        return added.takeIf {
            proxy.server == expected.server && proxy.port == expected.port && type == expected.type &&
                added.isEnabled == expected.enabled
        }
    }

    private data class NativeIdentity(
        val server: String?, val port: Int, val type: SettingsProxyType,
        val username: String? = null, val password: String? = null,
        val secret: String? = null, val httpOnly: Boolean = false,
    ) {
        override fun toString() = "NativeIdentity(<redacted>)"
    }

    private fun identity(proxy: TdApi.Proxy): NativeIdentity? = when (val type = proxy.type) {
        is TdApi.ProxyTypeSocks5 -> NativeIdentity(proxy.server, proxy.port, SettingsProxyType.SOCKS5,
            type.username, type.password)
        is TdApi.ProxyTypeHttp -> NativeIdentity(proxy.server, proxy.port, SettingsProxyType.HTTP,
            type.username, type.password, httpOnly = type.httpOnly)
        is TdApi.ProxyTypeMtproto -> NativeIdentity(proxy.server, proxy.port, SettingsProxyType.MTPROTO,
            secret = type.secret)
        else -> null
    }

    private fun <T> failure(response: TdApi.Object? = null) =
        SettingsResult<T>(error = response?.let(::settingsError) ?: SettingsError.REQUEST_FAILED)

    private fun <T> request(lease: SettingsProxyProbeLease, command: TdApi.Function<*>,
        map: (TdApi.Object) -> SettingsResult<T>, unexpected: () -> Unit, result: (SettingsResult<T>) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun complete(value: SettingsResult<T>) {
            if (once.compareAndSet(false, true)) runCatching { result(value) }.onFailure { unexpected() }
        }
        val stage = lease.beginStage()
        if (stage == null) { complete(SettingsResult(error = SettingsError.NOT_READY)); return }
        try {
            val submitted = lease.dispatch {
                send(command, { native ->
                    lease.deliver {
                        lease.endStage(stage)
                        val mapped = runCatching { map(native) }.getOrElse { failure() }
                        complete(mapped)
                    }
                }, {
                    lease.deliver {
                        lease.endStage(stage)
                        complete(SettingsResult(error = SettingsError.NOT_READY))
                    }
                })
            }
            if (!submitted) {
                lease.endStage(stage)
                complete(SettingsResult(error = SettingsError.NOT_READY))
            }
        } catch (_: Throwable) {
            // A throwing send may already have submitted. Keep the stage until its raw callback.
            if (once.compareAndSet(false, true)) unexpected()
        }
    }
}
