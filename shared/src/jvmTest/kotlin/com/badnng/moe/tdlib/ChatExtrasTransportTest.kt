package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class ChatExtrasTransportTest {
    @Test
    fun archiveAndFolderLoadingUseTheirOwnNativeList() {
        val fixture = Fixture()
        fixture.session.loadChats(ChatListRef(-1), 50) { }
        assertIs<TdApi.ChatListArchive>(assertIs<TdApi.LoadChats>(fixture.last.command).chatList)
        fixture.session.loadChats(ChatListRef(19), 50) { }
        assertEquals(19, assertIs<TdApi.ChatListFolder>(assertIs<TdApi.LoadChats>(fixture.last.command).chatList).chatFolderId)
    }

    @Test
    fun automaticReceiptsDoNotForceReadAndUnreadFlagsAreExplicitRequests() {
        val fixture = Fixture()
        fixture.session.viewMessages(10, listOf(1, 2, 1), false) { }
        val read = assertIs<TdApi.ViewMessages>(fixture.last.command)
        assertFalse(read.forceRead)
        assertContentEquals(longArrayOf(1, 2), read.messageIds)
        fixture.session.markUnread(10, true) { }
        val unread = assertIs<TdApi.ToggleChatIsMarkedAsUnread>(fixture.last.command)
        assertEquals(10L, unread.chatId)
        assertTrue(unread.isMarkedAsUnread)
    }

    @Test
    fun downloadingFilesNeverExposePartialPathsAndDownloadsCompleteAsynchronously() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MediaFile>>()
        fixture.session.download(7, results::add)
        assertIs<TdApi.GetFile>(fixture.last.command)
        fixture.last.result(file(7, completed = false))
        assertIs<TdApi.GetFileDownloadedPrefixSize>(fixture.last.command)
        fixture.last.result(TdApi.FileDownloadedPrefixSize(0))
        val request = assertIs<TdApi.DownloadFile>(fixture.last.command)
        assertEquals(7, request.fileId)
        assertFalse(request.synchronous)
        assertEquals(0L, request.offset)
        assertEquals(1024L, request.limit)
        fixture.last.result(file(7, completed = false))
        assertTrue(results.isEmpty(), "Asynchronous acceptance is not full-cache completion")
        assertNull(mapMediaFile(file(7, completed = false)).localPath)
        val complete = mapMediaFile(file(7, completed = true))
        fixture.session.observeDownload(complete)
        assertEquals(complete, results.single().value)
        assertEquals("C:/synthetic-private/image.webp", complete.localPath)
        assertFalse(complete.toString().contains("private"))
        assertTrue(complete.downloaded)
        fixture.session.retireDownloads()
    }

    @Test
    fun photoMappingKeepsDimensionsSpoilersAndThumbnailButExcludesSecretMedia() {
        val photo = TdApi.MessagePhoto().apply {
            this.photo = TdApi.Photo(false, null, arrayOf(
                TdApi.PhotoSize("s", file(1), 100, 100, intArrayOf()),
                TdApi.PhotoSize("y", file(2), 1280, 960, intArrayOf()),
            ))
            hasSpoiler = true
        }
        val media = assertNotNull(mapMessageMedia(photo))
        assertEquals(2, media.file.id)
        assertEquals(1, media.thumbnail?.id)
        assertEquals(1280, media.width)
        assertEquals(960, media.height)
        assertTrue(media.hasSpoiler)
        photo.isSecret = true
        assertNull(mapMessageMedia(photo))
    }

    @Test
    fun stickerFormatsAndThumbnailsAreMappedWithoutNativeRendering() {
        for ((native, format) in listOf(
            TdApi.StickerFormatWebp() to MediaFormat.WEBP,
            TdApi.StickerFormatTgs() to MediaFormat.TGS,
            TdApi.StickerFormatWebm() to MediaFormat.WEBM,
        )) {
            val item = assertNotNull(mapSticker(sticker(native)))
            assertEquals(format, item.media.format)
            assertEquals(MediaFormat.WEBP, item.media.thumbnailFormat)
            assertEquals(8, item.media.file.id)
            assertEquals(9, item.media.thumbnail?.id)
            assertFalse(item.toString().contains("private"))
        }
    }

    @Test
    fun stickerPickerReadsRegularInstalledSetsAndUnattachedRecentStickers() {
        val fixture = Fixture()
        val recent = mutableListOf<ChatResult<List<StickerItem>>>()
        fixture.session.recentStickers(recent::add)
        assertFalse(assertIs<TdApi.GetRecentStickers>(fixture.last.command).isAttached)
        fixture.last.result(TdApi.Stickers(arrayOf(sticker())))
        assertEquals(8, recent.single().value?.single()?.media?.file?.id)
        val installed = mutableListOf<ChatResult<List<StickerPack>>>()
        fixture.session.installedStickerSets(installed::add)
        assertIs<TdApi.StickerTypeRegular>(assertIs<TdApi.GetInstalledStickerSets>(fixture.last.command).stickerType)
        fixture.last.result(TdApi.StickerSets(1, arrayOf(TdApi.StickerSetInfo().apply {
            id = 20; title = "synthetic private pack"; size = 12; covers = arrayOf(sticker())
        })))
        assertEquals(12, installed.single().value?.single()?.size)
        fixture.session.stickerSet(20, recent::add)
        assertEquals(20L, assertIs<TdApi.GetStickerSet>(fixture.last.command).setId)
        fixture.last.result(TdApi.StickerSet().apply { id = 20; stickerType = TdApi.StickerTypeRegular(); stickers = arrayOf(sticker()) })
        assertEquals(2, recent.size)
    }

    @Test
    fun stickerSetRejectsMismatchedNativeSetIdentity() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<List<StickerItem>>>()
        fixture.session.stickerSet(20, results::add)
        fixture.last.result(TdApi.StickerSet().apply {
            id = 21; stickerType = TdApi.StickerTypeRegular(); stickers = arrayOf(sticker())
        })
        assertNull(results.single().value)
        assertNotNull(results.single().error)
    }

    @Test
    fun photoAndStickerSendsUseNativeFileInputsWithoutPaymentsOrReplyMarkup() {
        val fixture = Fixture()
        fixture.session.sendPhoto(10, "C:/synthetic-private/photo.jpg", "private caption") { }
        val photoSend = assertIs<TdApi.SendMessage>(fixture.last.command)
        val photo = assertIs<TdApi.InputMessagePhoto>(photoSend.inputMessageContent)
        assertEquals("C:/synthetic-private/photo.jpg", assertIs<TdApi.InputFileLocal>(photo.photo.photo).path)
        assertEquals("private caption", photo.caption.text)
        assertNull(photo.selfDestructType)
        assertNull(photoSend.replyTo)
        assertNull(photoSend.replyMarkup)
        assertEquals(0L, photoSend.options.paidMessageStarCount)
        assertFalse(photoSend.options.allowPaidBroadcast)
        fixture.session.sendSticker(10, assertNotNull(mapSticker(sticker()))) { }
        val stickerSend = assertIs<TdApi.SendMessage>(fixture.last.command)
        val input = assertIs<TdApi.InputMessageSticker>(stickerSend.inputMessageContent)
        assertEquals(8, assertIs<TdApi.InputFileId>(input.sticker.sticker).id)
        assertEquals(512, input.sticker.width)
        assertEquals(0L, stickerSend.options.paidMessageStarCount)
        assertFalse(stickerSend.options.onlyPreview)
    }

    @Test
    fun fullListPositionsReadStateFoldersAndFilesArriveAsTypedUpdates() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateChatDraftMessage(10, null, arrayOf(
            TdApi.ChatPosition(TdApi.ChatListArchive(), 400, false, null),
            TdApi.ChatPosition(TdApi.ChatListFolder(7), 500, false, null),
        )))
        assertEquals(mapOf(ChatListRef(-1) to 400L, ChatListRef(7) to 500L), assertIs<ChatEvent.Positions>(events.last()).positions)
        mapper.accept(TdApi.UpdateChatFolders(arrayOf(folderInfo()), 1, false))
        assertEquals(1, assertIs<ChatEvent.Folders>(events.last()).mainPosition)
        mapper.accept(TdApi.UpdateChatReadInbox(10, 21, 3))
        assertEquals(21L, assertIs<ChatEvent.Inbox>(events.last()).lastReadId)
        mapper.accept(TdApi.UpdateChatIsMarkedAsUnread(10, true))
        assertTrue(assertIs<ChatEvent.MarkedUnread>(events.last()).marked)
        mapper.accept(TdApi.UpdateUnreadChatCount(TdApi.ChatListFolder(7), 20, 5, 3, 2, 1))
        assertEquals(5, assertIs<ChatEvent.UnreadChats>(events.last()).count)
        mapper.accept(TdApi.UpdateFile(file(8, true)))
        assertTrue(assertIs<ChatEvent.FileUpdated>(events.last()).file.downloaded)
        assertFalse(events.toString().contains("private"))
    }

    @Test
    fun folderEditingPreservesRichNameAndHiddenFieldsWhileChangingRules() {
        val fixture = Fixture()
        val original = TdApi.ChatFolder().apply {
            name = TdApi.ChatFolderName(TdApi.FormattedText("📁 Work", arrayOf(TdApi.TextEntity(0, 2, TdApi.TextEntityTypeCustomEmoji(9987)))), true)
            icon = TdApi.ChatFolderIcon("Work"); colorId = 3; isShareable = false
            pinnedChatIds = longArrayOf(1); includedChatIds = longArrayOf(2); excludedChatIds = longArrayOf(3)
            includeContacts = true
        }
        var loaded: ChatFolder? = null
        fixture.session.getFolder(7) { loaded = it.value }
        fixture.last.result(original)
        val editable = assertNotNull(loaded).copy(excludeRead = true)
        fixture.session.saveFolder(editable) { }
        assertEquals(7, assertIs<TdApi.GetChatFolder>(fixture.last.command).chatFolderId)
        fixture.last.result(original)
        val edit = assertIs<TdApi.EditChatFolder>(fixture.last.command)
        assertSame(original.name, edit.folder.name)
        assertEquals("Work", edit.folder.icon.name)
        assertEquals(3, edit.folder.colorId)
        assertFalse(edit.folder.isShareable)
        assertContentEquals(longArrayOf(1), edit.folder.pinnedChatIds)
        assertContentEquals(longArrayOf(2), edit.folder.includedChatIds)
        assertContentEquals(longArrayOf(3), edit.folder.excludedChatIds)
        assertTrue(edit.folder.excludeRead)
    }

    @Test
    fun creatingAndDeletingFoldersNeverLeaveTheirChats() {
        val fixture = Fixture()
        val saved = mutableListOf<ChatResult<ChatFolderInfo>>()
        fixture.session.saveFolder(ChatFolder(name = "Unread", includeGroups = true, excludeRead = true), saved::add)
        assertTrue(assertIs<TdApi.CreateChatFolder>(fixture.last.command).folder.excludeRead)
        fixture.last.result(folderInfo())
        assertEquals(7, saved.single().value?.id)
        fixture.session.deleteFolder(7) { }
        assertTrue(assertIs<TdApi.DeleteChatFolder>(fixture.last.command).leaveChatIds.isEmpty())
    }

    @Test
    fun photoAndStickerPermissionsDoNotFollowTextPermissionBlindly() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(1, 5, TdApi.ChatMemberStatusMember(0), true, 0)))
        mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
            id = 10; type = TdApi.ChatTypeBasicGroup(1)
            permissions = TdApi.ChatPermissions().apply { canSendBasicMessages = false; canSendPhotos = true; canSendOtherMessages = false }
        }))
        val chat = assertIs<ChatEvent.NewChat>(events.last()).chat
        assertFalse(chat.canSend)
        assertTrue(chat.canSendPhotos)
        assertFalse(chat.canSendStickers)
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(1, 5,
            TdApi.ChatMemberStatusRestricted(true, 0, TdApi.ChatPermissions()), true, 0)))
        assertEquals(ChatEvent.Permissions(10, false, false, false), events.last())
    }

    @Test
    fun extraRequestsSanitizeErrorsAndIgnoreDuplicateCompletions() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MediaFile>>()
        fixture.session.download(8, results::add)
        fixture.last.result(TdApi.Error(403, "private native response"))
        fixture.last.result(file(8, true))
        fixture.last.error()
        assertEquals(1, results.size)
        assertEquals(ChatError.FORBIDDEN, results.single().error)
        assertFalse(results.toString().contains("private"))
        assertFalse(ChatFolder(name = "private folder").toString().contains("private"))
    }

    private fun file(id: Int, completed: Boolean = false) = TdApi.File().apply {
        this.id = id; size = 1024; expectedSize = 1024
        local = TdApi.LocalFile().apply {
            path = "C:/synthetic-private/image.webp"; canBeDownloaded = true
            isDownloadingCompleted = completed; isDownloadingActive = !completed
            downloadedSize = if (completed) 1024 else 100
        }
    }

    private fun sticker(format: TdApi.StickerFormat = TdApi.StickerFormatWebp()) = TdApi.Sticker().apply {
        id = 18; setId = 20; width = 512; height = 512; emoji = "🙂"
        this.format = format; sticker = file(8)
        thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatWebp(), 100, 100, file(9))
    }

    private fun folderInfo() = TdApi.ChatFolderInfo(7,
        TdApi.ChatFolderName(TdApi.FormattedText("private folder", emptyArray()), false),
        TdApi.ChatFolderIcon("Unread"), -1, false, false)

    private class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, result, error -> requests += Request(command, result, error) }
        val last get() = requests.last()
    }
    private class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val error: () -> Unit)
}
