package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class AccountProfileTest {
    @Test
    fun getMeLoadsRealSelfFieldsOnceAndKeepsTheirStringRenderingRedacted() {
        val fixture = Fixture()
        assertNull(fixture.state.account)
        fixture.controller.start()
        assertEquals(1, fixture.requests.count { it.command is TdApi.GetMe })
        fixture.me.result(user(17))
        fixture.flush()
        val account = assertNotNull(fixture.state.account)
        assertEquals(17L, account.userId)
        assertEquals("private first private last", account.displayName)
        assertEquals("private_username", account.username)
        assertEquals("15550001234", account.phoneNumber)
        assertEquals(70, account.avatar?.id)
        assertFalse(account.toString().contains("private"))
        assertFalse(account.toString().contains("15550001234"))
        assertFalse(fixture.state.toString().contains("private"))
    }

    @Test
    fun newerSelfUpdatesWinOverGetMeAndUnrelatedUsersNeverBecomeTheAccount() {
        val fixture = Fixture()
        fixture.update(user(18).apply { firstName = "unrelated" })
        assertNull(fixture.state.account)
        fixture.update(user(17).apply { firstName = "newer"; profilePhoto = null })
        fixture.me.result(user(17))
        fixture.flush()
        assertEquals("newer private last", fixture.state.account?.displayName)
        assertNull(fixture.state.account?.avatar)
        fixture.update(user(18).apply { firstName = "unrelated again" })
        assertEquals(17L, fixture.state.account?.userId)
        assertEquals("newer private last", fixture.state.account?.displayName)
        fixture.update(user(17).apply { firstName = "latest" })
        assertEquals("latest private last", fixture.state.account?.displayName)
        assertEquals("latest private last", fixture.state.senderNames[17])
    }

    @Test
    fun onlyTheAuthenticatedProfileAvatarBecomesDownloadableAndUsesFileUpdates() {
        val fixture = Fixture()
        fixture.update(user(18).apply { profilePhoto = TdApi.ProfilePhoto().apply { small = file(71) } })
        fixture.controller.downloadFile(fixture.state.token, 71)
        assertTrue(fixture.requests.none { it.command is TdApi.DownloadFile })
        fixture.me.result(user(17))
        fixture.flush()
        assertEquals(setOf(70), fixture.state.mediaFiles.keys)
        fixture.controller.downloadFile(fixture.state.token, 70)
        val download = fixture.requests.single { it.command is TdApi.DownloadFile }
        assertEquals(70, assertIs<TdApi.DownloadFile>(download.command).fileId)
        fixture.mapper.accept(TdApi.UpdateFile(file(70, completed = true)))
        download.result(file(70))
        fixture.flush()
        assertTrue(assertNotNull(fixture.state.mediaFiles[70]).downloaded)
        assertTrue(fixture.state.downloadingFiles.isEmpty())
    }

    @Test
    fun retiredControllerCannotPublishLateProfileOrAvatarCallbacksIntoANewSession() {
        var sharedState = ChatSnapshot()
        val old = Fixture(generation = 1, publish = { sharedState = it })
        old.controller.deactivate()
        val next = Fixture(generation = 2, publish = { sharedState = it })
        next.me.result(user(22).apply { profilePhoto = null })
        next.flush()
        old.me.result(user(17))
        old.flush()
        old.update(user(17))
        old.controller.start()
        assertEquals(22L, sharedState.account?.userId)
        assertEquals(2L, sharedState.token.generation)
        assertEquals(1, old.requests.count { it.command is TdApi.GetMe })

        val pendingDownload = Fixture(generation = 3, publish = { sharedState = it })
        pendingDownload.me.result(user(17))
        pendingDownload.flush()
        pendingDownload.controller.downloadFile(pendingDownload.state.token, 70)
        val download = pendingDownload.requests.single { it.command is TdApi.DownloadFile }
        pendingDownload.controller.deactivate()
        val replacement = Fixture(generation = 4, publish = { sharedState = it })
        download.result(file(70, completed = true))
        pendingDownload.flush()
        assertEquals(replacement.state, sharedState)
        assertNull(sharedState.account)
        assertTrue(sharedState.mediaFiles.isEmpty())
    }

    @Test
    fun failedGetMeDoesNotSubstituteAnotherChatOrUserAndFiltersNativeErrors() {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.NewChat(ChatItem(18, "unrelated chat", avatar = MediaFile(71))))
        fixture.update(user(18))
        fixture.me.result(TdApi.Error(403, "private native error"))
        fixture.flush()
        assertNull(fixture.state.account)
        assertFalse(fixture.state.toString().contains("private"))
        assertTrue(fixture.state.enabled)
    }

    @Test
    fun savedMessagesIdentityComesOnlyFromNativePrivateChatType() {
        val fixture = Fixture()
        fixture.mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
            id = 99; title = "synthetic saved messages"; type = TdApi.ChatTypePrivate(17)
        }))
        fixture.mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
            id = 17; title = "synthetic unrelated group"; type = TdApi.ChatTypeBasicGroup(17)
        }))
        assertEquals(17L, fixture.state.knownChats.single { it.id == 99L }.privateUserId)
        assertNull(fixture.state.knownChats.single { it.id == 17L }.privateUserId)
    }

    @Test
    fun missingNativeOptionalProfileFieldsRemainEmpty() {
        val profile = mapAccountProfile(TdApi.User().apply { id = 17 })
        // Missing optional text/photo stays empty; the new appearance keeps the actual native zero values.
        assertEquals(AccountProfile(17, "", appearance = com.badnng.moe.settings.SettingsProfileColorsValues(
            com.badnng.moe.settings.SettingsProfileColorValue(0),
            com.badnng.moe.settings.SettingsProfileColorValue(0),
        )), profile)
    }

    private class Fixture(generation: Long = 1, publish: (ChatSnapshot) -> Unit = {}) {
        data class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit)
        val requests = mutableListOf<Request>()
        private val queued = ArrayDeque<() -> Unit>()
        var state = ChatSnapshot()
        val session = OfficialTdChatSession { command, result, _ ->
            requests += Request(command, result)
            if (command is TdApi.GetFileDownloadedPrefixSize) result(TdApi.FileDownloadedPrefixSize(0))
        }
        val controller = ChatController(generation, session, queued::addLast, { state = it; publish(it) })
        val mapper = TdChatUpdateMapper(controller::event)
        val me get() = requests.single { it.command is TdApi.GetMe }

        init { controller.start() }

        fun update(user: TdApi.User) = mapper.accept(TdApi.UpdateUser(user))
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private companion object {
        fun user(id: Long) = TdApi.User().apply {
            this.id = id
            firstName = "private first"
            lastName = "private last"
            usernames = TdApi.Usernames().apply { activeUsernames = arrayOf("private_username") }
            phoneNumber = "15550001234"
            profilePhoto = TdApi.ProfilePhoto().apply { small = file(70) }
        }

        fun file(id: Int, completed: Boolean = false) = TdApi.File().apply {
            this.id = id
            size = 1024
            expectedSize = 1024
            local = TdApi.LocalFile().apply {
                path = "C:/synthetic-private/avatar.jpg"
                canBeDownloaded = true
                isDownloadingCompleted = completed
                downloadedSize = if (completed) 1024 else 0
            }
        }
    }
}
