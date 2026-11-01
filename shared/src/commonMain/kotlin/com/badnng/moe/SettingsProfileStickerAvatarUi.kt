package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** The app root supplies an engine-owned provider; cell and clip consumers close only their own leases. */
internal data class ProfileStickerAvatarArtworkClient(val provider: ProfileStickerAvatarArtworkProvider?, val generation: Long)
internal val LocalProfileStickerAvatarArtwork = staticCompositionLocalOf { ProfileStickerAvatarArtworkClient(null, 0) }
@Composable
fun ProfileStickerAvatarArtworkHost(provider: ProfileStickerAvatarArtworkProvider?, generation: Long, content: @Composable () -> Unit) {
    val value = remember(provider, generation) { ProfileStickerAvatarArtworkClient(provider, generation) }
    CompositionLocalProvider(LocalProfileStickerAvatarArtwork provides value, content = content)
}

/** JVM actual observes the existing Tao activity flow; no preview override changes the real host. */
@Composable internal expect fun profileStickerAvatarWindowActive(): Boolean

/** Optional test hook. Payload is captured in composition; retained closures still revalidate the opening. */
internal data class ProfileStickerAvatarUiProbe(val dismiss: () -> Unit, val confirm: () -> Unit,
    val submit: () -> Unit, val cancelConfirmation: () -> Unit, val opening: SettingsProfileStickerAvatarOpening)
internal val LocalProfileStickerAvatarUiProbe = staticCompositionLocalOf<((ProfileStickerAvatarUiProbe) -> Unit)?> { null }
internal data class ProfileStickerAvatarCatalogProbe(val label: String, val action: () -> Unit)
internal val LocalProfileStickerAvatarCatalogProbe = staticCompositionLocalOf<((ProfileStickerAvatarCatalogProbe) -> Unit)?> { null }
internal data class ProfileStickerAvatarHslProbe(val label: String, val keyStep: (Int) -> Unit)
internal val LocalProfileStickerAvatarHslProbe = staticCompositionLocalOf<((ProfileStickerAvatarHslProbe) -> Unit)?> { null }

