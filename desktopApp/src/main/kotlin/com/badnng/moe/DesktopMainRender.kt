package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.badnng.moe.status.ConnectionPhase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import org.jetbrains.skia.EncodedImageFormat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** CPU-raster Compose verification of the explicit synthetic fixture; no native/account window. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun handleDesktopMainRender(args: Array<String>): Boolean {
    if (args.firstOrNull() != "--desktop-main-render") return false
    require(args.size >= 2 && args.drop(2).all {
        it in setOf("--slim", "--compact", "--design-size", "--dark", "--unread", "--info-open", "--list-collapsed", "--operations-open", "--admins-open", "--account-menu-open", "--chat-list-menu-open", "--message-menu-open", "--reaction-picker-open", "--sticker-picker-open", "--attachment-menu-open") ||
            it.startsWith("--menu-progress=")
    } && args.drop(2).distinct().size == args.size - 2 &&
        listOf("--slim", "--compact", "--design-size").count { it in args } <= 1) {
        "Usage: --desktop-main-render <absolute-new.png> [--slim | --compact | --design-size] [--dark] [--unread] " +
            "[--menu-progress=0..1] [--info-open | --list-collapsed | --operations-open | --admins-open | --account-menu-open | --chat-list-menu-open | --message-menu-open | --reaction-picker-open | --sticker-picker-open | --attachment-menu-open]"
    }
    val menuProgress = args.firstOrNull { it.startsWith("--menu-progress=") }?.substringAfter('=')?.toFloatOrNull()
    require(args.count { it.startsWith("--menu-progress=") } <= 1 &&
        (args.none { it.startsWith("--menu-progress=") } || menuProgress != null && menuProgress.isFinite() && menuProgress in 0f..1f))
    val target = Path.of(args[1])
    require(target.isAbsolute && target.fileName.toString().endsWith(".png", ignoreCase = true)) {
        "The render target must be an absolute PNG path."
    }
    require(Files.isDirectory(target.parent) && Files.notExists(target)) {
        "The render target must be new, with an existing parent directory."
    }
    val slim = "--slim" in args
    val compact = "--compact" in args
    val designSize = "--design-size" in args
    val infoOpen = "--info-open" in args
    val listCollapsed = "--list-collapsed" in args
    val operationsOpen = "--operations-open" in args
    val adminsOpen = "--admins-open" in args
    val accountMenuOpen = "--account-menu-open" in args
    val chatListMenuOpen = "--chat-list-menu-open" in args
    val messageMenuOpen = "--message-menu-open" in args
    val reactionPickerOpen = "--reaction-picker-open" in args
    val stickerPickerOpen = "--sticker-picker-open" in args
    val attachmentMenuOpen = "--attachment-menu-open" in args
    require(listOf(infoOpen, listCollapsed, operationsOpen, adminsOpen, accountMenuOpen, chatListMenuOpen, messageMenuOpen, reactionPickerOpen, stickerPickerOpen, attachmentMenuOpen).count { it } <= 1) {
        "Choose only one scene-input fixture."
    }
    require(!(infoOpen || listCollapsed || operationsOpen || adminsOpen) || !slim && !compact && !designSize && menuProgress == null) {
        "Scene-input fixtures use the default 1160x840 layout without a forced account menu."
    }
    require(!accountMenuOpen || menuProgress == null) { "The account-menu-open fixture uses the real reveal animation." }
    require(!(chatListMenuOpen || messageMenuOpen || reactionPickerOpen || stickerPickerOpen || attachmentMenuOpen) || menuProgress == null) { "Context menus use actual scene input." }
    val dark = "--dark" in args
    val width = when { slim -> 380; compact -> 780; designSize -> 1494; else -> 1160 }
    val height = when { compact -> 600; designSize -> 993; else -> 840 }
    dev.nucleusframework.graalvm.GraalVmInitializer.initialize()
    check(!SwingUtilities.isEventDispatchThread()) { "The offline renderer must start outside the event thread." }
    val asynchronousFailure = AtomicReference<Throwable?>()
    val previousHandler = renderOnEventThread {
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler.also {
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                asynchronousFailure.compareAndSet(null, error)
            }
        }
    }
    fun checkAsynchronousFailure() {
        asynchronousFailure.get()?.let { throw IllegalStateException("Compose rendering failed on the event thread.", it) }
    }
    var scene: ImageComposeScene? = null
    val bytes = try {
        val renderScene = renderOnEventThread {
            ImageComposeScene(width, height) {
                val controller = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                MiuixTheme(controller = controller) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        // Reserve the host's controls area without fabricating native window buttons.
                        DesktopTitleContent(ConnectionPhase.READY, Modifier.padding(end = 138.dp), showLabel = !slim)
                        DesktopTitleDivider()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            CompositionLocalProvider(LocalAccountMenuPreviewProgress provides menuProgress) {
                                DesktopMainPreview(windowFocused = true, dark = dark, unread = "--unread" in args,
                                    startInList = width < 980 && (accountMenuOpen || chatListMenuOpen || menuProgress != null && menuProgress > 0f))
                            }
                        }
                    }
                }
            }
        }
        scene = renderScene
        // Each invocation yields the event queue between frames, so lazy-list effects cannot
        // measure concurrently with a render or remain blocked behind the complete frame loop.
        var frameTime = 0L
        fun settleFrames() {
            repeat(24) {
                renderOnEventThread {
                    checkAsynchronousFailure()
                    renderScene.render(frameTime).close()
                }
                frameTime += 64_000_000L
            }
        }
        settleFrames()
        fun pointer(type: PointerEventType, position: Offset, pressed: Boolean = false, secondary: Boolean = false) {
            renderOnEventThread {
                checkAsynchronousFailure()
                renderScene.sendPointerEvent(type, position, timeMillis = frameTime / 1_000_000L,
                    buttons = PointerButtons(isPrimaryPressed = pressed && !secondary, isSecondaryPressed = pressed && secondary),
                    button = if (type == PointerEventType.Press || type == PointerEventType.Release)
                        if (secondary) PointerButton.Secondary else PointerButton.Primary else null)
            }
        }
        fun click(position: Offset) {
            pointer(PointerEventType.Press, position, pressed = true)
            settleFrames()
            pointer(PointerEventType.Release, position)
            settleFrames()
        }
        // All fixture input stays inside this in-memory scene; no OS pointer or native account state is used.
        if (messageMenuOpen || reactionPickerOpen) {
            val message = renderOnEventThread {
                val matches = mutableListOf<Offset>()
                fun visit(node: SemanticsNode) {
                    if (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                        it.text == "这版就按这个方向继续，细节我来补齐。"
                    }) matches += node.boundsInRoot.center
                    node.children.forEach(::visit)
                }
                renderScene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                check(matches.isNotEmpty()) { "The synthetic message must be visible." }
                matches.last()
            }
            pointer(PointerEventType.Press, message, pressed = true, secondary = true)
            pointer(PointerEventType.Release, message, secondary = true)
            settleFrames()
            if (reactionPickerOpen) {
                val more = renderOnEventThread {
                    val matches = mutableListOf<Offset>()
                    fun visit(node: SemanticsNode) {
                        if ("全部表情" in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                            matches += node.boundsInRoot.center
                        node.children.forEach(::visit)
                    }
                    renderScene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                    check(matches.isNotEmpty()) { "The message menu must expose its reaction expansion." }
                    matches.first()
                }
                click(more)
            }
        } else if (stickerPickerOpen || attachmentMenuOpen) {
            val trigger = renderOnEventThread {
                val matches = mutableListOf<Offset>()
                fun visit(node: SemanticsNode) {
                    if ((if (attachmentMenuOpen) "附件" else "贴纸") in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                        matches += node.boundsInRoot.center
                    node.children.forEach(::visit)
                }
                renderScene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                check(matches.size == 1) { "The synthetic scene must expose its composer popup trigger." }
                matches.single()
            }
            pointer(PointerEventType.Move, trigger)
            settleFrames()
            // Synthetic PNG IO completes outside the scene thread.
            Thread.sleep(120)
            settleFrames()
        } else if (chatListMenuOpen) {
            val chat = renderOnEventThread {
                val matches = mutableListOf<Offset>()
                fun visit(node: SemanticsNode) {
                    if (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Miuix 设计交流" })
                        matches += node.boundsInRoot.center
                    node.children.forEach(::visit)
                }
                renderScene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                check(matches.isNotEmpty()) { "The synthetic chat must be visible." }
                matches.first()
            }
            pointer(PointerEventType.Press, chat, pressed = true, secondary = true)
            pointer(PointerEventType.Release, chat, secondary = true)
            settleFrames()
        } else if (accountMenuOpen) {
            val trigger = renderOnEventThread {
                val triggers = mutableListOf<Offset>()
                fun visit(node: SemanticsNode) {
                    if ("打开账号菜单" in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) {
                        triggers += node.boundsInRoot.center
                    }
                    node.children.forEach(::visit)
                }
                renderScene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                check(triggers.size == 1) { "The synthetic scene must expose one account-menu trigger." }
                triggers.single()
            }
            click(trigger)
        } else if (infoOpen) {
            // The fixed default fixture puts the dedicated information action at this position.
            click(Offset(1074f, 93f))
        } else if (listCollapsed) {
            // Default rail=72px, list=354*1160/1494px, and a 6px divider: drag its center to the 72px list minimum.
            val divider = Offset(72f + 354f * 1160f / 1494f + 3f, height / 2f)
            val minimum = Offset(72f + 72f + 3f, divider.y)
            pointer(PointerEventType.Press, divider, pressed = true)
            settleFrames()
            pointer(PointerEventType.Move, minimum, pressed = true)
            settleFrames()
            pointer(PointerEventType.Release, minimum)
            settleFrames()
        } else if (adminsOpen) {
            val trigger = renderOnEventThread {
                val matches = mutableListOf<Offset>()
                fun visit(node: SemanticsNode) {
                    if ("管理员" in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                        matches += node.boundsInRoot.center
                    node.children.forEach(::visit)
                }
                renderScene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                check(matches.size == 1) { "The synthetic scene must expose one header administrator trigger." }
                matches.single()
            }
            click(trigger)
        } else if (operationsOpen) {
            click(Offset(1116f, 93f))
        }
        renderOnEventThread {
            checkAsynchronousFailure()
            val frame = renderScene.render(frameTime + 64_000_000L)
            try {
                val png = checkNotNull(frame.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed." }
                try {
                    png.bytes
                } finally {
                    png.close()
                }
            } finally {
                frame.close()
            }
        }
    } finally {
        try {
            renderOnEventThread { scene?.close() }
        } finally {
            // This final queue turn also observes asynchronous work queued during disposal.
            renderOnEventThread { Thread.currentThread().uncaughtExceptionHandler = previousHandler }
        }
    }
    checkAsynchronousFailure()
    Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    println("DESKTOP_MAIN_RENDER_OK")
    return true
}

private fun <T> renderOnEventThread(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(Callable(action))
    SwingUtilities.invokeAndWait(task)
    return try {
        task.get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }
}
