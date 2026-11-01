package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.badnng.moe.media.LocalAttachmentClipboard
import com.badnng.moe.settings.*
import com.badnng.moe.status.ConnectionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.floor

/** The visible order never changes the source index used by native checks or actions. */
internal data class SettingsProxyDisplayRow(
    val index: Int,
    val proxy: SettingsProxy,
    val check: SettingsProxyCheckRow?,
)

internal fun settingsProxyDisplayRows(
    proxies: List<SettingsProxy>,
    checks: List<SettingsProxyCheckRow>?,
): List<SettingsProxyDisplayRow> = proxies.indices.reversed().map { index ->
    SettingsProxyDisplayRow(index, proxies[index], checks?.getOrNull(index))
}

internal fun settingsProxyIsShareable(proxy: SettingsProxy): Boolean = proxy.type != SettingsProxyType.HTTP

internal fun settingsProxyShareFormat(modifiers: PointerKeyboardModifiers): SettingsProxyShareFormat =
    if (modifiers.isCtrlPressed || modifiers.isAltPressed || modifiers.isShiftPressed) {
        SettingsProxyShareFormat.INTERNAL
    } else SettingsProxyShareFormat.PUBLIC

internal fun settingsProxySharingErrorText(error: SettingsError): String = when (error) {
    SettingsError.NOT_READY -> "代理页面已失效，请重新打开后重试。"
    SettingsError.REQUEST_FAILED -> "分享链接未能生成，请重试。"
    SettingsError.RESULT_UNKNOWN -> "结果尚未确认，请先刷新代理状态。"
    SettingsError.FORBIDDEN -> "当前无法分享此代理。"
    SettingsError.RATE_LIMITED -> "操作过于频繁，请稍后再试。"
    SettingsError.INVALID_VALUE -> "代理数据无效，或分享内容超过剪贴板容量。"
    SettingsError.UNSUPPORTED -> "此代理暂不支持分享。"
    SettingsError.CONFLICT -> "代理已变化，请刷新后重试。"
}

/** Compare the complete visible source and its original check identity, not a sorted index. */
internal fun settingsProxyShareRequestIsCurrent(snapshot: SettingsSnapshot, request: SettingsProxyShareRequest): Boolean {
    if (!snapshot.enabled || SettingsSection.ADVANCED !in snapshot.loaded ||
        SettingsSection.ADVANCED in snapshot.loading || SettingsSection.ADVANCED in snapshot.saving ||
        snapshot.errors[SettingsSection.ADVANCED] != null || snapshot.token != request.token.parent ||
        snapshot.proxyChecks.listEpoch != request.listEpoch || settingsProxyCheckRows(snapshot, request.token) == null) return false
    return when (val target = request.target) {
        is SettingsProxyShareTarget.One -> snapshot.proxies.getOrNull(target.index) == target.proxy &&
            settingsProxyIsShareable(target.proxy)
        is SettingsProxyShareTarget.All -> target.rows == snapshot.proxies && target.rows.isNotEmpty()
    }
}

/** No URL is stored here: the native result is an independently revocable temporary owner. */
internal data class SettingsProxySharingState(
    val serial: Long = 0,
    val request: SettingsProxyShareRequest? = null,
    val qr: Boolean = false,
    val loading: Boolean = false,
    val content: SettingsProxyShareContent? = null,
    val error: SettingsError? = null,
    val copyError: Boolean = false,
) {
    override fun toString() = "SettingsProxySharingState(<redacted>)"
}

/** A retained callback cannot publish into a later request or keep a closed page's result. */
internal class SettingsProxySharingSession(private val page: SettingsProxyChecksPageLease) {
    private val mutable = MutableStateFlow(SettingsProxySharingState())
    val state: StateFlow<SettingsProxySharingState> = mutable

    fun request(request: SettingsProxyShareRequest, qr: Boolean) {
        if (!page.alive.value) return
        var begun: SettingsProxySharingState
        while (true) {
            val previous = mutable.value
            begun = SettingsProxySharingState(previous.serial + 1, request, qr, loading = true)
            if (mutable.compareAndSet(previous, begun)) {
                previous.content?.close()
                break
            }
        }
        if (!page.alive.value) { invalidate(); return }
        try {
            page.share(request) { result -> complete(begun, result) }
        } catch (_: Exception) {
            complete(begun, SettingsResult(error = SettingsError.REQUEST_FAILED))
        }
    }