@Composable
internal fun SettingsProfileStickerAvatarBuilder(opening: SettingsProfileStickerAvatarOpening?,
    snapshot: SettingsProfileExtrasSnapshot, enabled: Boolean, onDismiss: (SettingsProfileStickerAvatarOpening) -> Unit) {
    if (opening == null) return
    val pageActive = LocalSettingsPageActive.current
    val current = pageActive && snapshot.token == opening.parent
    val focused = LocalWindowInfo.current.isWindowFocused && profileStickerAvatarWindowActive()
    LaunchedEffect(opening, focused) { if (!focused) opening.clearFrozenPreview() }
    val latestDismiss by rememberUpdatedState(onDismiss)
    SideEffect { opening.environment(current, enabled && pageActive && !snapshot.loading && snapshot.data != null, snapshot.busy, snapshot.data?.photoId) }
    DisposableEffect(opening) { onDispose { opening.close() } }
    LaunchedEffect(opening, current, enabled, snapshot.loading, snapshot.busy, snapshot.data) {
        if (current) {
            opening.environment(true, enabled && pageActive && !snapshot.loading && snapshot.data != null, snapshot.busy, snapshot.data?.photoId)
            opening.open()
        } else { opening.close(); latestDismiss(opening) }
    }
    LaunchedEffect(opening.accepted) { if (opening.accepted) { opening.close(); latestDismiss(opening) } }
    var narrowCatalog by remember(opening) { mutableStateOf(false) }
    val capturedConfirmation = opening.confirmation
    val dismiss = { opening.close(); latestDismiss(opening) }
    val confirm = { opening.confirm(); if (opening.confirmation != null) narrowCatalog = false }
    val submit: () -> Unit = { capturedConfirmation?.let(opening::submit) }
    val cancelConfirmation: () -> Unit = { capturedConfirmation?.let(opening::cancelConfirm) }
    val sink = LocalProfileStickerAvatarUiProbe.current
    val payload = ProfileStickerAvatarUiProbe(dismiss, confirm, submit, cancelConfirmation, opening)
    SideEffect { sink?.invoke(payload) }
    LocalSettingsSecurityConfirmationHost.current.Content(true, "创建表情头像", null, 680.dp, dismiss) {
        val density = LocalDensity.current
        val window = LocalWindowInfo.current
        val maxHeight = with(density) { window.containerSize.height.toDp() }
        var bodyViewport by remember(opening) { mutableStateOf(Rect.Zero) }
        val bodyScroll = rememberScrollState()
        LaunchedEffect(opening.confirmation) { if (opening.confirmation != null) bodyScroll.scrollTo(0) }
        LaunchedEffect(narrowCatalog) { bodyScroll.scrollTo(0) }
        Column(Modifier.fillMaxWidth().heightIn(max = (maxHeight - 148.dp).coerceIn(96.dp, 620.dp)),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("sticker-avatar-body")
                .onGloballyPositioned { bodyViewport = it.boundsInWindow() }) {
                Column(Modifier.fillMaxWidth().verticalScroll(bodyScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val version = opening.version // Compose observes mutations performed by pure editor operations.
                    val editing = opening.colorsEdit
                    val confirmation = opening.confirmation
                    if (editing != null) {
                        AvatarColorEditor(opening, editing, version)
                    } else {
                        if (confirmation != null) {
                            Text("确认设置头像？", fontSize = 16.sp)
                            Text("将使用当前选择和背景颜色。", fontSize = 13.sp)
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth >= 520.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                AvatarDesignPanel(opening, bodyViewport, current && focused, Modifier.weight(1f), version)
                                AvatarCatalogPanel(opening, bodyViewport, current && focused, Modifier.weight(1f))
                            } else if (narrowCatalog && confirmation == null) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    ChatButton("返回设计", enabled = opening.active && !opening.waitingForSave) { narrowCatalog = false }
                                    AvatarCatalogPanel(opening, bodyViewport, current && focused, Modifier.fillMaxWidth())
                                }
                            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (confirmation == null)
                                    ChatButton("选择表情或贴纸", enabled = opening.editingEnabled) {
                                        if (opening.retainPreviewBeforeNavigation()) narrowCatalog = true
                                    }
                                opening.windowNotice?.let { SettingsNote(it, error = true) }
                                AvatarDesignPanel(opening, bodyViewport, current && focused, Modifier.fillMaxWidth(), version)
                            }
                        }
                    }
                    opening.error?.let { SettingsNote(it, error = true) }
                    if (opening.picker == null && !opening.loading && opening.error != null) {
                        val revision = opening.errorRevision
                        ChatButton("重试读取", enabled = opening.active) { opening.retryOpen(revision) }
                    }
                }
            }
            val editing = opening.colorsEdit
            val confirmation = opening.confirmation
            Row(Modifier.fillMaxWidth().testTag("sticker-avatar-actions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    editing != null -> {
                        ChatButton("取消颜色", Modifier.weight(1f), enabled = opening.editable) { opening.finishColors(editing, false) }
                        ChatButton("应用颜色", Modifier.weight(1f), enabled = opening.editable, primary = true) { opening.finishColors(editing, true) }
                    }
                    confirmation != null -> {
                        ChatButton(if (opening.waitingForSave) "关闭" else "返回编辑", Modifier.weight(1f),
                            onClick = if (opening.waitingForSave) dismiss else cancelConfirmation)
                        ChatButton(if (opening.waitingForSave) "正在保存…" else "确认保存", Modifier.weight(1f),
                            enabled = opening.editable, primary = true, onClick = submit)
                    }
                    else -> {
                        ChatButton("取消", Modifier.weight(1f), onClick = dismiss)
                        ChatButton("保存", Modifier.weight(1f), enabled = opening.editable && opening.selectionUsable,
                            primary = true, onClick = confirm)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarDesignPanel(opening: SettingsProfileStickerAvatarOpening, viewport: Rect, mediaActive: Boolean,
    modifier: Modifier, version: Int) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AvatarPreview(opening, viewport, mediaActive, Modifier.size(168.dp))
        if (opening.selectionNeedsReselection)
            SettingsNote("已保留上次选择的草稿；请重新读取并选择后保存。")
        else if (opening.editor.selection != null)
            Text(if (opening.loading) "正在验证所选内容…" else "已选择", fontSize = 13.sp)
        Text("背景颜色", modifier = Modifier.align(Alignment.Start), fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(AvatarPreviewPalettes.size) { index ->
                val colors = checkNotNull(AvatarPreviewPalettes.get(index))
                AvatarColorSwatch(colors, opening.editor.selectedPalette == index, "预设颜色 ${index + 1}",
                    opening.editingEnabled, Modifier.weight(1f).aspectRatio(1f)) { opening.choosePalette(index) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatButton("自定义颜色", Modifier.weight(1f), enabled = opening.editingEnabled) { opening.startColors() }
            if (opening.editor.hasCustomColors && opening.editor.selectedPalette != null)
                ChatButton("恢复自定义", Modifier.weight(1f), enabled = opening.editingEnabled) { opening.chooseCustom() }
        }
    }
}

@Composable
private fun AvatarColorEditor(opening: SettingsProfileStickerAvatarOpening, edit: AvatarPreviewEditor.ColorsEdit, version: Int) {
    val colors = checkNotNull(AvatarPreviewColors.from(edit.colors()))
    val index = edit.selectedIndex
    val hsl = opening.colorHsl(edit)
    val revision = opening.colorControlRevision
    Text("自定义背景", fontSize = 16.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        edit.colors().forEachIndexed { i, rgb ->
            AvatarColorSwatch(checkNotNull(AvatarPreviewColors.from(listOf(rgb))), i == index, "颜色 ${i + 1}",
                opening.editable, Modifier.size(38.dp)) { opening.colorIndex(edit, i) }
        }
        ChatButton("＋", enabled = opening.editable && colors.size < 4) { opening.colorAdd(edit) }
        ChatButton("－", enabled = opening.editable && colors.size > 1) { opening.colorRemove(edit) }
    }
    @Composable fun control(label: String, value: Float, range: ClosedFloatingPointRange<Float>, part: AvatarHslPart) {
        val focus = remember(edit, part) { FocusRequester() }
        val enabled = opening.editable
        val keyStep: (Int) -> Unit = { direction ->
            if ((direction == -1 || direction == 1) && opening.editable && opening.colorsEdit === edit &&
                edit.selectedIndex == index && opening.colorControlRevision == revision) {
                val current = opening.colorHsl(edit)
                val actual = when (part) {
                    AvatarHslPart.HUE -> current.hue
                    AvatarHslPart.SATURATION -> current.saturation * 100f
                    AvatarHslPart.LIGHTNESS -> current.lightness * 100f
                }
                opening.colorChange(edit, index, revision, part,
                    (actual + direction * (range.endInclusive - range.start) / 30f).coerceIn(range))
            }
        }
        val sink = LocalProfileStickerAvatarHslProbe.current
        val payload = ProfileStickerAvatarHslProbe(label, keyStep)
        SideEffect { sink?.invoke(payload) }
        Text("$label ${value.roundToInt()}", fontSize = 13.sp)
        Slider(value = value, valueRange = range, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
        }.focusRequester(focus).onPreviewKeyEvent { event ->
            if (!opening.editable || event.key != Key.DirectionLeft && event.key != Key.DirectionRight) false
            else {
                if (event.type == KeyEventType.KeyDown) keyStep(if (event.key == Key.DirectionLeft) -1 else 1)
                true
            }
        }.focusable(enabled).pointerInput(enabled) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (enabled && event.type == PointerEventType.Press) focus.requestFocus()
                }
            }
        }, onValueChange = { opening.colorChange(edit, index, revision, part, it) })
    }
    control("色相", hsl.hue, 0f..360f, AvatarHslPart.HUE)
    control("饱和度", hsl.saturation * 100f, 0f..100f, AvatarHslPart.SATURATION)
    control("亮度", hsl.lightness * 100f, 0f..100f, AvatarHslPart.LIGHTNESS)
}

