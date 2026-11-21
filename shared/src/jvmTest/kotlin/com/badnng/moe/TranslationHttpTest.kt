package com.badnng.moe

import com.badnng.moe.tdlib.ProxyConfiguration
import com.badnng.moe.tdlib.ProxyType
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TranslationHttpTest {
    @Test
    fun authenticatedHttpsGetAndPostSendCredentialsOnlyOnConnectWithoutGlobalChanges() {
        val authenticator = Authenticator.getDefault()
        val disabledSchemes = System.getProperty("jdk.http.auth.tunneling.disabledSchemes")
        for (body in listOf(null, "private text".toByteArray())) {
            withProxy("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n") { config, received ->
                assertFailsWith<IOException> {
                    executeTranslationRequest(URI("https://origin.invalid/translate"), config, 2_000, 2_000, body)
                }
                val request = received()
                assertTrue(request.startsWith("CONNECT origin.invalid:443 HTTP/1.1\r\n"))
                assertTrue(request.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n", ignoreCase = true))
                assertFalse(request.lineSequence().any { it.startsWith("Authorization:", ignoreCase = true) })
                assertFalse(request.contains("private text"), "POST body must wait for a successful TLS tunnel")
            }
        }
        assertSame(authenticator, Authenticator.getDefault())
        assertEquals(disabledSchemes, System.getProperty("jdk.http.auth.tunneling.disabledSchemes"))
    }

    @Test
    fun httpProxyChallengeRetriesOnceWithoutOriginAuthorizationAndKeepsPostBody() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { socket ->
            socket.soTimeout = 5_000
            val executor = Executors.newSingleThreadExecutor()
            val requests = executor.submit<List<String>> {
                (0..1).map { attempt ->
                    socket.accept().use { client ->
                        client.soTimeout = 5_000
                        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val headers = readHeaders(input)
                        val length = headers.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) offset += input.read(body, offset, length - offset).also { check(it > 0) }
                        val reply = if (attempt == 0) "407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"proxy\""
                            else "200 OK"
                        client.getOutputStream().write("HTTP/1.1 $reply\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        headers + body.concatToString()
                    }
                }
            }
            try {
                val proxy = ProxyConfiguration(ProxyType.HTTP, "127.0.0.1", socket.localPort, "user", "pass")
                val response = executeTranslationRequest(URI("http://origin.invalid/translate"), proxy, 2_000, 2_000,
                    "private text".toByteArray())
                try { assertEquals(200, response.responseCode) } finally { response.disconnect() }
                val captured = requests.get(5, TimeUnit.SECONDS)
                assertFalse(captured[0].contains("Proxy-Authorization:"))
                assertTrue(captured[1].contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"))
                captured.forEach {
                    assertTrue(it.endsWith("private text"))
                    assertFalse(it.lineSequence().any { line -> line.startsWith("Authorization:", true) })
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun loopbackBypassesConfiguredAuthenticatedProxy() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                assertEquals(null, it.requestHeaders.getFirst("Proxy-Authorization"))
                assertEquals(null, it.requestHeaders.getFirst("Authorization"))
                it.sendResponseHeaders(200, -1)
            }
        }
        server.start()
        try {
            val proxy = ProxyConfiguration(ProxyType.HTTP, "127.0.0.1", 1, "user", "pass")
            val response = executeTranslationRequest(URI("http://127.0.0.1:${server.address.port}/"), proxy, 2_000, 2_000)
            try { assertEquals(200, response.responseCode) } finally { response.disconnect() }
        } finally { server.stop(0) }
    }

    private fun withProxy(reply: String, test: (ProxyConfiguration, () -> String) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { socket ->
            socket.soTimeout = 5_000
            val executor = Executors.newSingleThreadExecutor()
            val request = executor.submit<String> {
                socket.accept().use { client ->
                    client.soTimeout = 5_000
                    val headers = readHeaders(client.getInputStream().bufferedReader(Charsets.ISO_8859_1))
                    client.getOutputStream().write(reply.toByteArray())
                    headers
                }
            }
            try {
                test(ProxyConfiguration(ProxyType.HTTP, "127.0.0.1", socket.localPort, "user", "pass")) {
                    request.get(5, TimeUnit.SECONDS)
                }
            } finally { executor.shutdownNow() }
        }
    }

    private fun readHeaders(input: java.io.BufferedReader): String = buildString {
        while (true) {
            val line = input.readLine() ?: error("Proxy connection closed before headers")
            append(line).append("\r\n")
            if (line.isEmpty()) break
        }
    }
}
