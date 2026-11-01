package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatAdministrator
import com.badnng.moe.chat.ChatDetails
import com.badnng.moe.chat.ChatDetailsState
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real administrator content and button pixels over changing synthetic sources only. */
class AdminPopupReadabilityRenderTest {
    @Test
    fun administratorNamesAndCloseButtonStayReadableOverChangingFineColorStripes() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            verifyReadability(density, mode)
        }
    }

    private fun verifyReadability(density: Float, mode: ColorSchemeMode) {
        val show = mutableStateOf(false)
        val changedSource = mutableStateOf(false)
        var closeRequests = 0
        var expectedButtonFill = 0
        var expectedForeground = 0
        val chat = ChatItem(10, "Synthetic administrators", basicGroupId = 20)
        val name = "清晰姓名 Alice"
        val subtitle = "创建者 · Owner"
        val snapshot = ChatSnapshot(enabled = true, selected = chat, token = ChatToken(1, 1, chat.id),
            details = ChatDetailsState(details = ChatDetails(chat.id, administrators = listOf(
                ChatAdministrator(101, name, "Owner", owner = true), ChatAdministrator(102, "第二管理员 Bob", "Moderator")))))
        ComposePixelScene((900 * density).roundToInt(), (520 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = controller) {
                    expectedButtonFill = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 1f).toArgb()
                    expectedForeground = MiuixTheme.colorScheme.onSurface.toArgb()
                    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                    val stripe = 2.dp.toPx()
                                    repeat((size.width / stripe).toInt() + 1) { index ->
                                        val color = if (changedSource.value) {
                                            if (index % 2 == 0) Color.Cyan else Color.Blue
                                        } else if (index % 2 == 0) Color.Red else Color.Yellow
                                        drawRect(color, Offset(index * stripe, 0f), Size(stripe, size.height))
                                    }
                                }
                                Box(Modifier.offset(730.dp, 24.dp).size(32.dp)) {
                                    ConversationAdminsPopup(show.value, snapshot, ChatFeatureActions(),
                                        onDismissRequest = { closeRequests++; show.value = false })
                                }
                            }
                        }
                    }
                }
            }
        }.use { scene ->
            val rawBefore = scene.settledFrame()
            show.value = true
            val before = scene.settledFrame()
            val panel = assertNotNull(scene.semanticsBounds {
                "管理员" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.singleOrNull(), "The fixture must render the real administrator popup.")
            fun textBounds(text: String) = assertNotNull(scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
            }.singleOrNull(), "The real popup must expose the text '$text'.")
            val nameBounds = textBounds(name)
            val subtitleBounds = textBounds(subtitle)
            val closeText = textBounds("关闭")
            val closeButton = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "关闭" }
            }.singleOrNull(), "The actual close action must remain a semantic button.")
            changedSource.value = true
            val after = scene.settledFrame()
            scene.moveTo(closeButton.center.x, closeButton.center.y)
            scene.pressAt(closeButton.center.x, closeButton.center.y)
            scene.releaseAt(closeButton.center.x, closeButton.center.y)
            val rawAfter = scene.settledFrame()
            val report = exportFrames(panel, density, mode,
                mapOf("raw-before" to rawBefore, "admins-before" to before, "admins-after" to after, "raw-after" to rawAfter))
            val fixture = "density=$density theme=$mode images=$report"
            assertTrue(!show.value && closeRequests == 1, "The real readable close button must close once: $fixture")
            assertTrue(closeButton.height >= 40 * density - 1,
                "The real close target must retain at least 40dp height: ${closeButton.height / density}dp $fixture")

            val bodyX = (panel.right - 15 * density).roundToInt()
            val bodyY = nameBounds.center.y.roundToInt()
            val sourceDelta = distance(rawBefore[bodyX, bodyY], rawAfter[bodyX, bodyY])
            val bodyDelta = distance(before[bodyX, bodyY], after[bodyX, bodyY])
            assertTrue(sourceDelta >= 150 && bodyDelta in 2..20,
                "Administrator glass must attenuate distracting source changes while still sampling them: " +
                    "sourceDelta=$sourceDelta bodyDelta=$bodyDelta (required source>=150, body=2..20) $fixture")
            val adjacentX = (bodyX - 2 * density).roundToInt()
            val stripeContrast = distance(before[bodyX, bodyY], before[adjacentX, bodyY])
            assertTrue(stripeContrast <= 8,
                "The real glass must soften fine source stripes behind the names: stripeContrast=$stripeContrast $fixture")

            val buttonX = (closeButton.left + 14 * density).roundToInt()
            val buttonY = closeButton.center.y.roundToInt()
            val fillBefore = before[buttonX, buttonY]
            val fillAfter = after[buttonX, buttonY]
            val fillError = maxOf(distance(fillBefore, expectedButtonFill), distance(fillAfter, expectedButtonFill))
            val fillChange = distance(fillBefore, fillAfter)
            assertTrue(fillError <= 2 && fillChange <= 1,
                "The close button must paint a stable opaque theme fill above the glass: " +
                    "fillError=$fillError fillChange=$fillChange expected=${expectedButtonFill.toUInt().toString(16)} " +
                    "before=${fillBefore.toUInt().toString(16)} after=${fillAfter.toUInt().toString(16)} $fixture")

            for ((phase, pixels) in listOf("before" to before, "after" to after)) {
                fun verifyText(label: String, bounds: Rect, background: Int, minimum: Double) {
                    val contrast = pixelsIn(pixels, bounds).maxOf { contrastRatio(it, background) }
                    assertTrue(contrast >= minimum,
                        "The actual $label glyphs must retain foreground contrast: ratio=$contrast required=$minimum phase=$phase $fixture")
                }
                verifyText("administrator name", nameBounds, pixels[bodyX, bodyY], 7.0)
                verifyText("administrator subtitle", subtitleBounds, pixels[bodyX, subtitleBounds.center.y.roundToInt()], 4.5)
                verifyText("close action", closeText, pixels[buttonX, buttonY], 4.5)
                val foregroundError = pixelsIn(pixels, closeText).minOf { distance(it, expectedForeground) }
                assertTrue(foregroundError <= 24,
                    "The real close glyphs must render the strong theme foreground: closestError=$foregroundError phase=$phase $fixture")
            }
        }
    }

    private fun pixelsIn(frame: ComposePixelFrame, bounds: Rect): List<Int> = buildList {
        for (y in floor(bounds.top).toInt() until ceil(bounds.bottom).toInt())
            for (x in floor(bounds.left).toInt() until ceil(bounds.right).toInt()) add(frame[x, y])
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(pixel: Int): Double {
            fun channel(shift: Int): Double {
                val value = (pixel ushr shift and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun exportFrames(panel: Rect, density: Float, mode: ColorSchemeMode,
        frames: Map<String, ComposePixelFrame>): Path {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "admin-popup-readability", "$mode-$density")
        Files.createDirectories(directory)
        frames.forEach { (name, frame) ->
            val left = floor(panel.left - 12 * density).toInt().coerceAtLeast(0)
            val top = floor(panel.top - 12 * density).toInt().coerceAtLeast(0)
            val right = ceil(panel.right + 12 * density).toInt().coerceAtMost(frame.width)
            val bottom = ceil(panel.bottom + 12 * density).toInt().coerceAtMost(frame.height)
            val image = BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_ARGB)
            for (y in top until bottom) for (x in left until right) image.setRGB(x - left, y - top, frame[x, y])
            check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
        }
        println("Synthetic administrator readability images: $directory")
        return directory
    }

    private fun distance(a: Int, b: Int) = listOf(0, 8, 16).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }
}
