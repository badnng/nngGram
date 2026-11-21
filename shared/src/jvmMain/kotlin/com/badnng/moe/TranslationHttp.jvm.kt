package com.badnng.moe

import com.badnng.moe.tdlib.ProxyConfiguration
import com.badnng.moe.tdlib.ProxyType
import com.badnng.moe.tdlib.SystemProxyDetector
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal fun translationWebProxy(): ProxyConfiguration? = SystemProxyDetector.detectForHttp()

internal interface TranslationHttpResponse {
    val responseCode: Int
    val inputStream: InputStream
    fun getHeaderField(name: String): String?
    fun disconnect()
}

/** Credentials belong only to this HTTP proxy, never to an origin or the JVM global authenticator. */
internal fun executeTranslationRequest(
    endpoint: URI,
    proxy: ProxyConfiguration?,
    connectTimeout: Int,
    readTimeout: Int,
    body: ByteArray? = null,
): TranslationHttpResponse {
    val selected = proxy.takeUnless { endpoint.host.lowercase() in loopbackTranslationHosts }
    if (selected?.type == ProxyType.HTTP && (selected.username.isNotEmpty() || selected.password.isNotEmpty())) {
        val address = InetSocketAddress.createUnresolved(selected.host, selected.port)
        val authorization = Credentials.basic(selected.username, selected.password)
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, address))
            .proxyAuthenticator { route, response ->
                // Also handles OkHttp's synthetic preemptive CONNECT challenge. A failed credential is not retried.
                if (route?.proxy?.address() != address || response.request.header("Proxy-Authorization") != null ||
                    response.challenges().none { it.scheme.equals("Basic", ignoreCase = true) ||
                        it.scheme == "OkHttp-Preemptive" }) null
                else response.request.newBuilder().header("Proxy-Authorization", authorization).build()
            }
            .authenticator(Authenticator.NONE)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
            // HttpURLConnection has no write deadline. Retain that behavior.
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder().url(endpoint.toURL()).header("Accept-Encoding", "identity")
        if (body != null) request.header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8").post(body.toRequestBody())
        val response = try { client.newCall(request.build()).execute() } catch (failure: Throwable) {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            throw failure
        }
        return object : TranslationHttpResponse {
            override val responseCode get() = response.code
            override val inputStream get() = response.body.byteStream()
            override fun getHeaderField(name: String) = response.header(name)
            override fun disconnect() {
                try {
                    response.close()
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }
    val url = endpoint.toURL()
    val connection = when {
        endpoint.host.lowercase() in loopbackTranslationHosts -> url.openConnection(Proxy.NO_PROXY)
        selected != null -> url.openConnection(Proxy(
            if (selected.type == ProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved(selected.host, selected.port),
        ))
        else -> url.openConnection()
    } as HttpURLConnection
    try {
        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        connection.instanceFollowRedirects = false
        if (body != null) {
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return object : TranslationHttpResponse {
            override val responseCode get() = connection.responseCode
            override val inputStream get() = connection.inputStream
            override fun getHeaderField(name: String) = connection.getHeaderField(name)
            override fun disconnect() = connection.disconnect()
        }
    } catch (failure: Throwable) {
        connection.disconnect()
        throw failure
    }
}
