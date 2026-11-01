package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import com.badnng.moe.security.LocalLockWitness
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class OfficialTdSavedNotificationSoundsHotLockTest {
    @Test fun finalRemovalSuccessAndErrorSettleTheirRealReceiptAfterPageLossWithoutReadback() {
        for (accepted in listOf(true, false)) {
            val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
            f.api.remove(1, saved(sound()), f::active, results::add)
            f.last.reply(user()); f.last.reply(sound()); val final = f.last; val count = f.requests.size
            assertIs<TdApi.RemoveSavedNotificationSound>(final.command); f.enabled = false
            final.reply(if (accepted) TdApi.Ok() else TdApi.Error(403, "synthetic")); final.reply(TdApi.Ok())
            assertEquals(1, results.size)
            assertEquals(if (accepted) NotificationSettingsWriteReceipt.ACCEPTED else NotificationSettingsWriteReceipt.REJECTED, results.single().receipt)
            assertEquals(if (accepted) SettingsError.NOT_READY else SettingsError.FORBIDDEN, results.single().error)
            assertEquals(count, f.requests.size)
        }
    }

    @Test fun uncertainRemovalKeepsPhysicalQueueAndLateTerminalReleasesItWithoutARepeatedWrite() {
        val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
        f.api.remove(1, saved(sound()), f::active, results::add)
        f.last.reply(user()); f.last.reply(sound()); val final = f.last; val count = f.requests.size
        final.failed(); assertEquals(NotificationSettingsWriteReceipt.INDETERMINATE, results.single().receipt)
        f.api.remove(1, saved(sound()), f::active) { }
        assertEquals(count, f.requests.size)
        final.reply(TdApi.Error(403, "synthetic"))
        assertEquals(NotificationSettingsWriteReceipt.REJECTED, results.last().receipt)
        assertEquals(count + 1, f.requests.size)
        assertIs<TdApi.GetMe>(f.last.command)
        assertEquals(1, f.requests.count { it.command is TdApi.RemoveSavedNotificationSound })
    }

    @Test fun uploadAcceptsOnlyItsValidatedNotificationSoundAndUnknownObjectsKeepTheWriteUnresolved() = upload { staged ->
        for (unexpectedFirst in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
            f.api.add(data(), staged, f::active, results::add); f.loadSteps(emptyList())
            val final = f.last; val count = f.requests.size
            assertIs<TdApi.AddSavedNotificationSound>(final.command)
            if (unexpectedFirst) {
                final.reply(TdApi.Chats(0, longArrayOf()))
                assertEquals(NotificationSettingsWriteReceipt.INDETERMINATE, results.single().receipt)
                assertFalse(results.single().locallyAccepted)
            }
            f.enabled = false; final.reply(sound()); final.reply(sound())
            assertEquals(if (unexpectedFirst) 2 else 1, results.size)
            assertEquals(NotificationSettingsWriteReceipt.ACCEPTED, results.last().receipt)
            assertTrue(results.last().locallyAccepted)
            assertEquals(count, f.requests.size, "Typed success after page loss never starts readback.")
        }
    }

    @Test fun explicitNonDispatchReleasesTheMutationQueueWithoutClaimingNativeRejectionOrAcceptance() {
        val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
        f.api.remove(1, saved(sound()), f::active, results::add)
        f.denyFinal = true; f.last.reply(user()); f.last.reply(sound())
        assertEquals(NotificationSettingsWriteReceipt.NOT_DISPATCHED, results.single().receipt)
        // The real blocked gate proves non-dispatch but supplies no callback error.
        // This page remains active, so the adapter retains its REQUEST_FAILED fallback.
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        assertTrue(f.requests.none { it.command is TdApi.RemoveSavedNotificationSound })
        f.denyFinal = false; val count = f.requests.size
        f.api.remove(1, saved(sound()), f::active) { }
        assertEquals(count + 1, f.requests.size)
    }

    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        var enabled = true
        val requests = mutableListOf<Pending>()
        var denyFinal = false
        // Dispatch refusal comes from the real gate for the exact marked command;
        // an ordinary receipt label cannot prove raw native send was not entered.
        private val deniedFinalSend = TdUserInteractionGate().apply {
            bindLocalWitness { LocalLockWitness(1, true) }
        }.guard { command, reply, failed -> requests += Pending(command, reply, failed) }
        val api = OfficialTdSavedNotificationSounds { command, reply, failed ->
            if (denyFinal && command is TdApi.RemoveSavedNotificationSound) deniedFinalSend(command, reply, failed)
            else requests += Pending(command, reply, failed)
        }
        val last get() = requests.last()
        fun active() = enabled
        fun loadSteps(sounds: List<TdApi.NotificationSound>, options: List<TdApi.Object> = limits()) {
            assertIs<TdApi.GetMe>(last.command); last.reply(user())
            assertIs<TdApi.GetSavedNotificationSounds>(last.command); last.reply(TdApi.NotificationSounds(sounds.toTypedArray()))
            for ((index, value) in options.withIndex()) {
                assertEquals(listOf("notification_sound_size_max", "notification_sound_duration_max", "notification_sound_count_max")[index],
                    assertIs<TdApi.GetOption>(last.command).name)
                last.reply(value)
            }
        }
        fun names() = requests.map { it.command.javaClass.simpleName }
        fun assertMetadataOnly() { assertTrue(requests.all { it.command is TdApi.GetMe || it.command is TdApi.GetOption ||
            it.command is TdApi.GetSavedNotificationSounds || it.command is TdApi.GetSavedNotificationSound ||
            it.command is TdApi.AddSavedNotificationSound || it.command is TdApi.RemoveSavedNotificationSound }) }
    }
    private companion object {
        fun user(id: Long = 1) = TdApi.User().apply { this.id = id; type = TdApi.UserTypeRegular() }
        fun sound(id: Long = -2, fileId: Int = 42) = TdApi.NotificationSound(id, 1, 123, "synthetic sound", "opaque data",
            TdApi.File().apply { this.id = fileId; size = 16; expectedSize = 16
                local = TdApi.LocalFile(); remote = TdApi.RemoteFile().apply { uniqueId = "unique-$fileId" } })
        fun saved(value: TdApi.NotificationSound) = SavedNotificationSound(value.id, value.duration, value.date, value.title, value.data, mapMediaFile(value.sound))
        fun data() = SavedNotificationSoundsData(1, emptyList(), NotificationSoundLimits(1024, 2, 10))
        fun limits(bytes: Long = 1024, count: Long = 10): List<TdApi.Object> =
            listOf(TdApi.OptionValueInteger(bytes), TdApi.OptionValueInteger(2), TdApi.OptionValueInteger(count))
        fun upload(body: (PreparedNotificationSoundUpload) -> Unit) {
            val directory = Files.createTempDirectory("notification-sound-metadata-")
            val path = directory.resolve("staged.mp3")
            try { Files.write(path, ByteArray(16)); body(PreparedNotificationSoundUpload(path.toAbsolutePath().toString(), 16, 500)) }
            finally { Files.deleteIfExists(path); Files.deleteIfExists(directory) }
        }
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
}
