package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.status.EngineFailure
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** In-memory pixels and pointer events only; no host, configuration file, client or account. */
class AppWelcomeRenderTest {
    @Test fun startIsExplicitAndAnUnacknowledgedClickCannotSubmitTwice() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame().save("start-light")
            assertEquals(0, fixture.continues)
            scene.click(scene.text("开始使用"))
            scene.settledFrame()
            scene.click(scene.text("开始使用"))
            scene.settledFrame()
            assertEquals(1, fixture.continues)
            assertTrue(scene.texts("打开配置文件").isEmpty())
            scene.settledFrame()
        }
    }

    @Test fun pendingAndInactiveScreensHaveNoDispatchingControls() {
        val fixture = Fixture()
        fixture.state = AppWelcomeState(AppWelcomePhase.INITIALIZING)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("正在准备…"))
            assertTrue(scene.texts("打开配置文件").isEmpty())
            scene.settledFrame()
            fixture.state = AppWelcomeState(active = false)
            scene.settledFrame()
            scene.click(scene.text("开始使用"))
            assertTrue(scene.texts("打开配置文件").isEmpty())
            scene.settledFrame()
            assertEquals(0, fixture.continues)
        }
    }

    @Test fun aRealFailureAfterPreparationKeepsRecoveryTextAndAllowsOneExplicitRetry() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("开始使用"))
            fixture.state = AppWelcomeState(AppWelcomePhase.INITIALIZING, generation = 1)
            scene.settledFrame()
            fixture.state = AppWelcomeState(AppWelcomePhase.FAILED, generation = 1,
                failure = EngineFailure("客户端未能准备完成", "检查本地配置后重试。"),
                configurationOpenError = "配置文件暂时无法打开。")
            scene.settledFrame()
            scene.text("客户端未能准备完成")
            scene.text("检查本地配置后重试。")
            assertTrue(scene.texts("配置文件暂时无法打开。").isEmpty())
            scene.click(scene.text("重试"))
            scene.settledFrame()
            scene.click(scene.text("重试"))
            scene.settledFrame()
            assertEquals(2, fixture.continues)
            assertTrue(scene.texts("开始使用").isEmpty())
        }
    }

    @Test fun themesDensityAndShortWindowsKeepActionsReachableThroughTheActualScroll() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture(width = 360, height = 320, density = density, dark = dark)
            fixture.state = AppWelcomeState(AppWelcomePhase.CONFIGURATION)
            fixture.scene().use { scene ->
                scene.settledFrame()
                assertFalse(scene.texts("nngGram").isEmpty())
                repeat(12) {
                    val button = scene.texts("重试").singleOrNull()
                    if (button == null || button.height <= 0 || button.top < 0 || button.bottom > fixture.pixelHeight - 4) {
                        scene.scrollAt(fixture.pixelWidth / 2f, fixture.pixelHeight / 2f, deltaY = 2f)
                        scene.settledFrame()
                    }
                }
                val button = scene.text("重试")
                assertTrue(button.height > 0 && button.top >= 0 && button.bottom <= fixture.pixelHeight, "The real primary button must scroll into the short viewport.")
                scene.settledFrame().save("configuration-short-${if (dark) "dark" else "light"}-$density")
                scene.click(button)
                scene.settledFrame()
                assertEquals(1, fixture.continues)
            }
        }
    }

    private class Fixture(val width: Int = 760, val height: Int = 680, val density: Float = 1f, val dark: Boolean = false) {
        var state by mutableStateOf(AppWelcomeState())
        var continues = 0
        val pixelWidth get() = (width * density).toInt()
        val pixelHeight get() = (height * density).toInt()
        fun scene() = ComposePixelScene(pixelWidth, pixelHeight) {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                MiuixTheme(controller = theme) {
                    Box(Modifier.requiredSize(width.dp, height.dp)) {
                        AppWelcomeScreen(state, { continues++ })
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.texts(value: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { annotated -> annotated.text == value }
    }
    private fun ComposePixelScene.text(value: String) = texts(value).single()
    private fun ComposePixelScene.click(bounds: Rect) {
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val target = File("build/settings-workspace-validation/app-welcome/$name.png")
        target.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", target))
    }
}
