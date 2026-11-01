package com.badnng.moe.tdlib

import com.badnng.moe.notifications.*
import com.badnng.moe.settings.*
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class NotificationSoundBootstrapTest {
    @Test fun firstSavedSoundUpdateSeedsTheCatalogWithoutInvalidatingItsOwnNegativeIdPlayback() {
        val requests = mutableListOf<Pending>()
        val catalog = OfficialTdSavedNotificationSounds { command, result, failed ->
            requests += Pending(command, result, failed)
        }
        val encoded = byteArrayOf(1, 2, 3, 4)
        val expectedSamples = ByteArray(192) { (it - 96).toByte() }
        val files = FilesFake(encoded)
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var decodes = 0
        val source = OfficialNotificationSoundSource(catalog, files, worker, Dispatchers.Unconfined) { bytes, duration, active ->
            decodes++
            assertContentEquals(encoded, bytes)
            assertEquals(NotificationSoundPcm.MAX_DURATION_MILLIS, duration)
            if (active()) SettingsResult(NotificationSoundPcm(expectedSamples))
            else SettingsResult(error = SettingsError.NOT_READY)
        }
        var invalidations = 0
        catalog.observe { invalidations++; source.invalidate() }
        val results = mutableListOf<SettingsResult<PreparedNotificationAudio>>()

        try {
            source.load(1, -22, { true }, results::add)
            assertIs<TdApi.GetMe>(requests[0].command)
            requests[0].reply(user())
            assertIs<TdApi.GetSavedNotificationSounds>(requests[1].command)

            // TDLib can publish the first list revision while its GetSavedNotificationSounds is pending.
            catalog.accept(TdApi.UpdateSavedNotificationSounds(longArrayOf(-22, -99)))
            assertEquals(0, invalidations, "The initial ID set establishes the catalog baseline.")
            requests[1].reply(TdApi.NotificationSounds(arrayOf(sound(-22, 73), sound(-99, 74))))
            val limits = listOf(1024L, 30L, 10L)
            for (index in limits.indices) {
                assertEquals(listOf("notification_sound_size_max", "notification_sound_duration_max", "notification_sound_count_max")[index],
                    assertIs<TdApi.GetOption>(requests[2 + index].command).name)
                requests[2 + index].reply(TdApi.OptionValueInteger(limits[index]))
            }

            assertEquals(1, files.reads, "Only the currently requested sound should acquire bytes.")
            assertEquals(-22L, files.expected?.id)
            assertEquals(73, files.expected?.file?.id)
            assertIs<TdApi.GetMe>(requests[5].command)
            requests[5].reply(user())
            assertEquals(-22L, assertIs<TdApi.GetSavedNotificationSound>(requests[6].command).notificationSoundId)
            requests[6].reply(sound(-22, 73))

            val prepared = assertNotNull(results.single().value)
            assertEquals(-22L, prepared.requestedSoundId)
            assertFalse(prepared.defaultFallback)
            assertContentEquals(expectedSamples, prepared.pcm.samples(100))
            assertEquals(1, decodes)
            assertEquals(1, files.closes)
            assertEquals(0, invalidations)
            assertTrue(requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile },
                "Catalog metadata must not issue unrelated TDLib file downloads.")
        } finally {
            source.close()
            worker.cancel()
        }
    }

    private class Pending(
        val command: TdApi.Function<*>,
        private val result: (TdApi.Object) -> Unit,
        private val failed: () -> Unit,
    ) {
        fun reply(value: TdApi.Object) = result(value)
        fun fail() = failed()
    }

    private class FilesFake(private val encoded: ByteArray) : TdNotificationSoundFilesSession {
        var reads = 0
        var closes = 0
        var expected: SavedNotificationSound? = null

        override fun read(accountUserId: Long, expected: SavedNotificationSound, maximumBytes: Int,
            active: () -> Boolean, result: (SettingsResult<NotificationSoundEncoded>) -> Unit,
        ): NotificationSoundFileLease {
            assertEquals(1L, accountUserId)
            assertTrue(active())
            reads++
            this.expected = expected
            result(SettingsResult(NotificationSoundEncoded(expected, encoded)))
            return object : NotificationSoundFileLease {
                override fun close() { closes++ }
            }
        }
    }

    private companion object {
        fun user() = TdApi.User().apply { id = 1; type = TdApi.UserTypeRegular() }

        fun sound(id: Long, fileId: Int) = TdApi.NotificationSound(id, 1, 123, "synthetic", "opaque",
            TdApi.File().apply {
                this.id = fileId; size = 4; expectedSize = 4
                local = TdApi.LocalFile()
                remote = TdApi.RemoteFile().apply { uniqueId = "sound-file-$fileId" }
            })
    }
}
