package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Causal Get -> Edit contracts; never creates JNI, opens an account, or submits a real folder. */
class OfficialTdFolderEditorTest {
    @Test fun anOpeningSourceIsOpaqueImmutableAndKeepsTheRealRichNameAndNullChosenIcon() {
        val f = Fixture()
        val raw = folder().apply { icon = null }
        val loaded = f.open(raw)
        assertTrue(loaded.sourceId > 0)
        assertEquals(listOf(MessageEntity(0, 2, MessageEntityKind.CUSTOM_EMOJI, id = 98765)), loaded.nameEntities)
        assertTrue(loaded.animateCustomEmoji)
        assertNull(loaded.iconName)
        raw.name.text.text = "Mutated"
        raw.includedChatIds[0] = 555
        assertEquals("📁 Work", loaded.name)
        assertEquals(listOf(101L), loaded.includedChatIds)
        assertEquals("ChatFolder(<redacted>)", loaded.toString())
    }

    @Test fun changingOnlyOneRulePreservesEveryUneditedFreshNativeField() {
        val f = Fixture()
        val source = f.open(folder())
        f.save(source.copy(includeBots = true))
        val fresh = folder("🧸 Remote", 444).apply {
            colorId = 6; icon = null; includedChatIds = longArrayOf(202); pinnedChatIds = longArrayOf(51, 50)
            excludedChatIds = longArrayOf(302); excludeRead = true; excludeMuted = true; animate(false)
        }
        f.last.respond(fresh)
        val sent = assertIs<TdApi.EditChatFolder>(f.last.command).folder
        assertSame(fresh.name, sent.name)
        assertNull(sent.icon)
        assertEquals(6, sent.colorId)
        assertContentEquals(fresh.includedChatIds, sent.includedChatIds)
        assertContentEquals(fresh.pinnedChatIds, sent.pinnedChatIds)
        assertContentEquals(fresh.excludedChatIds, sent.excludedChatIds)
        assertTrue(sent.excludeRead); assertTrue(sent.excludeMuted); assertTrue(sent.includeBots)
        assertTrue(sent.includeContacts)
        assertEquals(3, f.requests.size)
    }

    @Test fun aDirtyRuleConflictNeverOverwritesTheNewerNativeValueAndKeepsItsSourceForRetry() {
        val f = Fixture()
        val source = f.open(folder())
        f.save(source.copy(includeBots = true))
        f.last.respond(folder().apply { includeBots = true })
        assertEquals(ChatError.NOT_READY, f.results.single().error)
        assertEquals(0, f.edits())
        f.save(source.copy(includeBots = true))
        f.last.respond(folder())
        assertEquals(1, f.edits())
    }

    @Test fun aLocalSuffixEditRetainsTheCustomPrefixAndTheFreshIndependentAnimationChoice() {
        val f = Fixture()
        val source = f.open(folder())
        val desired = source.withNameText("📁 Works")
        assertEquals(source.nameEntities, desired.nameEntities)
        assertTrue(desired.animateCustomEmoji)
        f.save(desired)
        f.last.respond(folder().apply { animate(false) })
        val sent = assertIs<TdApi.EditChatFolder>(f.last.command).folder
        assertEquals("📁 Works", sent.name.text.text)
        assertEquals(98765L, assertIs<TdApi.TextEntityTypeCustomEmoji>(sent.name.text.entities.single().type).customEmojiId)
        assertFalse(sent.name.animateCustomEmoji, "An unedited animation field uses fresh, independently from the dirty title text.")
    }

    @Test fun changingOnlyAnimationPreservesARemoteRichRenameAndDirtyTitleChecksActualEntities() {
        val f = Fixture()
        val source = f.open(folder())
        f.save(source.copy(animateCustomEmoji = false))
        val fresh = folder("🧸 Remote", 444)
        f.last.respond(fresh)
        val sent = assertIs<TdApi.EditChatFolder>(f.last.command).folder
        assertSame(fresh.name.text, sent.name.text)
        assertFalse(sent.name.animateCustomEmoji)
        val conflict = Fixture()
        val old = conflict.open(folder())
        conflict.save(old.withNameText("📁 Works"))
        conflict.last.respond(folder(emojiId = 555))
        assertEquals(ChatError.NOT_READY, conflict.results.single().error)
        assertEquals(0, conflict.edits(), "Matching plain text cannot hide a conflicting native custom-emoji identity.")
    }

