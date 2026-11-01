package com.badnng.moe

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class HorizontalWheelScrollRenderTest {
    @Test
    fun verticalWheelScrollsTheRowAndOnlyConsumesEventsItCanMove() {
        lateinit var scroll: ScrollState
        var unconsumedEvents = 0
        ComposePixelScene(120, 80) {
            scroll = rememberScrollState()
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.type == PointerEventType.Scroll && event.changes.none { it.isConsumed }) unconsumedEvents++
                    }
                }
            }) {
                Row(Modifier.size(100.dp, 40.dp).verticalWheelToHorizontal(scroll).horizontalScroll(scroll)) {
                    repeat(8) { Box(Modifier.size(60.dp, 40.dp).background(if (it % 2 == 0) Color.Red else Color.Blue)) }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            scene.scrollAt(50f, 20f, deltaY = 1f)
            scene.settledFrame()
            assertTrue(scroll.value > 0)
            assertEquals(0, unconsumedEvents)

            scene.scrollAt(50f, 20f, deltaY = -1_000f)
            scene.settledFrame()
            assertEquals(0, scroll.value)
            val beforeBoundary = unconsumedEvents
            scene.scrollAt(50f, 20f, deltaY = -1f)
            scene.settledFrame()
            assertEquals(beforeBoundary + 1, unconsumedEvents, "The parent must receive a wheel event at the left boundary.")

            // Native wheel scrolling keeps ownership until a coroutine timeout, which is separate
            // from the scene's animation clock. Exercise X last so that ownership cannot affect
            // the preceding boundary assertion for the Y-to-X adapter.
            scene.scrollAt(50f, 20f, deltaY = 1f)
            scene.settledFrame()
            val beforeNativeX = scroll.value
            scene.scrollAt(50f, 20f, deltaX = 1f)
            scene.settledFrame()
            assertTrue(scroll.value != beforeNativeX, "Native horizontal scrolling must remain reachable.")
        }
    }

    @Test
    fun aMouseWheelCanReachAndSelectTheLastNativeMiuixMediaTab() {
        var selected = 0
        ComposePixelScene(240, 80) {
            val controller = remember { ThemeController(ColorSchemeMode.Dark) }
            val state = rememberLazyListState()
            var selectedIndex by remember { mutableIntStateOf(0) }
            MiuixTheme(controller = controller) {
                Box(Modifier.offset(10.dp, 10.dp).width(220.dp).height(45.dp)) {
                    TabRowWithContour(
                        tabs = listOf("照片与视频 128", "文件 23", "链接 9", "音乐 2", "语音 5"),
                        selectedTabIndex = selectedIndex,
                        onTabSelected = { selectedIndex = it; selected = it },
                        listState = state,
                        minWidth = 112.dp,
                        maxWidth = 140.dp,
                        modifier = Modifier.verticalWheelToHorizontal(state),
                    )
                }
            }
        }.use { scene ->
            scene.settledFrame()
            scene.scrollAt(120f, 30f, deltaY = 20f)
            scene.settledFrame()
            scene.moveTo(180f, 30f)
            scene.pressAt(180f, 30f)
            scene.releaseAt(180f, 30f)
            scene.settledFrame()
            assertEquals(4, selected, "The last native media tab must be selectable after wheel scrolling.")
        }
    }
}
