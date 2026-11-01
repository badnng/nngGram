package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class SettingsPrivacyControllerTest {
    @Test
    fun cancellingTheActualNativeVoiceOpeningAfterGetMeStopsBothFollowingOptionsAndDoesNotCancelANewRequest() {
        data class NativeRequest(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit)
        val requests = mutableListOf<NativeRequest>()
        val session = OfficialTdSettingsSession { command, receive, _ -> requests += NativeRequest(command, receive) }
        val queue = ArrayDeque<() -> Unit>()
        var snapshot = SettingsSnapshot()
        val controller = SettingsController(9, session, { queue.addLast(it) }, { snapshot = it })
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        controller.start()
        controller.load(snapshot.token, SettingsSection.PRIVACY)
        requests.toList().forEach { request ->
            when (request.command) {
                is TdApi.GetUserPrivacySettingRules -> request.respond(TdApi.UserPrivacySettingRules(
                    arrayOf(TdApi.UserPrivacySettingRuleAllowContacts())))
                is TdApi.GetAccountTtl -> request.respond(TdApi.AccountTtl(365))
                else -> fail("Unexpected synthetic privacy request")
            }
        }
        flush()
        val oldResults = mutableListOf<SettingsResult<PrivacyEditorToken>>()
        controller.openPrivacyEditor(snapshot.token, PrivacyKey.VOICE_VIDEO_MESSAGES, 71, oldResults::add)
        val oldGetMe = requests.last()
        assertIs<TdApi.GetMe>(oldGetMe.command)
        val before = requests.size
        controller.cancelPrivacyEditorOpening(snapshot.token, 71)
        oldGetMe.respond(TdApi.User().apply { id = 43; isPremium = true })
        flush()
        assertEquals(before, requests.size, "A cancelled GetMe must never start either option lookup.")
        assertTrue(oldResults.isEmpty())

        val newResults = mutableListOf<SettingsResult<PrivacyEditorToken>>()
        controller.openPrivacyEditor(snapshot.token, PrivacyKey.VOICE_VIDEO_MESSAGES, 72, newResults::add)
        controller.cancelPrivacyEditorOpening(snapshot.token, 71)
        assertIs<TdApi.GetMe>(requests.last().command)
        requests.last().respond(TdApi.User().apply { id = 43; isPremium = true })
        assertEquals("replies_bot_chat_id", assertIs<TdApi.GetOption>(requests.last().command).name)
        requests.last().respond(TdApi.OptionValueInteger(47))
        assertEquals("verification_codes_bot_chat_id", assertIs<TdApi.GetOption>(requests.last().command).name)
        requests.last().respond(TdApi.OptionValueInteger(48))
        flush()
        assertEquals(PrivacyKey.VOICE_VIDEO_MESSAGES, assertNotNull(newResults.single().value).key)
        assertTrue(oldResults.isEmpty())
    }

    @Test
    fun aPendingEligibilityCheckCanBeCancelledWithoutACompletedTokenAndOldCancellationCannotCloseItsReplacement() {
        val f = Fixture()
        f.session.delayOpening = true
        val oldResults = mutableListOf<SettingsResult<PrivacyEditorToken>>()
        f.controller.openPrivacyEditor(f.state.token, PrivacyKey.VOICE_VIDEO_MESSAGES, 31, oldResults::add)
        val first = f.session.openings.single()
        assertTrue(first.current())
        f.controller.cancelPrivacyEditorOpening(f.state.token, 31)
        assertFalse(first.current())
        first.result(SettingsResult(Unit))
        f.flush()
        assertTrue(oldResults.isEmpty())

        val newResults = mutableListOf<SettingsResult<PrivacyEditorToken>>()
        f.controller.openPrivacyEditor(f.state.token, PrivacyKey.VOICE_VIDEO_MESSAGES, 32, newResults::add)
        val replacement = f.session.openings.last()
        f.controller.cancelPrivacyEditorOpening(f.state.token, 31)
        assertTrue(replacement.current())
        first.result(SettingsResult(Unit))
        f.flush()
        assertTrue(newResults.isEmpty())
        replacement.result(SettingsResult(Unit))
        f.flush()
        val token = assertNotNull(newResults.single().value)
        f.controller.cancelPrivacyEditorOpening(f.state.token, 31)
        assertTrue(replacement.current())
        f.controller.privacyPeers(f.state.token, token, query(1), {})
        assertEquals(1, f.session.searches.size)
        f.controller.cancelPrivacyEditorOpening(f.state.token, 32)
        assertFalse(replacement.current())
        assertFalse(f.session.searches.single().current())
        assertTrue(oldResults.isEmpty())
    }

    @Test
    fun aNewOwnerQueryAndParentRevisionRevokeAllOlderSearchCallbacks() {
        val f = Fixture()
        val first = f.open()
        val delivered = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        f.controller.privacyPeers(f.state.token, first, query(1), delivered::add)
        val old = f.session.searches.single()
        assertTrue(old.current())
        f.controller.privacyPeers(f.state.token, first, query(2).copy(parentRevision = 3), delivered::add)
        val latest = f.session.searches.last()
        assertFalse(old.current())
        old.result(SettingsResult(SettingsPrivacyPeers(listOf(SettingsPrivacyPeer(PrivacyPeerId(51, false), "Old private title")), false)))
        f.flush()
        assertTrue(delivered.isEmpty())
        val rejected = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        f.controller.privacyPeers(f.state.token, first, query(3).copy(parentRevision = 2), rejected::add)
        assertEquals(SettingsError.NOT_READY, rejected.single().error)
        assertEquals(2, f.session.searches.size)
        latest.result(SettingsResult(SettingsPrivacyPeers(emptyList(), false)))
        latest.result(SettingsResult(error = SettingsError.FORBIDDEN))
        f.flush()
        assertEquals(1, delivered.size)
        val second = f.open()
        assertNotEquals(first, second)
        assertFalse(latest.current())
        f.controller.privacyPeers(f.state.token, first, query(99), rejected::add)
        assertEquals(2, f.session.searches.size)
        assertEquals(SettingsError.NOT_READY, rejected.last().error)
    }

    @Test
    fun closingOrRetiringAnEditorDisablesNativeChainsAndLatePublication() {
        for (retire in listOf(false, true)) {
            val f = Fixture()
            val owner = f.open()
            val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
            f.controller.privacyPeers(f.state.token, owner, query(1), results::add)
            val pending = f.session.searches.single()
            if (retire) f.controller.deactivate() else f.controller.closePrivacyEditor(f.state.token, owner)
            assertFalse(pending.current())
            pending.result(SettingsResult(SettingsPrivacyPeers(emptyList(), false)))
            f.flush()
            assertTrue(results.isEmpty())
            assertTrue(f.session.writes.isEmpty())
        }
    }

    @Test
    fun aChildSearchNeverWritesAndOnlyACurrentParentSaveCanSubmitItsOriginalDraft() {
        val f = Fixture()
        val owner = f.open()
        val draft = assertNotNull(PrivacyDraft.from(f.source)).copy(base = PrivacyBase.NOBODY, revision = 1)
        f.controller.privacyPeers(f.state.token, owner, query(1), {})
        assertTrue(f.session.writes.isEmpty())
        f.controller.savePrivacyDraft(f.state.token.copy(generation = 99), owner, draft)
        f.controller.savePrivacyDraft(f.state.token, owner.copy(owner = owner.owner + 1), draft)
        assertTrue(f.session.writes.isEmpty())
        f.controller.savePrivacyDraft(f.state.token, owner, draft)
        val write = f.session.writes.single()
        assertSame(draft, write.draft)
        assertTrue(write.current())
        assertFalse(f.session.searches.single().current())
        write.result(SettingsResult(error = SettingsError.CONFLICT))
        f.flush()
        assertEquals(SettingsError.CONFLICT, f.state.errors[SettingsSection.PRIVACY])
        assertEquals(f.source, f.state.privacy[PrivacyKey.LAST_SEEN])
        assertSame(draft.source, f.source)
        assertTrue(f.state.completedSaveRevision.isEmpty())
        assertTrue(f.state.failedSaveRevision.containsKey(SettingsSection.PRIVACY))
    }

    private class Fixture {
        val source = PrivacyRules(PrivacyBase.CONTACTS, listOf(PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS)))
        val session = FakeSession(source)
        val queue = ArrayDeque<() -> Unit>()
        var state = SettingsSnapshot()
        val controller = SettingsController(7, session, { queue.addLast(it) }, { state = it })
        init { controller.start(); controller.load(state.token, SettingsSection.PRIVACY); flush() }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun open(): PrivacyEditorToken {
            val results = mutableListOf<SettingsResult<PrivacyEditorToken>>()
            controller.openPrivacyEditor(state.token, PrivacyKey.LAST_SEEN, 100L + state.token.revision, results::add)
            flush()
            return assertNotNull(results.single().value)
        }
    }
    private data class Search(val current: () -> Boolean, val result: (SettingsResult<SettingsPrivacyPeers>) -> Unit)
    private data class Write(val draft: PrivacyDraft, val current: () -> Boolean, val result: (SettingsResult<Unit>) -> Unit)
    private data class Opening(val current: () -> Boolean, val result: (SettingsResult<Unit>) -> Unit)
    private class FakeSession(val source: PrivacyRules) : TdSettingsSession {
        val searches = mutableListOf<Search>()
        val writes = mutableListOf<Write>()
        var delayOpening = false
        val openings = mutableListOf<Opening>()
        override fun privacyEditorAllowed(key: PrivacyKey, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            if (delayOpening) openings += Opening(isCurrent, result) else result(SettingsResult(Unit))
        }
        override fun privacyPeers(query: PrivacyPeerQuery, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPrivacyPeers>) -> Unit) {
            searches += Search(isCurrent, result)
        }
        override fun savePrivacyDraft(key: PrivacyKey, draft: PrivacyDraft, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            writes += Write(draft, isCurrent, result)
        }
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) {
            result(SettingsResult(SettingsPrivacyData(mapOf(PrivacyKey.LAST_SEEN to source, PrivacyKey.VOICE_VIDEO_MESSAGES to source),
                emptyMap(), 365, emptyList(), emptyList())))
        }
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) = unsupported(result)
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    private fun query(revision: Long) = PrivacyPeerQuery(PrivacyExceptionTarget.ALLOW, 1, revision, PrivacyPeerSource.CONTACTS)
}
