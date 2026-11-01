package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsProfileColorsHotLockTest {
    @Test fun actualOkAfterPageRevocationIsAcceptedAndDoesNotReadBack() {
        val f = Fixture(); f.names(); val data = f.load()
        var active = true
        val results = mutableListOf<SettingsProfileColorMutationResult>()
        f.api.save(data, SettingsProfileColorPart.NAME, SettingsProfileColorValue(8), false, { active }, results::add)
        f.answerMe()
        val write = f.last
        assertIs<TdApi.SetAccentColor>(write.command)
        active = false
        val count = f.requests.size
        write.answer(TdApi.Ok())
        assertTrue(results.single().locallyAccepted)
        assertNull(results.single().actual)
        assertEquals(count, f.requests.size)
        assertEquals(1, f.writes())
        write.answer(TdApi.Error(400, "late duplicate"))
        assertEquals(1, results.size)
    }

    @Test fun actualErrorAfterRevocationClearsNativeOwnerAndKeepsRejection() {
        val f = Fixture(); f.names(); val data = f.load()
        var active = true
        val results = mutableListOf<SettingsProfileColorMutationResult>()
        f.api.save(data, SettingsProfileColorPart.NAME, SettingsProfileColorValue(8), false, { active }, results::add)
        f.answerMe(); val write = f.last
        active = false
        write.answer(TdApi.Error(429, "synthetic rate limit"))
        assertFalse(results.single().locallyAccepted)
        assertEquals(SettingsError.RATE_LIMITED, results.single().error)
        val reads = f.requests.size
        active = true
        f.api.save(data, SettingsProfileColorPart.NAME, SettingsProfileColorValue(8), false, { active }) {}
        assertEquals(reads + 1, f.requests.size) // owner released: a fresh validation read, no write replay
        assertIs<TdApi.GetMe>(f.last.command)
        assertEquals(1, f.writes())
    }

    @Test fun missingPhysicalResultIsIndeterminateRatherThanNativeRejection() {
        val f = Fixture(); f.names(); val data = f.load()
        var receipt: SettingsSpecialWriteReceipt? = null
        val results = mutableListOf<SettingsProfileColorMutationResult>()
        f.api.save(data, SettingsProfileColorPart.NAME, SettingsProfileColorValue(8), false, { true }) {
            receipt = SettingsSpecialMutationContext.receipt(); results += it
        }
        f.answerMe(); f.last.failed()
        assertEquals(SettingsSpecialWriteOutcome.INDETERMINATE, receipt?.outcome)
        assertFalse(results.single().locallyAccepted)
        val write = f.last
        f.api.retire() // retirement cannot invent a physical terminal
        write.answer(TdApi.Ok())
        assertEquals(2, results.size)
        assertTrue(results.last().locallyAccepted)
        assertEquals(1, f.writes())
    }

    private class Fixture {
        data class Request(val command: TdApi.Function<*>, val answer: (TdApi.Object) -> Unit, val failed: () -> Unit)
        val requests = mutableListOf<Request>()
        val api = OfficialTdSettingsProfileColors { command, answer, failed -> requests += Request(command, answer, failed) }
        val last get() = requests.last()
        fun answerMe(me: TdApi.User = user()) { assertIs<TdApi.GetMe>(last.command); last.answer(me) }
        fun names() { api.accept(TdApi.UpdateAccentColors(arrayOf(color(8), color(9)), intArrayOf(9, 0, 8))) }
        fun load(me: TdApi.User = user()): SettingsProfileColorsData {
            var result: SettingsResult<SettingsProfileColorsData>? = null
            api.load(43, { true }) { result = it }; answerMe(me)
            return assertNotNull(result?.value)
        }
        fun save(data: SettingsProfileColorsData, desired: SettingsProfileColorValue,
            part: SettingsProfileColorPart = SettingsProfileColorPart.NAME, replaceGift: Boolean = false,
        ): MutableList<SettingsProfileColorMutationResult> {
            val results = mutableListOf<SettingsProfileColorMutationResult>()
            api.save(data, part, desired, replaceGift, { true }, results::add); return results
        }
        fun writes() = requests.count { it.command is TdApi.SetAccentColor || it.command is TdApi.SetProfileAccentColor }
    }

    companion object {
        private fun color(id: Int) = TdApi.AccentColor(id, 0, intArrayOf(1, 2), intArrayOf(3), 0)
        private fun user() = TdApi.User().apply {
            id = 43; type = TdApi.UserTypeRegular(); haveAccess = true; isPremium = true
            accentColorId = 0; profileAccentColorId = 17
        }
        private fun gift() = TdApi.UpgradedGiftColors(1, 2, 3, 4, intArrayOf(5), 6, intArrayOf(7))
        private fun sticker(emojiId: Long, fileId: Int) = TdApi.Sticker().apply {
            id = fileId.toLong(); setId = 77; width = 100; height = 100; emoji = "x"
            format = TdApi.StickerFormatWebp(); fullType = TdApi.StickerFullTypeCustomEmoji(emojiId, false)
            sticker = TdApi.File().apply { id = fileId }
        }
    }
}
