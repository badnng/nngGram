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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.PrivacyKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual measured text bounds, including wrapped titles/values, rather than modifier inspection. */
class SettingsPrivacySummaryRowUiTest {
    @Test fun allPrivacyValuesCenterAgainstTitleAndExceptionsWithInsetRightEdge() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) for (width in listOf(330, 620)) {
                val height = 1450
                ComposePixelScene((width * density).roundToInt(), (height * density).roundToInt()) {
                    val theme = remember { ThemeController(mode) }
                    CompositionLocalProvider(LocalDensity provides Density(density)) {
                        MiuixTheme(controller = theme) {
                            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(16.dp)) {
                                PrivacyKey.entries.forEachIndexed { index, key ->
                                    SettingsRow(key.referenceLabel(), value(index), summary(index), onClick = {})
                                }
                            }
                        }
                    }
                }.use { scene ->
                    val frame = scene.settledFrame()
                    PrivacyKey.entries.forEachIndexed { index, key ->
                        val title = scene.text(key.referenceLabel())
                        val current = scene.text(value(index))
                        val exception = scene.text(summary(index))
                        assertTrue(abs((title.top + exception.bottom) / 2f - current.center.y) <= 1f,
                            "${key.name}: value centers against the full title/summary column")
                        assertTrue(current.left >= title.right + 15f * density, "${key.name}: columns overlap")
                        assertTrue(abs(current.right - (width - 32f) * density) <= 1f, "${key.name}: value keeps the extra 4 dp inset")
                        assertTrue(exception.top >= title.bottom + 4f * density,
                            "${key.name}: exceptions belong below the heading")
                        assertTrue(exception.left == title.left)
                        assertTrue(exception.bottom < height * density, "All eleven entries must be measured")
                    }
                    val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
                    val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                        .first { File(it, "settings.gradle.kts").isFile }
                    val target = File(repository, "docs/screenshots/official-settings/settings-value-alignment/privacy-${mode.name.lowercase()}-${width}-${density}.png")
                    target.parentFile.mkdirs()
                    ImageIO.write(image, "png", target)
                }
            }
        }
    }

    @Test fun disabledPrivacyRowsKeepTheirSummaryButCannotOpenAnEditor() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        ComposePixelScene(400, 160) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                SettingsRow("手机号码", "联系人", "允许 2 个用户", enabled.value, onClick = { clicks++ })
            }
        }.use { scene ->
            scene.settledFrame()
            val row = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsActions.OnClick) != null
            }.firstOrNull())
            scene.pressAt(row.center.x, row.center.y)
            scene.releaseAt(row.center.x, row.center.y)
            scene.settledFrame()
            assertEquals(1, clicks)
            enabled.value = false
            scene.settledFrame()
            scene.pressAt(row.center.x, row.center.y)
            scene.releaseAt(row.center.x, row.center.y)
            scene.settledFrame()
            assertEquals(1, clicks)
            scene.text("允许 2 个用户")
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.Disabled) != null }.isNotEmpty())
        }
    }

    private fun value(index: Int) = when (index) {
        0 -> "联系人，但具有例外规则的用户除外"
        1 -> "所有人"
        else -> "联系人 $index"
    }

    private fun summary(index: Int) = "允许 ${index + 1} 个用户；禁止 ${index + 2} 个群组的成员"

    // Reference labels stay local: the product's private label mapping need not become test API.
    private fun PrivacyKey.referenceLabel(): String = when (this) {
        PrivacyKey.PHONE_NUMBER -> "手机号码"
        PrivacyKey.FIND_BY_PHONE_NUMBER -> "通过手机号找到我"
        PrivacyKey.LAST_SEEN -> "最后上线时间与在线状态"
        PrivacyKey.PROFILE_PHOTO -> "头像"
        PrivacyKey.FORWARDED_LINK -> "转发消息中的个人链接"
        PrivacyKey.CALLS -> "通话"
        PrivacyKey.PEER_TO_PEER_CALLS -> "通话点对点连接"
        PrivacyKey.VOICE_VIDEO_MESSAGES -> "语音与视频消息"
        PrivacyKey.BIO -> "简介"
        PrivacyKey.BIRTHDATE -> "生日"
        PrivacyKey.CHAT_INVITES -> "群组与频道邀请"
    }

    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.firstOrNull { it.width > 0 && it.height > 0 }, label)
}

