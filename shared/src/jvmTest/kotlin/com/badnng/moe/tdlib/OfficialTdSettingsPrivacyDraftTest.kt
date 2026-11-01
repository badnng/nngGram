package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsPrivacyDraftTest {
    @Test
    fun closingBetweenCallbackValidationAndTheActualNativeSendStopsEveryGetAndSetBoundary() {
        for (stage in 0..2) {
            val original = source()
            val fixture = Fixture()
            fixture.load(original)
            val results = mutableListOf<SettingsResult<Unit>>()
            var intercept = stage == 0
            var remainingChecks = 1
            val current = { if (!intercept) true else remainingChecks-- > 0 }
            val beforeStart = fixture.requests.size
            fixture.api.saveDraft(PrivacyKey.LAST_SEEN,
                assertNotNull(PrivacyDraft.from(original)).copy(base = PrivacyBase.NOBODY), current, results::add)
            if (stage == 0) {
                // Initial source validation was current, but ownership ended before the first Get send.
                assertEquals(beforeStart, fixture.requests.size)
            } else {
                fixture.fresh(original)
                if (stage == 1) {
                    fixture.requests.last().respond(user(43))
                    fixture.requests.last().respond(TdApi.OptionValueInteger(47))
                    val beforeFinalGet = fixture.requests.size
                    intercept = true
                    fixture.requests.last().respond(TdApi.OptionValueInteger(48))
                    // The validation callback passed; the final Get's own send gate must still reject.
                    assertEquals(beforeFinalGet, fixture.requests.size)
                } else {
                    fixture.context()
                    val beforeSet = fixture.requests.size
                    intercept = true
                    fixture.fresh(original)
                    // Final-read callback passed; the Set's own send gate must still reject.
                    assertEquals(beforeSet, fixture.requests.size)
                }
            }
            assertNull(results.single().value)
            assertNoWrites(fixture)
        }
    }

    @Test
    fun splitNativeExceptionKindsRemainExactlyOrderedAndReadOnlyBeforeAnyWrite() {
        val source = source(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS), PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(12)))
        val mapped = mapPrivacyRules(native(source))
        assertFalse(mapped.editable)
        assertEquals(source.rules, mapped.rules)
        val fixture = Fixture()
        fixture.load(mapped)
        val before = fixture.requests.size
        val results = mutableListOf<SettingsResult<Unit>>()
        val forged = assertNotNull(PrivacyDraft.from(source)).withSelection(PrivacyExceptionTarget.ALLOW,
            listOf(PrivacyPeerId(11, false), PrivacyPeerId(12, false), PrivacyPeerId(13, false)))
        fixture.api.saveDraft(PrivacyKey.LAST_SEEN, forged, { true }, results::add)
        assertEquals(SettingsError.INVALID_VALUE, results.single().error)
        fixture.api.save(PrivacyKey.LAST_SEEN, PrivacyBase.NOBODY, results::add)
        assertEquals(SettingsError.NOT_READY, results.last().error)
        assertEquals(before, fixture.requests.size)
        assertNoWrites(fixture)
    }

    @Test
    fun anExplicitSaveRequiresTheLoadedSourceAndCurrentEditorBeforeAnyNativeRequest() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        val draft = assertNotNull(PrivacyDraft.from(source())).copy(base = PrivacyBase.NOBODY)
        fixture.api.saveDraft(PrivacyKey.LAST_SEEN, draft, { true }, results::add)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertTrue(fixture.requests.isEmpty())
        fixture.load(draft.source)
        val before = fixture.requests.size
        fixture.api.saveDraft(PrivacyKey.LAST_SEEN, draft, { false }, results::add)
        fixture.api.saveDraft(PrivacyKey.LAST_SEEN, draft.copy(source = source(PrivacyRule(PrivacyRuleKind.ALLOW_BOTS))),
            { true }, results::add)
        assertEquals(before, fixture.requests.size)
        assertEquals(List(3) { SettingsError.NOT_READY }, results.map { it.error })
    }

    @Test
    fun invalidBoundsAndUnsupportedPremiumNeverReachNativeRefresh() {
        val fixture = Fixture()
        val original = source()
        fixture.load(original)
        val draft = assertNotNull(PrivacyDraft.from(original))
        val before = fixture.requests.size
        val results = mutableListOf<SettingsResult<Unit>>()
        for (invalid in listOf(draft.copy(allowUsers = (1L..1001L).toList()),
            draft.copy(allowChats = (-21L..-1L).toList()), draft.copy(allowPremium = true))) {
            fixture.api.saveDraft(PrivacyKey.LAST_SEEN, invalid, { true }, results::add)
        }
        assertEquals(before, fixture.requests.size)
        assertEquals(List(3) { SettingsError.INVALID_VALUE }, results.map { it.error })
        assertNoWrites(fixture)
    }

    @Test
    fun theFirstFreshReadComparesIdsSpecialRulesAndTheirExactOrderBeforeIdentityResolution() {
        val original = source(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11, 12)),
            PrivacyRule(PrivacyRuleKind.ALLOW_BOTS), PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS))
        val changedSources = listOf(
            original.copy(rules = original.rules.map { if (it.kind == PrivacyRuleKind.ALLOW_USERS) it.copy(ids = listOf(12, 11)) else it }),
            original.copy(rules = original.rules.filterNot { it.kind == PrivacyRuleKind.RESTRICT_BOTS }),
            original.copy(rules = listOf(original.rules[1], original.rules[0]) + original.rules.drop(2)),
        )
        for (changed in changedSources) {
            val fixture = Fixture()
            fixture.load(original)
            val results = mutableListOf<SettingsResult<Unit>>()
            fixture.api.saveDraft(PrivacyKey.LAST_SEEN, assertNotNull(PrivacyDraft.from(original)).copy(base = PrivacyBase.NOBODY),
                { true }, results::add)
            val before = fixture.requests.size
            fixture.fresh(changed)
            assertEquals(before, fixture.requests.size)
            assertEquals(SettingsError.CONFLICT, results.single().error)
            assertTrue(fixture.requests.none { it.command is TdApi.GetMe })
            assertNoWrites(fixture)
        }
    }

    @Test
    fun onlyNewTypedIdentitiesAreResolvedAndFinalSavePreservesUntouchedNativeRules() {
        val original = source(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11)),
            PrivacyRule(PrivacyRuleKind.ALLOW_BOTS), PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(21)))
        val fixture = Fixture()
        fixture.load(original)
        val draft = assertNotNull(PrivacyDraft.from(original)).withSelection(PrivacyExceptionTarget.ALLOW,
            listOf(PrivacyPeerId(11, false), PrivacyPeerId(51, false), PrivacyPeerId(-30, true)))
        val results = mutableListOf<SettingsResult<Unit>>()
        fixture.api.saveDraft(PrivacyKey.LAST_SEEN, draft, { fixture.current }, results::add)
        fixture.fresh(original)
        fixture.context()
        assertEquals(51L, assertIs<TdApi.GetUser>(fixture.requests.last().command).userId)
        fixture.requests.last().respond(user(51))
        assertEquals(-30L, assertIs<TdApi.GetChat>(fixture.requests.last().command).chatId)
        fixture.requests.last().respond(TdApi.Chat().apply { id = -30; type = TdApi.ChatTypeSupergroup().apply { supergroupId = 300 } })
        assertEquals(300L, assertIs<TdApi.GetSupergroup>(fixture.requests.last().command).supergroupId)
        fixture.requests.last().respond(TdApi.Supergroup().apply { id = 300 })
        fixture.fresh(original)
        val pending = fixture.requests.last()
        val write = assertIs<TdApi.SetUserPrivacySettingRules>(pending.command)
        assertIs<TdApi.UserPrivacySettingShowStatus>(write.setting)
        assertEquals(assertNotNull(draft.replacement(PrivacyKey.LAST_SEEN)), mapPrivacyRules(write.rules))
        assertTrue(fixture.requests.none { it.command is TdApi.GetUser && it.command.userId == 11L })
        assertTrue(fixture.requests.none { it.command is TdApi.GetUser && it.command.userId == 21L })
        assertEquals(2, fixture.requests.count { it.command is TdApi.GetUserPrivacySettingRules } - PrivacyKey.entries.size)
        assertTrue(results.isEmpty())
        pending.respond(TdApi.Ok())
        pending.respond(TdApi.Error(403, "duplicate private settings"))
        pending.fail()
        assertEquals(Unit, results.single().value)
        assertEquals(original, draft.source)
    }

    @Test
    fun aChangedSourceAfterIdentityResolutionStillStopsTheNativeWrite() {
        val original = source()
        val fixture = Fixture()
        fixture.load(original)
        val results = mutableListOf<SettingsResult<Unit>>()
        val draft = assertNotNull(PrivacyDraft.from(original)).withSelection(PrivacyExceptionTarget.ALLOW, listOf(PrivacyPeerId(51, false)))
        fixture.api.saveDraft(PrivacyKey.LAST_SEEN, draft, { true }, results::add)
        fixture.fresh(original)
        fixture.context()
        fixture.requests.last().respond(user(51))
        fixture.fresh(source(PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(99))))
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertNoWrites(fixture)
        assertEquals(listOf(51L), draft.allowUsers)
        assertEquals(original, draft.source)
    }

    @Test
    fun wrongUserIdentityDeletedUsersPrivateChatSubstitutionAndChannelsCannotAuthorizeAWrite() {
        for (kind in 0..3) {
            val original = source()
            val fixture = Fixture()
            fixture.load(original)
            val identity = PrivacyPeerId(if (kind < 2) 51 else -30, kind >= 2)
            val draft = assertNotNull(PrivacyDraft.from(original)).withSelection(PrivacyExceptionTarget.ALLOW, listOf(identity))
            val results = mutableListOf<SettingsResult<Unit>>()
            fixture.api.saveDraft(PrivacyKey.LAST_SEEN, draft, { true }, results::add)
            fixture.fresh(original)
            fixture.context()
            when (kind) {
                0 -> fixture.requests.last().respond(user(999))
                1 -> fixture.requests.last().respond(user(51).apply { type = TdApi.UserTypeDeleted() })
                2 -> {
                    fixture.requests.last().respond(TdApi.Chat().apply { id = -30; type = TdApi.ChatTypePrivate().apply { userId = 51 } })
                    fixture.requests.last().respond(user(51))
                }
                else -> fixture.requests.last().respond(TdApi.Chat().apply { id = -30;
                    type = TdApi.ChatTypeSupergroup().apply { supergroupId = 300; isChannel = true } })
            }
            assertEquals(SettingsError.INVALID_VALUE, results.single().error)
            assertEquals(1, fixture.requests.count { it.command is TdApi.GetUserPrivacySettingRules } - PrivacyKey.entries.size)
            assertNoWrites(fixture)
        }
    }

    @Test
    fun nativeFailureLeavesTheDraftAndExpectedSourceAvailableForAnExplicitRetry() {
        val original = source(PrivacyRule(PrivacyRuleKind.ALLOW_BOTS))
        val fixture = Fixture()
        fixture.load(original)
        val draft = assertNotNull(PrivacyDraft.from(original)).copy(base = PrivacyBase.NOBODY)
        val results = mutableListOf<SettingsResult<Unit>>()
        fixture.api.saveDraft(PrivacyKey.LAST_SEEN, draft, { true }, results::add)
        fixture.readyWithoutNewIdentities(original)
        val failed = fixture.requests.last()
        assertIs<TdApi.SetUserPrivacySettingRules>(failed.command)
        failed.respond(TdApi.Error(403, "private exception details"))
        failed.respond(TdApi.Ok())
        failed.fail()
        assertEquals(SettingsError.FORBIDDEN, results.single().error)
        assertFalse(results.toString().contains("private exception"))
        assertEquals(original, draft.source)
        assertEquals(PrivacyBase.NOBODY, draft.base)

        fixture.api.saveDraft(PrivacyKey.LAST_SEEN, draft, { true }, results::add)
        fixture.readyWithoutNewIdentities(original)
        assertIs<TdApi.SetUserPrivacySettingRules>(fixture.requests.last().command)
        fixture.requests.last().respond(TdApi.Ok())
        assertEquals(2, results.size)
        assertEquals(Unit, results.last().value)
    }

    @Test
    fun retiringTheEditorDuringEitherFreshReadOrContextCannotReachTheNextNativeStep() {
        for (stage in 0..2) {
            val original = source()
            val fixture = Fixture()
            fixture.load(original)
            val results = mutableListOf<SettingsResult<Unit>>()
            fixture.api.saveDraft(PrivacyKey.LAST_SEEN, assertNotNull(PrivacyDraft.from(original)).copy(base = PrivacyBase.NOBODY),
                { fixture.current }, results::add)
            if (stage >= 1) fixture.fresh(original)
            if (stage >= 2) fixture.context()
            val pending = fixture.requests.last()
            val before = fixture.requests.size
            fixture.current = false
            if (stage == 1) pending.respond(user(43)) else pending.respond(native(original))
            pending.fail()
            assertEquals(before, fixture.requests.size)
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertNoWrites(fixture)
        }
    }

    @Test
    fun voiceMessagePrivacyRechecksTheActualPremiumFlagEvenWhenNoNewPeerWasSelected() {
        for (premium in listOf(false, true)) {
            val original = source()
            val fixture = Fixture()
            fixture.load(original)
            val results = mutableListOf<SettingsResult<Unit>>()
            fixture.api.saveDraft(PrivacyKey.VOICE_VIDEO_MESSAGES,
                assertNotNull(PrivacyDraft.from(original)).copy(base = PrivacyBase.NOBODY), { true }, results::add)
            fixture.fresh(original)
            fixture.context(premium)
            if (!premium) {
                assertEquals(SettingsError.FORBIDDEN, results.single().error)
                assertNoWrites(fixture)
            } else {
                fixture.fresh(original)
                assertIs<TdApi.UserPrivacySettingAllowPrivateVoiceAndVideoNoteMessages>(
                    assertIs<TdApi.SetUserPrivacySettingRules>(fixture.requests.last().command).setting)
                fixture.requests.last().respond(TdApi.Ok())
                assertEquals(Unit, results.single().value)
            }
        }
    }

    private data class Request(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture {
        val requests = mutableListOf<Request>()
        var current = true
        val api = OfficialTdSettingsPrivacy { command, respond, fail -> requests += Request(command, respond, fail) }
        fun load(source: PrivacyRules) {
            val results = mutableListOf<SettingsResult<Pair<Map<PrivacyKey, PrivacyRules>, Map<PrivacyKey, SettingsError>>>>()
            val before = requests.size
            api.load(results::add)
            val loads = requests.drop(before)
            assertEquals(PrivacyKey.entries.size, loads.size)
            loads.forEach { assertIs<TdApi.GetUserPrivacySettingRules>(it.command); it.respond(native(source)) }
            assertEquals(PrivacyKey.entries.toSet(), assertNotNull(results.single().value).first.keys)
        }
        fun fresh(source: PrivacyRules) {
            val pending = requests.last()
            assertIs<TdApi.GetUserPrivacySettingRules>(pending.command)
            pending.respond(native(source))
            pending.respond(TdApi.Error(403, "duplicate private source"))
            pending.fail()
        }
        fun context(premium: Boolean = false) {
            assertIs<TdApi.GetMe>(requests.last().command)
            requests.last().respond(user(43).apply { isPremium = premium })
            assertEquals("replies_bot_chat_id", assertIs<TdApi.GetOption>(requests.last().command).name)
            requests.last().respond(TdApi.OptionValueInteger(47))
            assertEquals("verification_codes_bot_chat_id", assertIs<TdApi.GetOption>(requests.last().command).name)
            requests.last().respond(TdApi.OptionValueInteger(48))
        }
        fun readyWithoutNewIdentities(source: PrivacyRules) {
            fresh(source)
            context()
            fresh(source)
        }
    }
    private companion object {
        fun source(vararg prefix: PrivacyRule) = PrivacyRules(PrivacyBase.CONTACTS,
            prefix.toList() + PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS) + PrivacyRule(PrivacyRuleKind.RESTRICT_ALL))
        fun native(source: PrivacyRules) = TdApi.UserPrivacySettingRules(source.rules.map(::nativePrivacyRule).toTypedArray())
        fun user(id: Long) = TdApi.User().apply { this.id = id; type = TdApi.UserTypeRegular(); firstName = "Synthetic user" }
        fun assertNoWrites(fixture: Fixture) = assertTrue(fixture.requests.none { it.command is TdApi.SetUserPrivacySettingRules })
    }
}
