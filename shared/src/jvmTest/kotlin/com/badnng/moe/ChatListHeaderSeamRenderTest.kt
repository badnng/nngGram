package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Detect the actual painted separator under the folder tabs, independently of its layout node. */
class ChatListHeaderSeamRenderTest {
    @Test
    fun theSearchAndFolderHeaderHasNoHorizontalDividerAboveTheFirstChat() {
        for (density in listOf(1f, 1.25f)) for (compact in listOf(false, true)) {
            for (dark in listOf(false, true)) verifyHeader(density, compact, dark)
        }
    }

    private fun verifyHeader(density: Float, compact: Boolean, dark: Boolean) {
        val widthDp = if (compact) 300 else 354
        val first = ChatItem(1, "首个合成会话", order = 100)
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
            items = listOf(first, ChatItem(2, "第二个合成会话", order = 90)), listEnd = true)
        ComposePixelScene((widthDp * density).roundToInt(), (500 * density).roundToInt()) {
            // An unmistakable divider color makes a real one-pixel separator observable
            // without mistaking the changing glass sampling or text for that separator.
            val colors = remember(dark) {
                if (dark) darkColorScheme(dividerLine = Color.Red)
                else lightColorScheme(dividerLine = Color.Red)
            }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = compact, showRail = true)) {
                MiuixTheme(colors = colors) {
                    ChatListPane(snapshot, onSelect = {}, onRetryLoad = {}, onSelectList = {},
                        onManageFolders = {}, features = ChatFeatureActions(), modifier = Modifier.fillMaxSize())
                }
            }
        }.use { scene ->
            val frame = scene.settledFrame()
            val tabs = scene.semanticsBounds { it.getOrNull(SemanticsProperties.Role) == Role.Tab }
            assertTrue(tabs.isNotEmpty(), "The fixture must contain the actual folder/filter tabs.")
            val tabsBottom = tabs.maxOf { it.bottom }
            val firstRow = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == first.title }
            }.singleOrNull(), "The real first chat must remain visible below the header.")
            val gapDp = (firstRow.top - tabsBottom) / density
            assertTrue(gapDp in 14f..19f,
                "Removing the separator must retain the compact natural gap above the first card: $gapDp")
            val startX = (32 * density).roundToInt()
            val endX = frame.width - startX
            val startY = ceil(tabsBottom + 3 * density).toInt()
            val endY = floor(firstRow.top - 2 * density).toInt()
            assertTrue(endY > startY, "The pixel scan must observe a nonempty header/card gap.")
            val longestPaintedDivider = (startY..endY).maxOf { y ->
                (startX until endX).count { x ->
                    val pixel = frame[x, y]
                    val red = pixel ushr 16 and 255
                    red - (pixel ushr 8 and 255) > 50 && red - (pixel and 255) > 50
                }
            }
            assertTrue(longestPaintedDivider < (endX - startX) / 2,
                "A horizontal divider is still painted between the tabs and first card: " +
                    "$longestPaintedDivider pixels at density=$density compact=$compact dark=$dark")
        }
    }
}
