package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class ChatMediaIndexTransportTest {
    @Test
    fun everyCategoryUsesItsNativeIndexAndServerCountWithoutFetchingHistoryOrDownloading() {
        val expected = mapOf(
            ChatMediaCategory.PHOTOS_AND_VIDEOS to TdApi.SearchMessagesFilterPhotoAndVideo::class,
            ChatMediaCategory.FILES to TdApi.SearchMessagesFilterDocument::class,
            ChatMediaCategory.LINKS to TdApi.SearchMessagesFilterUrl::class,
            ChatMediaCategory.AUDIO to TdApi.SearchMessagesFilterAudio::class,
            ChatMediaCategory.VOICE to TdApi.SearchMessagesFilterVoiceNote::class,
        )
        expected.forEach { (category, filter) ->
            val fixture = Fixture()
            var page: ChatResult<ChatSearchPage>? = null
            fixture.session.mediaIndex(-10, category, 800) { page = it }
            val search = assertIs<TdApi.SearchChatMessages>(fixture.last.command)
            assertEquals(-10L, search.chatId)
            assertNull(search.topicId)
            assertEquals("", search.query)
            assertNull(search.senderId)
            assertEquals(800L, search.fromMessageId)
            assertEquals(0, search.offset)
            assertEquals(50, search.limit)
            assertEquals(filter, assertNotNull(search.filter)::class)
            fixture.last.done(TdApi.FoundChatMessages(900, arrayOf(message(700, -10)), 600))
            assertEquals("600", page?.value?.nextOffset)
            assertEquals(900, page?.value?.totalCount)
            var count: ChatResult<Int>? = null
            fixture.session.mediaIndexCount(-10, category) { count = it }
            val nativeCount = assertIs<TdApi.GetChatMessageCount>(fixture.last.command)
            assertEquals(-10L, nativeCount.chatId)
            assertNull(nativeCount.topicId)
            assertEquals(filter, assertNotNull(nativeCount.filter)::class)
            assertFalse(nativeCount.returnLocal)
            fixture.last.done(TdApi.Count(900))
            assertEquals(900, count?.value)
            assertEquals(2, fixture.calls.size)
        }
    }

    @Test
    fun pageIdentityValidationAndCursorExhaustionRetainNativeProtection() {
        val fixture = Fixture()
        var result: ChatResult<ChatSearchPage>? = null
        fixture.session.mediaIndex(10, ChatMediaCategory.FILES, 800) { result = it }
        fixture.last.done(TdApi.FoundChatMessages(1, arrayOf(message(700).apply { canBeSaved = false }), 800))
        assertEquals("", result?.value?.nextOffset)
        assertFalse(assertNotNull(result?.value).messages.single().canBeSaved)
        fixture.session.mediaIndex(10, ChatMediaCategory.FILES, 0) { result = it }
        fixture.last.done(TdApi.FoundChatMessages(1, arrayOf(message(700, 11)), 0))
        assertNull(result?.value)
        assertEquals(ChatError.REQUEST_FAILED, result?.error)
        fixture.session.mediaIndex(10, ChatMediaCategory.FILES, 0) { result = it }
        fixture.last.done(TdApi.FoundChatMessages(0, emptyArray(), 0))
        assertEquals(emptyList(), result?.value?.messages)
        assertEquals(0, result?.value?.totalCount)
        fixture.session.mediaIndex(10, ChatMediaCategory.FILES, 0) { result = it }
        // The pinned schema explicitly permits -1 when a search total is not yet known.
        fixture.last.done(TdApi.FoundChatMessages(-1, arrayOf(message(600)), 500))
        assertEquals(listOf(600L), result?.value?.messages?.map { it.id })
        assertEquals(-1, result?.value?.totalCount)
    }

    @Test
    fun invalidInputsAndUnknownCountsNeverBecomeAnEmptySuccess() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<*>>()
        fixture.session.mediaIndex(0, ChatMediaCategory.FILES, 0, results::add)
        fixture.session.mediaIndex(10, ChatMediaCategory.FILES, -1, results::add)
        fixture.session.mediaIndexCount(0, ChatMediaCategory.FILES, results::add)
        assertTrue(fixture.calls.isEmpty())
        fixture.session.mediaIndexCount(10, ChatMediaCategory.FILES, results::add)
        fixture.last.done(TdApi.Count(-1))
        assertTrue(results.all { it.error == ChatError.REQUEST_FAILED && it.value == null })
    }

    @Test
    fun nativeFailuresAndDuplicateCallbacksRemainTypedAndPrivate() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Int>>()
        fixture.session.mediaIndexCount(10, ChatMediaCategory.AUDIO, results::add)
        val pending = fixture.last
        pending.done(TdApi.Error(403, "synthetic private media index"))
        pending.done(TdApi.Count(4))
        pending.failed()
        assertEquals(1, results.size)
        assertEquals(ChatError.FORBIDDEN, results.single().error)
        assertFalse(results.toString().contains("private"))
        val throwing = OfficialTdChatSession { _, _, _ -> throw IllegalStateException("synthetic private media index") }
        throwing.mediaIndexCount(10, ChatMediaCategory.AUDIO, results::add)
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        assertFalse(results.toString().contains("private"))
    }

    private class Fixture {
        val calls = mutableListOf<Call>()
        val session = OfficialTdChatSession { command, done, failed -> calls += Call(command, done, failed) }
        val last get() = calls.last()
    }

    private class Call(val command: TdApi.Function<*>, val done: (TdApi.Object) -> Unit, val failed: () -> Unit)

    private fun message(id: Long, chatId: Long = 10) = TdApi.Message().apply {
        this.id = id
        this.chatId = chatId
        senderId = TdApi.MessageSenderUser(7)
        content = TdApi.MessageText(TdApi.FormattedText("synthetic media caption", emptyArray()), null, null)
    }
}
