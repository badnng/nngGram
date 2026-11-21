package com.badnng.moe.tdlib

import com.badnng.moe.SetupDraft
import com.badnng.moe.SetupProxyMode
import com.badnng.moe.platform.DesktopPlatform
import java.nio.file.Files
import kotlin.test.*

class SystemProxyTest {
    private val scutil = """
        <dictionary> {
          HTTPEnable : 1
          HTTPPort : 7897
          HTTPProxy : 127.0.0.1
          SOCKSEnable : 1
          SOCKSPort : 7898
          SOCKSProxy : 127.0.0.1
        }
    """.trimIndent()

    @Test fun macOsPrefersSocksAndFallsBackToHttp() {
        val socks = assertNotNull(SystemProxyDetector.detect(DesktopPlatform.MACOS_ARM64, emptyMap()) { scutil })
        assertEquals(ProxyType.SOCKS5, socks.type)
        assertEquals("127.0.0.1", socks.host)
        assertEquals(7898, socks.port)
        val http = assertNotNull(SystemProxyDetector.detect(DesktopPlatform.MACOS_X64, emptyMap()) {
            scutil.replace("SOCKSEnable : 1", "SOCKSEnable : 0")
        })
        assertEquals(ProxyType.HTTP, http.type)
        assertEquals(7897, http.port)
        assertNull(SystemProxyDetector.detect(DesktopPlatform.MACOS_X64, emptyMap()) { "<dictionary> {\n}\n" })
    }

    @Test fun httpsDownloadsPreferTheSystemHttpProxyEvenWithoutShellEnvironment() {
        val proxy = assertNotNull(SystemProxyDetector.detectForHttp(DesktopPlatform.MACOS_ARM64, emptyMap()) { scutil })
        assertEquals(ProxyType.HTTP, proxy.type)
        assertEquals(7897, proxy.port)
        val fallback = assertNotNull(SystemProxyDetector.detectForHttp(DesktopPlatform.MACOS_ARM64, emptyMap()) {
            scutil.replace("HTTPEnable : 1", "HTTPEnable : 0")
        })
        assertEquals(ProxyType.SOCKS5, fallback.type)
        assertEquals(7898, fallback.port)
    }

    @Test fun windowsReadsBothRegistryFormsAndRespectsTheDisabledFlag() {
        fun registry(enable: String, server: String): (List<String>) -> String? = { command ->
            when (command.last()) {
                "ProxyEnable" -> "\r\nHKEY_CURRENT_USER\\Software\r\n    ProxyEnable    REG_DWORD    $enable\r\n"
                "ProxyServer" -> "\r\nHKEY_CURRENT_USER\\Software\r\n    ProxyServer    REG_SZ    $server\r\n"
                else -> null
            }
        }
        val bare = assertNotNull(SystemProxyDetector.detect(DesktopPlatform.WINDOWS_X64, emptyMap(), registry("0x1", "127.0.0.1:7890")))
        assertEquals(ProxyType.HTTP, bare.type)
        assertEquals(7890, bare.port)
        val split = assertNotNull(SystemProxyDetector.detect(DesktopPlatform.WINDOWS_X64, emptyMap(),
            registry("0x1", "http=127.0.0.1:7890;https=127.0.0.1:7890;socks=127.0.0.1:7891")))
        assertEquals(ProxyType.SOCKS5, split.type)
        assertEquals(7891, split.port)
        assertNull(SystemProxyDetector.detect(DesktopPlatform.WINDOWS_X64, emptyMap(), registry("0x0", "127.0.0.1:7890")))
    }

    @Test fun linuxUsesEnvironmentThenGnomeSettings() {
        val environment = assertNotNull(SystemProxyDetector.detect(DesktopPlatform.LINUX_X64,
            mapOf("all_proxy" to "socks5h://user:secret@127.0.0.1:1080")) { null })
        assertEquals(ProxyType.SOCKS5, environment.type)
        assertEquals("user", environment.username)
        assertEquals("secret", environment.password)
        val gnome = assertNotNull(SystemProxyDetector.detect(DesktopPlatform.LINUX_ARM64, emptyMap()) { command ->
            when (command.takeLast(2)) {
                listOf("org.gnome.system.proxy", "mode") -> "'manual'\n"
                listOf("org.gnome.system.proxy.socks", "host") -> "''\n"
                listOf("org.gnome.system.proxy.socks", "port") -> "0\n"
                listOf("org.gnome.system.proxy.https", "host") -> "'proxy.local'\n"
                listOf("org.gnome.system.proxy.https", "port") -> "8080\n"
                else -> null
            }
        })
        assertEquals(ProxyType.HTTP, gnome.type)
        assertEquals("proxy.local", gnome.host)
        assertNull(SystemProxyDetector.detect(DesktopPlatform.LINUX_X64, mapOf("https_proxy" to "https://tls-proxy:443")) { null })
    }

    @Test fun systemModeFollowsTheDetectedProxyAndFallsBackToDirect() {
        val root = Files.createTempDirectory("system-proxy-")
        val path = root.resolve("config.properties")
        Files.writeString(path, "api_id=12345\napi_hash=${"a".repeat(32)}\nproxy_type=system\n")
        var detected: ProxyConfiguration? = ProxyConfiguration(ProxyType.SOCKS5, "127.0.0.1", 7897)
        val store = ConfigurationStore(path, root.resolve("data"), systemProxy = { detected })
        assertEquals(7897, assertNotNull(store.read()).proxy?.port)
        assertEquals("SOCKS5 127.0.0.1:7897", store.systemProxyLabel())
        detected = ProxyConfiguration(ProxyType.HTTP, "bad host/", 1)
        assertNull(assertNotNull(store.read()).proxy)
        detected = null
        assertNull(assertNotNull(store.read()).proxy)
    }

    @Test fun guideSaveRewritesOnlyItsKeysAndRejectsInvalidInputWithoutWriting() {
        val root = Files.createTempDirectory("setup-guide-")
        val store = ConfigurationStore(root.resolve("config.properties"), root.resolve("data"))
        assertNull(store.read())
        assertEquals(SetupProxyMode.SYSTEM, store.setupSnapshot().draft.proxyMode)
        Files.writeString(store.path, Files.readString(store.path) + "use_test_dc=false\ndata_directory=${root.resolve("custom").toString().replace('\\', '/')}\n")
        val before = Files.readString(store.path)
        assertNotNull(store.saveSetup(SetupDraft(apiId = "12345", apiHash = "short")))
        assertNotNull(store.saveSetup(SetupDraft("12345", "a".repeat(32), SetupProxyMode.SOCKS5, "127.0.0.1:1", "1080")))
        assertEquals(before, Files.readString(store.path))
        assertNull(store.saveSetup(SetupDraft("12345", "a".repeat(32), SetupProxyMode.SOCKS5, "127.0.0.1", "1080", "u", "p \\x")))
        val saved = assertNotNull(store.read())
        assertEquals(12345, saved.apiId)
        assertEquals(1080, saved.proxy?.port)
        assertEquals("p \\x", saved.proxy?.password)
        assertEquals(root.resolve("custom"), saved.dataDirectory)
        assertEquals(SetupProxyMode.SOCKS5, store.setupSnapshot().draft.proxyMode)
        assertNull(store.saveSetup(SetupDraft("12345", "a".repeat(32), SetupProxyMode.DIRECT, "127.0.0.1", "1080")))
        assertNull(assertNotNull(store.read()).proxy)
        assertFalse(Files.readString(store.path).lines().any { it.startsWith("proxy_host=") })
    }
}
