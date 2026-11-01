package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.status.*
import com.badnng.moe.settings.LoginEmailSuggestions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatEngineTest {
    @Test
    fun nativeMandatoryGateRejectsAlreadyQueuedChatInputAndRetainsAccountStateUntilUnlock() = runTest {
        withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.proxyResult(null)
            session.authorize(AuthorizationPhase.READY)
            session.update(ChatEvent.NewChat(ChatItem(11, "synthetic gated chat", order = 5)))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11)
            runCurrent()
            val token = engine.state.value.chats.token
            assertTrue(engine.sendText(token, "queued before mandatory gate"))
            session.interactionGate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            runCurrent()
            assertTrue(session.chats.sends.isEmpty())
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertTrue(engine.state.value.chats.enabled)
            assertEquals(token, engine.state.value.chats.token)
            assertEquals(listOf(11L), engine.state.value.chats.items.map { it.id })
            assertFalse(engine.sendText(token, "rejected before enqueue"))
            val outcomes = mutableListOf<ChatResult<Unit>>()
            var released = 0
            val retained = ownedAttachmentLease { released++ }
            engine.submitClipboardPhoto(token, "C:/synthetic/gated.png", RichMessageText(""), retained, outcomes::add)
            retained.release()
            assertEquals(1, released)
            assertEquals(ChatError.NOT_READY, outcomes.single().error)
            assertTrue(engine.visibleMessages(token, emptyList(), false), "Owned cleanup remains available.")
            runCurrent()
            session.interactionGate.update(LoginEmailSuggestions(revision = 2))
            runCurrent()
            assertFalse(engine.state.value.loginEmail.mandatory)
            assertEquals(1, session.chats.loads)
            assertTrue(engine.sendText(token, "explicit send after unlock"))
            runCurrent()
            assertEquals(1, session.chats.sends.size)
        }
    }

    @Test
    fun earlyMandatoryGateDefersChatStartAndAnOldGateCannotLockTheNextNativeGeneration() = runTest {
        withEngine { engine, transport ->
            val old = transport.sessions.last()
            old.interactionGate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            old.authorize(AuthorizationPhase.READY)
            old.proxyResult(null)
            runCurrent()
            assertEquals(0, old.chats.loads)
            assertFalse(engine.state.value.chats.enabled)
            assertTrue(engine.state.value.loginEmail.mandatory)
            old.interactionGate.update(LoginEmailSuggestions(revision = 2))
            runCurrent()
            assertEquals(1, old.chats.loads)
            val stopping = async { engine.stop() }
            runCurrent()
            assertTrue(stopping.await())
            engine.start()
            runCurrent()
            val next = transport.sessions.last()
            next.authorize(AuthorizationPhase.WAITING_PARAMETERS)
            runCurrent()
            next.authorize(AuthorizationPhase.READY)
            next.proxyResult(null)
            old.interactionGate.update(LoginEmailSuggestions(3, requiredPresent = true, requiredEpoch = 2))
            runCurrent()
            assertFalse(engine.state.value.loginEmail.mandatory)
            assertEquals(1, next.chats.loads)
        }
    }

    @Test
    fun reportCompletionClosesOnlyAfterAllBasicGroupStagesWhileFailuresKeepTheConversationOpen() = runTest {
        withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.proxyResult(null)
            session.authorize(AuthorizationPhase.READY)
            val bar = ChatActionBar(ChatActionBarKind.REPORT_SPAM)
            val member = ChatReportSpamCapability(bar, canLeave = true, needsHistoryRemoval = true, canDeleteHistory = true)
            session.update(ChatEvent.NewChat(ChatItem(11, "synthetic basic group", order = 5, basicGroupId = 111,
                actionBar = bar, canLeave = true)))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11); runCurrent()
            val token = engine.state.value.chats.token
            engine.reportSpamAndLeave(token); runCurrent()
            session.chats.spamCapabilities.last()(ChatResult(member)); runCurrent()
            session.chats.spamReports.single()(ChatResult(Unit)); runCurrent()
            session.chats.spamCapabilities.last()(ChatResult(member)); runCurrent()
            session.chats.spamLeaves.single()(ChatResult(error = ChatError.REQUEST_FAILED)); runCurrent()
            assertEquals(token, engine.state.value.chats.token)
            assertEquals(11L, engine.state.value.chats.selected?.id)
            assertTrue(engine.state.value.chats.reportSpam.reported)
            engine.reportSpamAndLeave(token); runCurrent()
            session.chats.spamCapabilities.last()(ChatResult(member)); runCurrent()
            session.chats.spamLeaves.last()(ChatResult(Unit)); runCurrent()
            val left = member.copy(canLeave = false, isMember = false, actionBar = null)
            session.chats.spamCapabilities.last()(ChatResult(left)); runCurrent()
            assertTrue(engine.state.value.chats.reportSpam.left)
            assertTrue(engine.state.value.chats.reportSpam.clearPending)
            assertEquals(token, engine.state.value.chats.token)
            session.chats.spamClears.single()(ChatResult(error = ChatError.REQUEST_FAILED)); runCurrent()
            assertEquals(11L, engine.state.value.chats.selected?.id)
            engine.reportSpamAndLeave(token); runCurrent()
            session.chats.spamCapabilities.last()(ChatResult(left)); runCurrent()
            session.chats.spamClears.last()(ChatResult(Unit)); runCurrent()
            assertNull(engine.state.value.chats.token.chatId)
            assertNull(engine.state.value.chats.selected)
            assertEquals(5L, engine.state.value.chats.knownChats.single().positions[ChatListRef()])
            assertEquals(1, session.chats.spamReports.size)
            assertEquals(2, session.chats.spamLeaves.size)
            assertEquals(2, session.chats.spamClears.size)
            engine.selectChat(engine.state.value.chats.token, 11); runCurrent()
            assertTrue(engine.state.value.chats.reportSpam.completed)
            engine.reportSpamAndLeave(engine.state.value.chats.token); runCurrent()
            assertEquals(1, session.chats.spamReports.size)
            assertEquals(2, session.chats.spamLeaves.size)
            assertEquals(2, session.chats.spamClears.size)
        }
    }

    @Test
    fun reportSpamActionsStayBoundToTheCurrentEngineGenerationEvenAfterNativeAcceptance() = runTest {
        withEngine { engine, transport ->
            val old = transport.sessions.last()
            old.proxyResult(null)
            old.authorize(AuthorizationPhase.READY)
            old.update(ChatEvent.NewChat(ChatItem(11, "synthetic group", order = 5, supergroupId = 111,
                isJoinedGroup = true, actionBar = ChatActionBar(ChatActionBarKind.REPORT_SPAM))))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11); runCurrent()
            val token = engine.state.value.chats.token
            assertTrue(engine.reportSpamAndLeave(token)); runCurrent()
            old.chats.spamCapabilities.single()(ChatResult(ChatReportSpamCapability(
                ChatActionBar(ChatActionBarKind.REPORT_SPAM), canLeave = true)))
            runCurrent()
            assertEquals(1, old.chats.spamReports.size)
            val stopping = async { engine.stop() }; runCurrent(); assertTrue(stopping.await())
            engine.start(); runCurrent()
            val next = transport.sessions.last()
            next.authorize(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            next.proxyResult(null); next.authorize(AuthorizationPhase.READY); runCurrent()
            old.chats.spamReports.single()(ChatResult(Unit)); runCurrent()
            assertTrue(old.chats.spamLeaves.isEmpty())
            assertTrue(next.chats.spamCapabilities.isEmpty())
            assertFalse(engine.reportSpamAndLeave(token))
            assertFalse(engine.dismissSpamBar(token))
            assertFalse(engine.state.value.chats.reportSpam.visible)
        }
    }

    @Test
    fun theActualAttachmentConfirmationEntryRetainsVideoFilesAcrossItsUiCompletion() = runTest {
        withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.proxyResult(null)
            session.authorize(AuthorizationPhase.READY)
            session.update(ChatEvent.NewChat(ChatItem(11, "合成会话", order = 5)))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11)
            runCurrent()
            val thumbnail = Files.createTempFile("synthetic-engine-confirmation-", ".jpg")
            val lease = ownedAttachmentLease { Files.deleteIfExists(thumbnail) }
            val video = LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 100, 1, 64, 48,
                thumbnail = LocalAttachmentThumbnail(thumbnail.toString(), 64, 48, 50), lease = lease)
            val results = mutableListOf<ChatResult<Unit>>()
            try {
                engine.submitClipboardFile(engine.state.value.chats.token, video, RichMessageText("caption")) { result ->
                    results += result
                    if (result.error == null) lease.release()
                }
                runCurrent()
                assertTrue(results.isEmpty())
                session.chats.sends.single()(ChatResult(ChatMessage(-10, 11, outgoing = true, kind = MessageKind.VIDEO,
                    delivery = MessageDelivery.PENDING)))
                runCurrent()
                assertNull(results.single().error)
                assertTrue(Files.exists(thumbnail), "The real UI submitClipboardFile path must outlive confirmation disposal")
                session.update(ChatEvent.SendSucceeded(ChatMessage(100, 11, outgoing = true, kind = MessageKind.VIDEO), -10))
                runCurrent()
                assertFalse(Files.exists(thumbnail))
                assertEquals(1, results.size)
            } finally {
                lease.release()
                Files.deleteIfExists(thumbnail)
            }
        }
    }

    @Test
    fun aDiscardedOrRefusedAttachmentConfirmationReleasesItsNativeHandleAndKeepsUiOwnership() = runTest {
        for (discard in listOf(false, true)) withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.proxyResult(null)
            session.authorize(AuthorizationPhase.READY)
            session.update(ChatEvent.NewChat(ChatItem(11, "合成会话", order = 5)))
            session.update(ChatEvent.NewChat(ChatItem(12, "其他合成会话", order = 4)))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11)
            runCurrent()
            val token = engine.state.value.chats.token
            var cleaned = 0
            val lease = ownedAttachmentLease { cleaned++ }
            val video = LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 100, 1, 64, 48, lease = lease)
            if (discard) engine.selectChat(token, 12)
            val results = mutableListOf<ChatResult<Unit>>()
            engine.submitClipboardFile(token, video, RichMessageText("caption"), results::add)
            runCurrent()
            if (!discard) {
                session.chats.sends.single()(ChatResult(error = ChatError.REQUEST_FAILED))
                runCurrent()
            }
            assertEquals(if (discard) ChatError.NOT_READY else ChatError.REQUEST_FAILED, results.single().error)
            assertEquals(0, cleaned, "A failed attempt releases only its retained handle; the open selection remains reusable")
            lease.release()
            assertEquals(1, cleaned, "Neither enqueue discard nor native refusal may leak the retained handle")
        }
    }

    @Test
    fun preparedVideoOwnershipIsRetainedBeforeEnqueueAndReleasedOnDiscardOrNativeDelivery() = runTest {
        for (switchBeforeSubmission in listOf(false, true)) withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.proxyResult(null)
            session.authorize(AuthorizationPhase.READY)
            session.update(ChatEvent.NewChat(ChatItem(11, "合成会话", order = 5)))
            session.update(ChatEvent.NewChat(ChatItem(12, "其他合成会话", order = 4)))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11)
            runCurrent()
            val token = engine.state.value.chats.token
            var cleaned = 0
            val lease = ownedAttachmentLease { cleaned++ }
            val video = LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 100, 1, 64, 48,
                thumbnail = LocalAttachmentThumbnail("C:/synthetic/thumb.jpg", 64, 48, 50), lease = lease)
            if (switchBeforeSubmission) engine.selectChat(token, 12)
            assertTrue(engine.sendAttachment(token, video, RichMessageText("caption")))
            lease.release()
            assertEquals(0, cleaned, "The queued engine command already owns the temporary thumbnail")
            runCurrent()
            if (switchBeforeSubmission) {
                assertTrue(session.chats.sends.isEmpty())
                assertEquals(1, cleaned)
            } else {
                session.chats.sends.single()(ChatResult(ChatMessage(-10, 11, outgoing = true, kind = MessageKind.VIDEO,
                    delivery = MessageDelivery.PENDING)))
                runCurrent()
                assertEquals(0, cleaned)
                session.update(ChatEvent.SendSucceeded(ChatMessage(100, 11, outgoing = true, kind = MessageKind.VIDEO), -10))
                runCurrent()
                assertEquals(1, cleaned)
            }
        }
    }

    @Test
    fun ownedInputSurvivesCloseTimeoutAndIsReleasedOnlyWhenNativeClosedArrives() = runTest {
        withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.proxyResult(null)
            session.authorize(AuthorizationPhase.READY)
            session.update(ChatEvent.NewChat(ChatItem(11, "合成会话", order = 5)))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11)
            runCurrent()
            var cleaned = false
            val lease = ownedAttachmentLease { cleaned = true }
            engine.sendOwnedPhoto(engine.state.value.chats.token, "C:/synthetic/photo.png", RichMessageText(""), lease)
            lease.release()
            runCurrent()
            assertFalse(cleaned)
            session.closeImmediately = false
            val stopping = async { engine.stop(timeoutMillis = 10) }
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            assertFalse(stopping.await())
            assertFalse(cleaned, "A timeout leaves the native client and its local input file alive.")
            session.authorize(AuthorizationPhase.CLOSED)
            runCurrent()
            assertTrue(cleaned)
            session.closeImmediately = true
        }
    }

    @Test
    fun anOwnedInputQueuedBehindASelectionChangeReleasesItsDiscardedHandle() = runTest {
        withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.proxyResult(null)
            session.authorize(AuthorizationPhase.READY)
            session.update(ChatEvent.NewChat(ChatItem(11, "合成会话", order = 5)))
            session.update(ChatEvent.NewChat(ChatItem(12, "其他合成会话", order = 4)))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11)
            runCurrent()
            val oldToken = engine.state.value.chats.token
            engine.selectChat(oldToken, 12)
            var cleaned = false
            val lease = ownedAttachmentLease { cleaned = true }
            val results = mutableListOf<ChatResult<Unit>>()
            engine.submitClipboardPhoto(oldToken, "C:/synthetic/photo.png", RichMessageText(""), lease, results::add)
            lease.release()
            runCurrent()
            assertTrue(cleaned)
            assertEquals(ChatError.NOT_READY, results.single().error)
            assertTrue(session.chats.sends.isEmpty())
        }
    }

    @Test
    fun restoredAuthorizationWaitsForProxyAndStartsTheListOnlyOnce() = runTest {
        withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.update(ChatEvent.NewChat(ChatItem(11, "synthetic private chat", order = 5)))
            session.authorize(AuthorizationPhase.READY)
            runCurrent()
            assertFalse(engine.state.value.chats.enabled)
            assertEquals(0, session.chats.loads)
            session.proxyResult(null)
            runCurrent()
            assertTrue(engine.state.value.chats.enabled)
            assertEquals(listOf(11L), engine.state.value.chats.items.map { it.id })
            assertEquals(1, session.chats.loads)
            session.authorize(AuthorizationPhase.READY)
            session.proxyResult(null)
            runCurrent()
            assertEquals(1, session.chats.loads)
        }
    }

    @Test
    fun failedProxyCannotLoadOrSendDespiteAnAuthorizedSession() = runTest {
        withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.authorize(AuthorizationPhase.READY)
            session.proxyResult(407)
            runCurrent()
            assertFalse(engine.state.value.chats.enabled)
            assertFalse(engine.sendText(engine.state.value.chats.token, "synthetic text"))
            assertEquals(0, session.chats.loads)
            assertTrue(session.chats.sends.isEmpty())
        }
    }

    @Test
    fun stopClearsAccountContentAndLateCallbacksCannotRepopulateTheNextGeneration() = runTest {
        withEngine { engine, transport ->
            val old = transport.sessions.last()
            old.proxyResult(null)
            old.authorize(AuthorizationPhase.READY)
            old.update(ChatEvent.NewChat(ChatItem(11, "synthetic private chat", order = 5)))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11)
            runCurrent()
            val oldToken = engine.state.value.chats.token
            assertTrue(engine.sendText(oldToken, "private synthetic outgoing"))
            runCurrent()
            assertEquals(1, old.chats.sends.size)
            val stopping = async { engine.stop() }
            runCurrent()
            assertTrue(stopping.await())
            assertFalse(engine.state.value.chats.enabled)
            assertTrue(engine.state.value.chats.items.isEmpty())
            assertTrue(engine.state.value.chats.messages.isEmpty())
            engine.start()
            runCurrent()
            val next = transport.sessions.last()
            next.authorize(AuthorizationPhase.WAITING_PARAMETERS)
            runCurrent()
            next.proxyResult(null)
            next.authorize(AuthorizationPhase.READY)
            old.update(ChatEvent.NewMessage(ChatMessage(20, 11, text = "obsolete incoming")))
            old.chats.sends.single()(ChatResult(ChatMessage(21, 11, text = "obsolete ack", outgoing = true)))
            old.chats.histories.single()(ChatResult(listOf(ChatMessage(19, 11, text = "obsolete history"))))
            runCurrent()
            assertTrue(engine.state.value.chats.enabled)
            assertTrue(engine.state.value.chats.items.isEmpty())
            assertTrue(engine.state.value.chats.messages.isEmpty())
            assertFalse(engine.sendText(oldToken, "stale form must not send"))
            assertTrue(next.chats.sends.isEmpty())
        }
    }

    @Test
    fun incomingContentReachesTheUiButNeverTheSnapshotStringOrSafeJournal() = runTest {
        withEngine { engine, transport ->
            val session = transport.sessions.last()
            session.proxyResult(null)
            session.authorize(AuthorizationPhase.READY)
            session.update(ChatEvent.NewChat(ChatItem(11, "synthetic-private-title", order = 5)))
            session.update(ChatEvent.SenderName(8, "synthetic-private-sender"))
            runCurrent()
            engine.selectChat(engine.state.value.chats.token, 11)
            runCurrent()
            session.update(ChatEvent.NewMessage(ChatMessage(20, 11, senderId = 8, text = "synthetic-private-body")))
            runCurrent()
            assertEquals("synthetic-private-body", engine.state.value.chats.messages.single().text)
            session.authorize(AuthorizationPhase.READY) // Exercise journal recording after content arrives.
            runCurrent()
            val diagnostic = engine.state.value.toString() + Files.readString(Path.of(assertNotNull(engine.state.value.eventLogPath)))
            for (secret in listOf("synthetic-private-title", "synthetic-private-sender", "synthetic-private-body")) {
                assertFalse(diagnostic.contains(secret))
            }
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, FakeTransport) -> Unit) {
        val root = Files.createTempDirectory("chat-engine-test-")
        val path = root.resolve("tdlib.properties")
        Files.writeString(path, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        val transport = FakeTransport()
        val engine = syntheticOpenEngine(ConfigurationStore(path, root.resolve("data")), transport, StandardTestDispatcher(testScheduler))
        try {
            engine.start()
            runCurrent()
            transport.sessions.last().authorize(AuthorizationPhase.WAITING_PARAMETERS)
            runCurrent()
            block(engine, transport)
        } finally {
            assertTrue(engine.close())
        }
    }

    private class FakeTransport : TdTransport {
        val sessions = mutableListOf<FakeSession>()
        override fun load() = NativeInfo("fixture-version", "fixture-commit", "fixture-path")
        override fun create(
            onAuthorization: (AuthorizationDetails) -> Unit,
            onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit,
            onChatEvent: (ChatEvent) -> Unit,
        ): TdSession = FakeSession(onAuthorization, onChatEvent).also(sessions::add)
    }

    private class FakeSession(
        private val authorization: (AuthorizationDetails) -> Unit,
        val update: (ChatEvent) -> Unit,
    ) : SyntheticOpenKeySession {
        override val chats = FakeChats()
        override val interactionGate = TdUserInteractionGate()
        var closeImmediately = true
        lateinit var proxyResult: (Int?) -> Unit
        fun authorize(phase: AuthorizationPhase) = authorization(AuthorizationDetails(phase))
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) { proxyResult = result }
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in chat fixtures")
        override fun close() { if (closeImmediately) authorize(AuthorizationPhase.CLOSED) }
    }

    private class FakeChats : TdChatSession {
        var loads = 0
        val histories = mutableListOf<(ChatResult<List<ChatMessage>>) -> Unit>()
        val sends = mutableListOf<(ChatResult<ChatMessage>) -> Unit>()
        val spamCapabilities = mutableListOf<(ChatResult<ChatReportSpamCapability>) -> Unit>()
        val spamReports = mutableListOf<(ChatResult<Unit>) -> Unit>()
        val spamLeaves = mutableListOf<(ChatResult<Unit>) -> Unit>()
        val spamClears = mutableListOf<(ChatResult<Unit>) -> Unit>()
        override fun reportSpamCapability(chatId: Long, result: (ChatResult<ChatReportSpamCapability>) -> Unit) { spamCapabilities += result }
        override fun reportSpam(chatId: Long, result: (ChatResult<Unit>) -> Unit) { spamReports += result }
        override fun leaveChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) { spamLeaves += result }
        override fun clearReportedBasicGroupHistory(chatId: Long, result: (ChatResult<Unit>) -> Unit) { spamClears += result }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { loads++; result(ChatResult(ChatLoadResult(endReached = true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) { histories += result }
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) { sends += result }
        override fun sendPhotoReply(chatId: Long, path: String, caption: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) { sends += result }
        override fun sendAttachmentReply(chatId: Long, attachment: LocalAttachment, caption: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) { sends += result }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("No retry in engine fixtures")
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = result(ChatResult(Unit))
    }
}