    @Test fun perPeerIntentPreservesUnrelatedHiddenAdditionsAndFreshPinnedOrder() {
        val f = Fixture()
        val source = f.open(folder().apply { pinnedChatIds = longArrayOf(50, 51) })
        f.save(source.copy(includedChatIds = listOf(101, 103), excludedChatIds = emptyList()))
        val fresh = folder().apply { pinnedChatIds = longArrayOf(51, 50, 52); includedChatIds = longArrayOf(101, 202)
            excludedChatIds = longArrayOf(301, 302) }
        f.last.respond(fresh)
        val sent = assertIs<TdApi.EditChatFolder>(f.last.command).folder
        assertContentEquals(longArrayOf(51, 50, 52), sent.pinnedChatIds)
        assertContentEquals(longArrayOf(101, 202, 103), sent.includedChatIds)
        assertContentEquals(longArrayOf(302), sent.excludedChatIds)
    }

    @Test fun changingTheSamePeerOrPinnedOrderConflictsWithoutSendingAnEdit() {
        for (reorder in listOf(false, true)) {
            val f = Fixture()
            val source = f.open(folder().apply { pinnedChatIds = longArrayOf(50, 51) })
            f.save(if (reorder) source.copy(pinnedChatIds = listOf(51, 50)) else source.copy(includedChatIds = emptyList(), excludedChatIds = listOf(301, 101)))
            val fresh = folder().apply {
                if (reorder) pinnedChatIds = longArrayOf(50, 51, 52)
                else { includedChatIds = longArrayOf(); pinnedChatIds = longArrayOf(50, 51, 101) }
            }
            f.last.respond(fresh)
            assertEquals(ChatError.NOT_READY, f.results.single().error)
            assertEquals(0, f.edits())
        }
    }

    @Test fun aFreshSharedDefinitionCannotPassThroughTheOldNonSharedEditor() {
        val f = Fixture()
        val source = f.open(folder())
        f.save(source.withNameText("📁 Works"))
        f.last.respond(folder().apply { isShareable = true; includedChatIds = longArrayOf(999) })
        assertEquals(ChatError.UNSUPPORTED, f.results.single().error)
        assertEquals(0, f.edits())
    }

    @Test fun everyReadToWriteStageChecksLeaseRetirementAndEvenIdenticalMetadataUpdates() {
        for (stage in 0..3) {
            val f = Fixture()
            val source = f.open(folder())
            f.save(source.copy(includeBots = true))
            val held = f.last
            when (stage) {
                0 -> f.owned = false
                1 -> f.session.folderSettings.retire()
                2 -> f.update()
                else -> { f.update(); f.update() }
            }
            held.respond(folder())
            assertEquals(ChatError.NOT_READY, f.results.single().error)
            assertEquals(0, f.edits())
        }
        val opening = Fixture()
        var loaded: ChatFolder? = null
        opening.session.getFolder(7, { opening.owned }) { loaded = it.value }
        opening.owned = false
        opening.last.respond(folder())
        assertNull(loaded)
    }

    @Test fun anAlreadySentEditReportsItsRealAcceptanceOnceAfterRetirement() {
        val f = Fixture()
        val source = f.open(folder())
        f.save(source.copy(includeBots = true))
        f.last.respond(folder())
        val edit = f.last
        f.owned = false; f.session.folderSettings.retire()
        edit.respond(info()); edit.respond(info()); edit.fail()
        assertEquals(1, f.results.size)
        assertEquals(7, f.results.single().value?.id)
        assertEquals(3, f.requests.size)
    }

    @Test fun sourceKeysAreSessionBoundedAndUnknownOrEvictedKeysNeverReadOrWrite() {
        val f = Fixture()
        val first = f.open(folder())
        f.open(folder()); f.open(folder())
        val before = f.requests.size
        f.save(first.copy(includeBots = true))
        assertEquals(before, f.requests.size)
        assertEquals(ChatError.NOT_READY, f.results.single().error)
        val other = Fixture()
        other.save(first.copy(includeBots = true))
        assertTrue(other.requests.isEmpty())
        assertEquals(ChatError.NOT_READY, other.results.single().error)
    }

    @Test fun creationKeepsExplicitRichNameAndAnimationButRejectsOverlappingPeerLists() {
        val f = Fixture()
        val desired = mapChatFolder(0, folder()).copy(sourceId = 0)
        f.save(desired)
        val sent = assertIs<TdApi.CreateChatFolder>(f.last.command).folder
        assertTrue(sent.name.animateCustomEmoji)
        assertEquals(98765L, assertIs<TdApi.TextEntityTypeCustomEmoji>(sent.name.text.entities.single().type).customEmojiId)
        val invalid = Fixture()
        invalid.save(desired.copy(includedChatIds = listOf(50)))
        assertTrue(invalid.requests.isEmpty())
        assertEquals(ChatError.UNSUPPORTED, invalid.results.single().error)
    }