@Composable
private fun AvatarCatalogPanel(opening: SettingsProfileStickerAvatarOpening, body: Rect, mediaActive: Boolean, modifier: Modifier) {
    var query by remember(opening, opening.source) {
        mutableStateOf((opening.source as? AvatarCatalogSource.Packs)?.query.orEmpty())
    }
    var gridViewport by remember(opening, opening.source) { mutableStateOf(Rect.Zero) }
    val source = opening.source; val epoch = opening.catalogEpoch; val picker = opening.picker
    val probe = LocalProfileStickerAvatarCatalogProbe.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val sourceLabel = when (source) {
            AvatarCatalogSource.Default -> "默认表情"
            is AvatarCatalogSource.Packs -> when (source.kind) {
                ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> "已安装的表情包"
                ProfileStickerAvatarPackKind.REGULAR -> "已安装的普通贴纸包"
                ProfileStickerAvatarPackKind.MASK -> "已安装的面具贴纸包"
            }
            is AvatarCatalogSource.Pack -> "包内内容：${shortAvatarPackTitle(source.value.title)}"
        }
        Text("当前：$sourceLabel", maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        when {
            opening.selectionNeedsReselection -> Text("上次选择需要重新确认", fontSize = 12.sp)
            opening.editor.selection != null && opening.loading -> Text("正在验证所选内容…", fontSize = 12.sp)
            opening.editor.selection != null -> Text(
                if (opening.selectedCandidate == null) "已选内容在其他页" else "本页已选择",
                fontSize = 12.sp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val emoji = { if (picker != null) opening.navigateCatalog(epoch, picker, source, AvatarCatalogSource.Default) }
            val emojiPacks = { if (picker != null) opening.navigateCatalog(epoch, picker, source, AvatarCatalogSource.Packs(ProfileStickerAvatarPackKind.CUSTOM_EMOJI)) }
            val stickers = { if (picker != null) opening.navigateCatalog(epoch, picker, source, AvatarCatalogSource.Packs(ProfileStickerAvatarPackKind.REGULAR)) }
            ChatButton("表情", Modifier.weight(1f), enabled = picker != null && opening.catalogNavigationEnabled, onClick = emoji)
            ChatButton("表情包", Modifier.weight(1f), enabled = picker != null && opening.catalogNavigationEnabled, onClick = emojiPacks)
            ChatButton("贴纸", Modifier.weight(1f), enabled = picker != null && opening.catalogNavigationEnabled, onClick = stickers)
        }
        if (source is AvatarCatalogSource.Packs && source.kind != ProfileStickerAvatarPackKind.CUSTOM_EMOJI)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val regular = { if (picker != null) opening.navigateCatalog(epoch, picker, source, AvatarCatalogSource.Packs(ProfileStickerAvatarPackKind.REGULAR)) }
                val mask = { if (picker != null) opening.navigateCatalog(epoch, picker, source, AvatarCatalogSource.Packs(ProfileStickerAvatarPackKind.MASK)) }
                val payload = ProfileStickerAvatarCatalogProbe("面具贴纸", mask)
                SideEffect { probe?.invoke(payload) }
                ChatButton("普通贴纸", Modifier.weight(1f), enabled = picker != null && opening.editingEnabled, onClick = regular)
                ChatButton("面具贴纸", Modifier.weight(1f), enabled = picker != null && opening.editingEnabled, onClick = mask)
            }
        if (source is AvatarCatalogSource.Pack && picker != null) {
            val action = { opening.returnToPacks(epoch, picker, source) }
            val payload = ProfileStickerAvatarCatalogProbe("返回已安装的包", action)
            SideEffect { probe?.invoke(payload) }
            ChatButton("返回已安装的包", enabled = opening.editingEnabled, onClick = action)
        }
        if (source is AvatarCatalogSource.Packs) {
            TextField(query, { if (opening.editingEnabled) query = it }, label = "筛选已安装的包", singleLine = true,
                enabled = opening.editingEnabled, modifier = Modifier.fillMaxWidth())
            if (query.length > 1024) SettingsNote("搜索内容最多 1024 个字符。")
            ChatButton("搜索已安装的包", enabled = picker != null && opening.editingEnabled && query.length <= 1024) {
                if (picker != null) opening.searchInstalled(epoch, picker, source, query)
            }
            LazyColumn(Modifier.fillMaxWidth().height(224.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(opening.packs, key = { it.ticket }) { pack ->
                    var namesExpanded by remember(pack.ticket) { mutableStateOf(false) }
                    val action = { if (picker != null) opening.openPack(epoch, picker, source, pack) }
                    val payload = ProfileStickerAvatarCatalogProbe(pack.title, action)
                    SideEffect { probe?.invoke(payload) }
                    val end = if (pack.title.length > 96 && pack.title[95].isHighSurrogate()) 95 else minOf(pack.title.length, 96)
                    val title = pack.title.take(end) + if (end < pack.title.length) "…" else ""
                    Column(Modifier.fillMaxWidth()) {
                        ChatButton(title, Modifier.fillMaxWidth().semantics { contentDescription = pack.title },
                            enabled = opening.editingEnabled, onClick = action)
                        if (pack.shortName.isNotEmpty()) Text("@${pack.shortName}", maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        if (pack.title.length > 96 || pack.shortName.length > 96) {
                            ChatButton(if (namesExpanded) "收起完整名称" else "查看完整名称", enabled = opening.editingEnabled) {
                                namesExpanded = !namesExpanded
                            }
                            if (namesExpanded) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())) {
                                Text(pack.title)
                                if (pack.shortName.isNotEmpty()) Text("@${pack.shortName}")
                            }
                        }
                    }
                }
            }
            if (!opening.loading && opening.error == null && opening.packs.isEmpty() && opening.previousWindow == null && opening.nextWindow == null)
                Text("没有匹配的已安装包", fontSize = 13.sp)
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(56.dp), modifier = Modifier.fillMaxWidth().height(224.dp)
                .onGloballyPositioned { gridViewport = it.boundsInWindow() },
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(opening.candidates, key = { _, value -> value.ticket }) { index, candidate ->
                    AvatarCandidateCell(opening, candidate, index, body, gridViewport, mediaActive)
                }
            }
            if (!opening.loading && opening.candidates.isEmpty() && opening.error == null)
                Text(if (opening.previousWindow != null || opening.nextWindow != null) "本页没有可选择的内容" else "没有可显示的内容", fontSize = 13.sp)
        }
        if (picker != null && !opening.loading && !opening.catalogInvalidated &&
            (opening.error == null || opening.candidates.isNotEmpty() || opening.packs.isNotEmpty() ||
                opening.previousWindow != null || opening.nextWindow != null))
            Text("第 ${opening.windowNumber} 页", fontSize = 12.sp)
        if (picker != null && (opening.previousWindow != null || opening.nextWindow != null)) {
            val previous = opening.previousWindow; val next = opening.nextWindow
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatButton("上一页", Modifier.weight(1f), enabled = opening.editingEnabled && previous != null) {
                    if (previous != null) opening.moveWindow(epoch, picker, source, previous)
                }
                ChatButton("下一页", Modifier.weight(1f), enabled = opening.editingEnabled && next != null) {
                    if (next != null) opening.moveWindow(epoch, picker, source, next)
                }
            }
        }
        if (opening.loading) Text("正在读取…", fontSize = 13.sp)
        opening.windowNotice?.let {
            SettingsNote(it, error = true)
            if (picker != null) {
                val revision = opening.currentWindowNoticeRevision()
                val action = { opening.reloadAfterPreviewFailure(epoch, picker, source, revision) }
                val payload = ProfileStickerAvatarCatalogProbe("重新读取并选择", action)
                SideEffect { probe?.invoke(payload) }
                ChatButton("重新读取并选择", enabled = opening.editingEnabled, onClick = action)
            }
        }
        if (opening.limited) SettingsNote("部分来源条目身份重复或冲突，无法选择。")
        if (opening.error != null && opening.retryCatalogAllowed && picker != null && !opening.waitingForSave) {
            val revision = opening.errorRevision
            val action = { opening.retryCatalog(epoch, picker, source, revision) }
            val retryLabel = if (source is AvatarCatalogSource.Pack) "返回已安装包并重试" else "重试读取内容"
            val payload = ProfileStickerAvatarCatalogProbe(retryLabel, action)
            SideEffect { probe?.invoke(payload) }
            ChatButton(retryLabel, enabled = opening.editingEnabled, onClick = action)
        }
    }
}

