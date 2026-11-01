package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeController

class ComposerHoverMenuRenderTest {
    @Test
    fun hoveringOpensAnAlignedGlassMenuAndClickingTheIconInvokesThePickerOnce() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) for (composerInset in listOf(0, 64)) {
            var source by mutableStateOf(Color(0xff20ba70))
            var pickerCalls = 0
            var stickerHover = false
            ComposePixelScene((366 * density).roundToInt(), (500 * density).roundToInt()) {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    val controller = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                    MiuixTheme(controller = controller) {
                        val backdrop = rememberChatBackdrop()
                        val focus = remember { FocusRequester() }
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
                                    drawRect(source)
                                }
                                ConversationComposer(TextFieldValue(), {}, true, false, true, true, true, true,
                                    focus, {}, null, {}, {}, {}, {}, { pickerCalls++ },
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(composerInset.dp),
                                    onStickerHoverChanged = { stickerHover = it })
                            }
                        }
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                val attach = scene.description("附件")
                scene.moveTo(attach.center.x, attach.center.y)
                val opened = scene.settledFrame()
                val menu = scene.menu()
                opened.save("${if (dark) "dark" else "light"}-$density-inset$composerInset-open")
                val shadowSpace = ComposerAttachmentMenuShadowPadding.value * density
                val expectedLeft = attach.left.coerceIn(shadowSpace,
                    (366f * density - shadowSpace - menu.width).coerceAtLeast(shadowSpace))
                assertTrue(abs(menu.left - expectedLeft) <= 2 * density,
                    "The card body must follow its real attachment icon after reserving complete window shadow space: inset=$composerInset, $attach -> $menu")
                if (attach.left >= shadowSpace) {
                    assertTrue(abs(menu.left - attach.left) <= 2 * density,
                        "With sufficient window margin the card body must align exactly with the actual attachment icon.")
                } else {
                    assertTrue(abs(menu.left - shadowSpace) <= 2 * density,
                        "The edge fixture must reserve the exact shadow padding, rather than clip it outside the window.")
                }
                assertTrue(abs(menu.bottom - (attach.top - 14 * density)) <= 2 * density,
                    "The painted attachment card must keep the official 10dp shadow allowance plus the requested small 4dp lift: $attach -> $menu")
                assertTrue(abs(menu.bottom + shadowSpace - (attach.top - 4 * density)) <= 2 * density,
                    "The complete native shadow window must stop only 4dp above the original button, keeping its pointer target reachable.")
                assertTrue(scene.semanticsBounds {
                    it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "文字格式" }
                }.isEmpty(), "Text formatting must not remain in the attachment menu.")
                assertEquals(0, pickerCalls, "Hovering must not open a native picker.")
                val sampleX = (menu.right - 5 * density).roundToInt()
                val sampleY = menu.center.y.roundToInt()
                source = Color(0xffa054d8)
                val changed = scene.settledFrame()
                assertTrue(distance(opened[sampleX, sampleY], changed[sampleX, sampleY]) >= 10,
                    "The popup must sample the changing backdrop, rather than use a solid menu card.")
                scene.moveTo(menu.center.x, menu.bottom - 8 * density)
                Thread.sleep(ComposerPanelHideDelayMillis + 40)
                scene.settledFrame()
                assertNotNull(scene.menu(), "Crossing the gap into the panel must cancel delayed hiding.")
                scene.moveTo(attach.center.x, attach.center.y)
                scene.settledFrame(2)
                Thread.sleep(ComposerPanelHideDelayMillis + 40)
                scene.settledFrame()
                assertNotNull(scene.menu(),
                    "Returning from the panel must reach the real attachment button and cancel delayed hiding despite the Popup's shadow window.")
                assertEquals(0, pickerCalls, "Returning by hover must keep the native picker closed.")
                scene.moveTo(menu.center.x, menu.bottom - 8 * density)
                scene.settledFrame(2)
                val file = assertNotNull(scene.semanticsBounds {
                    it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "文件" }
                }.singleOrNull())
                scene.click(file)
                scene.settledFrame()
                assertEquals(1, pickerCalls)
                scene.click(attach)
                scene.settledFrame()
                assertEquals(2, pickerCalls, "One attachment-button click must directly invoke exactly one picker.")
                val sticker = scene.description("贴纸")
                scene.moveTo(sticker.center.x, sticker.center.y)
                scene.settledFrame()
                assertTrue(stickerHover, "The sticker entry must forward real hover events to its retained panel.")
            }
        }
    }

    private fun ComposePixelScene.description(label: String) = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.menu() = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == "附件菜单"
    }.singleOrNull())
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun distance(first: Int, second: Int) = listOf(0, 8, 16).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }

    private fun ComposePixelFrame.save(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "attachment-card-anchor/composer").apply { mkdirs() }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
    }
}
