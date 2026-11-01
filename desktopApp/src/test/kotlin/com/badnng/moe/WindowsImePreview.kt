@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.badnng.moe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Test-only headful probe. No configuration store, TDLib, account, network or text logging. */
fun main(args: Array<String>) {
    require(args.isEmpty() || args.contentEquals(arrayOf("--baseline")))
    val useSessionBoundary = args.isEmpty()
    check(System.getProperty("os.name").startsWith("Windows"))
    nucleusApplication(emptyArray(), backend = NucleusBackend.Tao, enableSingleInstance = false) {
        val theme = remember { ThemeController(ColorSchemeMode.Light) }
        val audit = remember { WindowsImePreviewAudit() }
        MiuixTheme(controller = theme) {
            NucleusDecoratedWindowTheme(isDark = false) {
                DecoratedWindow(onCloseRequest = ::exitApplication, title = "nngGram · 离线 IME 诊断（不联网）",
                    state = rememberWindowState(width = 940.dp, height = 840.dp)) {
                    val hwnd = checkNotNull(nucleusWindow.unsafe.taoWindow?.nativeHandle)
                    val native = remember(hwnd) { WindowsImeMessages(hwnd, audit) }
                    DisposableEffect(native) {
                        native.attach()
                        onDispose { native.close() }
                    }
                    Column(Modifier.fillMaxSize()) {
                        TitleBar { Text("离线 IME 诊断 · 不记录输入正文") }
                        WindowsImePreviewContent(audit, native, useSessionBoundary)
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowsImePreviewContent(audit: WindowsImePreviewAudit, native: WindowsImeMessages, useSessionBoundary: Boolean) {
    var value by remember { mutableStateOf(TextFieldValue()) }
    var other by remember { mutableStateOf(TextFieldValue()) }
    var fieldGeneration by remember { mutableIntStateOf(0) }
    var recompositions by remember { mutableIntStateOf(0) }
    var localSends by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(audit.snapshot()) }
    val primary = remember { FocusRequester() }
    val secondary = remember { FocusRequester() }
    // Changing an interceptor restarts upstream input. Keep this exact instance through every poll/recomposition.
    val interceptor = remember(audit) {
        PlatformTextInputInterceptor { request, nextHandler ->
            val session = audit.startSession()
            val forwarded = object : PlatformTextInputMethodRequest by request {
                override val editText: (TextEditingScope.() -> Unit) -> Unit = { block ->
                    audit.edit(session)
                    request.editText(block)
                }
            }
            try {
                coroutineScope {
                    launch {
                        snapshotFlow {
                            val current = request.value()
                            "len=${current.text.length} selection=${current.selection.start}:${current.selection.end} " +
                                "composition=${current.composition?.let { "${it.start}:${it.end}" } ?: "none"}"
                        }.collect { audit.record("VALUE session=$session $it") }
                    }
                    nextHandler.startInputMethod(forwarded)
                }
            } finally { audit.endSession(session) }
        }
    }
    LaunchedEffect(native) {
        while (true) {
            audit.nativeState(native.readState())
            report = audit.snapshot()
            delay(200)
        }
    }
    LaunchedEffect(fieldGeneration) {
        withFrameNanos { }
        primary.requestFocus()
    }
    LaunchedEffect(menuOpen) {
        if (!menuOpen) {
            withFrameNanos { }
            primary.requestFocus()
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("仅离线合成输入。可切换中英文、候选提交/取消、切焦点或重建字段；日志只含长度、事件与句柄状态。")
        val editors: @Composable () -> Unit = { InterceptPlatformTextInput(interceptor) {
            key(fieldGeneration) {
                TextField(value, { value = it }, label = "主要测试输入框", modifier = Modifier.fillMaxWidth()
                    .focusProperties { canFocus = !menuOpen }
                    .focusRequester(primary).onFocusChanged { audit.focus("primary", it.isFocused) }, maxLines = 4)
            }
            TextField(other, { other = it }, label = "第二输入框（焦点交接）", modifier = Modifier.fillMaxWidth()
                .focusProperties { canFocus = !menuOpen }
                .focusRequester(secondary).onFocusChanged { audit.focus("secondary", it.isFocused) }, maxLines = 2)
        } }
        if (useSessionBoundary) WindowsImeTextInputHost(editors) else editors()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                localSends++
                value = TextFieldValue()
                primary.requestFocus()
                audit.record("LOCAL_SEND count=$localSends")
            }, modifier = Modifier.focusProperties { canFocus = false },
                enabled = value.text.isNotBlank() && value.composition == null) { Text("本地发送（无网络）") }
            Button(onClick = { secondary.requestFocus() }) { Text("切第二框") }
            Button(onClick = { recompositions++; audit.record("RECOMPOSE count=$recompositions") }) { Text("仅重组") }
            Button(onClick = { fieldGeneration++; audit.record("REBUILD generation=$fieldGeneration") }) { Text("重建输入框") }
        }
        Button(onClick = { menuOpen = true; audit.record("MENU_OPEN") }, modifier = Modifier.fillMaxWidth()) {
            Text("打开菜单 / 会话修复=${if (useSessionBoundary) "开" else "关"}")
        }
        if (menuOpen) Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 240),
            onDismissRequest = { menuOpen = false; audit.record("MENU_DISMISS") },
            properties = PopupProperties(focusable = true)) {
            val popupFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { popupFocus.requestFocus() }
            Column(Modifier.width(290.dp).padding(16.dp).focusRequester(popupFocus).focusable()) {
                Text("仅离线菜单，点击空白后继续中文输入")
                Button(onClick = { menuOpen = false; audit.record("MENU_CLOSE") }) { Text("关闭菜单") }
            }
        }
        Text("本地发送=$localSends　重组=$recompositions　字段代次=$fieldGeneration")
        Text("主框长度=${value.text.length}　组合长度=${value.composition?.length ?: 0}　第二框长度=${other.text.length}")
        Text(report.native)
        Text("Compose session: start=${report.started} end=${report.ended} active=${report.active} edit=${report.edits}")
        Text("原生事件计数：${report.counts}")
        Text("最近事件（无正文）：")
        report.lines.takeLast(14).forEach { Text(it) }
    }
}

private data class WindowsImeReport(val started: Long, val ended: Long, val active: Long?, val edits: Long,
    val native: String, val counts: String, val lines: List<String>)

private class WindowsImePreviewAudit {
    private var started = 0L
    private var ended = 0L
    private var active: Long? = null
    private var edits = 0L
    private var native = "原生探针等待窗口关联"
    private val counts = linkedMapOf<String, Long>()
    private val lines = ArrayDeque<String>()
    @Synchronized fun startSession(): Long = (++started).also { active = it; record("SESSION_START id=$it") }
    @Synchronized fun endSession(id: Long) { ended++; if (active == id) active = null; record("SESSION_END id=$id") }
    @Synchronized fun edit(id: Long) { edits++; record("EDIT session=$id count=$edits") }
    @Synchronized fun focus(field: String, focused: Boolean) = record("COMPOSE_FOCUS field=$field focused=$focused")
    @Synchronized fun nativeState(value: String) { native = value }
    @Synchronized fun nativeEvent(name: String, flags: Long, state: String) {
        counts[name] = (counts[name] ?: 0) + 1
        record("$name flags=0x${flags.toString(16)} $state")
    }
    @Synchronized fun record(line: String) {
        lines.addLast(line)
        while (lines.size > 80) lines.removeFirst()
        println("IME_PREVIEW $line")
    }
    @Synchronized fun snapshot() = WindowsImeReport(started, ended, active, edits, native,
        counts.entries.joinToString { "${it.key}=${it.value}" }, lines.toList())
}

/** Metadata-only window-thread observer; every message always reaches DefSubclassProc unchanged. */
private class WindowsImeMessages(private val handle: Long, private val audit: WindowsImePreviewAudit) : AutoCloseable {
    private val hwnd = Pointer(handle)
    private val user = NativeLibrary.getInstance("user32")
    private val imm = NativeLibrary.getInstance("imm32")
    private val kernel = NativeLibrary.getInstance("kernel32")
    private val controls = NativeLibrary.getInstance("comctl32")
    private val windowThread = user.fn("GetWindowThreadProcessId").invokeInt(arrayOf(hwnd, null))
    private var attached = false
    private var closed = false
    private val callback = object : SubclassCallback {
        override fun invoke(window: Pointer, message: Int, wParam: Long, lParam: Long, id: Long, data: Long): Long {
            try {
                if (!closed) {
                    val name = when (message) {
                        0x010D -> "IME_START"
                        0x010F -> "IME_COMPOSITION"
                        0x010E -> "IME_END"
                        0x0281 -> "IME_SETCONTEXT"
                        0x0051 -> "INPUTLANGCHANGE"
                        0x0007 -> "NATIVE_FOCUS"
                        0x0008 -> "NATIVE_BLUR"
                        0x0100, 0x0104 -> "VK_PROCESSKEY".takeIf { wParam == 0xE5L }
                        else -> null
                    }
                    if (name != null) audit.nativeEvent(name,
                        if (message == 0x010F || message == 0x0051) lParam else wParam, readState())
                }
            } catch (error: Throwable) {
                audit.record("NATIVE_OBSERVER_ERROR type=${error.javaClass.simpleName}")
            }
            val destroying = message == 0x0082
            if (destroying) close() // Remove on WM_NCDESTROY before forwarding, on this HWND's owning thread.
            val result = controls.fn("DefSubclassProc").invokeLong(arrayOf(window, message, wParam, lParam))
            if (destroying) { attached = false; retained.remove(handle) }
            return result
        }
    }
    fun attach() {
        requireWindowThread()
        check(!closed && controls.fn("SetWindowSubclass").invokeInt(arrayOf(hwnd, callback, SUBCLASS_ID, 0L)) != 0)
        attached = true
        retained[handle] = this // Strong callback ownership until successful removal or native destruction.
        audit.record("NATIVE_ATTACHED ${readState()}")
    }
    fun readState(): String {
        val context = imm.fn("ImmGetContext").invokePointer(arrayOf(hwnd))
        val hasContext = context != null && Pointer.nativeValue(context) != 0L
        val open = if (hasContext) imm.fn("ImmGetOpenStatus").invokeInt(arrayOf(context)) != 0 else null
        if (hasContext) imm.fn("ImmReleaseContext").invokeInt(arrayOf(hwnd, context))
        val hkl = Pointer.nativeValue(user.fn("GetKeyboardLayout").invokePointer(arrayOf(windowThread)))
        val language = when (hkl.toInt() and 0x3ff) { 4 -> "中文"; 9 -> "英文"; else -> "其他" }
        val focused = Pointer.nativeValue(user.fn("GetFocus").invokePointer(emptyArray())) == handle
        return "thread=${windowThread.toLong() and 0xffff_ffffL} HKL=0x${java.lang.Long.toHexString(hkl)}($language) HIMC=${if (hasContext) "ready" else "null"} IME-open=$open nativeFocus=$focused"
    }
    override fun close() {
        requireWindowThread()
        closed = true
        if (!attached) return
        if (controls.fn("RemoveWindowSubclass").invokeInt(arrayOf(hwnd, callback, SUBCLASS_ID)) != 0) {
            attached = false
            retained.remove(handle)
            audit.record("NATIVE_DETACHED")
        }
    }
    private fun requireWindowThread() {
        check(kernel.fn("GetCurrentThreadId").invokeInt(emptyArray()) == windowThread) { "IME observer requires the HWND owner thread" }
    }
    private fun NativeLibrary.fn(name: String) = getFunction(name, Function.ALT_CONVENTION)
    private interface SubclassCallback : StdCallLibrary.StdCallCallback {
        fun invoke(hwnd: Pointer, message: Int, wParam: Long, lParam: Long, id: Long, data: Long): Long
    }
    companion object {
        private const val SUBCLASS_ID = 0x4E4E47494D45L
        private val retained = mutableMapOf<Long, WindowsImeMessages>()
    }
}
