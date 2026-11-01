package com.badnng.moe.tdlib

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsPreparedProfileAnimation
import com.badnng.moe.settings.SettingsResult
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class OfficialTdProfilePhotoAnimationTest {
    @Test fun validationPrecedesFreshSelfAndExactPrivateNativeAnimationWrite() {
        val f = Fixture()
        f.upload()
        assertTrue(f.requests.isEmpty())
        f.validated()
        assertIs<TdApi.GetMe>(f.requests.single().command)
        f.me()
        val set = assertIs<TdApi.SetProfilePhoto>(f.requests.last().command)
        assertFalse(set.isPublic)
        val animation = assertIs<TdApi.InputChatPhotoAnimation>(set.photo)
        assertEquals("never-probed-synthetic.mp4", assertIs<TdApi.InputFileLocal>(animation.animation).path)
        assertEquals(1.25, animation.mainFrameTimestamp)
        f.requests.last().answer(TdApi.Ok())
        assertEquals(Unit, f.results.single().value)
        assertEquals(1, f.releases)
    }

    @Test fun invalidAdmissionNeverSchedulesValidationOrNativeRequests() {
        val bad = listOf<(SettingsPreparedProfileAnimation) -> SettingsPreparedProfileAnimation>(
            { it.copy(path = "") }, { it.copy(size = 2_000_001) }, { it.copy(dimension = 801) },
            { it.copy(durationMillis = 0) }, { it.copy(sha256 = "bad") },
            { it.copy(mainFrameTimestampSeconds = Double.NaN) },
            { it.copy(mainFrameTimestampSeconds = Double.POSITIVE_INFINITY) },
            { it.copy(mainFrameTimestampSeconds = 2.0) },
        )
        bad.forEach { change ->
            val f = Fixture()
            f.upload(animation = change(f.animation()))
            assertEquals(SettingsError.INVALID_VALUE, f.results.single().error)
            assertNull(f.validation)
            assertTrue(f.requests.isEmpty())
            assertEquals(1, f.releases)
        }
        listOf(0L to 91L, 43L to 0L).forEach { (userId, photoId) ->
            val f = Fixture()
            f.upload(userId, photoId)
            assertEquals(SettingsError.INVALID_VALUE, f.results.single().error)
            assertEquals(1, f.releases)
            assertNull(f.validation)
        }
    }

    @Test fun wrongSelfOrChangedCurrentPhotoCannotWrite() {
        listOf(999L to 91L, 43L to 92L).forEach { (id, photo) ->
            val f = Fixture()
            f.upload(); f.validated(); f.me(id, photo)
            assertEquals(if (id != 43L) SettingsError.NOT_READY else SettingsError.CONFLICT, f.results.single().error)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun signedNativePhotoIdsAndNoCurrentPhotoRemainExact() {
        listOf<Long?>(-923L, null).forEach { expected ->
            val f = Fixture()
            f.upload(photoId = expected); f.validated(); f.me(photo = expected)
            assertIs<TdApi.SetProfilePhoto>(f.requests.last().command)
            f.requests.last().answer(TdApi.Ok())
            assertEquals(Unit, f.results.single().value)
        }
    }

    @Test fun validationFailureAndDuplicateCallbacksConvergeOnce() {
        val f = Fixture()
        f.upload()
        f.validated(SettingsResult(error = SettingsError.INVALID_VALUE))
        f.validated(); f.validated()
        assertEquals(SettingsError.INVALID_VALUE, f.results.single().error)
        assertTrue(f.requests.isEmpty())
        val g = Fixture()
        g.upload(); g.validated(); g.validated()
        assertEquals(1, g.requests.size)
        val self = g.requests.single()
        g.me(); self.answer(TdApi.User().apply { id = 43 }); self.failed()
        assertEquals(2, g.requests.size)
        val write = g.requests.last()
        write.answer(TdApi.Error(429, "private native text")); write.answer(TdApi.Ok()); write.failed()
        assertEquals(SettingsError.RATE_LIMITED, g.results.single().error)
        assertFalse(g.results.toString().contains("private native"))
    }

    @Test fun staleValidationAndSelfResponseReturnNotReadyWithoutWrite() {
        val f = Fixture()
        f.upload(); f.active = false; f.validated()
        assertEquals(SettingsError.NOT_READY, f.results.single().error)
        assertTrue(f.requests.isEmpty())
        val g = Fixture()
        g.upload(); g.validated(); g.active = false; g.me()
        assertEquals(SettingsError.NOT_READY, g.results.single().error)
        assertEquals(1, g.requests.size)
    }

    @Test fun retireBeforeWriteFinishesOnceAndDoesNotReleaseRunningValidation() {
        val f = Fixture()
        f.upload(); f.api.retire(); f.api.retire()
        assertEquals(SettingsError.NOT_READY, f.results.single().error)
        assertEquals(0, f.releases)
        f.validated()
        assertEquals(1, f.releases)
        assertEquals(1, f.results.size)
        assertTrue(f.requests.isEmpty())
        f.upload()
        assertEquals(SettingsError.NOT_READY, f.results.last().error)
        assertEquals(2, f.releases)
        val g = Fixture()
        g.upload(); g.validated(); g.api.retire(); g.me()
        assertEquals(SettingsError.NOT_READY, g.results.single().error)
        assertEquals(1, g.requests.size)
    }

    @Test fun dispatchedWriteKeepsLateOkAfterPageCloseAndRetirement() {
        val f = Fixture()
        f.upload(); f.validated(); f.me()
        val write = f.requests.last()
        f.active = false; f.api.retire()
        assertTrue(f.results.isEmpty())
        write.answer(TdApi.Ok()); write.failed(); write.answer(TdApi.Ok())
        assertEquals(Unit, f.results.single().value)
        assertEquals(2, f.requests.size)
    }

    @Test fun throwingAdapterAndNativeSendReturnSanitizedFailureOnce() {
        val f = Fixture(validationThrows = true)
        f.upload()
        assertEquals(SettingsError.REQUEST_FAILED, f.results.single().error)
        assertEquals(1, f.releases)
        assertTrue(f.requests.isEmpty())
        val g = Fixture(sendThrows = true)
        g.upload(); g.validated()
        assertEquals(SettingsError.REQUEST_FAILED, g.results.single().error)
        assertFalse(g.results.toString().contains("sensitive"))
    }

    @Test fun adapterThrowAfterSuccessfulCallbackCannotOverrideDispatchedNativeWrite() {
        val results = mutableListOf<SettingsResult<Unit>>()
        var native: ((TdApi.Object) -> Unit)? = null
        var releases = 0
        val lease = ownedAttachmentLease { releases++ }
        val api = OfficialTdProfilePhotoAnimation({ request, callback, _ ->
            if (request is TdApi.GetMe) callback(TdApi.User().apply { id = 43 })
            else { assertIs<TdApi.SetProfilePhoto>(request); native = callback }
        }, { _, handle, _, callback ->
            handle.release(); callback(SettingsResult(Unit)); error("after callback")
        })
        api.upload(43, null, SettingsPreparedProfileAnimation(
            "synthetic.mp4", 42, 800, 2_000, 0.0, "a".repeat(64), lease,
        ), lease, { true }, results::add)
        assertTrue(results.isEmpty())
        api.retire()
        assertNotNull(native)(TdApi.Ok())
        assertEquals(Unit, results.single().value)
        assertEquals(1, releases)
    }

    @Test fun foreignCurrentPredicateCanRetireFromAnotherThreadWithoutHelperMonitor() {
        val reentered = AtomicBoolean()
        val completed = CountDownLatch(1)
        val releases = AtomicInteger()
        val lease = ownedAttachmentLease { releases.incrementAndGet() }
        lateinit var api: OfficialTdProfilePhotoAnimation
        api = OfficialTdProfilePhotoAnimation({ _, _, _ -> error("must not send") },
            { _, handle, _, _ -> handle.release(); error("must not validate") })
        api.upload(43, null, testAnimation(lease), lease, {
            reentered.set(retireOnAnotherThread(api))
            false
        }) { completed.countDown() }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertTrue(reentered.get())
        assertEquals(1, releases.get())
    }

    @Test fun foreignResultCanRetireFromAnotherThreadWithoutHelperMonitor() {
        val reentered = AtomicBoolean()
        val calls = AtomicInteger()
        val lease = ownedAttachmentLease {}
        lateinit var api: OfficialTdProfilePhotoAnimation
        api = OfficialTdProfilePhotoAnimation({ _, _, _ -> error("must not send") },
            { _, handle, _, callback ->
                handle.release(); callback(SettingsResult(error = SettingsError.INVALID_VALUE))
            })
        api.upload(43, null, testAnimation(lease), lease, { true }) {
            reentered.set(retireOnAnotherThread(api))
            calls.incrementAndGet()
        }
        assertTrue(reentered.get())
        assertEquals(1, calls.get())
    }

    @Test fun finalCurrentCheckAfterSelfClaimCanDeclineBeforeNativeWrite() {
        val results = mutableListOf<SettingsResult<Unit>>()
        val requests = mutableListOf<Request>()
        val selfChecks = AtomicInteger()
        var answeringSelf = false
        val lease = ownedAttachmentLease {}
        val api = OfficialTdProfilePhotoAnimation({ request, callback, failed ->
            requests += Request(request, callback, failed)
        }, { _, handle, _, callback -> handle.release(); callback(SettingsResult(Unit)) })
        api.upload(43, null, testAnimation(lease), lease, {
            !answeringSelf || selfChecks.incrementAndGet() == 1
        }, results::add)
        assertIs<TdApi.GetMe>(requests.single().command)
        answeringSelf = true
        requests.single().answer(TdApi.User().apply { id = 43 })
        assertEquals(2, selfChecks.get())
        assertEquals(1, requests.size)
        assertEquals(SettingsError.NOT_READY, results.single().error)
    }

    private fun retireOnAnotherThread(api: OfficialTdProfilePhotoAnimation): Boolean {
        val finished = CountDownLatch(1)
        Thread({ api.retire(); finished.countDown() }, "profile-animation-reentry-test")
            .apply { isDaemon = true; start() }
        return finished.await(5, TimeUnit.SECONDS)
    }

    private fun testAnimation(lease: AttachmentLease) = SettingsPreparedProfileAnimation(
        "synthetic.mp4", 42, 800, 2_000, 0.0, "a".repeat(64), lease,
    )

    private class Fixture(val validationThrows: Boolean = false, val sendThrows: Boolean = false) {
        var active = true
        var releases = 0
        var validation: ((SettingsResult<Unit>) -> Unit)? = null
        val requests = mutableListOf<Request>()
        val results = mutableListOf<SettingsResult<Unit>>()
        val api = OfficialTdProfilePhotoAnimation({ command, answer, failed ->
            if (sendThrows) error("sensitive path")
            requests += Request(command, answer, failed)
        }, { _, handle, _, callback ->
            if (validationThrows) { handle.release(); error("sensitive validation") }
            validation = { response -> handle.release(); callback(response) }
        })
        fun animation(): SettingsPreparedProfileAnimation = SettingsPreparedProfileAnimation(
            "never-probed-synthetic.mp4", 42, 800, 2_000, 1.25, "a".repeat(64),
            ownedAttachmentLease { releases++ },
        )
        fun upload(userId: Long = 43, photoId: Long? = 91, animation: SettingsPreparedProfileAnimation = animation()) =
            api.upload(userId, photoId, animation, animation.lease, { active }, results::add)
        fun validated(response: SettingsResult<Unit> = SettingsResult(Unit)) = assertNotNull(validation)(response)
        fun me(id: Long = 43, photo: Long? = 91) = requests.last().answer(TdApi.User().apply {
            this.id = id
            profilePhoto = photo?.let { TdApi.ProfilePhoto().apply { this.id = it } }
        })
    }
    private data class Request(
        val command: TdApi.Function<*>, val answer: (TdApi.Object) -> Unit, val failed: () -> Unit,
    )
}
