package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsProxy
import com.badnng.moe.settings.SettingsResult
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** TDLib response order is only this client's source order, not Desktop's stored history order. */
class OfficialTdSettingsProxyOrderTest {
    @Test
    fun enabledChangesDoNotPromoteARowInsideTheBoundedNativeList() {
        val ids = listOf(19, 2, 42, 7)
        for (selected in listOf<Int?>(null, 2, 7)) {
            val rows = read(ids.map { entry(it, it == selected) })
            assertEquals(ids, rows.map { it.id })
            assertEquals(selected, rows.singleOrNull { it.enabled }?.id)
        }
    }

    @Test
    fun overflowKeepsTheEnabledEntryWithoutReorderingTheRetainedSource() {
        val ids = (1..260).toList()
        for (selected in listOf<Int?>(null, 128, 256, 259)) {
            val rows = read(ids.map { entry(it, it == selected) })
            val expected = if (selected != null && selected > 256) ids.take(255) + selected else ids.take(256)
            assertEquals(expected, rows.map { it.id })
            assertEquals(selected, rows.singleOrNull { it.enabled }?.id)
        }
    }

    private fun read(entries: List<TdApi.AddedProxy>): List<SettingsProxy> {
        val outcomes = mutableListOf<SettingsResult<List<SettingsProxy>>>()
        val api = OfficialTdSettingsAdvanced { command, reply, _ ->
            assertIs<TdApi.GetProxies>(command)
            reply(TdApi.AddedProxies(entries.toTypedArray()))
        }
        api.proxies(outcomes::add)
        assertNull(outcomes.single().error)
        return assertNotNull(outcomes.single().value)
    }

    private fun entry(id: Int, enabled: Boolean) = TdApi.AddedProxy(id, 0, enabled, "",
        TdApi.Proxy("proxy-$id.invalid", 1080, TdApi.ProxyTypeSocks5("u", "p")))
}
