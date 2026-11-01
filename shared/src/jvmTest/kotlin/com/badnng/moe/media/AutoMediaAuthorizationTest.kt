package com.badnng.moe.media

import com.badnng.moe.chat.*
import java.security.MessageDigest
import kotlin.test.*

class AutoMediaAuthorizationTest {
    private val chat = ChatItem(7, "synthetic", privateUserId = 7)
    private val policy = AutoMediaPolicy()
    private fun message(format: MediaFormat, size: Long = 1, name: String = "document.pdf", mime: String = "application/pdf",
        downloaded: Boolean = false, canSave: Boolean = true): ChatMessage = ChatMessage(8, 7,
        media = MessageMedia(MediaFile(9, size, expectedSize = Long.MAX_VALUE, localPath = if (downloaded) "synthetic-cache" else null,
            downloaded = downloaded, canDownload = true), format = format, fileName = name, mimeType = mime), canBeSaved = canSave)
    private fun decide(message: ChatMessage, chat: ChatItem = this.chat, ask: Boolean = false, known: Boolean = true,
        policy: AutoMediaPolicy = this.policy) = automaticMediaDecision(policy, chat, message, message.media!!.file, ask, known)

    @Test fun complete185SuffixSetMatchesPinnedCanonicalHashAndWindowsPrecedence() {
        assertEquals(185, officialWindowsExecutableSuffixes.size)
        val canonical = officialWindowsExecutableSuffixes.sorted().joinToString("\n", postfix = "\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("f88f88436cf90bbaf4b728245e03e70f810fb55554f5b3b214a7d86b77d477ba", hash)
        officialWindowsExecutableSuffixes.forEach { suffix -> assertTrue(executableAutoMediaName("picture.${suffix.uppercase()}"), suffix) }
        listOf("document.fon", "document.key", "document.exe. ", "a/path\\other.JS", "document\u202e.exe",
            "a\\document.exe ").forEach { assertTrue(executableAutoMediaName(it), it) }
        assertFalse(executableAutoMediaName("document.pdf"))
        assertFalse(executableAutoMediaName("a.exe/document.pdf"))
    }

    @Test fun protectedPhotoAndVideoKeepNativePermissionsButNeverExternalCopy() {
        val protected = chat.copy(hasProtectedContent = true)
        val photo = decide(message(MediaFormat.PHOTO, canSave = false), protected, ask = true, known = false)
        assertTrue(photo.autoCacheAllowed)
        assertFalse(photo.externalCopyAllowed)
        val video = decide(message(MediaFormat.VIDEO, canSave = false), protected, ask = true, known = false)
        assertTrue(video.autoStreamAllowed)
        assertFalse(video.externalCopyAllowed)
    }

    @Test fun pngCacheExceptionIsStrictAndIndependentOfAskOrProtection() {
        val small = message(MediaFormat.FILE, 10 * MIB - 1, "image_document.png", "image/png", canSave = false)
        assertTrue(decide(small, chat.copy(hasProtectedContent = true), ask = true, known = false,
            policy = policy.withSource(AutoMediaSource.USER, policy.rules(AutoMediaSource.USER).withLimit(AutoMediaType.FILE, 20 * MIB))).autoCacheAllowed)
        assertFalse(decide(small).externalCopyAllowed)
        val atBoundary = small.copy(media = small.media!!.copy(file = small.media!!.file.copy(size = 10 * MIB)))
        assertFalse(ordinaryDocumentCached(atBoundary.media!!, atBoundary.media!!.file))
        assertFalse(decide(atBoundary, ask = true).autoCacheAllowed)
        assertFalse(ordinaryDocumentCached(small.media!!.copy(mimeType = "image/jpeg"), small.media!!.file))
        assertFalse(ordinaryDocumentCached(small.media!!.copy(fileName = "photo.png"), small.media!!.file))
    }

    @Test fun ordinaryDocumentNeedsSeparateCompletedCopyAuthorization() {
        val pending = message(MediaFormat.FILE)
        assertTrue(decide(pending).autoCacheAllowed)
        assertFalse(decide(pending).externalCopyAllowed)
        assertEquals(AutoMediaDecisionReason.NOT_COMPLETED, decide(pending).copyReason)
        assertFalse(decide(pending, ask = true).autoCacheAllowed)
        assertFalse(decide(pending, known = false).autoCacheAllowed)
        assertFalse(decide(pending, chat.copy(hasProtectedContent = true)).autoCacheAllowed)
        val ready = message(MediaFormat.FILE, downloaded = true)
        assertTrue(decide(ready).externalCopyAllowed)
        assertFalse(decide(ready.copy(canBeSaved = false)).externalCopyAllowed)
        assertFalse(decide(ready, ask = true).externalCopyAllowed)
    }

    @Test fun unknownZeroStartsFiniteCacheEligibilityAndActualSizeRevokesOnlyItsPolicy() {
        assertTrue(decide(message(MediaFormat.PHOTO, 0)).autoCacheAllowed)
        assertTrue(decide(message(MediaFormat.FILE, 0)).autoCacheAllowed)
        assertTrue(decide(message(MediaFormat.VIDEO, 0)).autoCacheAllowed)
        assertTrue(decide(message(MediaFormat.VIDEO, 0)).autoStreamAllowed)
        assertFalse(decide(message(MediaFormat.PHOTO, 8 * MIB + 1)).autoCacheAllowed)
        assertFalse(decide(message(MediaFormat.VIDEO, 50 * MIB + 1)).autoStreamAllowed)
        assertFalse(decide(message(MediaFormat.VIDEO, 0), policy = policy.sourceEnabled(AutoMediaSource.USER, false)).autoCacheAllowed)
    }

    @Test fun gifCacheIsSeparateFromAutoplayAndNativeOrPeerFailuresDoNotAuthorize() {
        val disabled = policy.sourceEnabled(AutoMediaSource.USER, false)
        val gif = message(MediaFormat.ANIMATION, name = "movie.mp4")
        assertTrue(decide(gif, policy = disabled).autoCacheAllowed)
        assertFalse(decide(gif, policy = disabled).autoStreamAllowed)
        assertFalse(decide(gif, policy = policy.copy(peers = mapOf(chat.id to AutoMediaOverride.DENY))).autoCacheAllowed)
        val uploading = gif.copy(media = gif.media!!.copy(file = gif.media!!.file.copy(uploading = true)))
        assertFalse(decide(uploading).autoCacheAllowed)
        assertFalse(decide(message(MediaFormat.FILE, name = "image_tool.exe", mime = "image/png")).autoCacheAllowed)
        assertFalse(decide(gif.copy(chatId = 99)).autoStreamAllowed)
    }
}
