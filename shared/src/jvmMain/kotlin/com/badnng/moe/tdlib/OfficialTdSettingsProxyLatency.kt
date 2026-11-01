package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsProxy
import com.badnng.moe.settings.SettingsProxyLatency
import com.badnng.moe.settings.SettingsProxyType
import com.badnng.moe.settings.SettingsResult
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/** One native query. The caller owns bounded concurrency and supplies a thread-safe revocable owner. */
internal class OfficialTdSettingsProxyLatency(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun ping(expected: SettingsProxy, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProxyLatency>) -> Unit) {
        val completed = AtomicBoolean()
        fun current() = runCatching(isCurrent).getOrDefault(false)
        fun finish(value: SettingsResult<SettingsProxyLatency>) {
            if (completed.compareAndSet(false, true)) {
                val output = if (current()) value else SettingsResult(error = SettingsError.NOT_READY)
                runCatching { result(output) }
            }
        }
        if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        if (expected.type == SettingsProxyType.WEB) { finish(SettingsResult(error = SettingsError.UNSUPPORTED)); return }
        if (expected.id <= 0 || expected.server.isBlank() || expected.port !in 1..65535) {
            finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }

        read firstRead@{ first ->
            if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@firstRead }
            val list = first.value
            if (list == null) { finish(SettingsResult(error = first.error ?: SettingsError.REQUEST_FAILED)); return@firstRead }
            val native = runCatching { find(list, expected) }.getOrNull()
            val proxy = native?.proxy
            val frozenIdentity = proxy?.let(::identity)
            if (proxy == null || frozenIdentity == null) { finish(SettingsResult(error = SettingsError.CONFLICT)); return@firstRead }

            request<SettingsProxyLatency>(TdApi.PingProxy(proxy), { response ->
                if (response !is TdApi.Seconds) failure(response)
                else {
                    val millis = response.seconds * 1000.0
                    if (!response.seconds.isFinite() || response.seconds < 0.0 || !millis.isFinite() ||
                        millis >= Long.MAX_VALUE.toDouble()) failure()
                    else SettingsResult(SettingsProxyLatency(millis.roundToLong()))
                }
            }) measured@{ measurement ->
                if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@measured }
                // Detect removal/replacement, including credentials and HTTP-only changes, before publication.
                read lastRead@{ last ->
                    if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@lastRead }
                    val fresh = last.value
                    if (fresh == null) { finish(SettingsResult(error = last.error ?: SettingsError.REQUEST_FAILED)); return@lastRead }
                    val latest = runCatching { find(fresh, expected)?.proxy }.getOrNull()
                    if (latest == null || identity(latest) != frozenIdentity) finish(SettingsResult(error = SettingsError.CONFLICT))
                    else finish(measurement)
                }
            }
        }
    }

    private fun read(result: (SettingsResult<TdApi.AddedProxies>) -> Unit) =
        request(TdApi.GetProxies(), { response ->
            if (response is TdApi.AddedProxies && response.proxies != null) SettingsResult(response)
            else failure(response)
        }, result)

    /** The public identity must still equal the presented row, and the ID must be unique. */
    private fun find(list: TdApi.AddedProxies, expected: SettingsProxy): TdApi.AddedProxy? {
        var added: TdApi.AddedProxy? = null
        for (candidate in list.proxies.orEmpty()) {
            if (candidate.id != expected.id) continue
            if (added != null) return null
            added = candidate
        }
        val found = added ?: return null
        val proxy = found.proxy ?: return null
        val type = when (proxy.type) {
            is TdApi.ProxyTypeSocks5 -> SettingsProxyType.SOCKS5
            is TdApi.ProxyTypeHttp -> SettingsProxyType.HTTP
            is TdApi.ProxyTypeMtproto -> SettingsProxyType.MTPROTO
            else -> return null
        }
        return found.takeIf {
            proxy.server == expected.server && proxy.port == expected.port && type == expected.type &&
                found.isEnabled == expected.enabled
        }
    }

    /** Exists only for this in-flight native operation; never published or logged. */
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

    private fun <T> request(
        command: TdApi.Function<*>, map: (TdApi.Object) -> SettingsResult<T>, result: (SettingsResult<T>) -> Unit,
    ) {
        val completed = AtomicBoolean()
        fun finish(value: SettingsResult<T>) { if (completed.compareAndSet(false, true)) result(value) }
        try {
            send(command, { response -> finish(try { map(response) } catch (_: Exception) { failure() }) },
                { finish(SettingsResult(error = SettingsError.NOT_READY)) })
        } catch (_: Exception) { finish(failure()) }
    }
}
