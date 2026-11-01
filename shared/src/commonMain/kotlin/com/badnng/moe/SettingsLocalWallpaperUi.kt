package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** The controller owns the transaction; this surface only retains the pixels it displays. */
@Composable
internal fun SettingsLocalWallpaperControls(owner: () -> Boolean) {
    val controller = LocalLocalWallpaperController.current ?: return
    val pageActive by rememberUpdatedState(LocalSettingsPageActive.current)
    val currentOwner by rememberUpdatedState(owner)
    // Freeze this visit as well as consulting current composition state: a picker
    // return from an earlier visit must not become valid after reopening the page.
    val ownsPage = { pageActive && owner() && currentOwner() }
    val enabled = pageActive && !controller.busy && owner()
    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    LaunchedEffect(controller, pageActive) {
        if (!pageActive) controller.cancel()
    }
    SettingsGroup("本地壁纸") {
        SettingsAction("从本机选择图片", enabled) {
            if (ownsPage() && !controller.busy) controller.pick(ownsPage)
        }
        SettingsRow("当前来源", if (controller.committed != null) "本地图片" else "内置壁纸",
            summary = if (controller.committed != null)
                "本地图片覆盖上方的内置模式。恢复内置后，仍使用上方选择的跟随主题、白天或夜间模式。"
            else "仅保存在此设备；支持 JPEG、PNG 和 WebP 图片。")
        if (controller.busy) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            SettingsNote("正在处理本地壁纸……")
        }
        controller.error?.let { error ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { SettingsNote(error, error = true) }
        }
        controller.preview?.let { selection ->
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsNote("预览尚未应用；明暗切换只影响此处的消息样例。")
                SettingsLocalWallpaperPreview(selection, controller.previewDark)
                SettingsToggle("夜间预览", controller.previewDark, enabled) {
                    if (ownsPage() && !controller.busy) controller.setPreviewDark(it)
                }
                if (selection.frame.blurAvailable) {
                    SettingsToggle("模糊壁纸", selection.blurred, enabled) {
                        if (ownsPage() && !controller.busy) controller.setBlurred(it)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton("应用本地壁纸", enabled = enabled, primary = true) {
                        if (ownsPage() && !controller.busy) controller.apply(ownsPage)
                    }
                    ChatButton("取消预览", enabled = pageActive && owner()) {
                        if (ownsPage()) controller.cancel()
                    }
                }
            }
        }
        SettingsAction("恢复内置壁纸", enabled && controller.committed != null) {
            if (ownsPage() && !controller.busy) controller.restore(ownsPage)
        }
    }
}

@Composable
private fun SettingsLocalWallpaperPreview(selection: LocalWallpaperSelection, dark: Boolean) {
    val frame = remember(selection.frame) { selection.frame.retain() }
    DisposableEffect(frame) { onDispose { frame.close() } }
    var physicalSize by remember(frame) { mutableStateOf(IntSize.Zero) }
    var preparedVersion by remember(frame) { mutableIntStateOf(0) }
    val blurred = selection.blurred && frame.blurAvailable
    LaunchedEffect(frame, physicalSize, blurred) {
        if (frame.width > 0 && frame.height > 0 && physicalSize.width > 0 && physicalSize.height > 0) {
            val scale = maxOf(physicalSize.width.toFloat() / frame.width, physicalSize.height.toFloat() / frame.height)
            frame.prepare((frame.width * scale).roundToInt().coerceAtLeast(1),
                (frame.height * scale).roundToInt().coerceAtLeast(1), blurred)
            preparedVersion++
        }
    }
    val preferences = LocalSettingsPreferences.current
    val theme = rememberSettingsThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        preferences.accentColor, dark)
    MiuixTheme(controller = theme) {
        val colors = MiuixTheme.colorScheme
        Box(Modifier.fillMaxWidth().height(208.dp).chatSquircleSurface(colors.surface, 15.dp)
            .semantics { contentDescription = if (dark) "本地壁纸夜间预览" else "本地壁纸白天预览" }) {
            Canvas(Modifier.fillMaxSize().onSizeChanged { physicalSize = it }.testTag("settings-local-wallpaper-preview")) {
                // Observe worker completion in the draw scope, even if size and frame identity stayed the same.
                @Suppress("UNUSED_VARIABLE") val preparation = preparedVersion
                if (frame.width <= 0 || frame.height <= 0 || size.width <= 0f || size.height <= 0f) return@Canvas
                val scale = maxOf(size.width / frame.width, size.height / frame.height)
                val width = frame.width * scale
                val height = frame.height * scale
                val left = (size.width - width) / 2f
                val top = (size.height - height) / 2f
                clipRect {
                    frame.draw(this, Rect(left, top, left + width, top + height), blurred)
                }
            }
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("聊天背景预览", Modifier.widthIn(max = 250.dp)
                    .chatSquircleBackground(colors.surfaceContainer, 15.dp).padding(horizontal = 11.dp, vertical = 8.dp),
                    style = chatMessageTextStyle(), color = colors.onSurface)
                Text("应用后用于此设备的聊天背景。", Modifier.align(Alignment.End).widthIn(max = 250.dp)
                    .chatSquircleBackground(lerp(colors.surfaceContainer, colors.primary, .1f), 15.dp)
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                    style = chatMessageTextStyle(), color = colors.onSurface)
            }
        }
    }
}
