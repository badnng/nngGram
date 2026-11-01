package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.folders.FolderCapabilities
import com.badnng.moe.folders.FolderNativeState
import com.badnng.moe.folders.FolderRecommendation
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Pinned Java API calls only; no account, native client, history, files or synchronization claims. */
class OfficialTdFolderSettingsTest {
    @Test
    fun capabilitiesReadActualUserAndNamedOptionsAndTreatZeroCapacityAsARealValue() {
        for (premium in listOf(false, true)) {
            val fixture = Fixture(FolderNativeState())
            val result = mutableListOf<SettingsResult<FolderCapabilities>>()
            fixture.api.capabilities(fixture::current, result::add)
            fixture.answerCapabilities(premium = premium, count = 0, chosen = 10_000)
            assertEquals(FolderCapabilities(premium, 0, 10_000), result.single().value)
            assertEquals(listOf("GetMe", "GetOption", "GetOption"), fixture.requests.map { it.command.javaClass.simpleName })
            assertMetadataOnly(fixture)
        }
    }

    @Test
    fun missingWrongTypeAndOutOfRangeOptionsNeverInventAUsableCapability() {
        for (bad in listOf<TdApi.Object>(TdApi.OptionValueEmpty(), TdApi.OptionValueBoolean(true),
            TdApi.OptionValueInteger(-1), TdApi.OptionValueInteger(101), TdApi.Error(500, "private native detail"))) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<FolderCapabilities>>()
            fixture.api.capabilities(fixture::current, result::add)
            fixture.answerMe()
            assertEquals("chat_folder_count_max", assertIs<TdApi.GetOption>(fixture.last.command).name)
            fixture.last.respond(bad)
            assertNull(result.single().value)
            assertNotNull(result.single().error)
            assertEquals(2, fixture.requests.size)
        }
        for (chosen in listOf(0L, 10_001L, Long.MAX_VALUE)) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<FolderCapabilities>>()
            fixture.api.capabilities(fixture::current, result::add)
            fixture.answerMe()
            fixture.last.respond(TdApi.OptionValueInteger(10))
            fixture.last.respond(TdApi.OptionValueInteger(chosen))
            assertNull(result.single().value)
            assertNotNull(result.single().error)
        }
    }

    @Test
    fun capabilityReadChainsCheckTheLeaseAndRetirementBeforeEveryFollowingSend() {
        for (retire in listOf(false, true)) for (afterCount in listOf(false, true)) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<FolderCapabilities>>()
            fixture.api.capabilities(fixture::current, result::add)
            if (afterCount) fixture.answerMe()
            val held = fixture.last
            fixture.revoke(retire)
            held.respond(if (afterCount) TdApi.OptionValueInteger(10) else me(true))
            assertEquals(if (afterCount) 2 else 1, fixture.requests.size)
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertMetadataOnly(fixture)
        }
    }

    @Test
    fun recommendationsAcceptEmptyAndSixtyFourButRejectOversizeAndMalformedPayloadsAsAWhole() {
        val empty = Fixture()
        assertTrue(empty.load().isEmpty())
        val full = Fixture()
        val list = full.load(*(0 until 64).map { payload("Rec$it") }.toTypedArray())
        assertEquals(64, list.size)
        assertEquals(64, list.map { it.key }.distinct().size)
        assertTrue(list.all { it.key > 0 })
        assertEquals(1, full.requests.size, "A nonmatching metadata summary does not require existing folder details.")
        val invalid = listOf(
            Array(65) { TdApi.RecommendedChatFolder(payload("R$it"), "description") },
            arrayOf(TdApi.RecommendedChatFolder(payload("  "), "description")),
            arrayOf(TdApi.RecommendedChatFolder(payload().apply { isShareable = true }, "description")),
            arrayOf(TdApi.RecommendedChatFolder(payload(), "x".repeat(4097))),
        )
        for (values in invalid) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<List<FolderRecommendation>>>()
            fixture.api.recommendations(fixture::current, result::add)
            fixture.last.respond(TdApi.RecommendedChatFolders(values))
            assertNull(result.single().value)
            assertEquals(SettingsError.UNSUPPORTED, result.single().error)
            assertEquals(1, fixture.requests.size)
            assertMetadataOnly(fixture)
        }
    }

    @Test
    fun opaqueRecommendationCreatesItsExactRichPayloadOnceAndNativeAcceptanceNeedsNoUpdateFirst() {
        val fixture = Fixture()
        val original = richPayload("📁 Suggested")
        val key = fixture.load(original).single().key
        val result = mutableListOf<SettingsResult<ChatFolderInfo>>()
        fixture.api.createRecommended(key, fixture.state, fixture::current, result::add)
        fixture.answerCapabilities()
        val create = assertIs<TdApi.CreateChatFolder>(fixture.last.command)
        assertSame(original, create.folder)
        assertSame(original.name.text.entities, create.folder.name.text.entities)
        assertTrue(create.folder.name.animateCustomEmoji)
        assertContentEquals(original.pinnedChatIds, create.folder.pinnedChatIds)
        fixture.last.respond(info(22, original.name.text.text, original.colorId))
        assertEquals(22, result.single().value?.id)
        val acceptedCount = fixture.requests.size
        fixture.api.createRecommended(key, fixture.state, fixture::current, result::add)
        assertEquals(acceptedCount, fixture.requests.size, "An accepted opaque recommendation key cannot create twice.")
        assertEquals(SettingsError.NOT_READY, result.last().error)
        assertAllowed(fixture)
    }

    @Test
    fun recommendationFilteringResolvesOnlyMatchingSummariesAndKeepsSameNameDifferentRules() {
        val fixture = Fixture()
        val exact = payload("Work")
        val different = payload("Work").apply { includeBots = true }
        val result = mutableListOf<SettingsResult<List<FolderRecommendation>>>()
        fixture.api.recommendations(fixture::current, result::add)
        fixture.last.respond(TdApi.RecommendedChatFolders(arrayOf(
            TdApi.RecommendedChatFolder(exact, "equal"), TdApi.RecommendedChatFolder(different, "different"),
            TdApi.RecommendedChatFolder(payload("Other"), "other"))))
        assertEquals(2, assertIs<TdApi.GetChatFolder>(fixture.last.command).chatFolderId)
        fixture.last.respond(payload("Work"))
        val visible = assertNotNull(result.single().value)
        assertEquals(listOf("different", "other"), visible.map { it.description })
        assertEquals(1, fixture.requests.count { it.command is TdApi.GetChatFolder })
        assertMetadataOnly(fixture)
        val created = mutableListOf<SettingsResult<ChatFolderInfo>>()
        fixture.api.createRecommended(visible.first().key, fixture.state, fixture::current, created::add)
        fixture.answerCapabilities()
        assertEquals(2, assertIs<TdApi.GetChatFolder>(fixture.last.command).chatFolderId)
        fixture.last.respond(payload("Work"))
        assertSame(different, assertIs<TdApi.CreateChatFolder>(fixture.last.command).folder)
    }

    @Test
    fun recommendationMetadataChainsStopOnSourceChangeAndRetiredCallbacksCannotReviveTheirCache() {
        for (retire in listOf(false, true)) {
            val state = baseState().copy(folders = listOf(ChatFolderInfo(2, "Work", "Folder", 2), ChatFolderInfo(3, "Work", "Folder", 2)))
            val fixture = Fixture(state)
            val result = mutableListOf<SettingsResult<List<FolderRecommendation>>>()
            fixture.api.recommendations(fixture::current, result::add)
            fixture.last.respond(TdApi.RecommendedChatFolders(arrayOf(TdApi.RecommendedChatFolder(payload("Work"), "candidate"))))
            val firstDetail = fixture.last
            assertIs<TdApi.GetChatFolder>(firstDetail.command)
            if (retire) fixture.api.retire() else fixture.accept(state.copy(tagsEnabled = false))
            firstDetail.respond(payload("Work"))
            assertEquals(2, fixture.requests.size, "A stale first detail must not authorize the next metadata lookup.")
            assertNull(result.single().value)
            assertNotNull(result.single().error)
        }
        val fixture = Fixture()
        val result = mutableListOf<SettingsResult<List<FolderRecommendation>>>()
        fixture.api.recommendations(fixture::current, result::add)
        val held = fixture.last
        fixture.api.retire()
        held.respond(TdApi.RecommendedChatFolders(arrayOf(TdApi.RecommendedChatFolder(payload(), "late"))))
        assertNull(result.single().value)
        val created = mutableListOf<SettingsResult<ChatFolderInfo>>()
        fixture.api.createRecommended(1, fixture.state, fixture::current, created::add)
        assertEquals(1, fixture.requests.size)
        assertEquals(SettingsError.NOT_READY, created.single().error)
    }

    @Test
    fun recommendationCreationRequiresAKnownCurrentKeyFreshCapacityAndUnchangedNativeSource() {
        val unknown = Fixture()
        val unknownResult = mutableListOf<SettingsResult<ChatFolderInfo>>()
        unknown.api.createRecommended(9_999, unknown.state, unknown::current, unknownResult::add)
        assertEquals(SettingsError.NOT_READY, unknownResult.single().error)
        assertTrue(unknown.requests.isEmpty())
        for (scenario in 0..3) {
            val fixture = Fixture()
            val key = fixture.load(payload()).single().key
            val result = mutableListOf<SettingsResult<ChatFolderInfo>>()
            fixture.api.createRecommended(key, fixture.state, fixture::current, result::add)
            fixture.answerMe(premium = false)
            fixture.last.respond(TdApi.OptionValueInteger(if (scenario == 0) 0 else if (scenario == 1) fixture.state.folders.size.toLong() else 10))
            val chosen = fixture.last
            assertEquals("chat_folder_chosen_chat_count_max", assertIs<TdApi.GetOption>(chosen.command).name)
            if (scenario == 2) fixture.accept(fixture.state.copy(mainPosition = 1))
            if (scenario == 3) fixture.owned = false
            chosen.respond(TdApi.OptionValueInteger(100))
            assertNull(result.single().value)
            assertNotNull(result.single().error)
            assertTrue(fixture.requests.none { it.command is TdApi.CreateChatFolder })
        }
        val refreshed = Fixture()
        val oldKey = refreshed.load(payload()).single().key
        val newKey = refreshed.load(payload("New")).single().key
        assertTrue(oldKey != newKey)
        val oldResult = mutableListOf<SettingsResult<ChatFolderInfo>>()
        val readCount = refreshed.requests.size
        refreshed.api.createRecommended(oldKey, refreshed.state, refreshed::current, oldResult::add)
        assertEquals(readCount, refreshed.requests.size)
        assertEquals(SettingsError.NOT_READY, oldResult.single().error)
    }

    @Test
    fun reorderUsesTheCompleteCustomIdPermutationAndRechecksActualPremiumForMainPosition() {
        for ((premium, main) in listOf(true to 2, false to 0)) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.reorder(listOf(3, 2), main, fixture.state, fixture::current, result::add)
            fixture.answerMe(premium)
            val reorder = assertIs<TdApi.ReorderChatFolders>(fixture.last.command)
            assertContentEquals(intArrayOf(3, 2), reorder.chatFolderIds)
            assertEquals(main, reorder.mainChatListPosition)
            fixture.last.respond(TdApi.Ok())
            assertEquals(Unit, result.single().value)
            assertAllowed(fixture)
        }
        val ordinary = Fixture()
        val denied = mutableListOf<SettingsResult<Unit>>()
        ordinary.api.reorder(listOf(3, 2), 1, ordinary.state, ordinary::current, denied::add)
        ordinary.answerMe(false)
        assertEquals(SettingsError.FORBIDDEN, denied.single().error)
        assertEquals(1, ordinary.requests.size)
        for ((ids, main) in listOf(listOf(2) to 0, listOf(2, 2) to 0, listOf(2, 4) to 0,
            listOf(0, 2, 3) to 0, listOf(2, 3) to -1, listOf(2, 3) to 3, (2..102).toList() to 0)) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.reorder(ids, main, fixture.state, fixture::current, result::add)
            assertEquals(SettingsError.INVALID_VALUE, result.single().error)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun everyMutationComparesThePrivateNativeViewAndMalformedUpdatesNeverAuthorizeWrites() {
        val alternatives = listOf(baseState().copy(mainPosition = 1), baseState().copy(tagsEnabled = false),
            baseState().copy(folders = baseState().folders.map { if (it.id == 2) it.copy(name = "Renamed") else it }),
            baseState().copy(folders = baseState().folders.map { if (it.id == 2) it.copy(iconName = "Other") else it }),
            baseState().copy(folders = baseState().folders.map { if (it.id == 2) it.copy(isShareable = true) else it }),
            baseState().copy(folders = baseState().folders.map { if (it.id == 2) it.copy(hasMyInviteLinks = true) else it }))
        for (changed in alternatives) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.reorder(listOf(3, 2), 0, fixture.state, fixture::current, result::add)
            fixture.accept(changed)
            fixture.answerMe()
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertEquals(1, fixture.requests.size)
        }
        val color = Fixture()
        val colorResult = mutableListOf<SettingsResult<Unit>>()
        color.api.color(color.state.folders.first(), 6, color.state, color::current, colorResult::add)
        color.answerMe()
        val detail = color.last
        color.accept(color.state.copy(mainPosition = 1))
        detail.respond(payload("Work"))
        assertEquals(SettingsError.NOT_READY, colorResult.single().error)
        assertTrue(color.requests.none { it.command is TdApi.EditChatFolder })
        for (bad in listOf(TdApi.UpdateChatFolders(arrayOf(info(1, "Invalid")), 0, true),
            TdApi.UpdateChatFolders(arrayOf(info(2), info(2)), 0, true),
            TdApi.UpdateChatFolders(arrayOf(info(2)), 2, true),
            TdApi.UpdateChatFolders(Array(101) { info(it + 2) }, 0, true))) {
            val fixture = Fixture()
            fixture.api.accept(bad)
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.remove(2, fixture.state, fixture::current, result::add)
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun tagToggleUsesActualPremiumAndReturnsNativeAcceptanceBeforeAnUpdateWithoutClaimingCloudCompletion() {
        for ((premium, enabled) in listOf(true to true, false to false)) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.toggleTags(enabled, fixture.state, fixture::current, result::add)
            fixture.answerMe(premium)
            assertEquals(enabled, assertIs<TdApi.ToggleChatFolderTags>(fixture.last.command).areTagsEnabled)
            fixture.last.respond(TdApi.Ok())
            assertEquals(Unit, result.single().value)
            assertEquals(2, fixture.requests.size, "Ok is accepted without a fabricated readback/server completion request.")
        }
        val fixture = Fixture()
        val result = mutableListOf<SettingsResult<Unit>>()
        fixture.api.toggleTags(true, fixture.state, fixture::current, result::add)
        fixture.answerMe(false)
        assertEquals(SettingsError.FORBIDDEN, result.single().error)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun removeAlwaysPassesEmptyLeaveIdsAndSharedOrMissingFoldersNeverReachDelete() {
        val fixture = Fixture()
        val result = mutableListOf<SettingsResult<Unit>>()
        fixture.api.remove(2, fixture.state, fixture::current, result::add)
        val deletion = assertIs<TdApi.DeleteChatFolder>(fixture.last.command)
        assertEquals(2, deletion.chatFolderId)
        assertContentEquals(longArrayOf(), deletion.leaveChatIds)
        fixture.last.respond(TdApi.Ok())
        assertEquals(Unit, result.single().value)
        assertEquals(1, fixture.requests.size)
        for (id in listOf(3, 999)) {
            val shared = Fixture(baseState().copy(folders = baseState().folders.map { if (it.id == 3) it.copy(isShareable = true) else it }))
            val denied = mutableListOf<SettingsResult<Unit>>()
            shared.api.remove(id, shared.state, shared::current, denied::add)
            assertEquals(SettingsError.UNSUPPORTED, denied.single().error)
            assertTrue(shared.requests.isEmpty())
        }
        assertAllowed(fixture)
    }

    @Test
    fun colorsMinusOneZeroAndSixCloneOnlyTheFreshColorAndPreserveAllNativeNameAndRuleFields() {
        for (color in listOf(-1, 0, 6)) {
            val original = richPayload("📁 Work")
            val state = baseState().copy(folders = listOf(ChatFolderInfo(2, original.name.text.text, original.icon.name, original.colorId)))
            val fixture = Fixture(state)
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.color(state.folders.single(), color, state, fixture::current, result::add)
            fixture.answerMe()
            assertEquals(2, assertIs<TdApi.GetChatFolder>(fixture.last.command).chatFolderId)
            fixture.last.respond(original)
            val edit = assertIs<TdApi.EditChatFolder>(fixture.last.command)
            assertEquals(2, edit.chatFolderId)
            val replacement = edit.folder
            assertNotSame(original, replacement)
            assertEquals(color, replacement.colorId)
            assertSame(original.name, replacement.name)
            assertSame(original.name.text.entities, replacement.name.text.entities)
            assertTrue(replacement.name.animateCustomEmoji)
            assertSame(original.icon, replacement.icon)
            assertContentEquals(original.pinnedChatIds, replacement.pinnedChatIds)
            assertContentEquals(original.includedChatIds, replacement.includedChatIds)
            assertContentEquals(original.excludedChatIds, replacement.excludedChatIds)
            assertEquals(flags(original), flags(replacement))
            fixture.last.respond(info(2, original.name.text.text, color))
            assertEquals(Unit, result.single().value)
            assertAllowed(fixture)
        }
    }

    @Test
    fun colorsRejectInvalidSharedUnentitledAndFreshConflictsButResolveTheRealDefaultIcon() {
        for (color in listOf(-2, 7)) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.color(fixture.state.folders.first(), color, fixture.state, fixture::current, result::add)
            assertEquals(SettingsError.INVALID_VALUE, result.single().error)
            assertTrue(fixture.requests.isEmpty())
        }
        for (scenario in 0..3) {
            val fixture = Fixture(if (scenario == 1) baseState().copy(tagsEnabled = false) else baseState())
            val folder = fixture.state.folders.first().let { if (scenario == 2) it.copy(isShareable = true) else if (scenario == 3) it.copy(name = "Fake") else it }
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.color(folder, 6, fixture.state, fixture::current, result::add)
            if (scenario < 2) fixture.answerMe(premium = scenario != 0)
            assertNull(result.single().value)
            assertTrue(fixture.requests.none { it.command is TdApi.GetChatFolder || it.command is TdApi.EditChatFolder })
        }
        for (changed in listOf(payload("Renamed"), payload("Work").apply { colorId = 1 },
            payload("Work").apply { isShareable = true }, payload("Work").apply { icon = TdApi.ChatFolderIcon("Other") })) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.color(fixture.state.folders.first(), 6, fixture.state, fixture::current, result::add)
            fixture.answerMe()
            fixture.last.respond(changed)
            assertEquals(SettingsError.CONFLICT, result.single().error)
            assertTrue(fixture.requests.none { it.command is TdApi.EditChatFolder })
        }
        val fixture = Fixture()
        val result = mutableListOf<SettingsResult<Unit>>()
        fixture.api.color(fixture.state.folders.first(), 6, fixture.state, fixture::current, result::add)
        fixture.answerMe()
        val original = payload("Work").apply { icon = null }
        fixture.last.respond(original)
        assertSame(original, assertIs<TdApi.GetChatFolderDefaultIconName>(fixture.last.command).folder)
        fixture.last.respond(TdApi.ChatFolderIcon("Folder"))
        assertNull(assertIs<TdApi.EditChatFolder>(fixture.last.command).folder.icon, "The computed display icon must not overwrite the native null icon.")
        fixture.last.respond(info(2, "Work", 6))
        assertEquals(Unit, result.single().value)
        assertAllowed(fixture)
    }

    @Test
    fun colorAndTagChainsRecheckLeaseRetirementAndNativeSourceBeforeEveryFollowingRequest() {
        for (retire in listOf(false, true)) for (stage in 0..2) {
            val fixture = Fixture()
            val result = mutableListOf<SettingsResult<Unit>>()
            fixture.api.color(fixture.state.folders.first(), 6, fixture.state, fixture::current, result::add)
            if (stage >= 1) fixture.answerMe()
            if (stage == 2) fixture.last.respond(payload("Work").apply { icon = null })
            val held = fixture.last
            fixture.revoke(retire)
            held.respond(when (stage) { 0 -> me(true); 1 -> payload("Work"); else -> TdApi.ChatFolderIcon("Folder") })
            assertEquals(stage + 1, fixture.requests.size)
            assertNull(result.single().value)
            assertTrue(fixture.requests.none { it.command is TdApi.EditChatFolder })
        }
        val fixture = Fixture()
        val result = mutableListOf<SettingsResult<Unit>>()
        fixture.api.toggleTags(true, fixture.state, fixture::current, result::add)
        fixture.accept(fixture.state.copy(tagsEnabled = false))
        fixture.answerMe()
        assertEquals(SettingsError.NOT_READY, result.single().error)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun duplicateFailureAndLateNativeAcceptanceCompleteOnceWithoutLeakingNativeErrorText() {
        val fixture = Fixture()
        val result = mutableListOf<SettingsResult<Unit>>()
        fixture.api.toggleTags(true, fixture.state, fixture::current, result::add)
        val getMe = fixture.last
        fixture.answerMe()
        getMe.respond(me(false)); getMe.fail()
        assertEquals(2, fixture.requests.size)
        val write = fixture.last
        write.respond(TdApi.Error(500, "Sensitive native folder detail"))
        write.respond(TdApi.Ok()); write.fail()
        assertEquals(1, result.size)
        assertEquals(SettingsError.REQUEST_FAILED, result.single().error)
        assertFalse(result.toString().contains("Sensitive"))
        val late = Fixture()
        val accepted = mutableListOf<SettingsResult<Unit>>()
        late.api.remove(2, late.state, late::current, accepted::add)
        val deletion = late.last
        late.owned = false
        late.api.retire()
        deletion.respond(TdApi.Ok()); deletion.respond(TdApi.Ok()); deletion.fail()
        assertEquals(Unit, accepted.single().value, "An already sent write's real native acceptance remains accountable after retirement.")
        assertEquals(1, late.requests.size)
        val throwing = Fixture()
        throwing.throwOnSend = true
        val failed = mutableListOf<SettingsResult<FolderCapabilities>>()
        throwing.api.capabilities(throwing::current, failed::add)
        assertEquals(SettingsError.REQUEST_FAILED, failed.single().error)
    }

    @Test
    fun equivalentRecommendationPayloadsWithDifferentKeysCannotCreateTwiceWithinOneBatch() {
        val fixture = Fixture()
        val keys = fixture.load(payload(), payload()).map { it.key }
        assertEquals(2, keys.distinct().size)
        val first = mutableListOf<SettingsResult<ChatFolderInfo>>()
        fixture.api.createRecommended(keys.first(), fixture.state, fixture::current, first::add)
        fixture.answerCapabilities()
        assertIs<TdApi.CreateChatFolder>(fixture.last.command)
        fixture.last.respond(info(22, "Suggested"))
        assertEquals(22, first.single().value?.id)
        val current = fixture.state.copy(folders = listOf(ChatFolderInfo(22, "Suggested", "Folder", 2)) + fixture.state.folders)
        fixture.accept(current)
        val before = fixture.requests.size
        val second = mutableListOf<SettingsResult<ChatFolderInfo>>()
        fixture.api.createRecommended(keys.last(), current, fixture::current, second::add)
        fixture.answerCapabilities()
        assertEquals(22, assertIs<TdApi.GetChatFolder>(fixture.last.command).chatFolderId)
        fixture.last.respond(payload())
        assertEquals(SettingsError.CONFLICT, second.single().error)
        assertNull(second.single().value, "An already existing native folder is not a newly created result.")
        assertTrue(fixture.requests.drop(before).none { it.command is TdApi.CreateChatFolder })
        assertEquals(1, fixture.requests.count { it.command is TdApi.CreateChatFolder })
        assertAllowed(fixture)
    }

    @Test
    fun anExternalEquivalentFolderInvalidatesAnOldRecommendationBeforeTheFinalCreate() {
        val fixture = Fixture()
        val key = fixture.load(payload()).single().key
        val changed = fixture.state.copy(folders = fixture.state.folders + ChatFolderInfo(8, "Suggested", "Folder", 2))
        fixture.accept(changed)
        val result = mutableListOf<SettingsResult<ChatFolderInfo>>()
        fixture.api.createRecommended(key, changed, fixture::current, result::add)
        fixture.answerCapabilities()
        assertEquals(8, assertIs<TdApi.GetChatFolder>(fixture.last.command).chatFolderId)
        fixture.last.respond(payload())
        assertEquals(SettingsError.CONFLICT, result.single().error)
        assertNull(result.single().value)
        assertTrue(fixture.requests.none { it.command is TdApi.CreateChatFolder })
        assertAllowed(fixture)
    }

    @Test
    fun recommendationWriteCandidateCallbacksCannotContinueAfterLeaseRetirementOrSourceChange() {
        for (scenario in 0..3) {
            val fixture = Fixture()
            val key = fixture.load(payload()).single().key
            val changed = fixture.state.copy(folders = fixture.state.folders + listOf(
                ChatFolderInfo(8, "Suggested", "Folder", 2), ChatFolderInfo(9, "Suggested", "Folder", 2)))
            fixture.accept(changed)
            val result = mutableListOf<SettingsResult<ChatFolderInfo>>()
            fixture.api.createRecommended(key, changed, fixture::current, result::add)
            fixture.answerCapabilities()
            val held = fixture.last
            assertEquals(8, assertIs<TdApi.GetChatFolder>(held.command).chatFolderId)
            val count = fixture.requests.size
            when (scenario) {
                0 -> fixture.owned = false
                1 -> fixture.api.retire()
                2 -> fixture.accept(changed.copy(tagsEnabled = false))
                else -> fixture.accept(changed)
            }
            held.respond(payload().apply { includeBots = true })
            assertEquals(count, fixture.requests.size, "The old candidate cannot authorize either another metadata request or Create.")
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertNull(result.single().value)
            assertTrue(fixture.requests.none { it.command is TdApi.CreateChatFolder })
            assertAllowed(fixture)
        }
    }

    private data class Request(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture(val state: FolderNativeState = baseState()) {
        val requests = mutableListOf<Request>()
        val last get() = requests.last()
        var owned = true
        var throwOnSend = false
        fun current() = owned
        val api = OfficialTdFolderSettings { command, respond, fail ->
            if (throwOnSend) error("Synthetic send failure")
            requests += Request(command, respond, fail)
        }
        init { accept(state) }
        fun accept(state: FolderNativeState) { api.accept(TdApi.UpdateChatFolders(state.folders.map {
            info(it.id, it.name, it.colorId, it.iconName, it.isShareable, it.hasMyInviteLinks)
        }.toTypedArray(), state.mainPosition, state.tagsEnabled)) }
        fun revoke(retire: Boolean) { if (retire) api.retire() else owned = false }
        fun answerMe(premium: Boolean = true) { assertIs<TdApi.GetMe>(last.command); last.respond(me(premium)) }
        fun answerCapabilities(premium: Boolean = true, count: Long = 10, chosen: Long = 100) {
            answerMe(premium)
            assertEquals("chat_folder_count_max", assertIs<TdApi.GetOption>(last.command).name)
            last.respond(TdApi.OptionValueInteger(count))
            assertEquals("chat_folder_chosen_chat_count_max", assertIs<TdApi.GetOption>(last.command).name)
            last.respond(TdApi.OptionValueInteger(chosen))
        }
        fun load(vararg folders: TdApi.ChatFolder): List<FolderRecommendation> {
            val result = mutableListOf<SettingsResult<List<FolderRecommendation>>>()
            api.recommendations(::current, result::add)
            assertIs<TdApi.GetRecommendedChatFolders>(last.command)
            last.respond(TdApi.RecommendedChatFolders(folders.map { TdApi.RecommendedChatFolder(it, "Description") }.toTypedArray()))
            return assertNotNull(result.single().value)
        }
    }
    private fun assertMetadataOnly(fixture: Fixture) = assertTrue(fixture.requests.all {
        it.command is TdApi.GetMe || it.command is TdApi.GetOption || it.command is TdApi.GetRecommendedChatFolders || it.command is TdApi.GetChatFolder
    }, "Folder discovery must remain metadata-only.")
    private fun assertAllowed(fixture: Fixture) = assertTrue(fixture.requests.all {
        it.command is TdApi.GetMe || it.command is TdApi.GetOption || it.command is TdApi.GetRecommendedChatFolders ||
            it.command is TdApi.GetChatFolder || it.command is TdApi.GetChatFolderDefaultIconName || it.command is TdApi.CreateChatFolder ||
            it.command is TdApi.EditChatFolder || it.command is TdApi.DeleteChatFolder || it.command is TdApi.ReorderChatFolders || it.command is TdApi.ToggleChatFolderTags
    }, "No timeline, membership, file, leave-chat or invite-link operations belong to these settings requests.")

    companion object {
        private fun baseState() = FolderNativeState(listOf(ChatFolderInfo(2, "Work", "Folder", 2), ChatFolderInfo(3, "Social", "Folder", 2)), tagsEnabled = true)
        private fun me(premium: Boolean) = TdApi.User().apply { id = 71; isPremium = premium; type = TdApi.UserTypeRegular() }
        private fun info(id: Int, name: String = "Work", color: Int = 2, icon: String? = "Folder", shared: Boolean = false, links: Boolean = false) =
            TdApi.ChatFolderInfo(id, TdApi.ChatFolderName(TdApi.FormattedText(name, emptyArray()), false), icon?.let { TdApi.ChatFolderIcon(it) }, color, shared, links)
        private fun payload(name: String = "Suggested") = TdApi.ChatFolder(
            TdApi.ChatFolderName(TdApi.FormattedText(name, emptyArray()), false), TdApi.ChatFolderIcon("Folder"), 2, false,
            longArrayOf(101), longArrayOf(-201), longArrayOf(301), true, false, true, true, false, false, false, true)
        private fun richPayload(name: String) = payload(name).apply {
            this.name = TdApi.ChatFolderName(TdApi.FormattedText(name, arrayOf(TdApi.TextEntity(0, 2, TdApi.TextEntityTypeCustomEmoji(98_765)))), true)
            pinnedChatIds = longArrayOf(101, -2_000_000_000_101)
            includedChatIds = longArrayOf(-201, 202)
            excludedChatIds = longArrayOf(301, -302)
            includeGroups = true
        }
        private fun flags(value: TdApi.ChatFolder) = listOf(value.isShareable, value.excludeMuted, value.excludeRead,
            value.excludeArchived, value.includeContacts, value.includeNonContacts, value.includeBots, value.includeGroups, value.includeChannels)
    }
}
