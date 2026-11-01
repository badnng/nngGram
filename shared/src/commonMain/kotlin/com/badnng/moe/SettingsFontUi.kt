package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/** Inline editor remains scrollable in narrow/short settings pages and uses the real Miuix controls. */
@Composable
internal fun SettingsFontControl(
    preferences: SettingsPreferences,
    onSave: (SettingsChatFont) -> Boolean,
    enabled: Boolean = true,
    session: SettingsFontEditorSession = remember { SettingsFontEditorSession() },
) {
    val service = LocalSettingsFontService.current
    var catalog by remember { mutableStateOf<SettingsFontCatalog?>(null) }
    var catalogFailed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(SettingsFontLoad()) }
    val current = LocalSettingsFontLoad.current
    val editing = session.editor
    val chosen = editing?.draft

    LaunchedEffect(editing, service, enabled) {
        if (editing != null && enabled) {
            catalog = null
            catalogFailed = false
            try {
                val result = service.catalog()
                coroutineContext.ensureActive()
                catalog = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { coroutineContext.ensureActive(); catalogFailed = true }
        }
    }
    LaunchedEffect(chosen, service, enabled) {
        if (chosen != null && enabled) preview = loadSettingsFontSafely(service, chosen)
    }
    SettingsGroup("字体") {
        SettingsRow("字体家族", settingsFontLabel(preferences.chatFont), enabled = enabled) {
            session.open(preferences.chatFont)
        }
        fontLoadNotice(current)?.let { SettingsNote(it, error = current.status == SettingsFontLoadStatus.FAILED) }
        if (editing != null && chosen != null) {
            val loaded = preview.takeIf { it.requested == chosen }
                ?: SettingsFontLoad(chosen, SettingsFontLoadStatus.LOADING)
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(session.query, { session.query = it.take(256) }, label = "搜索已安装字体", enabled = enabled,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                SettingsRow("默认（Open Sans）", if (chosen.mode == SettingsChatFontMode.DEFAULT_OPEN_SANS) "预览中" else null,
                    enabled = enabled) { editing.choose(SettingsChatFont()); session.saveFailed = false }
                SettingsRow("系统默认", if (chosen.mode == SettingsChatFontMode.SYSTEM_DEFAULT) "预览中" else null,
                    enabled = enabled) { editing.choose(SettingsChatFont(SettingsChatFontMode.SYSTEM_DEFAULT)); session.saveFailed = false }
                when {
                    catalogFailed -> SettingsNote("无法读取系统字体；可以取消后重新打开。", error = true)
                    catalog == null -> SettingsNote("正在读取系统字体…")
                    else -> {
                        val filtered = remember(catalog, session.query) {
                            catalog!!.families.filter { it.contains(session.query.trim(), ignoreCase = true) }
                        }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 208.dp)) {
                            items(filtered, key = { it }) { family ->
                                SettingsRow(family, if (chosen.familyName == family) "预览中" else null, enabled = enabled) {
                                    editing.choose(SettingsChatFont(SettingsChatFontMode.INSTALLED, family)); session.saveFailed = false
                                }
                            }
                        }
                        if (filtered.isEmpty()) SettingsNote("没有匹配的已安装字体。")
                        if (catalog!!.truncated) SettingsNote("字体数量超过显示上限，仅显示前 4096 个家族。")
                    }
                }
                fontLoadNotice(loaded)?.let { SettingsNote(it, error = loaded.status == SettingsFontLoadStatus.FAILED) }
                val fallback = bundledChatMessageFontFamily()
                Text("The quick brown fox 0123456789\n中文消息预览 🙂 · Aa Bb Cc",
                    style = chatMessageTextStyle().copy(fontFamily = loaded.family ?: fallback))
                if (session.saveFailed) SettingsNote("字体设置保存失败，当前字体未更改。", error = true)
                SettingsAction("取消字体更改", enabled) { session.cancel() }
                SettingsAction("保存字体", enabled && loaded.status in setOf(SettingsFontLoadStatus.DEFAULT, SettingsFontLoadStatus.READY)) {
                    if (editing.save(onSave)) session.cancel()
                    else session.saveFailed = true
                }
            }
        }
    }
}

internal fun fontLoadNotice(load: SettingsFontLoad): String? = when (load.status) {
    SettingsFontLoadStatus.DEFAULT, SettingsFontLoadStatus.READY -> null
    SettingsFontLoadStatus.LOADING -> "正在加载字体，暂用默认字体。"
    SettingsFontLoadStatus.MISSING -> "字体“${settingsFontLabel(load.requested)}”不可用；保留原选择，当前使用默认字体。"
    SettingsFontLoadStatus.FAILED -> "字体加载失败；保留原选择，当前使用默认字体。"
}
