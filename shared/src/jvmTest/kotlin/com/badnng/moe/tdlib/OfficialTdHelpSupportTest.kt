package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Synthetic native requests only: no JNI, account login, message send, history or OS actions. */
class OfficialTdHelpSupportTest {
    @Test fun resolvesTheActualSupportIdentityWithoutOpeningOrInventingListPositions() {
        val f = Fixture()
        val result = mutableListOf<ChatResult<TdSupportChat>>()
        f.api.resolve(f::active, result::add)
        assertIs<TdApi.GetSupportUser>(f.last.command)
        f.last.reply(user())
        val create = assertIs<TdApi.CreatePrivateChat>(f.last.command)
        assertEquals(71L, create.userId)
        assertFalse(create.force)
        f.last.reply(chat())
        assertEquals(810L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        val native = chat().apply { title = "actual returned title"; unreadCount = 3; hasProtectedContent = true }
        f.last.reply(native)
        val target = assertNotNull(result.single().value)
        assertEquals(71L, target.userId)
        assertEquals(810L, target.chat.id)
        assertEquals(71L, target.chat.privateUserId)
        assertEquals(native.title, target.chat.title)
        assertEquals(3, target.chat.unreadCount)
        assertTrue(target.chat.hasProtectedContent)
        assertTrue(target.chat.positions.isEmpty())
        assertEquals(0L, target.chat.order)
        assertEquals(listOf("GetSupportUser", "CreatePrivateChat", "GetChat"), f.names())
        assertEquals("TdSupportChat(redacted)", target.toString())
    }

    @Test fun freshChatMappingPreservesOnlyTheNativeListPositions() {
        val f = Fixture()
        val result = mutableListOf<ChatResult<TdSupportChat>>()
        f.api.resolve(f::active, result::add)
        f.last.reply(user()); f.last.reply(chat())
        f.last.reply(chat().apply {
            positions = arrayOf(TdApi.ChatPosition().apply { list = TdApi.ChatListArchive(); order = 91; isPinned = true })
        })
        val mapped = assertNotNull(result.single().value).chat
        assertEquals(mapOf(ChatListRef(-1) to 91L), mapped.positions)
        assertEquals(setOf(ChatListRef(-1)), mapped.pinnedLists)
        assertEquals(0L, mapped.order)
    }

    @Test fun rejectsMissingOrUntrustedSupportUsersBeforeCreatingAChat() {
        val invalid = listOf<TdApi.Object>(TdApi.Ok(), user(0), user(-1), user().apply { isSupport = false })
        for (value in invalid) {
            val f = Fixture(); val results = mutableListOf<ChatResult<TdSupportChat>>()
            f.api.resolve(f::active, results::add); f.last.reply(value)
            assertNotNull(results.single().error)
            assertEquals(listOf("GetSupportUser"), f.names())
        }
    }

    @Test fun rejectsCreatedChatWithWrongTypeIdentityOrZeroId() {
        val invalid = listOf<TdApi.Object>(TdApi.Ok(), chat(0), chat(userId = 72),
            chat().apply { type = TdApi.ChatTypeBasicGroup(71) }, chat().apply { type = null })
        for (value in invalid) {
            val f = Fixture(); val results = mutableListOf<ChatResult<TdSupportChat>>()
            f.api.resolve(f::active, results::add); f.last.reply(user()); f.last.reply(value)
            assertNotNull(results.single().error)
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun resolvedChatMustMatchBothCreatedIdAndSupportPrivateUserId() {
        for (value in invalidFreshChats()) {
            val f = Fixture(); val results = mutableListOf<ChatResult<TdSupportChat>>()
            f.api.resolve(f::active, results::add); f.last.reply(user()); f.last.reply(chat()); f.last.reply(value)
            assertNotNull(results.single().error)
            assertEquals(3, f.requests.size)
            assertFalse(f.requests.any { it.command is TdApi.OpenChat })
        }
    }

    @Test fun everyResolveStepReportsNativeErrorsWithoutLeakingNativeText() {
        for (step in 0..2) {
            val f = Fixture(); val results = mutableListOf<ChatResult<TdSupportChat>>()
            f.api.resolve(f::active, results::add)
            if (step >= 1) f.last.reply(user())
            if (step >= 2) f.last.reply(chat())
            f.last.reply(TdApi.Error(500, "private native detail"))
            val result = results.single()
            assertNull(result.value); assertNotNull(result.error); assertEquals(500, result.code)
            assertFalse(result.toString().contains("private native detail"))
            assertEquals(step + 1, f.requests.size)
        }
    }

    @Test fun opensOnlyAfterFreshIdentityValidationAndActualOpenOk() {
        val f = Fixture(); val results = mutableListOf<ChatResult<Unit>>()
        f.api.open(target(), f::active, results::add)
        assertEquals(810L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        assertTrue(results.isEmpty())
        f.last.reply(chat())
        assertEquals(810L, assertIs<TdApi.OpenChat>(f.last.command).chatId)
        assertTrue(results.isEmpty())
        f.last.reply(TdApi.Ok())
        assertEquals(Unit, results.single().value)
        assertEquals(listOf("GetChat", "OpenChat"), f.names())
    }

    @Test fun invalidTargetsAreRejectedWithoutNativeRequests() {
        val original = target()
        val invalid = listOf(original.copy(userId = 0), original.copy(chat = original.chat.copy(id = 0)),
            original.copy(userId = 72), original.copy(chat = original.chat.copy(basicGroupId = 4)),
            original.copy(chat = original.chat.copy(supergroupId = 4)), original.copy(chat = original.chat.copy(isChannel = true)))
        for (value in invalid) {
            val f = Fixture(); val results = mutableListOf<ChatResult<Unit>>()
            f.api.open(value, f::active, results::add)
            assertNotNull(results.single().error); assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun freshOpenChatRejectsWrongIdsAndNonPrivateTypesWithoutOpen() {
        for (value in invalidFreshChats()) {
            val f = Fixture(); val results = mutableListOf<ChatResult<Unit>>()
            f.api.open(target(), f::active, results::add); f.last.reply(value)
            assertNotNull(results.single().error); assertEquals(listOf("GetChat"), f.names())
        }
    }

    @Test fun freshGetAndOpenErrorsAreFailuresAndNeverBecomeFakeAcceptance() {
        for (atOpen in listOf(false, true)) for (native in listOf<TdApi.Object>(TdApi.Error(403, "private"), TdApi.User())) {
            val f = Fixture(); val results = mutableListOf<ChatResult<Unit>>()
            f.api.open(target(), f::active, results::add)
            if (atOpen) f.last.reply(chat())
            f.last.reply(native)
            assertNull(results.single().value); assertNotNull(results.single().error)
            assertEquals(if (atOpen) 2 else 1, f.requests.size)
        }
    }

    @Test fun inactiveOrRetiredOwnersStopEveryResolveContinuationAndCompleteOnce() {
        for (retire in listOf(false, true)) for (step in 0..2) {
            val f = Fixture(); val results = mutableListOf<ChatResult<TdSupportChat>>()
            f.api.resolve(f::active, results::add)
            if (step >= 1) f.last.reply(user())
            if (step >= 2) f.last.reply(chat())
            f.revoke(retire)
            f.last.reply(if (step == 0) user() else chat())
            assertEquals(ChatError.NOT_READY, results.single().error)
            assertEquals(step + 1, f.requests.size)
        }
    }

    @Test fun inactiveBeforeStartAndBeforeFreshOpenCompletionNeverOpens() {
        for (retire in listOf(false, true)) {
            val f = Fixture(); f.revoke(retire)
            val resolve = mutableListOf<ChatResult<TdSupportChat>>(); val open = mutableListOf<ChatResult<Unit>>()
            f.api.resolve(f::active, resolve::add); f.api.open(target(), f::active, open::add)
            assertEquals(ChatError.NOT_READY, resolve.single().error)
            assertEquals(ChatError.NOT_READY, open.single().error); assertTrue(f.requests.isEmpty())
            val pending = Fixture(); val pendingResult = mutableListOf<ChatResult<Unit>>()
            pending.api.open(target(), pending::active, pendingResult::add)
            pending.revoke(retire); pending.last.reply(chat())
            assertEquals(ChatError.NOT_READY, pendingResult.single().error)
            assertEquals(listOf("GetChat"), pending.names())
        }
    }

    @Test fun actualLateOpenOkRemainsObservableForCleanupButLateErrorDoesNot() {
        for (retire in listOf(false, true)) for (accepted in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<ChatResult<Unit>>()
            f.api.open(target(), f::active, results::add); f.last.reply(chat())
            f.revoke(retire)
            f.last.reply(if (accepted) TdApi.Ok() else TdApi.Error(500, "private"))
            if (accepted) assertEquals(Unit, results.single().value)
            else { assertNull(results.single().value); assertEquals(ChatError.NOT_READY, results.single().error) }
        }
    }

    @Test fun duplicateCallbacksCannotRepeatResolveContinuationsOrOpenAcceptance() {
        val f = Fixture(); val resolved = mutableListOf<ChatResult<TdSupportChat>>()
        f.api.resolve(f::active, resolved::add)
        val support = f.last; support.reply(user()); support.reply(user()); support.fail()
        val create = f.last; create.reply(chat()); create.reply(chat()); create.fail()
        val get = f.last; get.reply(chat()); get.reply(chat()); get.fail()
        assertEquals(3, f.requests.size); assertEquals(1, resolved.size)
        val opened = mutableListOf<ChatResult<Unit>>()
        f.api.open(assertNotNull(resolved.single().value), f::active, opened::add)
        val fresh = f.last; fresh.reply(chat()); fresh.reply(chat()); fresh.fail()
        val open = f.last; open.reply(TdApi.Ok()); open.reply(TdApi.Ok()); open.fail()
        assertEquals(5, f.requests.size); assertEquals(Unit, opened.single().value)
    }

    @Test fun discardedAndThrowingDispatchersCompleteEveryStageExactlyOnce() {
        for (step in 0..4) for (throwing in listOf(false, true)) {
            val f = Fixture()
            val resolved = mutableListOf<ChatResult<TdSupportChat>>(); val opened = mutableListOf<ChatResult<Unit>>()
            val api = OfficialTdHelpSupport { command, reply, fail ->
                val index = f.requests.size
                f.requests += Pending(command, reply, fail)
                val failureIndex = if (step <= 2) step else step - 3
                if (index == failureIndex) {
                    if (throwing) throw IllegalStateException("private native detail") else fail()
                }
            }
            if (step <= 2) {
                api.resolve(f::active, resolved::add)
                if (step >= 1) f.last.reply(user())
                if (step >= 2) f.last.reply(chat())
            } else {
                api.open(target(), f::active, opened::add)
                if (step == 4) f.last.reply(chat())
            }
            val held = f.last
            held.fail(); held.fail(); held.reply(TdApi.Ok())
            val actual = if (step <= 2) resolved.single() else opened.single()
            assertNull(actual.value); assertNotNull(actual.error)
            assertEquals(if (step <= 2) step + 1 else step - 2, f.requests.size)
        }
    }

    @Test fun officialSessionRetirementAndAuthorizationAndInteractionGatesRemainInForce() {
        val f = Fixture(); val official = OfficialTdChatSession { command, reply, fail -> f.requests += Pending(command, reply, fail) }
        official.retireDownloads()
        val retired = mutableListOf<ChatResult<TdSupportChat>>()
        official.helpSupport.resolve({ true }, retired::add)
        assertEquals(ChatError.NOT_READY, retired.single().error); assertTrue(f.requests.isEmpty())
        for (blockedByEmail in listOf(false, true)) for (step in 0..4) {
            var ready = true
            val gate = TdUserInteractionGate(); val suggestions = TdLoginEmailSuggestions(gate)
            val requests = mutableListOf<Pending>()
            val session = OfficialTdSession(false, gate, suggestions, { ready }) { command, reply, fail -> requests += Pending(command, reply, fail) }
            val api = assertNotNull(session.chats?.helpSupport)
            val resolved = mutableListOf<ChatResult<TdSupportChat>>(); val opened = mutableListOf<ChatResult<Unit>>()
            fun block() {
                if (blockedByEmail) suggestions.accept(TdApi.UpdateSuggestedActions(arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
                else ready = false
            }
            if (step == 0) block()
            if (step <= 2) {
                api.resolve({ true }, resolved::add)
                if (step == 1) { block(); requests.last().reply(user()) }
                if (step == 2) { requests.last().reply(user()); block(); requests.last().reply(chat()) }
                assertNotNull(resolved.single().error)
                assertEquals(step, requests.size)
            } else {
                api.open(target(), { true }, opened::add)
                if (step == 3) { block(); requests.last().reply(chat()); assertNotNull(opened.single().error) }
                else { requests.last().reply(chat()); block(); requests.last().reply(TdApi.Ok()); assertEquals(Unit, opened.single().value) }
                assertEquals(if (step == 3) 1 else 2, requests.size)
            }
        }
    }

    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture {
        var enabled = true
        val requests = mutableListOf<Pending>()
        val api = OfficialTdHelpSupport { command, reply, fail -> requests += Pending(command, reply, fail) }
        val last get() = requests.last()
        fun active() = enabled
        fun revoke(retire: Boolean) { if (retire) api.retire() else enabled = false }
        fun names() = requests.map { it.command.javaClass.simpleName }
    }

    private fun user(userId: Long = 71) = TdApi.User().apply { id = userId; isSupport = true }
    private fun chat(chatId: Long = 810, userId: Long = 71) = TdApi.Chat().apply {
        id = chatId; type = TdApi.ChatTypePrivate(userId); title = "synthetic support"; positions = emptyArray()
    }
    private fun target() = TdSupportChat(71, ChatItem(810, "synthetic support", positions = emptyMap(), privateUserId = 71))
    private fun invalidFreshChats() = listOf<TdApi.Object>(TdApi.Ok(), chat(0), chat(811), chat(userId = 72),
        chat().apply { type = TdApi.ChatTypeBasicGroup(71) }, chat().apply { type = TdApi.ChatTypeSupergroup(71, true) })
}
