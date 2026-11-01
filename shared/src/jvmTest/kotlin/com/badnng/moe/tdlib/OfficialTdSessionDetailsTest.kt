package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsSessionsData
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The only input is a synthetic TDLib response, never an account or real address. */
class OfficialTdSessionDetailsTest {
    @Test fun nativeLoginAndAuthorizationFieldsAndReturnedOrderArePreserved() {
        var command: TdApi.Function<*>? = null
        var reply: ((TdApi.Object) -> Unit)? = null
        val api = OfficialTdSettingsSecurity { request, response, _ -> command = request; reply = response }
        var result: SettingsResult<SettingsSessionsData>? = null
        api.sessions { result = it }
        assertIs<TdApi.GetActiveSessions>(command)
        val first = native(-11, "First", login = 1_600_000_000, last = 10, pending = true)
        val current = native(0, "Current", login = 0, last = 200, current = true)
        val last = native(-12, "Last", login = -1, last = 10, unconfirmed = true)
        assertNotNull(reply)(TdApi.Sessions(arrayOf(first, current, last), 180))
        val entries = assertNotNull(result?.value).entries
        assertEquals(listOf(-11L, 0L, -12L), entries.map { it.id })
        assertEquals(listOf(10, 200, 10), entries.map { it.lastActiveDate })
        assertEquals(listOf(1_600_000_000, 0, -1), entries.map { it.logInDate })
        assertTrue(entries[0].isPasswordPending)
        assertFalse(entries[0].isUnconfirmed)
        assertFalse(entries[1].isPasswordPending)
        assertTrue(entries[2].isUnconfirmed)
        assertEquals("192.0.2.9", entries[0].ipAddress)
        assertFalse(entries.toString().contains("192.0.2.9"), "Session diagnostics remain redacted")
    }

    private fun native(id: Long, model: String, login: Int, last: Int, current: Boolean = false,
        pending: Boolean = false, unconfirmed: Boolean = false) = TdApi.Session().apply {
        this.id = id
        isCurrent = current
        deviceModel = model
        applicationName = "Synthetic client"
        applicationVersion = "1.0"
        platform = "Synthetic platform"
        systemVersion = "10"
        logInDate = login
        lastActiveDate = last
        ipAddress = "192.0.2.9" // RFC 5737 documentation address, not account data.
        location = "Synthetic location"
        isPasswordPending = pending
        isUnconfirmed = unconfirmed
    }
}