private fun shortAvatarPackTitle(title: String): String {
    val end = if (title.length > 96 && title[95].isHighSurrogate()) 95 else minOf(title.length, 96)
    return title.take(end) + if (end < title.length) "…" else ""
}

private fun visibleIn(vararg rects: Rect): Boolean {
    if (rects.isEmpty()) return false
    val left = rects.maxOf { it.left }; val top = rects.maxOf { it.top }
    val right = rects.minOf { it.right }; val bottom = rects.minOf { it.bottom }
    return right > left && bottom > top
}
@Composable private fun windowRect(): Rect {
    val size = LocalWindowInfo.current.containerSize
    return Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
}

@Composable
private fun AvatarCandidateCell(opening: SettingsProfileStickerAvatarOpening, candidate: ProfileStickerAvatarCandidate,
    index: Int, body: Rect, grid: Rect, mediaActive: Boolean) {
    val client = LocalProfileStickerAvatarArtwork.current; val p = opening.picker ?: return
    val source = opening.source; val epoch = opening.catalogEpoch
    val scope = rememberCoroutineScope(); val window = windowRect()
    var bounds by remember(candidate.ticket) { mutableStateOf(Rect.Zero) }
    var retry by remember(candidate.ticket, epoch) { mutableIntStateOf(0) }
    var art by remember(candidate.ticket, epoch) { mutableStateOf<ProfileStickerAvatarArtwork?>(null) }
    var failure by remember(candidate.ticket, epoch) { mutableStateOf<Any?>(null) }
    val px = with(LocalDensity.current) { 48.dp.roundToPx() }.coerceIn(1, 512)
    val eligible = mediaActive && opening.active && visibleIn(bounds, body, grid, window) &&
        client.provider != null && client.generation == opening.parent.generation && client.provider.generation == client.generation
    DisposableEffect(client, p, candidate.ticket, epoch, source, eligible, px, retry) {
        art = null; failure = null
        val gate = AvatarUiLease(); var lease: ProfileStickerAvatarArtworkLease? = null
        // A provider may call back synchronously, even reentrantly, before returning its lease.
        val bound = CompletableDeferred<Boolean>()
        val delivered = MutableStateFlow(false)
        if (eligible) try { lease = client.provider!!.thumbnail(opening.parent, p, candidate.ticket, px,
            { gate.active() && opening.mediaOwnerActive() }) { result ->
            scope.launch {
                if (!bound.await()) return@launch
                if (delivered.compareAndSet(false, true) && gate.active() && opening.active && opening.picker === p &&
                    opening.catalogEpoch == epoch && opening.source === source && opening.candidates.any { it === candidate }) {
                    art = result.value?.takeIf { result.error == null && it.image != null }
                    if (art == null) { gate.close(); runCatching { lease?.close() }; failure = Any() }
                }
            }
        }
        if (gate.active() && opening.active && opening.picker === p && opening.catalogEpoch == epoch &&
            opening.source === source && opening.candidates.any { it === candidate }) bound.complete(true)
        else { gate.close(); runCatching { lease?.close() }; bound.complete(false) }
        } catch (_: Exception) {
            val current = gate.active() && opening.active && opening.picker === p && opening.catalogEpoch == epoch && opening.source === source
            gate.close(); runCatching { lease?.close() }; if (current) failure = Any(); bound.complete(false)
        }
        onDispose { gate.close(); bound.complete(false); runCatching { lease?.close() } }
    }
    val selected = opening.selectedCandidate === candidate.ticket
    val tint = MiuixTheme.colorScheme.onSurface
    Box(Modifier.aspectRatio(1f).onGloballyPositioned { bounds = it.boundsInWindow() }
        .chatSquircleSurface(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .18f) else MiuixTheme.colorScheme.surface, 15.dp)
        .clickable(enabled = opening.editingEnabled && (art != null || failure != null), onClickLabel = "查看候选 ${index + 1}") {
            opening.select(candidate, epoch, p, source)
        }
        .semantics { contentDescription = "候选 ${index + 1}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) { drawAvatarArt(art, tint) }
        if (client.provider == null || client.generation != opening.parent.generation || client.provider.generation != client.generation)
            Text("不可用", fontSize = 10.sp)
        else if (failure != null) {
            val marker = failure
            val action = {
                if (marker != null && failure === marker && opening.editingEnabled && opening.picker === p &&
                    opening.catalogEpoch == epoch && opening.source === source && opening.candidates.any { it === candidate }) {
                    failure = null; retry++
                }
            }
            val payload = ProfileStickerAvatarCatalogProbe("重试缩略图 ${index + 1}", action)
            val probe = LocalProfileStickerAvatarCatalogProbe.current
            SideEffect { probe?.invoke(payload) }
            ChatButton("重试", enabled = opening.editingEnabled, onClick = action)
        }
        else if (art == null) Text("加载中", fontSize = 10.sp)
    }
}

