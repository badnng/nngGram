package com.badnng.moe.tdlib

import com.badnng.moe.platform.DesktopPlatform
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Reads the operating system's fixed proxy once. TDLib opens its own sockets and never
 * consults OS proxy settings, so `proxy_type=system` converts them into an explicit proxy.
 * PAC scripts are not evaluated; without a fixed proxy the result is null (direct).
 */
object SystemProxyDetector {
    private const val WINDOWS_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    fun detect(
        platform: DesktopPlatform = DesktopPlatform.current,
        environment: Map<String, String> = System.getenv(),
        run: (List<String>) -> String? = ::runCommand,
    ): ProxyConfiguration? = when {
        platform.isWindows -> windows(run) ?: environment(environment)
        platform.isMacOS -> macOS(run) ?: environment(environment)
        else -> environment(environment) ?: gnome(run)
    }

    internal fun environment(environment: Map<String, String>): ProxyConfiguration? =
        listOf("ALL_PROXY", "all_proxy", "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
            .firstNotNullOfOrNull { name -> environment[name]?.let(::fromUrl) }

    /** `scutil --proxy` prints `Key : value` rows; SOCKS is preferred because TDLib speaks MTProto, not HTTP. */
    internal fun macOS(run: (List<String>) -> String?): ProxyConfiguration? {
        val values = run(listOf("scutil", "--proxy"))?.lineSequence()?.mapNotNull { line ->
            val parts = line.split(" : ", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }?.toMap() ?: return null
        fun entry(prefix: String, type: ProxyType): ProxyConfiguration? =
            if (values["${prefix}Enable"] == "1") proxy(type, values["${prefix}Proxy"], values["${prefix}Port"]) else null
        return entry("SOCKS", ProxyType.SOCKS5) ?: entry("HTTPS", ProxyType.HTTP) ?: entry("HTTP", ProxyType.HTTP)
    }

    /** `ProxyServer` is either `host:port` for every protocol or `http=…;https=…;socks=…`. */
    internal fun windows(run: (List<String>) -> String?): ProxyConfiguration? {
        fun value(name: String): String? = run(listOf("reg", "query", WINDOWS_KEY, "/v", name))?.lineSequence()
            ?.map { it.trim() }?.firstOrNull { it.startsWith(name, ignoreCase = true) }
            ?.split(Regex("\\s+"), limit = 3)?.getOrNull(2)?.trim()
        val enabled = value("ProxyEnable")?.removePrefix("0x")?.toIntOrNull(16) ?: return null
        if (enabled == 0) return null
        val server = value("ProxyServer")?.takeIf { it.isNotEmpty() } ?: return null
        if ('=' !in server) return fromAuthority(ProxyType.HTTP, server)
        val entries = server.split(';').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2) pair[0].trim().lowercase() to pair[1].trim() else null
        }.toMap()
        return entries["socks"]?.let { fromAuthority(ProxyType.SOCKS5, it) }
            ?: entries["https"]?.let { fromAuthority(ProxyType.HTTP, it) }
            ?: entries["http"]?.let { fromAuthority(ProxyType.HTTP, it) }
    }

    /** GNOME-compatible desktops; other Linux desktops are covered by the proxy environment variables. */
    internal fun gnome(run: (List<String>) -> String?): ProxyConfiguration? {
        fun setting(schema: String, key: String): String? =
            run(listOf("gsettings", "get", schema, key))?.trim()?.removeSurrounding("'")
        if (setting("org.gnome.system.proxy", "mode") != "manual") return null
        fun entry(name: String, type: ProxyType): ProxyConfiguration? =
            proxy(type, setting("org.gnome.system.proxy.$name", "host"), setting("org.gnome.system.proxy.$name", "port"))
        return entry("socks", ProxyType.SOCKS5) ?: entry("https", ProxyType.HTTP) ?: entry("http", ProxyType.HTTP)
    }

    internal fun fromUrl(value: String): ProxyConfiguration? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return try {
            val uri = URI(if ("://" in text) text else "http://$text")
            val type = when (uri.scheme?.lowercase()) {
                "socks", "socks5", "socks5h" -> ProxyType.SOCKS5
                "http" -> ProxyType.HTTP
                else -> return null
            }
            val user = uri.userInfo?.split(':', limit = 2)
            proxy(type, uri.host, uri.port.toString(), user?.getOrNull(0).orEmpty(), user?.getOrNull(1).orEmpty())
        } catch (_: Exception) { null }
    }

    private fun fromAuthority(type: ProxyType, value: String): ProxyConfiguration? {
        val authority = value.substringAfter("://").trimEnd('/')
        val port = authority.substringAfterLast(':', "")
        return proxy(type, authority.substringBeforeLast(':').removeSurrounding("[", "]"), port)
    }

    private fun proxy(type: ProxyType, host: String?, port: String?, username: String = "", password: String = ""): ProxyConfiguration? {
        val name = host?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
        val number = port?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return ProxyConfiguration(type, name, number, username, password)
    }

    /** A missing tool, a non-zero exit or a slow answer all mean "no system proxy". */
    private fun runCommand(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) output
        else { process.destroyForcibly(); null }
    } catch (_: Exception) { null }
}
