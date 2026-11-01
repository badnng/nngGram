package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Shared settings rows: measured columns and actual blue glyphs, including short values. */
class SettingsRowAlignmentUiTest {
    @Test fun mixedSettingsRowsKeepValueInkAtTheRightEdgeAndSummariesBelow() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) for (width in listOf(330, 620)) {
                var primary = 0
                ComposePixelScene((width * density).roundToInt(), (900 * density).roundToInt()) {
                    val theme = remember { ThemeController(mode) }
                    CompositionLocalProvider(LocalDensity provides Density(density)) {
                        MiuixTheme(controller = theme) {
                            primary = MiuixTheme.colorScheme.primary.toArgb()
                            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(16.dp)) {
                                SettingsRow("生日", "设置", "在个人资料中显示生日。", onClick = {})
                                SettingsRow("自动下载媒体", "已关闭", onClick = {})
                                SettingsRow(longTitle, longValue, longSummary, onClick = {})
                                SettingsRow(nullValueLabel, summary = "没有值时标题使用整行内容宽度。")
                                SettingsRow("选择附件", "Ctrl+O", "在聊天输入框内使用。")
                                SettingsRow("禁用的设备操作", "查看", "仍然保留说明。", enabled = false, onClick = {})
                            }
                        }
                    }
                }.use { scene ->
                    val frame = scene.settledFrame()
                    listOf("生日" to "设置", "自动下载媒体" to "已关闭", longTitle to longValue,
                        "选择附件" to "Ctrl+O", "禁用的设备操作" to "查看").forEach { (label, value) ->
                        val title = scene.text(label)
                        val current = scene.text(value)
                        val summaryText = when (label) {
                            "生日" -> "在个人资料中显示生日。"
                            longTitle -> longSummary
                            "选择附件" -> "在聊天输入框内使用。"
                            "禁用的设备操作" -> "仍然保留说明。"
                            else -> null
                        }
                        val leftBottom = summaryText?.let { scene.text(it).bottom } ?: title.bottom
                        assertTrue(abs((title.top + leftBottom) / 2f - current.center.y) <= 1f,
                            "$label: value centered against the complete title/summary column")
                        assertTrue(current.left >= title.right + 15 * density, "$label: separate columns")
                        assertTrue(abs(current.right - (width - 32) * density) <= 1f, "$label: right edge includes requested extra 4 dp inset")
                    }
                    // A full-width semantics box alone cannot establish TextAlign.End. Inspect real ink.
                    for (value in listOf("设置", "已关闭", "Ctrl+O")) {
                        val bounds = scene.text(value)
                        val layout = scene.textLayout(value)
                        assertEquals(1, layout.lineCount, "$value: short value stays on one line")
                        assertTrue(abs(layout.getLineRight(0) - layout.size.width) <= 1f,
                            "$value: the actual shaped text line ends at the column edge")
                        val ink = frame.primaryInk(bounds, primary)
                        // The shaped line above is strict. Painted core pixels additionally omit
                        // a glyph's side bearing and antialiased edge; CJK fallback can reserve
                        // up to half the 13 sp em here, plus one physical-pixel raster fringe.
                        val coreInkAllowance = 13f / 2f * density + 1f
                        assertTrue(bounds.right - ink.right <= coreInkAllowance,
                            "$value: blue core ink remains within half an em of the aligned line: $ink / $bounds")
                        assertTrue(ink.left > bounds.center.x, "$value: short value must not be painted at column start")
                    }
                    val longLabel = scene.text(longTitle)
                    val longCurrent = scene.text(longValue)
                    if (width == 330) {
                        assertTrue(longLabel.height > 30 * density, "Long title wraps")
                        assertTrue(longCurrent.height > 25 * density, "Long value wraps within its bounded column")
                    }
                    val summary = scene.text(longSummary)
                    assertTrue(summary.top >= longLabel.bottom + 4 * density)
                    assertEquals(longLabel.left, summary.left)
                    val noValue = scene.text(nullValueLabel)
                    assertTrue(abs(noValue.left - 28 * density) <= 1f)
                    assertTrue(abs(noValue.right - (width - 32) * density) <= 1f, "Null value leaves full width to title")
                    frame.saveAlignment("general-${mode.name.lowercase()}-$width-$density")
                }
            }
        }
    }

    @Test fun clickDisabledAndReadOnlyRowsKeepTheirDistinctSemantics() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        ComposePixelScene(400, 320) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                Column {
                    SettingsRow("可编辑", "管理", "操作说明", enabled = enabled.value, onClick = { clicks++ })
                    SettingsRow("只读", "原生值", "只读说明")
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val row = assertNotNull(scene.semanticsBounds { it.getOrNull(SemanticsActions.OnClick) != null }.singleOrNull())
            scene.pressAt(row.center.x, row.center.y)
            scene.releaseAt(row.center.x, row.center.y)
            scene.settledFrame()
            assertEquals(1, clicks)
            enabled.value = false
            scene.settledFrame()
            assertEquals(1, scene.semanticsBounds { it.getOrNull(SemanticsProperties.Disabled) != null }.size)
            scene.pressAt(row.center.x, row.center.y)
            scene.releaseAt(row.center.x, row.center.y)
            scene.settledFrame()
            assertEquals(1, clicks)
            val readOnly = scene.text("原生值")
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsActions.OnClick) != null }
                .none { it.contains(readOnly.center) }, "Read-only row must not expose an editor action")
            scene.text("操作说明")
            scene.text("只读说明")
        }
    }

    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.singleOrNull(), label)

    private fun ComposePixelScene.textLayout(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        unmergedSemanticsBounds {
            val matches = it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
            if (matches) it.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            matches
        }
        return assertNotNull(results.singleOrNull(), "$label: actual Compose text layout")
    }

    private fun ComposePixelFrame.primaryInk(bounds: Rect, primary: Int): Rect {
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        for (y in floor(bounds.top).toInt().coerceAtLeast(0) until ceil(bounds.bottom).toInt().coerceAtMost(height)) {
            for (x in floor(bounds.left).toInt().coerceAtLeast(0) until ceil(bounds.right).toInt().coerceAtMost(width)) {
                val pixel = this[x, y]
                if (listOf(0, 8, 16).all { shift -> abs(((pixel ushr shift) and 255) - ((primary ushr shift) and 255)) <= 24 }) {
                    xs += x
                    ys += y
                }
            }
        }
        assertTrue(xs.isNotEmpty(), "Value must paint blue glyphs")
        return Rect(xs.min().toFloat(), ys.min().toFloat(), xs.max() + 1f, ys.max() + 1f)
    }

    private fun ComposePixelFrame.saveAlignment(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val target = File(repository, "docs/screenshots/official-settings/settings-value-alignment/$name.png")
        target.parentFile.mkdirs()
        ImageIO.write(image, "png", target)
    }

    private companion object {
        const val longTitle = "很长的设备与自动下载设置标题需要自然换行而不与右侧值相互遮挡"
        const val longValue = "联系人和具有例外规则的群组成员，保持右侧独立列"
        const val longSummary = "较长的补充说明在标题和值全部结束之后独立换行，保留原有设置含义和操作。"
        const val nullValueLabel = "没有蓝色值的设置标题可以使用完整内容宽度，包括这一段较长的说明性标题"
    }
}