internal data class AvatarFrameTarget(val timeMillis: Long, val sequence: Long, val epoch: Long, val terminal: Boolean)

/** One request retains its lease until delivery or explicit owner cancellation. */
internal class AvatarFrameOwner {
    val gate = AvatarUiLease()
    var lease: ProfileStickerAvatarArtworkLease? = null
    val completion = CompletableDeferred<SettingsResult<ProfileStickerAvatarArtwork>?>()
    fun close() {
        gate.close(); val old = lease; lease = null
        runCatching { old?.close() }; completion.complete(null)
    }
}

internal class AvatarClipState {
    var clip by mutableStateOf<ProfileStickerAvatarPreviewClip?>(null)
    var art by mutableStateOf<ProfileStickerAvatarArtwork?>(null)
    private var frozenSource: ProfileStickerAvatarArtwork? = null
    var failed by mutableStateOf(false)
    var failure by mutableStateOf<Any?>(null)
    var replay by mutableIntStateOf(0)
    var target by mutableStateOf<AvatarFrameTarget?>(null); private set
    private var epoch = 0L
    private var nextSequence = 0L
    private var displayedSequence = 0L
    private var openGate: AvatarUiLease? = null
    private var openLease: ProfileStickerAvatarArtworkLease? = null
    private var inFlight: AvatarFrameOwner? = null
    private var displayed: AvatarFrameOwner? = null
    fun begin(gate: AvatarUiLease, lease: ProfileStickerAvatarArtworkLease) { openGate = gate; openLease = lease }
    fun startPlayback(animated: Boolean): Long {
        epoch++
        // Replay supersedes the preceding playback, including an unfinished terminal frame.
        inFlight?.close()
        target = AvatarFrameTarget(0, ++nextSequence, epoch, terminal = !animated)
        return epoch
    }
    fun aim(ownerEpoch: Long, timeMillis: Long, terminal: Boolean = false) {
        if (ownerEpoch != epoch) return
        val current = target
        if (current != null && current.epoch == ownerEpoch && current.timeMillis == timeMillis && current.terminal == terminal) return
        target = AvatarFrameTarget(timeMillis, ++nextSequence, ownerEpoch, terminal)
    }
    fun finishPlayback(ownerEpoch: Long) {
        if (ownerEpoch != epoch) return
        // Ending the play-once pass is an explicit cancellation, unlike an ordinary clock tick.
        epoch++; inFlight?.close()
        target = AvatarFrameTarget(0, ++nextSequence, epoch, terminal = true)
    }
    fun beginFrame(owner: AvatarFrameOwner) { check(inFlight == null); inFlight = owner }
    fun endFrame(owner: AvatarFrameOwner) { if (inFlight === owner) inFlight = null }
    fun current(frame: AvatarFrameTarget): Boolean {
        val latest = target ?: return false
        return frame.epoch == epoch && frame.epoch == latest.epoch && frame.sequence > displayedSequence &&
            (!latest.terminal || frame.terminal)
    }
    fun frame(owner: AvatarFrameOwner, target: AvatarFrameTarget, artwork: ProfileStickerAvatarArtwork): Boolean {
        if (inFlight !== owner || !owner.gate.active() || !current(target)) return false
        inFlight = null
        displayed?.close(); displayed = owner; displayedSequence = target.sequence; art = artwork
        return true
    }
    /** The provider retains a prepared frame, with no native/media/Save authority. */
    fun freezeDisplayed(opening: SettingsProfileStickerAvatarOpening, selection: ProfileStickerAvatarSelectionTicket): Boolean {
        val source = art ?: return true
        if (source.image == null) return true
        if (frozenSource === source && opening.frozenPreviewFor(selection)?.image != null) return true
        val retained = runCatching { source.freezeDisplay() }.getOrNull() ?: return false
        if (!opening.retainFrozenPreview(selection, retained)) return false
        frozenSource = source
        return true
    }
    /** Stop all borrowed media work, retaining only an independent already-displayed snapshot. */
    fun stop() {
        epoch++; target = null
        openGate?.close(); inFlight?.close(); displayed?.close()
        val oldClip = clip; val oldLease = openLease
        openGate = null; inFlight = null; displayed = null; clip = null; openLease = null; art = null; failure = null; frozenSource = null
        runCatching { oldClip?.close() }; runCatching { oldLease?.close() }
    }
    fun close() { stop() }
    fun fail() { stop(); failed = true; failure = Any() }
    /** A saved source keeps its independent request witness until explicit retry/disposal. */
    fun failRetainingSource() {
        epoch++; target = null; inFlight?.close(); displayed?.close()
        inFlight = null; displayed = null; art = null; failed = true; failure = Any()
    }
}