    private fun complete(begun: SettingsProxySharingState, result: SettingsResult<SettingsProxyShareContent>) {
        val resultContent = result.value
        while (true) {
            val previous = mutable.value
            if (previous !== begun || !page.alive.value) {
                if (previous.content !== resultContent) resultContent?.close()
                return
            }
            val content = resultContent.takeIf { result.error == null }
            val next = begun.copy(loading = false, content = content,
                error = result.error ?: if (content == null) SettingsError.REQUEST_FAILED else null)
            if (mutable.compareAndSet(previous, next)) {
                if (content == null) resultContent?.close()
                if (!page.alive.value) invalidate()
                return
            }
        }
    }

    fun dismiss(expected: SettingsProxySharingState) {
        while (true) {
            val previous = mutable.value
            if (previous !== expected) return
            if (mutable.compareAndSet(previous, SettingsProxySharingState(previous.serial + 1))) {
                previous.content?.close()
                return
            }
        }
    }

    fun invalidate() {
        while (true) {
            val previous = mutable.value
            if (mutable.compareAndSet(previous, SettingsProxySharingState(previous.serial + 1))) {
                previous.content?.close()
                return
            }
        }
    }

    fun fail(expected: SettingsProxySharingState) {
        while (true) {
            val previous = mutable.value
            if (previous !== expected) return
            if (mutable.compareAndSet(previous, previous.copy(content = null, error = SettingsError.CONFLICT))) {
                previous.content?.close()
                return
            }
        }
    }

    fun copyFailed(expected: SettingsProxySharingState) {
        while (true) {
            val previous = mutable.value
            if (previous !== expected) return
            if (mutable.compareAndSet(previous, previous.copy(copyError = true))) return
        }
    }
}

internal class SettingsProxySharingCallbacks(
    val canShare: Boolean,
    val canShareList: Boolean,
    val shareOne: (Int, SettingsProxy) -> Unit,
    val showQr: (Int, SettingsProxy) -> Unit,
    val shareList: () -> Unit,
)

