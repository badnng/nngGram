package com.badnng.moe.tdlib

import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.time.LocalDate
import kotlin.test.*

class OfficialTdSettingsProfileExtrasTest {
    @Test fun birthdayWriteNeverResolvesAnUnrelatedExistingPersonalChannel() {
        val f = Fixture()
        var result: SettingsResult<Unit>? = null
        f.api.setBirthdate(43, SettingsBirthdate(4, 5), SettingsBirthdate(6, 5), { true }) { result = it }
        f.me()
        f.last.answer(TdApi.UserFullInfo().apply { birthdate = TdApi.Birthdate(4, 5, 0); personalChatId = -77 })
        val write = assertIs<TdApi.SetBirthdate>(f.last.command)
        assertEquals(6, write.birthdate.day)
        assertTrue(f.requests.none { it.command is TdApi.GetChat || it.command is TdApi.GetSuitablePersonalChats || it.command is TdApi.GetSupergroup })
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, result?.value)
        assertEquals(3, f.requests.size)
    }
    @Test fun currentPersonalChannelReadsOnlyItsActualMetadataAndNeverLoadsTheCandidateList() {
        val f = Fixture()
        var result: SettingsResult<SettingsProfileExtrasData>? = null
        f.api.load(43, { true }) { result = it }
        f.me()
        f.last.answer(TdApi.UserFullInfo().apply { personalChatId = -77 })
        assertEquals(-77L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.answer(TdApi.Chat().apply { id = -77; title = "Actual current native channel"; type = TdApi.ChatTypeSupergroup(771, true) })
        assertEquals(-77L, result?.value?.personalChatId)
        assertEquals("Actual current native channel", result?.value?.personalChannel?.title)
        assertTrue(f.requests.none { it.command is TdApi.GetSuitablePersonalChats || it.command is TdApi.GetSupergroup })
        assertEquals(3, f.requests.size)
    }
    @Test fun failedCurrentChannelResolutionPreservesTheActualIdAndAnExplicitUnknownTitle() {
        val f = Fixture()
        var result: SettingsResult<SettingsProfileExtrasData>? = null
        f.api.load(43, { true }) { result = it }
        f.me()
        f.last.answer(TdApi.UserFullInfo().apply { personalChatId = -77 })
        f.last.answer(TdApi.Error(500, "private native metadata failure"))
        assertEquals(-77L, result?.value?.personalChatId)
        assertNull(result?.value?.personalChannel)
        assertEquals(SettingsError.REQUEST_FAILED, result?.value?.personalChannelError)
        assertFalse(result.toString().contains("private native"))
    }
    @Test fun loadUsesActualSelfAndFullInfoWithoutDownloadingAnyAvatar() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsProfileExtrasData>>()
        f.api.load(43, { true }, results::add)
        assertIs<TdApi.GetMe>(f.last.command)
        f.me(photoId = -923)
        assertEquals(43L, assertIs<TdApi.GetUserFullInfo>(f.last.command).userId)
        f.last.answer(TdApi.UserFullInfo().apply { birthdate = TdApi.Birthdate(29, 2, 0) })
        val data = assertNotNull(results.single().value)
        assertEquals(-923L, data.photoId)
        assertEquals(SettingsBirthdate(29, 2, 0), data.birthdate)
        assertEquals(SettingsCalendarDate(25, 10, 2026), data.today)
        assertEquals(2, f.requests.size)
        assertFalse(data.toString().contains("923"))
    }
    @Test fun wrongAccountAndRetiredOwnerStopAllChainedRequests() {
        val f = Fixture()
        var active = true
        val results = mutableListOf<SettingsResult<SettingsProfileExtrasData>>()
        f.api.load(43, { active }, results::add)
        f.last.answer(TdApi.User().apply { id = 999 })
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertEquals(1, f.requests.size)
        f.api.load(43, { active }, results::add)
        active = false
        f.me()
        assertEquals(SettingsError.NOT_READY, results.last().error)
        assertEquals(2, f.requests.size)
    }
    @Test fun validHiddenYearAndNullRemovalAreDifferentExplicitNativeRequests() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.api.setBirthdate(43, null, SettingsBirthdate(29, 2), { true }, results::add)
        f.birthdate(null)
        val set = assertIs<TdApi.SetBirthdate>(f.last.command)
        assertEquals(0, set.birthdate.year)
        assertEquals(29, set.birthdate.day)
        assertTrue(results.isEmpty())
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, results.single().value)
        f.api.setBirthdate(43, SettingsBirthdate(29, 2), null, { true }, results::add)
        f.birthdate(TdApi.Birthdate(29, 2, 0))
        assertNull(assertIs<TdApi.SetBirthdate>(f.last.command).birthdate)
        f.last.answer(TdApi.Ok())
        assertEquals(2, results.size)
    }
    @Test fun invalidDatesCannotReachTdlibSetOrAccidentallyRemoveBirthday() {
        val invalid = listOf(SettingsBirthdate(31, 4), SettingsBirthdate(29, 2, 2025), SettingsBirthdate(0, 2),
            SettingsBirthdate(1, 13), SettingsBirthdate(26, 10, 2026), SettingsBirthdate(1, 1, 1500))
        invalid.forEach { value ->
            val f = Fixture()
            var result: SettingsResult<Unit>? = null
            f.api.setBirthdate(43, null, value, { true }) { result = it }
            f.birthdate(null)
            assertEquals(SettingsError.INVALID_VALUE, result?.error, value.toString())
            assertTrue(f.requests.none { it.command is TdApi.SetBirthdate })
        }
    }
    @Test fun remoteBirthdayChangeFailsWithConflictWithoutOverwritingIt() {
        val f = Fixture()
        var result: SettingsResult<Unit>? = null
        f.api.setBirthdate(43, SettingsBirthdate(4, 5, 1990), SettingsBirthdate(5, 5, 1990), { true }) { result = it }
        f.birthdate(TdApi.Birthdate(6, 5, 1990))
        assertEquals(SettingsError.CONFLICT, result?.error)
        assertTrue(f.requests.none { it.command is TdApi.SetBirthdate })
    }
    @Test fun staticPhotoIsPrivateAndReportsOnlyOriginalNativeAcknowledgementOnce() {
        val f = Fixture()
        val photo = f.photo()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.api.uploadPhoto(43, 91, photo, { true }, results::add)
        f.me(91)
        val request = f.last
        val set = assertIs<TdApi.SetProfilePhoto>(request.command)
        assertFalse(set.isPublic)
        assertEquals("synthetic-private-photo.jpg", assertIs<TdApi.InputFileLocal>(assertIs<TdApi.InputChatPhotoStatic>(set.photo).photo).path)
        assertTrue(results.isEmpty())
        request.answer(TdApi.Error(429, "private account info"))
        request.answer(TdApi.Ok()); request.failed()
        assertEquals(SettingsError.RATE_LIMITED, results.single().error)
        assertFalse(results.toString().contains("private account"))
        photo.lease.release()
    }
    @Test fun changedPreviewBytesOrCurrentPhotoCannotBeUploadedAsEarlierChoice() {
        val f = Fixture()
        val photo = f.photo()
        var result: SettingsResult<Unit>? = null
        f.photoValid = false
        f.api.uploadPhoto(43, 91, photo, { true }) { result = it }
        assertEquals(SettingsError.INVALID_VALUE, result?.error)
        assertTrue(f.requests.isEmpty())
        f.photoValid = true
        f.api.uploadPhoto(43, 91, photo, { true }) { result = it }
        f.me(92)
        assertEquals(SettingsError.CONFLICT, result?.error)
        assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })
        photo.lease.release()
    }
    @Test fun removalRevalidatesAndPreservesSignedNativePhotoIdentity() {
        val f = Fixture()
        var result: SettingsResult<Unit>? = null
        f.api.removePhoto(43, -847, { true }) { result = it }
        f.me(-847)
        assertEquals(-847L, assertIs<TdApi.DeleteProfilePhoto>(f.last.command).profilePhotoId)
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, result?.value)
        f.api.removePhoto(43, -847, { true }) { result = it }
        f.me(-848)
        assertEquals(SettingsError.CONFLICT, result?.error)
        assertEquals(1, f.requests.count { it.command is TdApi.DeleteProfilePhoto })
    }

    private class Request(val command: TdApi.Function<*>, val answer: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        val requests = mutableListOf<Request>()
        val last get() = requests.last()
        var photoValid = true
        val api = OfficialTdSettingsProfileExtras({ request, done, fail -> requests += Request(request, done, fail) },
            today = { LocalDate.of(2026, 10, 25) }, validatePhoto = { photoValid })
        fun me(photoId: Long? = null) { last.answer(TdApi.User().apply { id = 43
            profilePhoto = photoId?.let { nativeId -> TdApi.ProfilePhoto().apply { id = nativeId } }
        }) }
        fun birthdate(value: TdApi.Birthdate?) { me(); last.answer(TdApi.UserFullInfo().apply { birthdate = value }) }
        fun photo() = SettingsPreparedProfilePhoto("synthetic-private-photo.jpg", 512, 32, "00".repeat(32), ownedAttachmentLease {})
    }
}
