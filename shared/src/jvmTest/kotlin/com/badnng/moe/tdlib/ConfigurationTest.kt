package com.badnng.moe.tdlib

import com.badnng.moe.status.FailureCategory
import java.nio.file.Files
import kotlin.test.*

class ConfigurationTest {
    @Test fun bundledIdentityWorksOnFirstLaunchWithoutCopyingItIntoLocalConfiguration() {
        val root = Files.createTempDirectory("bundled-config-")
        val store = ConfigurationStore(root.resolve("config.properties"), root.resolve("data"),
            bundledApiId = "12345", bundledApiHash = "b".repeat(32))
        repeat(2) {
            val config = assertNotNull(store.read())
            assertEquals(12345, config.apiId)
            assertEquals("b".repeat(32), config.apiHash)
            assertEquals(root.resolve("data"), config.dataDirectory)
        }
        assertFalse(Files.readString(store.path).contains("b".repeat(32)))
        assertFalse(Files.exists(root.resolve("data")))
    }

    @Test fun localIdentityOverridesTheWholeBundleAndPartialOverridesNeverMixPairs() {
        val root = Files.createTempDirectory("override-config-")
        val path = root.resolve("config.properties")
        val store = ConfigurationStore(path, root.resolve("data"),
            bundledApiId = "12345", bundledApiHash = "b".repeat(32))
        Files.writeString(path, "api_id=67890\napi_hash=${"a".repeat(32)}\nproxy_type=socks5\nproxy_host=localhost\nproxy_port=1080\n")
        val config = assertNotNull(store.read())
        assertEquals(67890, config.apiId)
        assertEquals("a".repeat(32), config.apiHash)
        assertEquals(1080, config.proxy?.port)
        Files.writeString(path, "api_id=67890\n")
        assertNull(store.read())
        Files.writeString(path, "api_hash=${"a".repeat(32)}\n")
        assertNull(store.read())
    }

    @Test
    fun relativeConfigurationPathIsReportedDuringStartupWithoutWritingToTheWorkingDirectory() {
        val store = ConfigurationStore(java.nio.file.Path.of("relative.properties"), Files.createTempDirectory("unused-data-"))
        assertFailsWith<SetupFailure> { store.read() }
    }

    @Test
    fun missingCredentialsCreateOnlyAnEmptyPrivateTemplate() {
        val root = Files.createTempDirectory("config-")
        val store = ConfigurationStore(root.resolve("local/config.properties"), root.resolve("data"))
        assertNull(store.read())
        assertNull(store.read())
        assertTrue(Files.readString(store.path).contains("api_id=\n"))
        assertFalse(Files.exists(root.resolve("data")))
    }