/** Dialog state is tied to the exact checks page and invalidated synchronously on refresh/leave. */
@Composable
internal fun SettingsProxySharingHost(
    snapshot: SettingsSnapshot,
    page: SettingsProxyChecksPageLease?,
    checksToken: SettingsProxyChecksToken?,
    available: Boolean,
    ownsPage: () -> Boolean,
    content: @Composable (SettingsProxySharingCallbacks) -> Unit,
) {
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestAvailable by rememberUpdatedState(available)
    val latestOwnsPage by rememberUpdatedState(ownsPage)
    val window = LocalWindowInfo.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val qrClipboard = LocalAttachmentClipboard.current as? SettingsProxyQrClipboard
    val textClipboard = LocalAttachmentClipboard.current as? SettingsProxyTextClipboard
    val session = remember(page) { page?.let(::SettingsProxySharingSession) }
    val fallback = remember { MutableStateFlow(SettingsProxySharingState()) }
    val state by (session?.state ?: fallback).collectAsState()
    // Closures from an outgoing dialog must retain the exact rendered request.
    val renderedState = state
    val revoke = remember(session) { { session?.invalidate(); Unit } }
    DisposableEffect(page, session) {
        if (page != null && session != null) page.installShareRevoker(revoke)
        onDispose { if (page != null && session != null) page.removeShareRevoker(revoke) }
    }
    fun current(request: SettingsProxyShareRequest): Boolean = session != null && page != null && page.alive.value &&
        latestAvailable && latestOwnsPage() && settingsProxyShareRequestIsCurrent(latestSnapshot, request)

    fun submit(target: SettingsProxyShareTarget, format: SettingsProxyShareFormat, qr: Boolean) {
        val token = checksToken ?: return
        val request = SettingsProxyShareRequest(token, snapshot.proxyChecks.listEpoch, target, format)
        if (current(request)) session?.request(request, qr)
    }

    val shareReady = checksToken != null && page != null && available && ownsPage() &&
        snapshot.errors[SettingsSection.ADVANCED] == null && settingsProxyCheckRows(snapshot, checksToken) != null
    val callbacks = SettingsProxySharingCallbacks(
        canShare = shareReady,
        // The 256-row view cannot prove that a shareable native item is absent.
        canShareList = shareReady && snapshot.proxies.isNotEmpty(),
        shareOne = { index, proxy ->
            submit(SettingsProxyShareTarget.One(index, proxy), settingsProxyShareFormat(window.keyboardModifiers), false)
        },
        showQr = { index, proxy ->
            submit(SettingsProxyShareTarget.One(index, proxy), SettingsProxyShareFormat.INTERNAL, true)
        },
        shareList = { submit(SettingsProxyShareTarget.All(snapshot.proxies.toList()), SettingsProxyShareFormat.PUBLIC, false) },
    )
    content(callbacks)

    val request = renderedState.request
    val valid = request != null && current(request)
    SideEffect { if (request != null && !valid) session?.dismiss(renderedState) }
    val qrPayload = if (valid && renderedState.qr) renderedState.content?.textIfCurrent() else null
    val qr = remember(session, renderedState.serial, qrPayload) { qrPayload?.let(LoginQrMatrix::encode) }
    val density = LocalDensity.current
    val viewport = with(density) { window.containerSize.height.toDp() }
    val bodyHeight = (viewport - 128.dp).coerceAtLeast(100.dp)
    // Keep the whole white canvas inside the real scroll viewport, with room to
    // reach it using wheel input. Buttons remain independently scrollable.
    val qrSide = minOf(204.dp, (bodyHeight - 8.dp).coerceAtLeast(1.dp))
    val qrFits = qr?.let { floor(with(density) { qrSide.toPx() } / (it.size + 8)) >= 1f } == true
    LocalSettingsSecurityConfirmationHost.current.Content(valid,
        title = if (renderedState.qr) "代理二维码" else "分享代理", summary = null, maxWidth = 420.dp,
        onDismissRequest = { session?.dismiss(renderedState) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = bodyHeight).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNote(if (request?.target is SettingsProxyShareTarget.All)
                "包含所有可分享代理，包括此处未显示的代理；链接可能包含代理登录信息。"
            else "链接可能包含代理登录信息。")
            when {
                renderedState.loading -> SettingsNote("正在准备分享链接……")
                renderedState.error != null -> SettingsNote(settingsProxySharingErrorText(renderedState.error), error = true)
                renderedState.content == null -> SettingsNote("分享链接暂不可用。", error = true)
                renderedState.qr && (qr == null || !qrFits) -> SettingsNote(
                    if (qrPayload == null) "代理已变化，请重新尝试。"
                    else if (qr == null) "二维码无法生成，可复制链接。"
                    else "当前窗口不足以完整显示二维码，可复制二维码或链接。", error = true)
                renderedState.qr -> {
                    val matrix = checkNotNull(qr)
                    Canvas(Modifier.size(qrSide).background(Color.White)
                        .semantics { contentDescription = "代理二维码" }) {
                        val actualContent = renderedState.content
                        val actualRequest = renderedState.request
                        if (session != null && actualContent != null && actualRequest != null && qrPayload != null &&
                            session.state.value === renderedState && current(actualRequest) &&
                            actualContent.textIfCurrent() == qrPayload) {
                            val cell = floor(size.minDimension / (matrix.size + 8))
                            val start = (size.minDimension - matrix.size * cell) / 2f
                            for (y in 0 until matrix.size) for (x in 0 until matrix.size) if (matrix[x, y]) {
                                drawRect(Color.Black, Offset(start + x * cell, start + y * cell), Size(cell, cell))
                            }
                        }
                    }
                    if (qrClipboard == null) SettingsNote("此窗口暂不支持复制二维码图片，可扫描或复制链接。")
                }
                else -> SettingsNote("链接已准备好，点击下方按钮复制。")
            }
            if (renderedState.copyError) SettingsNote("无法复制，请重试。", error = true)
            if (renderedState.qr && qr != null && qrPayload != null && renderedState.content != null) {
                ChatButton("复制二维码", Modifier.fillMaxWidth(), enabled = valid && qrClipboard != null, onClick = {
                    val expected = renderedState
                    val expectedRequest = expected.request
                    val expectedContent = expected.content
                    val matrix = checkNotNull(qr)
                    val payload = checkNotNull(qrPayload)
                    if (session != null && expectedRequest != null && expectedContent != null && qrClipboard != null &&
                        session.state.value === expected && current(expectedRequest)) {
                        if (expectedContent.textIfCurrent() != payload) session.fail(expected)
                        else {
                            val modules = BooleanArray(matrix.size * matrix.size) { index ->
                                matrix[index % matrix.size, index / matrix.size]
                            }
                            // Encode before acquiring the native commit lock. Nothing is published here.
                            val prepared = try {
                                runCatching { qrClipboard.prepareProxyQr(matrix.size, modules) }.getOrNull()
                            } finally { modules.fill(false) }
                            if (prepared == null) {
                                if (current(expectedRequest) && expectedContent.textIfCurrent() == payload)
                                    session.copyFailed(expected) else session.fail(expected)
                            } else try {
                                var attempted = false
                                var copied = false
                                val admitted = expectedContent.useIfCurrent { value ->
                                    if (value == payload && session.state.value === expected && current(expectedRequest)) {
                                        attempted = true
                                        copied = runCatching {
                                            prepared.publishIfCurrent {
                                                session.state.value === expected && current(expectedRequest) &&
                                                    expectedContent.textIfCurrent() == payload
                                            }
                                        }.getOrDefault(false)
                                    }
                                }
                                when {
                                    !admitted || !attempted -> session.fail(expected)
                                    copied -> session.dismiss(expected)
                                    !current(expectedRequest) || expectedContent.textIfCurrent() != payload -> session.fail(expected)
                                    else -> session.copyFailed(expected)
                                }
                            } finally { prepared.close() }
                        }
                    }
                })
            }
            if (renderedState.content != null && !renderedState.loading) {
                ChatButton("复制链接", Modifier.fillMaxWidth(), primary = true, enabled = valid, onClick = {
                    val expected = renderedState
                    val expectedRequest = expected.request
                    val expectedContent = expected.content
                    if (session != null && expectedRequest != null && expectedContent != null &&
                        session.state.value === expected && current(expectedRequest)) {
                        val payload = expectedContent.textIfCurrent()
                        if (payload == null) session.fail(expected)
                        else if (textClipboard != null) {
                            // Large list encoding is deliberately outside the native owner lock.
                            val prepared = runCatching { textClipboard.prepareProxyText(payload) }.getOrNull()
                            if (prepared == null) {
                                if (current(expectedRequest) && expectedContent.textIfCurrent() == payload)
                                    session.copyFailed(expected) else session.fail(expected)
                            } else try {
                                var attempted = false
                                var copied = false
                                val admitted = expectedContent.useIfCurrent { value ->
                                    if (value == payload && session.state.value === expected && current(expectedRequest)) {
                                        attempted = true
                                        copied = runCatching {
                                            prepared.publishIfCurrent {
                                                session.state.value === expected && current(expectedRequest) &&
                                                    expectedContent.textIfCurrent() == payload
                                            }
                                        }.getOrDefault(false)
                                    }
                                }
                                when {
                                    !admitted || !attempted -> session.fail(expected)
                                    copied -> session.dismiss(expected)
                                    !current(expectedRequest) || expectedContent.textIfCurrent() != payload -> session.fail(expected)
                                    else -> session.copyFailed(expected)
                                }
                            } finally { prepared.close() }
                        } else {
                            // Legacy hosts have no success result; do not report a successful copy.
                            var committed = false
                            expectedContent.useIfCurrent { value ->
                                if (value == payload && session.state.value === expected && current(expectedRequest) &&
                                    expectedContent.textIfCurrent() == value) {
                                    clipboard.setText(AnnotatedString(value))
                                    committed = true
                                }
                            }
                            if (committed) session.dismiss(expected) else session.fail(expected)
                        }
                    }
                })
            }
            ChatButton("关闭", Modifier.fillMaxWidth(), onClick = { session?.dismiss(renderedState) })
        }
    }
}

