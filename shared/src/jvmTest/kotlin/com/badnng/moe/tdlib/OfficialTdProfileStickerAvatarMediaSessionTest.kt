package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real OfficialTdSession helper, facade and broker; only native send is synthetic. */
class OfficialTdProfileStickerAvatarMediaSessionTest {
    @Test fun sameHelperIssuesMediaGrantAndSharedBrokerDispatchesAfterFreshSourceCheck() {
        val f = Fixture()
        try {
            val selection = f.selection()
            val responses = CopyOnWriteArrayList<SettingsResult<ProfileStickerAvatarEncoded>>()
            val lease = f.session.stickerAvatarMedia.full(f.picker, selection, { true }, responses::add)
            f.next<TdApi.GetMe>().reply(self())
            f.next<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>().reply(defaults())
            val prefixCall = f.next<TdApi.GetFileDownloadedPrefixSize>()
            val prefix = assertIs<TdApi.GetFileDownloadedPrefixSize>(prefixCall.command)
            assertEquals(3456, prefix.fileId)
            assertEquals(0L, prefix.offset)
            assertTrue(responses.isEmpty())
            assertTrue(f.calls.isEmpty(), "Download must wait for the real prefix response")
            prefixCall.reply(TdApi.FileDownloadedPrefixSize(0))
            prefixCall.reply(TdApi.FileDownloadedPrefixSize(0))
            val downloadCall = f.next<TdApi.DownloadFile>()
            val download = assertIs<TdApi.DownloadFile>(downloadCall.command)
            assertEquals(3456, download.fileId)
            assertEquals(16, download.priority)
            assertEquals(0L, download.offset)
            assertEquals(16L, download.limit)
            assertFalse(download.synchronous)
            assertTrue(responses.isEmpty())
            assertTrue(f.session.chats.managesDownload(3456), "Chat and profile media borrow the same session broker")
            lease.close()
            assertEquals(listOf(SettingsError.NOT_READY), responses.map { it.error })
            assertTrue(responses.none { it.value != null })
            assertTrue(f.calls.isEmpty(), "Physical cancellation waits for the download acceptance")
            assertTrue(f.session.chats.managesDownload(3456), "A pending native acceptance retains its broker lane")
            val accepted = TdApi.File().apply {
                id = 3456; size = 16
                local = TdApi.LocalFile().apply { canBeDownloaded = true; isDownloadingActive = true }
                remote = TdApi.RemoteFile().apply { uniqueId = "synthetic-only" }
            }
            downloadCall.reply(accepted)
            downloadCall.reply(accepted)
            val cancelCall = f.next<TdApi.CancelDownloadFile>()
            val cancel = assertIs<TdApi.CancelDownloadFile>(cancelCall.command)
            assertEquals(3456, cancel.fileId)
            assertFalse(cancel.onlyIfPending)
            assertTrue(f.session.chats.managesDownload(3456), "A sent cancellation is not yet an acknowledgement")
            cancelCall.reply(TdApi.Ok())
            cancelCall.reply(TdApi.Ok())
            assertFalse(f.session.chats.managesDownload(3456))
            assertEquals(listOf(SettingsError.NOT_READY), responses.map { it.error })
            assertTrue(f.calls.isEmpty())
        } finally { f.session.close() }
    }

    @Test fun continuationRechecksReadyMandatoryAndRetirementBeforeNextSend() {
        for (mode in 0..3) {
            val f = Fixture()
            try {
                val selection = f.selection()
                val lease = f.session.stickerAvatarMedia.full(f.picker, selection, { true }) { it.value?.close() }
                val getMe = f.next<TdApi.GetMe>()
                when (mode) {
                    0 -> f.ready = false
                    1 -> f.suggestions.accept(TdApi.UpdateSuggestedActions(
                        arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
                    2 -> f.session.settings.retire()
                    else -> f.session.close()
                }
                getMe.reply(self()); getMe.reply(self())
                val later = generateSequence { f.calls.poll(200, TimeUnit.MILLISECONDS) }.toList()
                assertTrue(later.none { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers ||
                    it.command is TdApi.GetFileDownloadedPrefixSize ||
                    it.command is TdApi.DownloadFile })
                lease.close()
            } finally { f.session.close() }
        }
    }

    private data class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        @Volatile var ready = true
        val gate = TdUserInteractionGate()
        val suggestions = TdLoginEmailSuggestions(gate)
        val calls = LinkedBlockingQueue<Call>()
        val session = OfficialTdSession(offline = true, interactionGate = gate, loginEmailSuggestions = suggestions,
            isAuthorizationReady = { ready }, send = { command, reply, failed -> calls.add(Call(command, reply, failed)) })
        val avatar = checkNotNull(session.settings.profileExtras?.stickerAvatar)
        val picker = checkNotNull(avatar.open(43) { true })
        inline fun <reified T : TdApi.Function<*>> next(): Call {
            val call = checkNotNull(calls.poll(3, TimeUnit.SECONDS)) { "Missing synthetic request" }
            assertIs<T>(call.command)
            return call
        }
        fun selection(): ProfileStickerAvatarSelectionTicket {
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            avatar.loadDefault(picker, result::add)
            next<TdApi.GetMe>().reply(self())
            next<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>().reply(defaults())
            val candidate = checkNotNull(result.single().value).candidates.single()
            assertEquals(ProfileStickerAvatarIdentity.CustomEmoji(-700), candidate.identity)
            assertEquals(18L, candidate.nativeStickerId)
            assertEquals(0L, candidate.nativeSetId)
            return checkNotNull(avatar.select(picker, candidate.ticket))
        }
    }
    private companion object {
        fun self() = TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = -911 } }
        fun defaults() = TdApi.Stickers(arrayOf(TdApi.Sticker().apply {
            id = 18; setId = 0; width = 100; height = 100
            format = TdApi.StickerFormatWebp()
            fullType = TdApi.StickerFullTypeCustomEmoji(-700, true)
            sticker = TdApi.File().apply {
                id = 3456; size = 16
                local = TdApi.LocalFile().apply { canBeDownloaded = true }
                remote = TdApi.RemoteFile().apply { uniqueId = "synthetic-only" }
            }
        }))
    }
}
