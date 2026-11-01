package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Ownership unit tests; the separate pipeline tests exercise real native source admission. */
class SavedProfileStickerMediaBridgeTest {
    @Test fun ordinaryFailureKeepsOnlyTheLiveRequestWitnessUntilExplicitClose() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
        val bridge = f.bridge(results::add)
        assertTrue(bridge.requestCurrent(), "pending admission is already a one-way witness")
        bridge.start(f.grant()) { _, done ->
            done(SettingsResult(error = SettingsError.REQUEST_FAILED))
            f.read()
        }
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        assertEquals(1, f.closed); assertTrue(bridge.requestCurrent())
        f.source.set(false); assertFalse(bridge.requestCurrent())
        f.source.set(true); assertFalse(bridge.requestCurrent())
        bridge.close(); assertEquals(1, f.closed)
    }

    @Test fun successSurvivesReadCloseButNotTheCapturedSourceOrCaller() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
        val token = SettingsSavedProfileStickerAvatarDemand()
        val bridge = f.bridge(results::add, token)
        bridge.start(f.grant(token)) { _, done -> done(SettingsResult(f.encoded())); f.read() }
        val output = assertNotNull(results.single().value)
        assertEquals(1, f.closed); assertTrue(token.current(), "normal read completion must not revoke the UI token")
        val source = output.sourceLease()
        bridge.close(); assertFalse(bridge.requestCurrent()); assertTrue(token.current()); assertTrue(source())
        token.revokeDemand(); assertTrue(source(), "the demand token alone is not the exported source owner")
        f.source.set(false); assertFalse(source())
        f.source.set(true); assertFalse(source())
        output.close(); assertEquals(0L, f.budget.used())
    }

    @Test fun closeBeforeActorAdmissionOrLateCallbackCannotInstallAnOutput() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
        val bridge = f.bridge(results::add); bridge.close()
        var submitted = false
        bridge.start(f.grant()) { _, _ -> submitted = true; f.read() }
        assertFalse(submitted); assertTrue(results.isEmpty())
        val other = f.bridge(results::add); lateinit var done: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit
        other.start(f.grant()) { _, response -> done = response; f.read() }
        other.close(); done(SettingsResult(f.encoded()))
        assertTrue(results.isEmpty()); assertEquals(0L, f.budget.used())
    }

    @Test fun uncertainSubmitStopsDemandWithoutDestroyingFailureWitness() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
        val bridge = f.bridge(results::add); val grant = f.grant()
        lateinit var demand: () -> Boolean
        bridge.start(grant) { active, _ -> demand = active; error("possibly dispatched") }
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        assertTrue(bridge.requestCurrent()); assertFalse(demand()); assertFalse(grant.readCurrent())
        bridge.close()
    }

    @Test fun offSceneDemandRevocationImmediatelyStopsTheAtomicsOnlyBrokerGate() {
        val f = Fixture(); val token = SettingsSavedProfileStickerAvatarDemand()
        val bridge = f.bridge({}, token)
        val grant = f.grant(token); lateinit var demand: () -> Boolean
        bridge.start(grant) { active, _ -> demand = active; f.read() }
        assertTrue(grant.readCurrent()); assertTrue(demand())
        token.revokeDemand()
        assertFalse(grant.readCurrent()); assertFalse(demand())
        assertTrue(bridge.requestCurrent(), "demand revocation alone does not invent source invalidation")
        f.caller.set(false); assertFalse(bridge.requestCurrent())
        bridge.close()

        val next = Fixture(); val earlyToken = SettingsSavedProfileStickerAvatarDemand()
        val early = next.bridge({}, earlyToken); var submitted = false
        earlyToken.revokeDemand()
        early.start(next.grant(earlyToken)) { _, _ -> submitted = true; next.read() }
        assertFalse(submitted, "a late actor bind cannot revive an already revoked demand")
        assertFalse(early.requestCurrent())
    }

    @Test fun savedAndBuilderRequestsShareTheExistingEnvironmentAdmissionLimit() {
        val environment = ProfileStickerAvatarMediaEnvironment(1, { true }) { _, _ -> }
        val builder = environment.reader.preview(SettingsProfileExtrasToken(1, 1, 43),
            ProfileStickerAvatarPickerTicket(), ProfileStickerAvatarSelectionTicket(), { true }) {}
        fun bridge() = SavedProfileStickerMediaBridge(environment::current, { true }, {}, removed = environment::remove)
        val admitted = List(63) { bridge() }
        admitted.forEach { assertTrue(environment.register(it)) }
        val next = bridge()
        assertFalse(environment.register(next), "one builder and 63 saved reads use the same 64 slots")
        builder.close(); assertTrue(environment.register(next))
        environment.close()
        assertTrue(admitted.none { it.requestCurrent() }); assertFalse(next.requestCurrent())
    }

    @Test fun brokerConflictRevokesAnAlreadyDeliveredSourceBeforeActorCleanup() {
        val f = Fixture(); val facts = SavedProfileStickerFacts(); val sourceLive = AtomicBoolean(true)
        val file = MediaFile(7, size = 100, uniqueId = "native-7")
        val asset = ProfileStickerAvatarAsset(file, MediaFormat.WEBP, 1, 1)
        assertNotNull(facts.accept(ProfileStickerAvatarMediaFingerprint(asset, null, false)).value)
        var notified = false
        val captured = SavedProfileStickerCaptured(
            SettingsProfilePhotoSticker(SettingsProfilePhotoSticker.Type.CustomEmoji(-7), SettingsProfilePhotoSticker.Background.Solid(0)),
            { sourceLive.get() && facts.current() }, { sourceLive.get() && facts.current() }, facts,
            { notified = true; sourceLive.set(false) })
        val grant = SavedProfileStickerActorGrant(captured, captured.atomicCurrent, captured.sourceCurrent) {}
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
        val bridge = f.bridge(results::add)
        bridge.start(grant) { _, done -> done(SettingsResult(f.encoded())); f.read() }
        val output = assertNotNull(results.single().value); val outputGate = output.sourceLease()
        bridge.close(); assertTrue(outputGate())
        val queued = ArrayDeque<() -> Unit>()
        fun schedule(delay: Long, action: () -> Unit) { if (delay == 0L) queued.addLast(action) }
        val native = object : DownloadBrokerNative {
            override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) = Unit
            override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) = completed(ChatResult(0L))
            override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) = Unit
            override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) = Unit
            override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) = completed(ChatResult(Unit))
        }
        val broker = TdDownloadBroker(native, ::schedule, { 0L }, { schedule(0, it) })
        val owner = MediaDownloadOwner(ChatToken(Long.MIN_VALUE, Long.MIN_VALUE, null), 0, MediaDownloadPurpose.PROFILE_EMOJI, 1)
        broker.boundedFullWithEvidence(owner, file, 24 * 1024 * 1024, { facts.current() }, facts.evidence) {
            schedule(0) { captured.invalidate() }
        }
        broker.observe(file.copy(size = 200, downloaded = true, downloadedSize = 200))
        assertFalse(outputGate()); assertFalse(notified)
        while (queued.isNotEmpty()) queued.removeFirst()()
        assertTrue(notified); output.close(); assertEquals(0L, f.budget.used()); broker.close()
    }

    private class Fixture {
        val source = AtomicBoolean(true); val caller = AtomicBoolean(true)
        val budget = ProfileStickerAvatarEncodedBudget(); var closed = 0
        fun grant(demand: SettingsSavedProfileStickerAvatarDemand = SettingsSavedProfileStickerAvatarDemand()): SavedProfileStickerActorGrant {
            val captured = SavedProfileStickerCaptured(
                SettingsProfilePhotoSticker(SettingsProfilePhotoSticker.Type.CustomEmoji(-7),
                    SettingsProfilePhotoSticker.Background.Solid(0)), source::get, source::get,
                SavedProfileStickerFacts(), { source.set(false) })
            return SavedProfileStickerActorGrant(captured, source::get, source::get, demand) {}
        }
        fun bridge(result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
            demand: SettingsSavedProfileStickerAvatarDemand = SettingsSavedProfileStickerAvatarDemand()) =
            SavedProfileStickerMediaBridge({ true }, caller::get, result, demand) {}
        fun read() = object : ProfileStickerAvatarReadLease {
            private val once = AtomicBoolean()
            override fun close() { if (once.compareAndSet(false, true)) closed++ }
        }
        fun encoded(): ProfileStickerAvatarEncoded {
            val media = ProfileStickerAvatarOwnedMedia(ProfileStickerAvatarMediaRole.FULL,
                ProfileStickerAvatarAsset(MediaFile(1, 3, uniqueId = "actual"), MediaFormat.WEBP, 1, 1), false)
            return ProfileStickerAvatarEncoded.take(media, byteArrayOf(1, 2, 3), checkNotNull(budget.reserve(3)))
        }
    }
}
