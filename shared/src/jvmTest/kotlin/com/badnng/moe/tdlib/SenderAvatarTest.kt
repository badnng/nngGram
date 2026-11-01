package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class SenderAvatarTest {
    @Test
    fun nativeUserAvatarIsAvailableAfterItsMessageIsKnownAndBecomesDownloadable() {
        val fixture = Fixture()
        fixture.user(17, 70)
        assertTrue(fixture.state.senderAvatars.isEmpty())
        fixture.controller.downloadFile(fixture.state.token, 70)
        assertTrue(fixture.downloads.isEmpty())
        val message = message(17)
        fixture.controller.event(ChatEvent.NewMessage(message))
        assertEquals(70, fixture.state.senderAvatar(message)?.id)
        assertEquals(70, fixture.state.senderAvatars[17]?.id)
        fixture.controller.downloadFile(fixture.state.token, 70)
        assertEquals(70, assertIs<TdApi.DownloadFile>(fixture.downloads.single().command).fileId)
    }

    @Test
    fun aUserUpdateAfterTheMessageSuppliesTheRealAvatarWithoutChangingSelection() {
        val fixture = Fixture()
        val message = message(17)
        fixture.controller.event(ChatEvent.NewMessage(message))
        val token = fixture.state.token
        assertNull(fixture.state.senderAvatar(message))
        fixture.user(17, 70)
        assertEquals(token, fixture.state.token)
        assertEquals(70, fixture.state.senderAvatar(message)?.id)
        assertTrue(70 in fixture.state.mediaFiles)
    }

    @Test
    fun aChatListLastMessageAlsoEstablishesItsRealSenderAvatar() {
        val fixture = Fixture()
        fixture.user(17, 70)
        fixture.controller.event(ChatEvent.NewChat(ChatItem(10, "synthetic", lastMessage = message(17))))
        assertEquals(70, fixture.state.senderAvatars[17]?.id)
        assertTrue(70 in fixture.state.mediaFiles)
    }

    @Test
    fun historyMessagesEstablishTheAvatarOfTheirNativeUserSender() {
        val fixture = Fixture()
        fixture.user(17, 70)
        fixture.controller.event(ChatEvent.NewChat(ChatItem(10, "synthetic", order = 100)))
        fixture.controller.select(fixture.state.token, 10)
        val history = fixture.requests.single { it.command is TdApi.GetChatHistory }
        history.result(TdApi.Messages(1, arrayOf(TdApi.Message().apply {
            id = 1
            chatId = 10
            senderId = TdApi.MessageSenderUser(17)
            content = TdApi.MessageText().apply { text = TdApi.FormattedText("synthetic", emptyArray()) }
        })))
        fixture.flush()
        val message = fixture.state.messages.single()
        assertEquals(17L, message.senderId)
        assertEquals(70, fixture.state.senderAvatar(message)?.id)
        fixture.controller.downloadFile(fixture.state.token, 70)
        assertEquals(70, assertIs<TdApi.DownloadFile>(fixture.downloads.single().command).fileId)
    }

    @Test
    fun usersAndSenderChatsWithTheSameNumericIdKeepDifferentAvatars() {
        val fixture = Fixture()
        fixture.user(17, 70)
        val userMessage = message(17)
        val chatMessage = message(17).copy(id = 2, senderIsChat = true)
        fixture.controller.event(ChatEvent.NewMessage(userMessage))
        fixture.controller.event(ChatEvent.NewChat(ChatItem(17, "synthetic channel", avatar = MediaFile(80))))
        assertEquals(70, fixture.state.senderAvatar(userMessage)?.id)
        assertEquals(80, fixture.state.senderAvatar(chatMessage)?.id)
        fixture.controller.event(ChatEvent.Photo(17, null))
        assertEquals(70, fixture.state.senderAvatar(userMessage)?.id)
        assertNull(fixture.state.senderAvatar(chatMessage))
    }

    @Test
    fun aChatSenderDoesNotAuthorizeDownloadingAnUnrelatedUsersAvatar() {
        val fixture = Fixture()
        fixture.user(17, 70)
        fixture.controller.event(ChatEvent.NewMessage(message(17).copy(senderIsChat = true)))
        assertTrue(fixture.state.senderAvatars.isEmpty())
        fixture.controller.downloadFile(fixture.state.token, 70)
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun replacingAndDeletingTheUserAvatarSurvivesAnOldDownloadCompletion() {
        val fixture = Fixture()
        val message = message(17)
        fixture.controller.event(ChatEvent.NewMessage(message))
        fixture.user(17, 70)
        fixture.controller.downloadFile(fixture.state.token, 70)
        val oldDownload = fixture.downloads.single()
        fixture.user(17, 71)
        oldDownload.result(file(70, completed = true))
        fixture.flush()
        assertEquals(71, fixture.state.senderAvatar(message)?.id)
        fixture.user(17, null)
        assertNull(fixture.state.senderAvatar(message))
        oldDownload.result(file(70, completed = true))
        fixture.flush()
        assertNull(fixture.state.senderAvatar(message))
    }

    @Test
    fun aNewerFileUpdateKeepsItsDownloadedPathWhenAnOlderDownloadResultArrives() {
        val fixture = Fixture()
        val message = message(17)
        fixture.controller.event(ChatEvent.NewMessage(message))
        fixture.user(17, 70)
        fixture.controller.downloadFile(fixture.state.token, 70)
        val download = fixture.downloads.single()
        fixture.mapper.accept(TdApi.UpdateFile(file(70, completed = true)))
        download.result(file(70))
        fixture.flush()
        val avatar = assertNotNull(fixture.state.senderAvatar(message))
        assertTrue(fixture.state.mediaFiles.getValue(avatar.id).downloaded)
        assertEquals("C:/synthetic-avatar/avatar.png", fixture.state.mediaFiles.getValue(avatar.id).localPath)
    }

    @Test
    fun aRetiredControllerCannotPublishLateSenderPhotosIntoAnotherSession() {
        var shared = ChatSnapshot()
        val old = Fixture(generation = 1, publish = { shared = it })
        old.controller.event(ChatEvent.NewMessage(message(17)))
        old.user(17, 70)
        old.controller.downloadFile(old.state.token, 70)
        val oldDownload = old.downloads.single()
        old.controller.deactivate()
        val next = Fixture(generation = 2, publish = { shared = it })
        old.user(17, 71)
        oldDownload.result(file(70, completed = true))
        old.flush()
        assertEquals(next.state, shared)
        assertTrue(shared.senderAvatars.isEmpty())
        assertTrue(shared.mediaFiles.isEmpty())
    }

    @Test
    fun nativeSenderAvatarMetadataStaysRedactedInSnapshotAndEventStrings() {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.NewMessage(message(17)))
        fixture.user(17, 70)
        val avatar = assertNotNull(fixture.state.senderAvatars[17])
        val printed = listOf(fixture.state, avatar,
            ChatEvent.SenderName(17, "private sender", profile = AccountProfile(17, "private sender", avatar = avatar))).joinToString()
        assertFalse(printed.contains("private"))
        assertFalse(printed.contains("synthetic-avatar"))
    }

    private class Fixture(generation: Long = 1, publish: (ChatSnapshot) -> Unit = {}) {
        data class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit)
        val requests = mutableListOf<Request>()
        val downloads get() = requests.filter { it.command is TdApi.DownloadFile }
        var state = ChatSnapshot()
        private val queued = ArrayDeque<() -> Unit>()
        private val session = OfficialTdChatSession { command, result, _ ->
            requests += Request(command, result)
            if (command is TdApi.GetFileDownloadedPrefixSize) result(TdApi.FileDownloadedPrefixSize(0))
        }
        val controller = ChatController(generation, session, queued::addLast, { state = it; publish(it) })
        val mapper = TdChatUpdateMapper(controller::event)

        init { controller.start() }

        fun user(id: Long, avatarId: Int?) = mapper.accept(TdApi.UpdateUser(TdApi.User().apply {
            this.id = id
            firstName = "private sender"
            profilePhoto = avatarId?.let { TdApi.ProfilePhoto().apply { small = file(it) } }
        }))

        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private companion object {
        fun message(senderId: Long) = ChatMessage(1, 10, senderId = senderId, text = "synthetic")

        fun file(id: Int, completed: Boolean = false) = TdApi.File().apply {
            this.id = id
            size = 1024
            expectedSize = 1024
            local = TdApi.LocalFile().apply {
                path = if (completed) "C:/synthetic-avatar/avatar.png" else ""
                canBeDownloaded = true
                isDownloadingCompleted = completed
                downloadedSize = if (completed) 1024 else 0
            }
        }
    }
}
