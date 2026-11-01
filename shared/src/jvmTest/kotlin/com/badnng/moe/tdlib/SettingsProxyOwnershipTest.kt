package com.badnng.moe.tdlib

import com.badnng.moe.status.LoginRequestKind
import com.badnng.moe.status.LoginResult
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class SettingsProxyOwnershipTest {
    @Test
    fun theMarkerRoundTripsOnlyOwnershipAndNeverStoresProxyData() = withDirectory { directory ->
        val path = directory.resolve("nested/settings-connection.properties")
        val ownership = SettingsProxyOwnership(path)
        assertFalse(ownership.usesNativeSelection())
        assertFalse(Files.exists(path))
        assertTrue(ownership.markNativeSelection())
        assertTrue(SettingsProxyOwnership(path).usesNativeSelection())
        assertEquals("version=1\nowner=tdlib\n", Files.readString(path))
        val values = Properties().apply { Files.newBufferedReader(path).use(::load) }
        assertEquals(setOf("version", "owner"), values.stringPropertyNames())
        assertTrue(ownership.markNativeSelection())
        Files.list(path.parent).use { assertEquals(1L, it.count()) }
    }

    @Test
    fun invalidVersionsOwnersExtraFieldsAndMalformedFilesKeepLegacyOwnership() = withDirectory { directory ->
        val path = directory.resolve("settings-connection.properties")
        val ownership = SettingsProxyOwnership(path)
        for (text in listOf(
            "",
            "version=2\nowner=tdlib\n",
            "version=1\nowner=other\n",
            "owner=tdlib\n",
            "version=1\nowner=tdlib\npassword=private\n",
            "version=1\nowner=\\uNOT_HEX\n",
            "version=1\nowner=tdlib\n#" + "x".repeat(256),
        )) {
            Files.writeString(path, text)
            assertFalse(ownership.usesNativeSelection())
        }
        assertFalse(SettingsProxyOwnership(directory).usesNativeSelection())
    }

    @Test
    fun failedReplacementKeepsTheExistingTargetAndRemovesItsTemporaryMarker() = withDirectory { directory ->
        val occupied = Files.createDirectory(directory.resolve("settings-connection.properties"))
        Files.writeString(occupied.resolve("keep.txt"), "retain me")
        assertFalse(SettingsProxyOwnership(occupied).markNativeSelection())
        assertEquals("retain me", Files.readString(occupied.resolve("keep.txt")))
        Files.list(directory).use { assertEquals(1L, it.count()) }

        val blockedParent = directory.resolve("not-a-directory")
        Files.writeString(blockedParent, "retain parent")
        assertFalse(SettingsProxyOwnership(blockedParent.resolve("settings-connection.properties")).markNativeSelection())
        assertEquals("retain parent", Files.readString(blockedParent))
    }

    @Test
    fun nativeSelectionBeyondAnyUiBoundIsConfirmedBeforeNetworkingResumes() {
        val fixture = TransportFixture()
        val results = mutableListOf<Int?>()
        fixture.session.resumeConfiguredProxy(results::add)
        val read = fixture.requests.single()
        assertIs<TdApi.GetProxies>(read.command)
        read.respond(TdApi.AddedProxies(Array(300) { index ->
            TdApi.AddedProxy(index + 1, 0, index == 299, "",
                TdApi.Proxy("proxy.example", 1080, TdApi.ProxyTypeSocks5("private", "private-password")))
        }))
        assertEquals(300, assertIs<TdApi.EnableProxy>(fixture.requests.last().command).proxyId)
        read.respond(TdApi.Error(403, "late private proxy data"))
        read.fail()
        assertEquals(2, fixture.requests.size)
        assertTrue(results.isEmpty())
        val confirmation = fixture.requests.last()
        confirmation.respond(TdApi.Ok())
        confirmation.respond(TdApi.Error(400, "late private credentials"))
        assertEquals(3, fixture.requests.size)
        assertIs<TdApi.NetworkTypeOther>(assertIs<TdApi.SetNetworkType>(fixture.requests.last().command).type)
        assertTrue(results.isEmpty())
        fixture.requests.last().respond(TdApi.Ok())
        fixture.requests.last().fail()
        assertEquals(listOf<Int?>(null), results)
        assertFalse(results.toString().contains("private"))
    }

    @Test
    fun nativeDirectModeIsConfirmedAndAnOfflineSessionNeverEnablesNetworking() {
        val fixture = TransportFixture(offline = true)
        val results = mutableListOf<Int?>()
        fixture.session.resumeConfiguredProxy(results::add)
        fixture.requests.single().respond(TdApi.AddedProxies(emptyArray()))
        assertIs<TdApi.DisableProxy>(fixture.requests.last().command)
        assertTrue(results.isEmpty())
        fixture.requests.last().respond(TdApi.Ok())
        assertEquals(listOf<Int?>(null), results)
        assertEquals(2, fixture.requests.size)
        assertFalse(fixture.requests.any { it.command is TdApi.SetNetworkType })
    }

    @Test
    fun failedNativeReadsOrConfirmationsNeverFallBackToDirectModeOrEnableNetworking() {
        val readFixture = TransportFixture()
        val readResults = mutableListOf<Int?>()
        readFixture.session.resumeConfiguredProxy(readResults::add)
        val read = readFixture.requests.single()
        read.respond(TdApi.Error(429, "private native proxy password"))
        read.respond(TdApi.AddedProxies(emptyArray()))
        read.fail()
        assertEquals(listOf<Int?>(429), readResults)
        assertEquals(1, readFixture.requests.size)

        val confirmationFixture = TransportFixture()
        val confirmationResults = mutableListOf<Int?>()
        confirmationFixture.session.resumeConfiguredProxy(confirmationResults::add)
        confirmationFixture.requests.single().respond(TdApi.AddedProxies(arrayOf(
            TdApi.AddedProxy(7, 0, true, "", TdApi.Proxy("proxy.example", 443, TdApi.ProxyTypeMtproto("ab".repeat(16)))),
        )))
        confirmationFixture.requests.last().respond(TdApi.Error(403, "private proxy details"))
        confirmationFixture.requests.last().respond(TdApi.Ok())
        assertEquals(listOf<Int?>(403), confirmationResults)
        assertEquals(2, confirmationFixture.requests.size)
        assertFalse(confirmationFixture.requests.any { it.command is TdApi.SetNetworkType })
        assertFalse((readResults.toString() + confirmationResults).contains("private"))
    }

    @Test
    fun droppedThrownOrMalformedReadsFailOnceWithoutASelectionRequest() {
        val fixture = TransportFixture()
        val results = mutableListOf<Int?>()
        fixture.session.resumeConfiguredProxy(results::add)
        fixture.requests.single().fail()
        fixture.requests.single().respond(TdApi.AddedProxies(emptyArray()))
        assertEquals(listOf<Int?>(-1), results)
        assertEquals(1, fixture.requests.size)

        val malformed = TransportFixture()
        val malformedResults = mutableListOf<Int?>()
        malformed.session.resumeConfiguredProxy(malformedResults::add)
        malformed.requests.single().respond(TdApi.AddedProxies())
        assertEquals(listOf<Int?>(-1), malformedResults)
        assertEquals(1, malformed.requests.size)

        val thrownResults = mutableListOf<Int?>()
        OfficialTdSession(false) { _, _, _ -> throw IllegalStateException("private exception") }
            .resumeConfiguredProxy(thrownResults::add)
        assertEquals(listOf<Int?>(-1), thrownResults)
    }

    @Test
    fun legacySessionImplementationsRetainTheirExistingConfigurationFallback() {
        val calls = mutableListOf<ProxyConfiguration?>()
        val session = object : TdSession {
            override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) { }
            override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) { calls += proxy; result(null) }
            override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) { }
            override fun close() { }
        }
        val results = mutableListOf<Int?>()
        session.resumeConfiguredProxy(results::add)
        assertEquals(listOf<ProxyConfiguration?>(null), calls)
        assertEquals(listOf<Int?>(null), results)
    }

    private class TransportFixture(offline: Boolean = false) {
        val requests = mutableListOf<Pending>()
        val session = OfficialTdSession(offline) { command, respond, fail -> requests += Pending(command, respond, fail) }
    }

    private data class Pending(
        val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit,
    )

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-proxy-ownership-test-")
        try { block(directory) }
        finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
