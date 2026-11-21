package com.badnng.moe

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val loopbackTranslationHosts = setOf("localhost", "127.0.0.1", "[::1]")

internal actual fun normalizeChatTranslationServer(input: String): String? {
    val address = input.trim()
    if (address.isEmpty()) return ""
    if (address.length > 2048) return null
    val uri = try { URI(address) } catch (_: java.net.URISyntaxException) { return null }
    val host = uri.host?.lowercase() ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        (uri.port != -1 && uri.port !in 1..65535)) return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" && (scheme != "http" || host !in loopbackTranslationHosts)) return null
    val path = uri.normalize().rawPath.trimEnd('/')
    val translationPath = if (path.endsWith("/translate")) path else "$path/translate"
    return "$scheme://${uri.rawAuthority}$translationPath"
}

private const val MAX_TRANSLATION_RESPONSE_BYTES = 128 * 1024

internal actual suspend fun translateChatText(
    server: String, source: String, target: String, text: String,
): ChatTranslationResult = withContext(Dispatchers.IO) {
    if (source == target) return@withContext ChatTranslationResult.Original
    val endpoint = normalizeChatTranslationServer(server)?.takeIf(String::isNotEmpty)
        ?: return@withContext ChatTranslationResult.Failure.CONNECTION
    val body = buildJsonObject {
        put("q", text)
        put("source", source)
        put("target", target)
        put("format", "text")
    }.toString().toByteArray(Charsets.UTF_8)
    val uri = URI(endpoint)
    val connection = try {
        executeTranslationRequest(
            uri, if (uri.host.lowercase() in loopbackTranslationHosts) null else translationWebProxy(),
            connectTimeout = 5_000, readTimeout = 20_000, body = body,
        )
    } catch (_: IOException) {
        return@withContext ChatTranslationResult.Failure.CONNECTION
    }
    try {
        when (connection.responseCode) {
            200 -> {
                val response = connection.inputStream.use { it.readNBytes(MAX_TRANSLATION_RESPONSE_BYTES + 1) }
                if (response.size > MAX_TRANSLATION_RESPONSE_BYTES) ChatTranslationResult.Failure.RESPONSE
                else parseChatTranslation(response.decodeToString(), text, target)
            }
            400, 422 -> ChatTranslationResult.Unsupported
            401, 403 -> ChatTranslationResult.Failure.ACCESS
            429 -> ChatTranslationResult.Failure.RATE_LIMIT
            else -> ChatTranslationResult.Failure.RESPONSE
        }
    } catch (_: IOException) {
        ChatTranslationResult.Failure.CONNECTION
    } finally {
        connection.disconnect()
    }
}

private fun parseChatTranslation(body: String, original: String, target: String): ChatTranslationResult {
    val response = try { Json.parseToJsonElement(body) as? JsonObject }
        catch (_: SerializationException) { null }
        catch (_: IllegalArgumentException) { null }
    val translated = (response?.get("translatedText") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf(String::isNotBlank) ?: return ChatTranslationResult.Failure.RESPONSE
    val language = ((response["detectedLanguage"] as? JsonObject)?.get("language") as? JsonPrimitive)?.content
    return if (language == target || translated.trim() == original.trim()) ChatTranslationResult.Original
    else ChatTranslationResult.Translated(translated)
}
