package com.badnng.moe

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class ChatListProgressiveFalloffRenderTest {
    @Test
    fun theActualHeaderRestoresDetailAcrossItsHeightWithoutMovingItsControls() {
        val path = Files.createTempFile("header-falloff-source-", ".png")
        try {
            val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width)
                image.setRGB(x, y, if (x / 6 % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt())
            check(ImageIO.write(image, "png", path.toFile()))
            val photo = MediaFile(701, downloaded = true, localPath = path.toString())
            val chats = (1L..30L).map { ChatItem(it, "渐变采样-$it", order = 100 - it, avatar = photo) }
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null), items = chats,
                knownChats = chats, listEnd = true, mediaFiles = mapOf(photo.id to photo))
            for ((density, dark, width) in listOf(
                Triple(1f, false, 354), Triple(1f, true, 240),
                Triple(1.25f, false, 240), Triple(1.25f, true, 354),
            )) {
                var searchActions = 0
                ComposePixelScene((width * density).roundToInt(), (560 * density).roundToInt()) {
                    val colors = remember(dark) { if (dark) darkColorScheme() else lightColorScheme() }
                    CompositionLocalProvider(LocalDensity provides Density(density),
                        LocalDesktopChatLayout provides DesktopChatLayout(compact = true, showRail = true)) {
                        MiuixTheme(colors = colors) {
                            ChatListPane(snapshot, {}, {}, {}, {}, features = ChatFeatureActions(),
                                onSearch = { searchActions++ }, modifier = Modifier.fillMaxSize(),
                                // A naturally tall, transparent folder slot exposes both parts of the
                                // actual Pane's background, without foreground labels in the samples.
                                folderTabs = { Spacer(Modifier.height(140.dp)) })
                        }
                    }
                }.use { scene ->
                    var frame = scene.settledFrame()
                    fun firstRow() = assertNotNull(scene.semanticsBounds {
                        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == chats.first().title }
                    }.singleOrNull())
                    fun searchBounds() = assertNotNull(scene.semanticsBounds {
                        "搜索当前聊天列表" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.singleOrNull())
                    fun contrastAt(centerY: Float): Int {
                        val pixels = ((30 * density).roundToInt()..(54 * density).roundToInt()).map { x ->
                            val pixel = frame[x, centerY.roundToInt()]
                            ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
                        }
                        return pixels.maxOrNull()!! - pixels.minOrNull()!!
                    }
                    val initialSearch = searchBounds()
                    val initialRow = firstRow()
                    val decodedDeadline = System.nanoTime() + 3_000_000_000L
                    while (contrastAt(initialRow.center.y) < 230 && System.nanoTime() < decodedDeadline) {
                        Thread.sleep(5)
                        frame = scene.settledFrame(2)
                    }
                    val rawContrast = contrastAt(initialRow.center.y)
                    assertTrue(rawContrast >= 230, "The stripe avatar must be decoded and clear below the feather.")
                    assertTrue(initialRow.top / density in 221f..223f,
                        "The background extension must not add to the naturally measured header or list inset.")

                    fun moveAvatarTo(targetDp: Float): Float {
                        repeat(90) {
                            val row = firstRow()
                            val difference = row.center.y - targetDp * density
                            if (abs(difference) <= 4 * density) return row.center.y
                            scene.scrollAt(width / 2f * density, 450 * density,
                                deltaY = if (difference > 0) 0.0625f else -0.0625f)
                            frame = scene.settledFrame(6)
                        }
                        error("The real source avatar did not reach its falloff sample position.")
                    }
                    val earlyContrast = contrastAt(moveAvatarTo(112f))
                    val lateContrast = contrastAt(moveAvatarTo(195f))
                    assertTrue(lateContrast > earlyContrast + 20,
                        "Detail must return across the header's full height instead of being held until its last 24 dp: " +
                            "$earlyContrast -> $lateContrast at density=$density dark=$dark width=$width")
                    assertTrue(earlyContrast < rawContrast - 30,
                        "The upper sample must lose real source detail: $rawContrast -> $earlyContrast.")
                    assertEquals(initialSearch, searchBounds(), "Scrolling the sampled source must not move the header controls.")
                    val beforeClick = searchActions
                    scene.moveTo(initialSearch.center.x, initialSearch.center.y)
                    scene.pressAt(initialSearch.center.x, initialSearch.center.y)
                    scene.releaseAt(initialSearch.center.x, initialSearch.center.y)
                    scene.settledFrame()
                    assertTrue(searchActions > beforeClick, "The natural search hit region must remain interactive above the blur.")
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