    @Test
    fun unicodeAndSpacesSurviveUtf8ConfigurationAndDatabasePreparation() {
        val root = Files.createTempDirectory("中文 配置 ")
        val data = root.resolve("用户 数据")
        val path = root.resolve("local.properties")
        Files.writeString(path, "api_id=12345\napi_hash=${"a".repeat(32)}\ndata_directory=${data.toString().replace('\\', '/')}\n")
        val config = assertNotNull(ConfigurationStore(path, root.resolve("unused")).read())
        assertEquals(data, config.dataDirectory)
        DataDirectoryLease.acquire(data).use { lease ->
            assertTrue(Files.isDirectory(lease.database))
            assertTrue(Files.isDirectory(lease.files))
            assertEquals(FailureCategory.DIRECTORY, assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(data) }.category)
        }
        DataDirectoryLease.acquire(data).close()
    }

    @Test
    fun invalidCredentialsNeverAppearInErrorsOrConfigurationText() {
        val root = Files.createTempDirectory("invalid-config-")
        val path = root.resolve("local.properties")
        val secret = "private-value-that-must-not-leak"
        Files.writeString(path, "api_id=12345\napi_hash=$secret\n")
        val failure = assertFailsWith<SetupFailure> { ConfigurationStore(path, root.resolve("data")).read() }
        assertFalse(failure.toString().contains(secret))
        assertFalse(failure.recovery.contains(secret))
        assertFalse(TdConfiguration(12345, secret, root).toString().contains(secret))
    }

    @Test
    fun fileInPlaceOfDataDirectoryFailsWithoutKeepingTheLease() {
        val root = Files.createTempDirectory("bad-data-")
        Files.writeString(root.resolve("database"), "occupied")
        assertEquals(FailureCategory.DIRECTORY, assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root) }.category)
        Files.delete(root.resolve("database"))
        DataDirectoryLease.acquire(root).close()
    }

    @Test
    fun missingProxySettingsMeanDirectAndDoNotRewriteAnExistingConfiguration() {
        val root = Files.createTempDirectory("direct-config-")
        val path = root.resolve("local.properties")
        val contents = "api_id=12345\napi_hash=${"a".repeat(32)}\n"
        Files.writeString(path, contents)
        assertNull(assertNotNull(ConfigurationStore(path, root).read()).proxy)
        assertEquals(contents, Files.readString(path))
    }

    @Test
    fun supportedProxySettingsPreserveCredentialsWithoutExposingThemInText() {
        val root = Files.createTempDirectory("proxy-config-")
        val path = root.resolve("local.properties")
        for ((value, type) in listOf("socks5" to ProxyType.SOCKS5, "http" to ProxyType.HTTP)) {
            Files.writeString(path, "api_id=12345\napi_hash=${"a".repeat(32)}\nproxy_type=$value\nproxy_host=localhost\nproxy_port=1080\nproxy_username=test-user\nproxy_password=test-private-password\n")
            val config = assertNotNull(ConfigurationStore(path, root).read())
            val proxy = assertNotNull(config.proxy)
            assertEquals(type, proxy.type)
            assertEquals("localhost", proxy.host)
            assertEquals(1080, proxy.port)
            assertEquals("test-user", proxy.username)
            assertEquals("test-private-password", proxy.password)
            for (secret in listOf(proxy.host, proxy.username, proxy.password)) {
                assertFalse(proxy.toString().contains(secret))
                assertFalse(config.toString().contains(secret))
            }
        }
    }

    @Test
    fun invalidProxyFieldsFailLocallyWithoutEchoingTheirValues() {
        val root = Files.createTempDirectory("bad-proxy-config-")
        val path = root.resolve("local.properties")
        val invalidSettings = listOf(
            "proxy_type=private-invalid-kind\n",
            "proxy_type=socks5\nproxy_host=\nproxy_port=1080\n",
            "proxy_type=socks5\nproxy_host=http://private-host\nproxy_port=1080\n",
            "proxy_type=socks5\nproxy_host=private-host:1080\nproxy_port=1080\n",
            "proxy_type=socks5\nproxy_host=127.0.0.1:1080\nproxy_port=1080\n",
            "proxy_type=socks5\nproxy_host=[::1]:1080\nproxy_port=1080\n",
            "proxy_type=http\nproxy_host=private-host\nproxy_port=0\n",
            "proxy_type=http\nproxy_host=private-host\nproxy_port=65536\n",
            "proxy_type=http\nproxy_host=private-host\nproxy_port=private-port\n",
        )
        for (settings in invalidSettings) {
            Files.writeString(path, "api_id=12345\napi_hash=${"a".repeat(32)}\n$settings")
            val failure = assertFailsWith<SetupFailure> { ConfigurationStore(path, root).read() }
            assertEquals(FailureCategory.CONFIGURATION, failure.category)
            assertFalse(failure.summary.contains("private-"))
            assertFalse(failure.recovery.contains("private-"))
        }
    }

    @Test
    fun ipv6ProxyLiteralsRemainSeparateFromThePort() {
        val root = Files.createTempDirectory("ipv6-proxy-config-")
        val path = root.resolve("local.properties")
        for (host in listOf("::1", "[::1]")) {
            Files.writeString(path, "api_id=12345\napi_hash=${"a".repeat(32)}\nproxy_type=socks5\nproxy_host=$host\nproxy_port=1080\n")
            val proxy = assertNotNull(assertNotNull(ConfigurationStore(path, root).read()).proxy)
            assertEquals("::1", proxy.host)
            assertEquals(1080, proxy.port)
        }
    }

    @Test
    fun unreadableConfigurationHasConfigurationCategory() {
        val root = Files.createTempDirectory("config-is-directory-")
        val failure = assertFailsWith<SetupFailure> { ConfigurationStore(root, root.resolve("data")).read() }
        assertEquals(FailureCategory.CONFIGURATION, failure.category)
    }

    @Test
    fun repeatedLeaseCloseIsSafeAndAllowsReopeningTheDatabaseDirectory() {
        val root = Files.createTempDirectory("close-lease-")
        val lease = DataDirectoryLease.acquire(root)
        lease.close()
        lease.close()
        DataDirectoryLease.acquire(root).close()
    }
}
