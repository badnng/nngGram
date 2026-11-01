package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsUsernameAvailability
import com.badnng.moe.settings.SettingsUsernameAvailabilityStatus
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfficialTdUsernameAvailabilityTest {
    @Test fun freshSelfIdIsUsedForTheNativeCheckAndOkRetainsItsNativeMeaning() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        fixture.api.check(43, "new_name", { true }, results::add)

        assertIs<TdApi.GetMe>(fixture.last.command)
        fixture.me(id = 43, editable = "old_name")
        val native = assertIs<TdApi.CheckChatUsername>(fixture.last.command)
        assertEquals(43L, native.chatId)
        assertFalse(native.chatId == 0L)
        assertEquals("new_name", native.username)
        assertTrue(results.isEmpty())

        fixture.last.answer(TdApi.CheckChatUsernameResultOk())
        assertEquals(SettingsUsernameAvailabilityStatus.CAN_BE_SET, assertNotNull(results.single().value).status)
    }

    @Test fun freshWrongSelfStopsBeforeCallingCheckChatUsername() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        fixture.api.check(43, "new_name", { true }, results::add)
        fixture.me(id = 99, editable = "")

        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.requests.none { it.command is TdApi.CheckChatUsername })
    }

    @Test fun exactCurrentEditableUsernameIsOwnedBySelfAndNeedsNoAvailabilityRequest() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        fixture.api.check(43, "my_name", { true }, results::add)
        fixture.me(id = 43, editable = "my_name")

        assertEquals(SettingsUsernameAvailabilityStatus.CURRENT_USERNAME, assertNotNull(results.single().value).status)
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.requests.none { it.command is TdApi.CheckChatUsername })
        assertFalse(results.toString().contains("my_name"))
    }

    @Test fun caseOnlyCurrentNameVariantUsesTheExactNativeQueryAndResult() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        fixture.api.check(43, "MY_Name", { true }, results::add)
        fixture.me(id = 43, editable = "my_name")

        val native = assertIs<TdApi.CheckChatUsername>(fixture.last.command)
        assertEquals(43L, native.chatId)
        assertEquals("MY_Name", native.username)
        fixture.last.answer(TdApi.CheckChatUsernameResultUsernameOccupied())
        assertEquals(SettingsUsernameAvailabilityStatus.OCCUPIED, assertNotNull(results.single().value).status)
        assertEquals(2, fixture.requests.size)
    }

    @Test fun everyPinnedNativeResultRemainsDistinctIncludingPurchasableAndLimits() {
        val cases = listOf(
            TdApi.CheckChatUsernameResultOk() to SettingsUsernameAvailabilityStatus.CAN_BE_SET,
            TdApi.CheckChatUsernameResultUsernameInvalid() to SettingsUsernameAvailabilityStatus.INVALID,
            TdApi.CheckChatUsernameResultUsernameOccupied() to SettingsUsernameAvailabilityStatus.OCCUPIED,
            TdApi.CheckChatUsernameResultUsernamePurchasable() to SettingsUsernameAvailabilityStatus.PURCHASABLE,
            TdApi.CheckChatUsernameResultPublicChatsTooMany() to SettingsUsernameAvailabilityStatus.PUBLIC_CHATS_TOO_MANY,
            TdApi.CheckChatUsernameResultPublicGroupsUnavailable() to SettingsUsernameAvailabilityStatus.PUBLIC_GROUPS_UNAVAILABLE,
        )

        cases.forEach { (native, expected) ->
            val fixture = Fixture()
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            fixture.api.check(43, "candidate", { true }, results::add)
            fixture.me(id = 43, editable = "current")
            fixture.last.answer(native)
            assertEquals(expected, assertNotNull(results.single().value).status)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun futureNativeSubtypeIsUnknownRatherThanCanBeSet() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        fixture.api.check(43, "future_case", { true }, results::add)
        fixture.me(id = 43, editable = "current")
        fixture.last.answer(object : TdApi.CheckChatUsernameResult() {
            override fun getConstructor() = Int.MIN_VALUE
        })

        assertEquals(SettingsUsernameAvailabilityStatus.UNKNOWN, assertNotNull(results.single().value).status)
    }

    @Test fun staleQueryAtAdmissionOrBetweenNativeStepsCannotPublishAResult() {
        val first = Fixture()
        val ignored = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        first.api.check(43, "candidate", { false }, ignored::add)
        assertEquals(SettingsError.NOT_READY, ignored.single().error)
        assertTrue(first.requests.isEmpty())

        val second = Fixture()
        var current = true
        val stale = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        second.api.check(43, "candidate", { current }, stale::add)
        current = false
        second.me(id = 43, editable = "current")
        assertEquals(SettingsError.NOT_READY, stale.single().error)
        assertEquals(1, second.requests.size)
        assertTrue(second.requests.none { it.command is TdApi.CheckChatUsername })
    }

    @Test fun nativeErrorIsSanitizedDuplicateCallbacksAreIgnoredAndWrongObjectsFail() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        fixture.api.check(43, "candidate", { true }, results::add)
        fixture.me(id = 43, editable = "current")
        fixture.last.answer(TdApi.Error(400, "private native username details"))
        fixture.last.answer(TdApi.CheckChatUsernameResultOk())
        fixture.last.fail()

        assertEquals(1, results.size)
        assertEquals(SettingsError.INVALID_VALUE, results.single().error)
        assertFalse(results.toString().contains("private native username details"))

        val malformed = Fixture()
        val malformedResults = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        malformed.api.check(43, "candidate", { true }, malformedResults::add)
        malformed.me(id = 43, editable = "current")
        malformed.last.answer(TdApi.Ok())
        assertEquals(SettingsError.REQUEST_FAILED, malformedResults.single().error)
    }

    private class Request(
        val command: TdApi.Function<*>,
        private val callback: (TdApi.Object) -> Unit,
        private val failure: () -> Unit,
    ) {
        fun answer(value: TdApi.Object) = callback(value)
        fun fail() = failure()
    }

    private class Fixture {
        val requests = mutableListOf<Request>()
        val api = OfficialTdUsernameAvailability { command, callback, failed ->
            requests += Request(command, callback, failed)
        }
        val last get() = requests.last()

        fun me(id: Long, editable: String) {
            last.answer(TdApi.User().apply {
                this.id = id
                usernames = TdApi.Usernames().apply { editableUsername = editable }
            })
        }
    }
}
