package com.badnng.moe

import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTranslationTest {
    @Test
    fun sendsPlainTextToLibreTranslateAndHandlesLimitsAndRedirects() = runBlocking {
        val requests = LinkedBlockingQueue<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                requests.add(it.requestMethod to it.requestBody.bufferedReader(Charsets.UTF_8).use { body -> body.readText() })
                when (path) {
                    "/limited/translate" -> it.sendResponseHeaders(429, -1)
                    "/redirect/translate" -> {
                        it.responseHeaders.add("Location", "/translate")
                        it.sendResponseHeaders(307, -1)
                    }
                    else -> {
                        val response = """{"translatedText":"你好","detectedLanguage":{"language":"ja","confidence":97}}"""
                            .toByteArray(Charsets.UTF_8)
                        it.sendResponseHeaders(200, response.size.toLong())
                        it.responseBody.write(response)
                    }
                }
            }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val original = "こんにちは \"絵文字\" 🐈\n二行目"
            assertEquals(ChatTranslationResult.Translated("你好"), translateChatText(origin, "auto", "zh", original))
            val (method, request) = requests.remove()
            assertEquals("POST", method)
            assertEquals(buildJsonObject {
                put("q", original)
                put("source", "auto")
                put("target", "zh")
                put("format", "text")
            }, Json.parseToJsonElement(request))
            assertEquals(ChatTranslationResult.Original, translateChatText(origin, "auto", "ja", original))
            assertEquals(ChatTranslationResult.Failure.RATE_LIMIT, translateChatText("$origin/limited", "ja", "zh", original))
            assertEquals(ChatTranslationResult.Failure.RESPONSE, translateChatText("$origin/redirect", "ja", "zh", original))
            assertEquals(3, requests.size, "Redirect must not resend the message")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun translationSettingsPersistAndProtectedMessagesAreExcluded() {
        val directory = Files.createTempDirectory("chat-translation")
        val file = directory.resolve("settings.properties")
        try {
            val preferences = ChatTranslationPreferences("https://translations.example/api/translate", "ja", "zh", true,
                provider = ChatTranslationProvider.LIBRE_TRANSLATE, automatic = true)
            assertTrue(writeSettingsPreferences(file, SettingsPreferences(translation = preferences)))
            assertEquals(preferences, readSettingsPreferences(file).translation)
            assertEquals("https://translations.example/api/translate", normalizeChatTranslationServer("https://translations.example/api/"))
            assertNull(normalizeChatTranslationServer("http://translations.example/translate"))
            assertNull(normalizeChatTranslationServer("//translations.example/translate"))
            assertNull(normalizeChatTranslationServer("https://name:secret@translations.example/"))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
        val group = ChatItem(1, "Group", basicGroupId = 2)
        assertTrue(group.supportsTranslation())
        assertFalse(group.copy(hasProtectedContent = true).supportsTranslation())
        assertFalse(group.copy(isChannel = true).supportsTranslation())
        val message = ChatMessage(12, 1, text = "こんにちは", canBeSaved = true)
        assertNull(message.copy(canBeSaved = false).translationMessage())
        assertNull(message.copy(entities = listOf(MessageEntity(0, 5, MessageEntityKind.SPOILER))).translationMessage())
        assertNotEquals(message.translationMessage(), message.copy(text = "おはよう").translationMessage())
    }

    @Test
    fun offlineModeNeedsNoServerAndKeepsOnlineLanguageChoices() {
        val old = ChatTranslationPreferences("https://translations.example/translate", "ja", "en",
            provider = ChatTranslationProvider.LIBRE_TRANSLATE)
        val offline = old.copy(provider = ChatTranslationProvider.OFFLINE, automatic = true)
        assertEquals("offline", offline.identity)
        assertEquals("auto", offline.effectiveSource)
        assertEquals("zh", offline.effectiveTarget)
        assertTrue(offline.enabledForGroup(null))
        assertFalse(offline.enabledForGroup(false))
        assertFalse(old.enabledForGroup(null))
        assertFalse(old.copy(server = "", automatic = true).enabledForGroup(null))
        assertEquals(old, offline.copy(provider = ChatTranslationProvider.LIBRE_TRANSLATE, automatic = false))
    }
}
