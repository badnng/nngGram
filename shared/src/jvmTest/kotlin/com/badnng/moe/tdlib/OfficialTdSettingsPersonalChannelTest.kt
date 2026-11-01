package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Fake transport only: personal-channel metadata never requires a history read or a media download. */
class OfficialTdSettingsPersonalChannelTest {
    @Test
    fun catalogKeepsNativeSignedIdentitiesAndOrderWithoutResolvingEveryCandidate() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPersonalChannelCatalog>>()
        f.api.catalog(43, { true }, results::add)
        val identity = f.last
        assertIs<TdApi.GetMe>(identity.command)
        f.me()
        identity.answer(TdApi.User().apply { id = 999 })
        assertEquals(2, f.requests.size) // A repeated callback cannot launch a second catalog request.
        assertIs<TdApi.GetSuitablePersonalChats>(f.last.command)
        f.catalog(longArrayOf(-53, -11, -53, -97), total = 4)
        val value = assertNotNull(results.single().value)
        assertEquals(listOf(-53L, -11L, -97L), value.ids)
        assertEquals(4, value.totalCount)
        assertFalse(value.limited)
        assertEquals(2, f.requests.size)
        assertFalse(value.toString().contains("-53"))
        assertMetadataOnly(f)
    }

    @Test
    fun catalogBoundsRawWorkAndDistinguishesKnownTotalFromAResourceLimit() {
        val f = Fixture()
        var result: SettingsResult<SettingsPersonalChannelCatalog>? = null
        f.api.catalog(43, { true }) { result = it }
        f.me()
        val raw = LongArray(700) { -(it + 1L) }.apply { this[1] = this[0] }
        f.catalog(raw, total = 900)
        val value = assertNotNull(result?.value)
        assertEquals(raw.take(512).distinct(), value.ids)
        assertEquals(511, value.ids.size)
        assertEquals(900, value.totalCount)
        assertTrue(value.limited)

        val unknown = Fixture()
        var unknownResult: SettingsResult<SettingsPersonalChannelCatalog>? = null
        unknown.api.catalog(43, { true }) { unknownResult = it }
        unknown.me()
        unknown.catalog(longArrayOf(-11), total = -1)
        assertNull(assertNotNull(unknownResult?.value).totalCount)
        assertFalse(assertNotNull(unknownResult?.value).limited)
    }

    @Test
    fun catalogErrorsAndWrongAccountsNeverBecomeSuccessfulEmptyLists() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPersonalChannelCatalog>>()
        f.api.catalog(43, { true }, results::add)
        f.last.answer(TdApi.User().apply { id = 999 })
        assertEquals(SettingsError.NOT_READY, results.last().error)
        assertEquals(1, f.requests.size)

        f.api.catalog(43, { true }, results::add)
        f.me()
        val native = f.last
        native.answer(TdApi.Error(429, "private failure text"))
        native.answer(TdApi.Chats(0, longArrayOf()))
        native.failed()
        assertEquals(SettingsError.RATE_LIMITED, results.last().error)
        assertEquals(2, results.size)
        assertTrue(results.all { it.value == null })
        assertFalse(results.toString().contains("private failure text"))

        f.api.catalog(43, { true }, results::add)
        f.me()
        f.catalog(longArrayOf(-11, 0), total = 2)
        assertEquals(SettingsError.REQUEST_FAILED, results.last().error)
        assertNull(results.last().value)
    }

    @Test
    fun pageResolvesOneNativeIdentityAtATimeAndPreservesPartialFailureCounts() {
        val f = Fixture()
        var result: SettingsResult<SettingsPersonalChannelsPage>? = null
        f.api.page(43, listOf(-11, -22, -33), { true }) { result = it }
        f.me()
        assertEquals(-11L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        assertEquals(2, f.requests.size)
        f.chat(-11, 101, title = "Actual native title")
        assertEquals(101L, assertIs<TdApi.GetSupergroup>(f.last.command).supergroupId)
        assertEquals(3, f.requests.size)
        assertNull(result)
        f.group(101, usernames = arrayOf("", "real_public_username", "other_alias"))
        assertEquals(-22L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.answer(TdApi.Error(500, "not available"))
        assertEquals(-33L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.answer(TdApi.Chat().apply { id = -33; title = "A group"; type = TdApi.ChatTypeBasicGroup(303) })
        val value = assertNotNull(result?.value)
        assertEquals(listOf(SettingsPersonalChannel(-11, "Actual native title", "real_public_username")), value.entries)
        assertEquals(2, value.unavailableCount)
        assertEquals(5, f.requests.size)
        assertMetadataOnly(f)
    }

    @Test
    fun pageRejectsMismatchedIdentitiesAndLostCreatorPublicChannelEligibility() {
        val invalidChats = listOf<(TdApi.Chat) -> Unit>(
            { it.id = -99 },
            { it.type = TdApi.ChatTypePrivate(99) },
            { it.type = TdApi.ChatTypeSupergroup(101, false) },
            { it.title = "" },
        )
        invalidChats.forEach { change ->
            val f = Fixture()
            var result: SettingsResult<SettingsPersonalChannelsPage>? = null
            f.api.page(43, listOf(-11), { true }) { result = it }
            f.me()
            f.chat(-11, 101, change = change)
            assertEquals(SettingsPersonalChannelsPage(emptyList(), 1), result?.value)
            assertTrue(f.requests.none { it.command is TdApi.GetSupergroup })
        }
        val invalidGroups = listOf<(TdApi.Supergroup) -> Unit>(
            { it.id = 999 },
            { it.isChannel = false },
            { it.status = TdApi.ChatMemberStatusMember() },
            { it.usernames = null },
            { it.usernames = TdApi.Usernames().apply { activeUsernames = emptyArray(); editableUsername = "inactive_name" } },
        )
        invalidGroups.forEach { change ->
            val f = Fixture()
            var result: SettingsResult<SettingsPersonalChannelsPage>? = null
            f.api.page(43, listOf(-11), { true }) { result = it }
            f.me(); f.chat(-11, 101); f.group(101, change = change)
            assertEquals(SettingsPersonalChannelsPage(emptyList(), 1), result?.value)
            assertMetadataOnly(f)
        }
        val failed = Fixture()
        var failure: SettingsResult<SettingsPersonalChannelsPage>? = null
        failed.api.page(43, listOf(-11), { true }) { failure = it }
        failed.me(); failed.chat(-11, 101); failed.last.failed()
        assertEquals(SettingsPersonalChannelsPage(emptyList(), 1), failure?.value)
    }

    @Test
    fun pageRejectsMalformedOrOversizedRequestsAndAcceptsExactly32RawCandidates() {
        listOf(List(33) { -(it + 1L) }, listOf(-1L, -1L), listOf(-1L, 0L)).forEach { ids ->
            val f = Fixture()
            var result: SettingsResult<SettingsPersonalChannelsPage>? = null
            f.api.page(43, ids, { true }) { result = it }
            assertEquals(SettingsError.INVALID_VALUE, result?.error)
            assertTrue(f.requests.isEmpty())
        }
        val f = Fixture()
        var result: SettingsResult<SettingsPersonalChannelsPage>? = null
        val ids = List(32) { -(it + 1L) }
        f.api.page(43, ids, { true }) { result = it }
        f.me()
        ids.forEach { id ->
            assertEquals(id, assertIs<TdApi.GetChat>(f.last.command).chatId)
            f.last.answer(TdApi.Error(404, "unavailable"))
        }
        assertEquals(SettingsPersonalChannelsPage(emptyList(), 32), result?.value)
        assertEquals(33, f.requests.size)
        assertMetadataOnly(f)
    }

    @Test
    fun currentReadsOnlyTheRealChannelIdentityWithoutRequiringCandidateOwnership() {
        val f = Fixture()
        var result: SettingsResult<SettingsPersonalChannel>? = null
        f.api.current(-11, { true }) { result = it }
        assertEquals(-11L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.chat(-11, 101, title = "Current native title")
        assertEquals(SettingsPersonalChannel(-11, "Current native title", ""), result?.value)
        assertEquals(1, f.requests.size)

        f.api.current(-11, { true }) { result = it }
        f.chat(-99, 101)
        assertEquals(SettingsError.CONFLICT, result?.error)
        f.api.current(0, { true }) { result = it }
        assertEquals(SettingsError.INVALID_VALUE, result?.error)
        assertEquals(2, f.requests.size)
        assertMetadataOnly(f)
    }

    @Test
    fun saveRevalidatesTheActualCurrentAndCandidateThenWaitsForNativeAcknowledgementOnce() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.api.save(43, -10, -22, { true }, results::add)
        f.me()
        assertEquals(43L, assertIs<TdApi.GetUserFullInfo>(f.last.command).userId)
        f.full(-10)
        assertIs<TdApi.GetSuitablePersonalChats>(f.last.command)
        f.catalog(longArrayOf(-11, -22))
        assertEquals(-22L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.chat(-22, 202)
        f.group(202)
        assertEquals(43L, assertIs<TdApi.GetUserFullInfo>(f.last.command).userId)
        f.full(-10)
        val write = f.last
        assertEquals(-22L, assertIs<TdApi.SetPersonalChat>(write.command).chatId)
        assertTrue(results.isEmpty())
        write.answer(TdApi.Ok()); write.answer(TdApi.Error(403, "late error")); write.failed()
        assertEquals(Unit, results.single().value)
        assertEquals(7, f.requests.size)
        assertMetadataOnly(f)
    }

    @Test
    fun remoteCurrentChangesOrDisappearingCandidateEligibilityPreventTheWrite() {
        val remote = Fixture()
        var result: SettingsResult<Unit>? = null
        remote.api.save(43, -10, -22, { true }) { result = it }
        remote.me(); remote.full(-99)
        assertEquals(SettingsError.CONFLICT, result?.error)
        assertEquals(2, remote.requests.size)

        val removed = Fixture()
        removed.api.save(43, -10, -22, { true }) { result = it }
        removed.me(); removed.full(-10); removed.catalog(longArrayOf(-11))
        assertEquals(SettingsError.CONFLICT, result?.error)
        assertTrue(removed.requests.none { it.command is TdApi.GetChat || it.command is TdApi.SetPersonalChat })

        val lost = Fixture()
        lost.api.save(43, -10, -22, { true }) { result = it }
        lost.me(); lost.full(-10); lost.catalog(longArrayOf(-22)); lost.chat(-22, 202)
        lost.group(202, change = { it.status = TdApi.ChatMemberStatusMember() })
        assertEquals(SettingsError.CONFLICT, result?.error)
        assertTrue(lost.requests.none { it.command is TdApi.SetPersonalChat })
    }

    @Test
    fun currentChangedDuringCandidateMetadataResolutionCannotBeOverwrittenAtCommit() {
        val f = Fixture()
        var result: SettingsResult<Unit>? = null
        f.api.save(43, -10, -22, { true }) { result = it }
        f.me(); f.full(-10); f.catalog(longArrayOf(-22)); f.chat(-22, 202); f.group(202)
        assertEquals(43L, assertIs<TdApi.GetUserFullInfo>(f.last.command).userId)
        f.full(-99) // Another client changes the current channel during candidate metadata requests.
        assertEquals(SettingsError.CONFLICT, result?.error)
        assertTrue(f.requests.none { it.command is TdApi.SetPersonalChat })
        assertEquals(2, f.requests.count { it.command is TdApi.GetUserFullInfo })
    }

    @Test
    fun explicitRemovalUsesNativeZeroAfterCurrentIdentityCheckWithoutFetchingCandidates() {
        val f = Fixture()
        var result: SettingsResult<Unit>? = null
        f.api.save(43, -10, 0, { true }) { result = it }
        f.me(); f.full(-10); f.full(-10)
        assertEquals(0L, assertIs<TdApi.SetPersonalChat>(f.last.command).chatId)
        assertNull(result)
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, result?.value)
        assertEquals(4, f.requests.size)
        assertTrue(f.requests.none { it.command is TdApi.GetSuitablePersonalChats || it.command is TdApi.GetChat })
        assertMetadataOnly(f)
    }

    @Test
    fun nativePermissionErrorsTransportFailureAndExceptionsNeverAcknowledgeASave() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.api.save(43, -10, 0, { true }, results::add)
        f.me(); f.full(-10); f.full(-10)
        val write = f.last
        write.answer(TdApi.Error(403, "private native detail")); write.answer(TdApi.Ok()); write.failed()
        assertEquals(SettingsError.FORBIDDEN, results.single().error)
        assertNull(results.single().value)
        assertFalse(results.toString().contains("private native detail"))

        f.api.save(43, -10, 0, { true }, results::add)
        f.me(); f.full(-10); f.full(-10); f.last.failed()
        assertEquals(SettingsError.NOT_READY, results.last().error)
        var thrown: SettingsResult<Unit>? = null
        OfficialTdSettingsPersonalChannel { _, _, _ -> throw IllegalStateException("private transport detail") }
            .save(43, -10, 0, { true }) { thrown = it }
        assertEquals(SettingsError.REQUEST_FAILED, thrown?.error)
    }

    @Test
    fun retiredLeaseStopsEveryUnsentSaveAndPageStepIncludingAnAlreadyQueuedIdentityRead() {
        val replies = listOf<TdApi.Object>(
            TdApi.User().apply { id = 43 },
            TdApi.UserFullInfo().apply { personalChatId = -10 },
            TdApi.Chats(1, longArrayOf(-22)),
            chatValue(-22, 202),
            groupValue(202),
            TdApi.UserFullInfo().apply { personalChatId = -10 },
        )
        replies.indices.forEach { retiredAt ->
            val f = Fixture()
            var active = true
            var result: SettingsResult<Unit>? = null
            f.api.save(43, -10, -22, { active }) { result = it }
            replies.take(retiredAt).forEach { f.last.answer(it) }
            val count = f.requests.size
            active = false
            f.last.answer(replies[retiredAt])
            assertEquals(count, f.requests.size, "No new send after retiring step $retiredAt")
            assertEquals(SettingsError.NOT_READY, result?.error)
            assertTrue(f.requests.none { it.command is TdApi.SetPersonalChat })
        }
        val f = Fixture()
        var active = true
        var result: SettingsResult<SettingsPersonalChannelsPage>? = null
        f.api.page(43, listOf(-11, -22), { active }) { result = it }
        f.me(); f.chat(-11, 101)
        active = false
        f.group(101)
        assertEquals(SettingsError.NOT_READY, result?.error)
        assertEquals(3, f.requests.size)
        val neverStarted = Fixture()
        neverStarted.api.current(-11, { false }) { assertEquals(SettingsError.NOT_READY, it.error) }
        neverStarted.api.catalog(43, { false }) { assertEquals(SettingsError.NOT_READY, it.error) }
        assertTrue(neverStarted.requests.isEmpty())
    }

    private class Request(val command: TdApi.Function<*>, val answer: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        val requests = mutableListOf<Request>()
        val last get() = requests.last()
        val api = OfficialTdSettingsPersonalChannel { command, answer, failed -> requests += Request(command, answer, failed) }
        fun me() = last.answer(TdApi.User().apply { id = 43 })
        fun full(current: Long) = last.answer(TdApi.UserFullInfo().apply { personalChatId = current })
        fun catalog(ids: LongArray, total: Int = ids.size) = last.answer(TdApi.Chats(total, ids))
        fun chat(id: Long, group: Long, title: String = "Native channel", change: (TdApi.Chat) -> Unit = {}) =
            last.answer(chatValue(id, group, title).also(change))
        fun group(id: Long, usernames: Array<String> = arrayOf("public_native_channel"), change: (TdApi.Supergroup) -> Unit = {}) =
            last.answer(groupValue(id, usernames).also(change))
    }

    private companion object {
        fun chatValue(id: Long, group: Long, title: String = "Native channel") = TdApi.Chat().apply {
            this.id = id; this.title = title; type = TdApi.ChatTypeSupergroup(group, true)
        }
        fun groupValue(id: Long, usernames: Array<String> = arrayOf("public_native_channel")) = TdApi.Supergroup().apply {
            this.id = id; isChannel = true; status = TdApi.ChatMemberStatusCreator(false, true)
            this.usernames = TdApi.Usernames().apply { activeUsernames = usernames }
        }
        fun assertMetadataOnly(f: Fixture) {
            assertTrue(f.requests.all { request ->
                when (request.command) {
                    is TdApi.GetMe, is TdApi.GetUserFullInfo, is TdApi.GetSuitablePersonalChats,
                    is TdApi.GetChat, is TdApi.GetSupergroup, is TdApi.SetPersonalChat -> true
                    else -> false
                }
            }, "No history, message, read-receipt, avatar-download or chat-creation request is permitted")
        }
    }
}