/** This box is the real popup anchor, so scrolling and resizing keep the menu with its row. */
@Composable
internal fun SettingsProxyRow(
    row: SettingsProxyDisplayRow,
    connection: ConnectionPhase,
    enabled: Boolean,
    canAct: () -> Boolean,
    onEnable: () -> Unit,
    onRemove: () -> Unit,
    share: SettingsProxySharingCallbacks,
) {
    val proxy = row.proxy
    var menuOpen by remember(row.index, proxy) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            SettingsRow("${proxy.server}:${proxy.port}", if (proxy.enabled) "已启用" else proxy.type.name,
                summary = settingsProxySummary(row.check, proxy, connection), enabled = enabled, onClick = onEnable)
        }
        Box {
            ChatButton("操作", enabled = enabled, onClick = { if (canAct()) menuOpen = true },
                modifier = Modifier.semantics { contentDescription = "代理操作 ${proxy.server}:${proxy.port}" })
            ChatShortcutPopup(show = menuOpen && enabled && canAct(), paneTitle = "代理操作",
                verticalMargin = 4.dp, surfaceOpacity = 0.94f,
                onDismissRequest = { menuOpen = false }) {
                if (settingsProxyIsShareable(proxy)) {
                    SettingsAction("分享代理", enabled = enabled && share.canShare) {
                        menuOpen = false
                        if (canAct()) share.shareOne(row.index, proxy)
                    }
                    SettingsAction("显示二维码", enabled = enabled && share.canShare) {
                        menuOpen = false
                        if (canAct()) share.showQr(row.index, proxy)
                    }
                } else SettingsNote("HTTP 代理不支持分享。")
            }
        }
    }
    SettingsAction("删除 ${proxy.server}:${proxy.port}", enabled, destructive = true, onClick = onRemove)
}
