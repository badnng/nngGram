package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsStorageTest {
    @Test fun exactTypeBucketsAreAggregatedWithoutReadingChatsOrDownloadingFiles() {
        val fixture = Fixture()
        var result: SettingsResult<SettingsStorageDetails>? = null
        fixture.api.storageDetails({ true }) { result = it }
        assertEquals(0, assertIs<TdApi.GetStorageStatistics>(fixture.requests.single().command).chatLimit)
        fixture.requests.single().reply(TdApi.StorageStatistics(45, 4, arrayOf(
            TdApi.StorageStatisticsByChat(0, 30, 3, arrayOf(
                TdApi.StorageStatisticsByFileType(TdApi.FileTypePhoto(), 20, 2),
                TdApi.StorageStatisticsByFileType(TdApi.FileTypeSecretThumbnail(), 10, 1))),
            TdApi.StorageStatisticsByChat(77, 15, 1, arrayOf(TdApi.StorageStatisticsByFileType(TdApi.FileTypePhoto(), 15, 1))),
        )))
        val value = assertNotNull(result?.value)
        assertEquals(45L, value.bytes)
        assertEquals(4, value.count)
        assertEquals(SettingsStorageCategory(SettingsStorageFileType.PHOTO, 35, 3), value.categories.first())
        assertFalse(value.categories.single { it.type == SettingsStorageFileType.SECRET_THUMBNAIL }.type.removable)
        assertEquals(1, fixture.requests.size)
    }

    @Test fun selectedClearUsesExactNativeTypesAndNeverEmptyDefaultAllTypes() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        fixture.api.clearStorage(emptySet(), { true }, results::add)
        fixture.api.clearStorage(setOf(SettingsStorageFileType.SECRET_THUMBNAIL), { true }, results::add)
        assertTrue(fixture.requests.isEmpty())
        assertEquals(List(2) { SettingsError.INVALID_VALUE }, results.map { it.error })
        fixture.api.clearStorage(setOf(SettingsStorageFileType.PHOTO, SettingsStorageFileType.DOCUMENT), { true }, results::add)
        val command = assertIs<TdApi.OptimizeStorage>(fixture.requests.single().command)
        assertEquals(0L, command.size)
        assertEquals(0, command.ttl)
        assertEquals(0, command.immunityDelay)
        assertEquals(setOf(TdApi.FileTypePhoto.CONSTRUCTOR, TdApi.FileTypeDocument.CONSTRUCTOR), command.fileTypes.map { it.constructor }.toSet())
        assertTrue(command.chatIds.isEmpty())
        assertTrue(command.excludeChatIds.isEmpty())
        assertFalse(command.returnDeletedFileStatistics)
        fixture.requests.single().reply(TdApi.StorageStatistics(0, 0, emptyArray()))
        assertEquals(Unit, results.last().value)
    }

    @Test fun malformedOrIncompleteNativeCountsNeverBecomeFabricatedZeroCategories() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsStorageDetails>>()
        for (stats in listOf(
            TdApi.StorageStatistics(-1, 0, emptyArray()),
            TdApi.StorageStatistics(5, 1, emptyArray()),
            TdApi.StorageStatistics(1, 1, arrayOf(TdApi.StorageStatisticsByChat(0, 1, 1,
                arrayOf(TdApi.StorageStatisticsByFileType(TdApi.FileTypePhoto(), -1, 1))))),
        )) {
            fixture.api.storageDetails({ true }, results::add)
            fixture.requests.last().reply(stats)
        }
        assertEquals(List(3) { SettingsError.REQUEST_FAILED }, results.map { it.error })
        assertTrue(results.all { it.value == null })
    }

    @Test fun retiredAndDuplicateCallbacksCannotExposeOldAccountResultsOrStartWrites() {
        val fixture = Fixture()
        var active = true
        val results = mutableListOf<SettingsResult<SettingsStorageDetails>>()
        fixture.api.storageDetails({ active }, results::add)
        active = false
        val pending = fixture.requests.single()
        pending.reply(TdApi.StorageStatistics(0, 0, emptyArray()))
        pending.reply(TdApi.Error(500, "private native text"))
        assertEquals(1, results.size)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        fixture.api.clearStorage(setOf(SettingsStorageFileType.PHOTO), { false }) { assertEquals(SettingsError.NOT_READY, it.error) }
        assertEquals(1, fixture.requests.size)
    }

    private class Fixture {
        data class Request(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit)
        val requests = mutableListOf<Request>()
        val api = OfficialTdSettingsStorage { command, reply, _ -> requests += Request(command, reply) }
    }
}
