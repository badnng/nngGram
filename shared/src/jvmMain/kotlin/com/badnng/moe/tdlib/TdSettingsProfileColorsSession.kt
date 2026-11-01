package com.badnng.moe.tdlib

import com.badnng.moe.settings.*

interface TdSettingsProfileColorsSession {
    fun observe(observer: (() -> Unit)?)
    fun catalog(): SettingsProfileColorsCatalog
    fun load(userId: Long, active: () -> Boolean, result: (SettingsResult<SettingsProfileColorsData>) -> Unit)
    fun save(expected: SettingsProfileColorsData, part: SettingsProfileColorPart, desired: SettingsProfileColorValue,
        replaceGift: Boolean, active: () -> Boolean, result: (SettingsProfileColorMutationResult) -> Unit)
    fun backgroundEmojis(active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit)
    fun resolveEmojis(ids: List<Long>, active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit)
    fun retire()
}