@Composable
private fun AvatarPreview(opening: SettingsProfileStickerAvatarOpening, body: Rect, mediaActive: Boolean, modifier: Modifier) {
    val editorVersion = opening.version
    val selection = remember(opening, editorVersion) { opening.editor.selection }
    val colors = remember(opening, editorVersion) { opening.editor.colors }
    val picker = opening.picker
    val client = LocalProfileStickerAvatarArtwork.current; val window = windowRect(); val scope = rememberCoroutineScope()
    var bounds by remember(opening) { mutableStateOf(Rect.Zero) }; var retry by remember(selection) { mutableIntStateOf(0) }
    val px = with(LocalDensity.current) { 168.dp.roundToPx() }.coerceIn(1, 1024)
    val state = remember(opening, picker, selection, client, px, retry) { AvatarClipState() }
    val previewVisible = mediaActive && opening.active && visibleIn(bounds, body, window)
    val eligible = previewVisible && opening.selectionUsable && selection != null && picker != null &&
        client.provider != null && client.generation == opening.parent.generation && client.provider.generation == client.generation
    DisposableEffect(opening, state, selection) {
        val owner = Any()
        opening.registerPreviewSnapshot(owner) { suspendMedia ->
            if (selection != null && opening.editor.selection === selection) {
                val retained = state.freezeDisplayed(opening, selection)
                if (retained && suspendMedia) state.stop()
                retained
            } else true
        }
        onDispose { opening.unregisterPreviewSnapshot(owner); state.close() }
    }
    LaunchedEffect(state, opening.waitingForSave, opening.selectionUsable, state.art) {
        if (!opening.waitingForSave && opening.selectionUsable && state.art?.image != null)
            opening.clearFrozenPreview(selection)
    }
    DisposableEffect(state, eligible) {
        // Publication must wait until state owns the returned lease, not merely for a coroutine yield.
        val bound = CompletableDeferred<Boolean>()
        val gate = AvatarUiLease()
        if (eligible) {
            // A newly visible attempt has its own outcome; an earlier failure must not mask it.
            state.failed = false; state.failure = null
            var lease: ProfileStickerAvatarArtworkLease? = null
            val delivered = MutableStateFlow(false)
            try { lease = client.provider!!.preview(opening.parent, picker!!, selection!!, px,
                { gate.active() && opening.mediaOwnerActive() }) { result ->
                val incoming = result.value
                val job = scope.launch {
                    val leaseIsBound = bound.await()
                    val clip = incoming
                    if (!delivered.compareAndSet(false, true)) { if (clip !== state.clip) runCatching { clip?.close() }; return@launch }
                    if (leaseIsBound && gate.active() && opening.active && opening.selectionUsable &&
                        opening.picker === picker && opening.editor.selection === selection) {
                        if (clip != null && result.error == null) state.clip = clip else { runCatching { clip?.close() }; state.fail() }
                    } else runCatching { clip?.close() }
                }
                job.invokeOnCompletion { if (it != null && delivered.compareAndSet(false, true)) runCatching { incoming?.close() } }
            }
            if (gate.active() && opening.active && opening.selectionUsable && opening.picker === picker && opening.editor.selection === selection) {
                state.begin(gate, checkNotNull(lease)); bound.complete(true)
            } else { gate.close(); runCatching { lease?.close() }; bound.complete(false) }
            } catch (_: Exception) {
                val current = gate.active() && opening.active && opening.picker === picker && opening.editor.selection === selection
                gate.close(); runCatching { lease?.close() }; if (current) state.fail(); bound.complete(false)
            }
        }
        onDispose { gate.close(); bound.complete(false); state.stop() }
    }
    val clip = state.clip
    LaunchedEffect(state, clip, eligible) {
        if (eligible && clip != null) snapshotFlow { state.target }.filterNotNull().collect { target ->
            if (!opening.active || !opening.selectionUsable || opening.picker !== picker || opening.editor.selection !== selection ||
                state.clip !== clip || !state.current(target)) return@collect
            val owner = AvatarFrameOwner()
            var transferred = false
            state.beginFrame(owner)
            try {
                owner.lease = clip.frame(target.timeMillis, { owner.gate.active() && opening.mediaOwnerActive() }) {
                    owner.completion.complete(it)
                }
                // Completion may come from a worker. Await resumes on the UI dispatcher, after the
                // returned lease exists, and the owner/selection/epoch checks below run there.
                val received = owner.completion.await() ?: return@collect
                val current = owner.gate.active() && opening.active && opening.selectionUsable && opening.picker === picker &&
                    opening.editor.selection === selection && state.clip === clip && state.current(target)
                val value = received.value
                if (current && received.error == null && value?.image != null)
                    transferred = state.frame(owner, target, value)
                else if (current) state.fail()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owner.gate.active() && state.clip === clip && state.current(target)) state.fail()
            } finally {
                state.endFrame(owner)
                if (!transferred) owner.close()
            }
        }
    }
    LaunchedEffect(state, clip, eligible, state.replay) {
        if (eligible && clip != null) {
            val animated = clip.animated && clip.durationMillis > 0 && clip.frameDurationMillis > 0
            val playback = state.startPlayback(animated)
            if (!animated) return@LaunchedEffect
            val interval = clip.frameDurationMillis.coerceAtLeast(1L)
            val start = withFrameNanos { it }
            while (eligible && state.clip === clip && !state.failed) {
                val elapsed = withFrameNanos { (it - start) / 1_000_000L }
                if (elapsed >= clip.durationMillis) { state.finishPlayback(playback); break }
                val frame = (elapsed / interval) * interval
                state.aim(playback, frame)
            }
        }
    }
    Box(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }.semantics { contentDescription = "头像预览" }, contentAlignment = Alignment.Center) {
        AvatarBackground(colors, Modifier.fillMaxSize())
        val frozen = opening.frozenPreviewFor(selection)
        Canvas(Modifier.fillMaxSize(.67f)) {
            val live = state.art
            drawAvatarArt(if (live?.image != null) live else frozen, Color.White)
        }
        when {
            selection == null -> Text("选择表情或贴纸", fontSize = 12.sp, color = Color.White)
            client.provider == null || client.generation != opening.parent.generation || client.provider.generation != client.generation -> Text("预览暂不可用", fontSize = 12.sp, color = Color.White)
            !mediaActive -> Text("预览已暂停", fontSize = 12.sp, color = Color.White)
            !opening.selectionUsable && !opening.loading -> if (frozen == null) Text("请重新选择", fontSize = 12.sp, color = Color.White)
            state.failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("预览不可用", fontSize = 12.sp, color = Color.White)
                val marker = state.failure
                val action = {
                    if (marker != null && state.failure === marker && state.failed && opening.active && opening.selectionUsable &&
                        opening.picker === picker && opening.editor.selection === selection) {
                        state.failure = null; state.failed = false; retry++
                    }
                }
                val payload = ProfileStickerAvatarCatalogProbe("重试预览", action)
                val probe = LocalProfileStickerAvatarCatalogProbe.current
                SideEffect { probe?.invoke(payload) }
                ChatButton("重试预览", enabled = opening.selectionUsable, onClick = action)
            }
            state.art?.image == null && frozen?.image == null -> Text("正在加载…", fontSize = 12.sp, color = Color.White)
            clip?.animated == true -> Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                ChatButton("重播", enabled = opening.active) { state.replay++ }
            }
        }
    }
}