    @Test fun malformedNativeTitlesAndOverlappingMembershipNeverCreateAnEditableSource() {
        for (raw in listOf(folder().apply { name.text.entities[0].offset = 1 },
            folder().apply { name.text.entities[0].length = Int.MAX_VALUE },
            folder().apply { includedChatIds = pinnedChatIds.copyOf() },
            folder("x".repeat(13)))) {
            val f = Fixture()
            val received = mutableListOf<ChatResult<ChatFolder>>()
            f.session.getFolder(7, { true }, received::add)
            f.last.respond(raw)
            assertNull(received.single().value)
            assertEquals(ChatError.UNSUPPORTED, received.single().error)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun retiredOpeningsAndCreationsNeverSendAndARawUpdateInvalidatesAnOpeningBeforePublishingItsSource() {
        for (retireSession in listOf(false, true)) {
            val f = Fixture()
            if (retireSession) f.session.folderSettings.retire() else f.owned = false
            val opened = mutableListOf<ChatResult<ChatFolder>>()
            f.session.getFolder(7, { f.owned }, opened::add)
            f.save(ChatFolder(name = "New", includeGroups = true))
            assertTrue(f.requests.isEmpty())
            assertEquals(ChatError.NOT_READY, opened.single().error)
            assertEquals(ChatError.NOT_READY, f.results.single().error)
        }
        val f = Fixture()
        val opened = mutableListOf<ChatResult<ChatFolder>>()
        f.session.getFolder(7, { true }, opened::add)
        val held = f.last
        f.update()
        held.respond(folder())
        assertEquals(ChatError.NOT_READY, opened.single().error)
        assertNull(opened.single().value)
        assertEquals(1, f.requests.size)
    }

    @Test fun aNativeEditFailureKeepsTheOriginalSourceForExplicitRetryButAcceptanceConsumesItOnce() {
        val f = Fixture()
        val source = f.open(folder())
        val desired = source.copy(includeBots = true)
        f.save(desired)
        f.last.respond(folder())
        f.last.respond(TdApi.Error(500, "synthetic failure"))
        assertEquals(ChatError.REQUEST_FAILED, f.results.single().error)
        f.save(desired)
        f.last.respond(folder())
        f.last.respond(info())
        assertEquals(7, f.results.last().value?.id)
        val acceptedRequests = f.requests.size
        f.save(desired)
        assertEquals(acceptedRequests, f.requests.size, "An already accepted source cannot acquire another native write.")
        assertEquals(ChatError.NOT_READY, f.results.last().error)
        assertEquals(2, f.edits())
    }

    @Test fun explicitColorIconAndAnimationConflictsNeverSendAnEdit() {
        for (field in 0..2) {
            val f = Fixture()
            val source = f.open(folder())
            val desired = when (field) {
                0 -> source.copy(colorId = 6)
                1 -> source.copy(iconName = "Study")
                else -> source.copy(animateCustomEmoji = false)
            }
            f.save(desired)
            f.last.respond(folder().apply { when (field) {
                0 -> colorId = 4
                1 -> icon = TdApi.ChatFolderIcon("Travel")
                else -> animate(false)
            } })
            assertEquals(ChatError.NOT_READY, f.results.single().error)
            assertEquals(0, f.edits())
        }
    }

    private data class Request(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture {
        val requests = mutableListOf<Request>()
        val last get() = requests.last()
        var owned = true
        val session = OfficialTdChatSession { command, respond, fail -> requests += Request(command, respond, fail) }
        val results = mutableListOf<ChatResult<ChatFolderInfo>>()
        init { update() }
        fun open(raw: TdApi.ChatFolder): ChatFolder {
            var value: ChatFolder? = null
            session.getFolder(7, { owned }) { value = it.value }
            assertIs<TdApi.GetChatFolder>(last.command)
            last.respond(raw)
            return assertNotNull(value)
        }
        fun save(value: ChatFolder) { session.saveFolder(value, { owned }, results::add) }
        fun update() { session.folderSettings.accept(TdApi.UpdateChatFolders(arrayOf(info()), 0, true)) }
        fun edits() = requests.count { it.command is TdApi.EditChatFolder }
    }
    private companion object {
        fun folder(text: String = "📁 Work", emojiId: Long = 98765) = TdApi.ChatFolder(
            TdApi.ChatFolderName(TdApi.FormattedText(text, arrayOf(TdApi.TextEntity(0, 2, TdApi.TextEntityTypeCustomEmoji(emojiId)))), true),
            TdApi.ChatFolderIcon("Work"), 0, false, longArrayOf(50), longArrayOf(101), longArrayOf(301),
            false, false, false, true, false, false, false, false)
        fun TdApi.ChatFolder.animate(enabled: Boolean) { name = TdApi.ChatFolderName(name.text, enabled) }
        fun info() = TdApi.ChatFolderInfo(7, folder().name, TdApi.ChatFolderIcon("Work"), 0, false, false)
    }
}
