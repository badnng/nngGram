package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** A single page-owned permit covers the complete ordered native link chain. */
internal class OfficialTdSettingsProxyShare(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun share(target: SettingsProxyShareTarget, isHttp: Boolean, lease: SettingsProxyProbeLease,
        isCurrent: () -> Boolean, result: (SettingsResult<List<String>>) -> Unit,
    ) {
        if (!lease.beginProbe()) { runCatching { result(SettingsResult(error = SettingsError.CONFLICT)) }; return }
        val finished = AtomicBoolean()
        val links = mutableListOf<String>()
        fun current() = runCatching(isCurrent).getOrDefault(false) && lease.current()
        fun finish(value: SettingsResult<List<String>>) {
            if (finished.compareAndSet(false, true)) {
                links.clear()
                lease.finishProbe()
                val output = if (current()) value else SettingsResult(error = SettingsError.NOT_READY)
                runCatching { result(output) }
            }
        }
        if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        val expected = when (target) {
            is SettingsProxyShareTarget.One -> listOf(target.proxy)
            is SettingsProxyShareTarget.All -> target.rows.toList()
        }
        if (expected.any { it.id <= 0 || it.server.isBlank() || it.port !in 1..65535 } ||
            expected.map { it.id }.toSet().size != expected.size ||
            (target is SettingsProxyShareTarget.One && target.index < 0)) {
            finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        if (target is SettingsProxyShareTarget.All && !isHttp) {
            finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        val unexpected = { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        read(lease, unexpected) first@{ first ->
            if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@first }
            val firstList = first.value
            if (firstList == null) { finish(SettingsResult(error = first.error ?: SettingsError.REQUEST_FAILED)); return@first }
            val native = runCatching { capture(firstList, expected, target is SettingsProxyShareTarget.All) }.getOrNull()
            if (native == null) { finish(SettingsResult(error = SettingsError.CONFLICT)); return@first }
            if (target is SettingsProxyShareTarget.One && target.proxy.type == SettingsProxyType.HTTP) {
                finish(SettingsResult(error = SettingsError.UNSUPPORTED)); return@first
            }
            val selectedNative = when (target) {
                is SettingsProxyShareTarget.One -> listOf(native.entries.getValue(target.proxy.id))
                is SettingsProxyShareTarget.All -> native.order.map { native.entries.getValue(it) }
                    .filter { it.identity.type != SettingsProxyType.HTTP }
            }
            if (selectedNative.isEmpty()) { finish(SettingsResult(error = SettingsError.UNSUPPORTED)); return@first }
            var textUnits = 0L

            fun next(index: Int) {
                if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
                if (index == selectedNative.size) {
                    read(lease, unexpected) last@{ last ->
                        if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@last }
                        val latestList = last.value
                        if (latestList == null) { finish(SettingsResult(error = last.error ?: SettingsError.REQUEST_FAILED)); return@last }
                        val latest = runCatching { capture(latestList, expected, target is SettingsProxyShareTarget.All) }.getOrNull()
                        if (latest == null || latest.identities != native.identities || latest.order != native.order ||
                            selectedNative.any { identity(it.proxy) != it.identity }) {
                            finish(SettingsResult(error = SettingsError.CONFLICT))
                        } else finish(SettingsResult(links.toList()))
                    }
                    return
                }
                val entry = selectedNative[index]
                // Do not synthesize Proxy from a redacted row, or permit a mutated object.
                if (identity(entry.proxy) != entry.identity) { finish(SettingsResult(error = SettingsError.CONFLICT)); return }
                request(lease, TdApi.GetInternalLink(TdApi.InternalLinkTypeProxy(entry.proxy), isHttp), { response ->
                    if (response is TdApi.HttpUrl && !response.url.isNullOrBlank() &&
                        '\u0000' !in response.url &&
                        (if (isHttp) response.url.startsWith("https://") else response.url.startsWith("tg://"))) {
                        SettingsResult(response.url)
                    } else failure(response)
                }, unexpected) link@{ link ->
                    if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@link }
                    if (identity(entry.proxy) != entry.identity) { finish(SettingsResult(error = SettingsError.CONFLICT)); return@link }
                    val value = link.value
                    if (value == null) { finish(SettingsResult(error = link.error ?: SettingsError.REQUEST_FAILED)); return@link }
                    val units = textUnits + value.length + (if (links.isEmpty()) 0 else 2)
                    // Win32's UTF-16 text buffer also contains its terminating code unit.
                    if (units > MAX_SHARE_TEXT_UNITS) {
                        links.clear()
                        finish(SettingsResult(error = SettingsError.INVALID_VALUE))
                        return@link
                    }
                    textUnits = units
                    links.add(value)
                    next(index + 1)
                }
            }
            next(0)
        }
    }

    /** Map native objects only inside this in-flight operation; never expose their credentials. */
    private fun capture(list: TdApi.AddedProxies, expected: List<SettingsProxy>, all: Boolean): Capture? {
        val source = list.proxies ?: return null
        if (all && settingsProxyRows(source) != expected) return null
        val expectedIds = expected.mapTo(mutableSetOf()) { it.id }
        val entries = mutableMapOf<Int, NativeEntry>()
        val identities = mutableMapOf<Int, NativeIdentity>()
        val order = mutableListOf<Int>()
        for (added in source) {
            if (added == null) return null
            if (!all && added.id !in expectedIds) continue
            if (added.id <= 0 || added.id in identities) return null
            // An unrecognised or missing native proxy could be shareable: do not silently omit it.
            val proxy = added.proxy ?: return null
            val identity = identity(proxy) ?: return null
            order.add(added.id)
            identities[added.id] = identity.copy(enabled = added.isEnabled)
            if (all || added.id in expectedIds) entries[added.id] = NativeEntry(proxy, identity)
        }
        for (row in expected) {
            val entry = entries[row.id] ?: return null
            val native = identities[row.id] ?: return null
            if (native.server != row.server || native.port != row.port || native.type != row.type ||
                native.enabled != row.enabled || identity(entry.proxy) != entry.identity) return null
        }
        if (all && order.filter { it in expectedIds } != expected.map { it.id }) return null
        return Capture(entries, if (all) identities else identities.filterKeys { it in expectedIds },
            if (all) order else emptyList())
    }

    private class Capture(val entries: Map<Int, NativeEntry>, val identities: Map<Int, NativeIdentity>, val order: List<Int>)
    private class NativeEntry(val proxy: TdApi.Proxy, val identity: NativeIdentity)
    private data class NativeIdentity(
        val server: String, val port: Int, val type: SettingsProxyType,
        val username: String? = null, val password: String? = null, val secret: String? = null,
        val path: String? = null, val httpOnly: Boolean = false, val enabled: Boolean = false,
    ) { override fun toString() = "NativeIdentity(<redacted>)" }

    private fun identity(proxy: TdApi.Proxy): NativeIdentity? {
        val server = proxy.server ?: return null
        if (server.isBlank() || proxy.port !in 1..65535) return null
        return when (val type = proxy.type) {
            is TdApi.ProxyTypeSocks5 -> NativeIdentity(server, proxy.port, SettingsProxyType.SOCKS5,
                username = type.username ?: return null, password = type.password ?: return null)
            is TdApi.ProxyTypeHttp -> NativeIdentity(server, proxy.port, SettingsProxyType.HTTP,
                username = type.username ?: return null, password = type.password ?: return null, httpOnly = type.httpOnly)
            is TdApi.ProxyTypeMtproto -> NativeIdentity(server, proxy.port, SettingsProxyType.MTPROTO,
                secret = type.secret ?: return null)
            is TdApi.ProxyTypeWeb -> NativeIdentity(server, proxy.port, SettingsProxyType.WEB,
                path = type.path ?: return null, secret = type.secret ?: return null)
            else -> null
        }
    }

    private fun read(lease: SettingsProxyProbeLease, unexpected: () -> Unit,
        result: (SettingsResult<TdApi.AddedProxies>) -> Unit,
    ) = request(lease, TdApi.GetProxies(), { response ->
        if (response is TdApi.AddedProxies && response.proxies != null) SettingsResult(response) else failure(response)
    }, unexpected, result)

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
                        complete(runCatching { map(native) }.getOrElse { failure() })
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
            // Unknown synchronous throw may follow submission: only the raw callback drains this stage.
            if (once.compareAndSet(false, true)) unexpected()
        }
    }

    private companion object {
        const val MAX_SHARE_TEXT_UNITS = 64L * 1024 * 1024 / 2 - 1
    }
}
