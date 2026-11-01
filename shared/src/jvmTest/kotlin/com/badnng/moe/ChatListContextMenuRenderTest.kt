package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.status.ConnectionPhase
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real workspace blur and real Popup pixels; all content and callbacks are synthetic. */
class ChatListContextMenuRenderTest {
    @Test
    fun aRealSecondaryClickKeepsTheOriginalCardInPlaceWhileBlurringTheWorkspaceThenRestoresIt() {
        for (density in listOf(1f, 1.25f)) for (widthDp in listOf(1160, 380)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                verifyWorkspaceMenu(widthDp, density, mode)
            }
        }
    }

    private fun verifyWorkspaceMenu(widthDp: Int, density: Float, mode: ColorSchemeMode) {
        val width = (widthDp * density).roundToInt()
        val height = (840 * density).roundToInt()
        val fixture = "$widthDp/$density/$mode"
        val rows = (0..8).map { ChatItem(it + 1L, "合成背景第${it}行", order = 100L - it) }
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
            account = AccountProfile(900001, "合成账号"), items = rows, knownChats = rows, listEnd = true)
        var menuFill = 0
        var selectedChats = 0
        var dispatched: ChatListAction? = null
        ComposePixelScene(width, height) {
            val controller = remember { ThemeController(mode) }
            val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = controller) {
                    menuFill = MiuixTheme.colorScheme.surfaceContainer.toArgb()
                    ChatWorkspace(snapshot, ConnectionPhase.READY, drafts,
                        onSettings = {}, onSelectChat = { _, _ -> selectedChats++ }, onLoadChats = {}, onLoadOlder = {},
                        onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> }, onMarkRead = {},
                        windowFocused = true, features = ChatFeatureActions(chatListAction = { _, _, _, action, completed ->
                            dispatched = action
                            completed(ChatResult(Unit))
                        }), darkTheme = mode == ColorSchemeMode.Dark, onDarkTheme = {}, persistUiPreferences = false)
                }
            }
        }.use { scene ->
            scene.moveTo(width - 4f, height - 4f)
            val before = scene.settledFrame()
            val target = assertNotNull(scene.textBounds(rows.first().title).singleOrNull(), fixture)
            val targetTitle = assertNotNull(scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == rows.first().title }
            }.singleOrNull(), fixture)
            val backgroundText = assertNotNull(scene.textBounds(rows[6].title).singleOrNull(), fixture)
            val avatar = assertNotNull(scene.descriptionBounds("打开账号菜单").singleOrNull(), fixture)
            scene.secondaryClickAt(target.center.x, target.center.y)
            val opened = scene.settledFrame()
            val card = assertNotNull(scene.menuBounds().singleOrNull(), "First open must attach popup focus safely: $fixture")
            val preview = assertNotNull(scene.descriptionBounds("会话卡片").singleOrNull(),
                "The actual source card must remain sharp above the workspace blur: $fixture")
            assertSameBounds(target, preview,
                "Opening the menu must preserve the original card's window coordinates and size: $fixture")
            assertTrue(card.top >= preview.bottom + 6f * density,
                "The actions must open below a top row: $preview/$card/$fixture")
            assertSourceDetail(before, opened, target, preview, targetTitle, menuFill, density, fixture)
            assertNotNull(scene.buttonTextBounds("归档聊天").singleOrNull(), fixture)
            assertTrue(card.left >= 0 && card.top >= 0 && card.right <= width && card.bottom <= height,
                "Menu and shadow must fit the viewport: $card/$fixture")
            assertTrue(changedPixels(before, opened, backgroundText, 8) >= 12,
                "The actual chat list text must be blurred under the Popup: $fixture")
            assertTrue(changedPixels(before, opened, avatar, 8) >= 12,
                "The persistent account avatar must share the context-menu blur: $fixture")
            scene.click(preview)
            scene.settledFrame()
            assertEquals(0, selectedChats, "The raised source card must not navigate while its menu owns input: $fixture")
            assertTrue(scene.menuBounds().isNotEmpty(), "Clicking the source card must not dismiss its menu: $fixture")

            scene.moveTo(width - 4f, height - 4f)
            scene.pressAt(width - 4f, height - 4f)
            scene.releaseAt(width - 4f, height - 4f)
            val exiting = scene.settledFrame(1)
            assertTrue(changedPixels(before, exiting, card, 8) >= 20,
                "Dismissing must retain painted menu content during its exit: $fixture")
            assertSourceDetail(before, exiting, target, target, targetTitle, menuFill, density, fixture)
            val restored = scene.settledFrame()
            assertTrue(scene.menuBounds().isEmpty(), "Dismissed menu must leave the semantics tree: $fixture")
            assertTrue(scene.descriptionBounds("会话卡片").isEmpty(), fixture)
            assertEquals(0, changedPixels(before, restored, backgroundText, 2),
                "Closing must restore the source text after the blur animation: $fixture")
            assertEquals(0, changedPixels(before, restored, avatar, 2),
                "Closing must restore the persistent avatar: $fixture")

            // The source layer disables focus while blurred; the independent Popup must
            // nevertheless keep its native action rows interactive after reopening.
            scene.secondaryClickAt(target.center.x, target.center.y)
            scene.settledFrame()
            scene.click(assertNotNull(scene.buttonTextBounds("置顶聊天").singleOrNull(), fixture))
            scene.settledFrame()
            assertEquals(ChatListAction.PIN, dispatched, "Popup interaction must survive workspace blur: $fixture")
            assertTrue(scene.menuBounds().isEmpty(), fixture)
            scene.secondaryClickAt(target.center.x, target.center.y)
            scene.settledFrame()
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            scene.settledFrame()
            assertTrue(scene.menuBounds().isEmpty() && scene.descriptionBounds("会话卡片").isEmpty(),
                "Escape must dismiss both parts of the same popup: $fixture")
            assertEquals(0, selectedChats, fixture)
        }
    }

    @Test
    fun aBottomCardRetainsItsPositionAndDecodedPhotoWhileItsMenuOpensAbove() {
        for (density in listOf(1f, 1.25f)) {
            val path = Files.createTempFile("lifted-chat-photo-", ".png")
            try {
                val photo = BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until 48) for (x in 0 until 48)
                    photo.setRGB(x, y, if (x / 4 % 2 == 0) 0xffff0033.toInt() else 0xff00ddff.toInt())
                check(ImageIO.write(photo, "png", path.toFile()))
                val file = MediaFile(800, downloaded = true, localPath = path.toString())
                val rows = (0..29).map { index -> ChatItem(index + 1L, "合成照片会话 $index", order = 100L - index,
                    avatar = file) }
                val snapshot = mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
                    account = AccountProfile(900001, "合成账号"), items = rows, knownChats = rows,
                    listEnd = true, mediaFiles = mapOf(file.id to file)))
                var downloads = 0
                ComposePixelScene((380 * density).roundToInt(), (840 * density).roundToInt()) {
                    val controller = remember { ThemeController(ColorSchemeMode.Light) }
                    val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
                    CompositionLocalProvider(LocalDensity provides Density(density)) {
                        MiuixTheme(controller = controller) {
                            ChatWorkspace(snapshot.value, ConnectionPhase.READY, drafts,
                                onSettings = {}, onSelectChat = { _, _ -> }, onLoadChats = {}, onLoadOlder = {},
                                onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> }, onMarkRead = {},
                                windowFocused = true, features = ChatFeatureActions(downloadFile = { _, _ -> downloads++ }),
                                darkTheme = false, onDarkTheme = {}, persistUiPreferences = false)
                        }
                    }
                }.use { scene ->
                    var before = scene.settledFrame()
                    // Pick the last complete painted row, rather than assuming a row index
                    // remains near the bottom when the official row density changes.
                    val (targetChat, target) = assertNotNull(rows.mapNotNull { chat ->
                        scene.textBounds(chat.title).singleOrNull()?.takeIf {
                            it.height > 0 && it.top >= 0 && it.bottom <= before.height - 2f * density
                        }?.let { chat to it }
                    }.maxByOrNull { it.second.bottom })
                    assertTrue(before.height - target.bottom <= 64f * density,
                        "The source fixture must actually occupy the bottom complete row of the viewport.")
                    var photoBounds: Rect? = null
                    val deadline = System.nanoTime() + 3_000_000_000L
                    while (photoBounds == null && System.nanoTime() < deadline) {
                        photoBounds = scene.unmergedSemanticsBounds {
                            "聊天图片" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        }.singleOrNull { it.center.y >= target.top && it.center.y <= target.bottom }
                        if (photoBounds == null) {
                            Thread.sleep(5)
                            before = scene.settledFrame(2)
                        }
                    }
                    val avatar = assertNotNull(photoBounds, "The synthetic photograph must finish decoding before the menu opens.")
                    before = scene.settledFrame(2)
                    val photoPixels = ((avatar.top + 5f * density).roundToInt() until (avatar.bottom - 5f * density).roundToInt())
                        .flatMap { y -> ((avatar.left + 5f * density).roundToInt() until (avatar.right - 5f * density).roundToInt())
                            .map { x -> before[x, y] } }
                    assertTrue(photoPixels.count { (it ushr 16 and 255) > 220 && (it ushr 8 and 255) < 40 } >= 30 &&
                        photoPixels.count { (it ushr 16 and 255) < 40 && (it ushr 8 and 255) > 180 && (it and 255) > 220 } >= 30,
                        "The comparison must observe both saturated colors of the decoded photograph.")
                    val unaffected = assertNotNull(scene.textBounds(rows.first().title).singleOrNull())
                    Files.delete(path)
                    scene.secondaryClickAt(target.center.x, target.center.y)
                    val opened = scene.settledFrame()
                    val preview = assertNotNull(scene.descriptionBounds("会话卡片").singleOrNull())
                    val menu = assertNotNull(scene.menuBounds().singleOrNull())
                    assertSameBounds(target, preview,
                        "A bottom source card must never move to make room for its menu.")
                    assertTrue(menu.bottom <= preview.top - 6f * density,
                        "Only the actions move above a bottom card: $menu/$preview")
                    assertTrue(menu.left >= 0 && menu.top >= 0 && menu.right <= opened.width,
                        "The actions and their shadow must remain inside the narrow window.")
                    assertTranslatedPixels(before, opened, target, preview,
                        Rect(avatar.left + 5f * density, avatar.top + 5f * density,
                            avatar.right - 5f * density, avatar.bottom - 5f * density), density,
                        "The original decoded photo must survive after its source file was deleted.")
                    assertEquals(0, downloads, "Raising a downloaded avatar must not request another native download.")
                    snapshot.value = snapshot.value.copy(items = rows.filterNot { it.id == targetChat.id },
                        knownChats = rows.filterNot { it.id == targetChat.id })
                    val removed = scene.settledFrame()
                    assertTrue(scene.menuBounds().isEmpty() && scene.descriptionBounds("会话卡片").isEmpty(),
                        "Removing the source row must dispose both its card and actions.")
                    assertEquals(0, changedPixels(before, removed, unaffected, 2),
                        "Disposing the source row must also release its workspace blur ownership.")
                }
            } finally { Files.deleteIfExists(path) }
        }
    }

    @Test
    fun bottomShadowHasRealPopupSpaceAtStandardAndFractionalDensity() {
        for (density in listOf(1f, 1.25f)) {
            val backdrop = ContextMenuBackdropState()
            val expanded = mutableStateOf(true)
            val mounted = mutableStateOf(true)
            val chat = ChatItem(1, "合成阴影会话")
            ComposePixelScene((620 * density).roundToInt(), (700 * density).roundToInt()) {
                val controller = remember { ThemeController(ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density), LocalContextMenuBackdrop provides backdrop) {
                    MiuixTheme(controller = controller) {
                        Box(Modifier.fillMaxSize().background(Color(0xffdddddd))) {
                            if (mounted.value) Box(Modifier.size(1.dp)) {
                                ChatListActionsMenu(chat, ChatSnapshot(enabled = true), ChatFeatureActions(),
                                    position = Offset(110f * density, 120f * density), expanded = expanded.value,
                                    onDismiss = { expanded.value = false })
                            }
                        }
                    }
                }
            }.use { scene ->
                val frame = scene.settledFrame()
                val card = assertNotNull(scene.menuBounds().singleOrNull())
                val centerX = card.center.x.roundToInt()
                val near = frame[centerX, (card.bottom + 3 * density).roundToInt()] and 255
                val far = frame[centerX, (card.bottom + 26 * density).roundToInt()] and 255
                assertTrue(near in 140..215, "A soft bottom shadow must paint below the actual card: $density/$near")
                assertTrue(far > near && far in 217..221,
                    "Shadow must fade inside the Popup's transparent padding: $density/$near->$far")
                assertTrue(backdrop.isExpanded && backdrop.hasMountedMenus)
                expanded.value = false
                scene.settledFrame(1)
                assertFalse(backdrop.isExpanded)
                assertTrue(backdrop.hasMountedMenus, "Closing retains ownership until animation disposal.")
                scene.settledFrame()
                assertFalse(backdrop.hasMountedMenus)

                expanded.value = true
                scene.settledFrame()
                assertTrue(backdrop.hasMountedMenus)
                mounted.value = false
                scene.settledFrame()
                assertFalse(backdrop.isExpanded || backdrop.hasMountedMenus,
                    "Disposing a row/list while open must remove its blur owner.")
            }
        }
    }

    private fun ComposePixelScene.menuBounds() = semanticsBounds { it.getOrNull(SemanticsProperties.PaneTitle) == "聊天操作" }
    private fun ComposePixelScene.descriptionBounds(text: String) = semanticsBounds {
        text in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.textBounds(text: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }
    private fun ComposePixelScene.buttonTextBounds(text: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun changedPixels(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect, threshold: Int): Int {
        var changed = 0
        for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(first.height)) {
            for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(first.width)) {
                if (colorDistance(first[x, y], second[x, y]) > threshold) changed++
            }
        }
        return changed
    }
    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }

    private fun assertSameBounds(expected: Rect, actual: Rect, message: String) {
        assertTrue(abs(expected.left - actual.left) <= 1f && abs(expected.top - actual.top) <= 1f &&
            abs(expected.right - actual.right) <= 1f && abs(expected.bottom - actual.bottom) <= 1f,
            "$message $expected -> $actual")
    }

    private fun assertSourceDetail(before: ComposePixelFrame, opened: ComposePixelFrame,
        source: Rect, lifted: Rect, detail: Rect, fill: Int, density: Float, fixture: String) {
        var ink = 0
        var retained = 0
        val dx = lifted.left - source.left
        val dy = lifted.top - source.top
        for (y in detail.top.roundToInt() until detail.bottom.roundToInt()) {
            for (x in detail.left.roundToInt() until detail.right.roundToInt()) {
                if (colorDistance(before[x, y], fill) < 60) continue
                ink++
                val actualX = (x + dx).roundToInt()
                val actualY = (y + dy).roundToInt()
                val radius = density.roundToInt()
                if ((-radius..radius).any { oy -> (-radius..radius).any { ox ->
                    colorDistance(before[x, y], opened[actualX + ox, actualY + oy]) <= 35
                } }) retained++
            }
        }
        assertTrue(ink >= 20, "The comparison must observe actual title glyphs: $fixture")
        assertTrue(retained >= ink * 0.9f,
            "The lifted title must retain its sharp source glyphs above the workspace blur: $retained/$ink/$fixture")
    }

    private fun assertTranslatedPixels(before: ComposePixelFrame, opened: ComposePixelFrame,
        source: Rect, lifted: Rect, detail: Rect, density: Float, message: String) {
        var samples = 0
        var retained = 0
        val dx = lifted.left - source.left
        val dy = lifted.top - source.top
        val radius = density.roundToInt()
        for (y in detail.top.roundToInt() until detail.bottom.roundToInt()) {
            for (x in detail.left.roundToInt() until detail.right.roundToInt()) {
                samples++
                val actualX = (x + dx).roundToInt()
                val actualY = (y + dy).roundToInt()
                if ((-radius..radius).any { oy -> (-radius..radius).any { ox ->
                    colorDistance(before[x, y], opened[actualX + ox, actualY + oy]) <= 25
                } }) retained++
            }
        }
        assertTrue(samples >= 100 && retained >= samples * 0.9f, "$message $retained/$samples at $density")
    }
}