internal fun DrawScope.drawAvatarArt(art: ProfileStickerAvatarArtwork?, tint: Color) {
    val image = art?.image ?: return
    if (image.width <= 0 || image.height <= 0) return
    val scale = minOf(size.width / image.width, size.height / image.height)
    val w = (image.width * scale).roundToInt().coerceAtLeast(1); val h = (image.height * scale).roundToInt().coerceAtLeast(1)
    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((size.width-w)/2f).roundToInt(), ((size.height-h)/2f).roundToInt()), dstSize = IntSize(w,h),
        colorFilter = if (art.needsRepainting) ColorFilter.tint(tint, BlendMode.SrcIn) else null, filterQuality = FilterQuality.Low)
}

@Composable
private fun AvatarColorSwatch(colors: AvatarPreviewColors, selected: Boolean, label: String, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }) {
        AvatarBackground(colors, Modifier.fillMaxSize())
        if (selected) Canvas(Modifier.fillMaxSize()) { drawCircle(Color.White, radius = size.minDimension / 2f - 2.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
    }
}

@Composable
private fun AvatarBackground(colors: AvatarPreviewColors, modifier: Modifier) {
    // The complex 64×64 field is prepared on a worker and only sampled during drawing.
    val complex by produceState<ImageBitmap?>(null, colors) {
        value = withContext(Dispatchers.Default) {
            AvatarPreviewGradient.complex45(colors)?.let { pixels ->
                val bitmap = ImageBitmap(64, 64); val canvas = androidx.compose.ui.graphics.Canvas(bitmap); val paint = Paint()
                for (y in 0 until 64) for (x in 0 until 64) {
                    paint.color = Color(0xFF000000L or pixels[y * 64 + x].toLong())
                    canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
                }
                bitmap
            }
        }
    }
    Canvas(modifier) {
        val path = Path().apply { addOval(Rect(Offset.Zero, size)) }
        clipPath(path) {
            if (colors.size == 1) drawRect(Color(0xFF000000L or colors.color(0).toLong()))
            else if (colors.size == 2) drawRect(Brush.linearGradient(
                listOf(Color(0xFF000000L or colors.color(0).toLong()), Color(0xFF000000L or colors.color(1).toLong())),
                start = Offset(size.width, 0f), end = Offset(0f, size.height)))
            else if (complex != null) drawImage(complex!!, srcOffset = IntOffset.Zero, srcSize = IntSize(64,64),
                dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Low)
            else drawRect(Color(0xFF000000L or colors.color(0).toLong()))
        }
    }
}
