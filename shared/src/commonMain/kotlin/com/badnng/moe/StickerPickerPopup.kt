package com.badnng.moe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.badnng.moe.chat.*
import com.badnng.moe.media.StickerCellMediaPreview
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** The selector owns its official 10px shadow allowance instead of borrowing menu padding. */
internal val StickerPickerShadowPadding = 10.dp
private val StickerPickerDropShadow = Shadow(radius = 6.dp, color = Color.Black.copy(alpha = .22f), offset = DpOffset(0.dp, 2.dp))
private const val MAX_STICKER_PLACEHOLDER_SLOTS = 4096

/** Composer-owned popup: no centered dialog, with resources retained through its ordinary exit. */
@Composable
internal fun StickerPicker(
    show: Boolean, snapshot: ChatSnapshot, features: ChatFeatureActions, active: Boolean, onDismiss: () -> Unit,
    anchor: Rect? = null, onMountedChange: (Boolean) -> Unit = {}, onHoverChanged: (Boolean) -> Unit = {},
    onSent: (ChatToken) -> Unit = {},
) {
    var owner by remember { mutableStateOf(snapshot.token) }
    if (owner != snapshot.token) {
        LaunchedEffect(show, snapshot.token) { if (show) onDismiss() else owner = snapshot.token }
        return
    }
    key(owner) { StickerPickerForSelection(show, snapshot, features, onDismiss, anchor, onMountedChange, onHoverChanged, onSent) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StickerPickerForSelection(
    show: Boolean, snapshot: ChatSnapshot, features: ChatFeatureActions, onDismiss: () -> Unit,
    anchor: Rect?, onMountedChange: (Boolean) -> Unit, onHoverChanged: (Boolean) -> Unit,
    onSent: (ChatToken) -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    var retained by remember { mutableStateOf<ChatSnapshot?>(null) }
    var retainedAnchor by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(show) { visibility.targetState = show }
    SideEffect { if (show) { retained = snapshot; retainedAnchor = anchor } }
    LaunchedEffect(show, visibility.isIdle, visibility.currentState) {
        if (!show && visibility.isIdle && !visibility.currentState) { retained = null; retainedAnchor = null }
    }
    if (!show && visibility.isIdle && !visibility.currentState) return
    val shown = if (show) snapshot else retained ?: return
    // The selector keeps its anchor button visible while its own sampled glass softens
    // the background. It still owns timeline suppression through the retained exit.
    ContextMenuBackdropRegistration(show, blurBackground = false)
    val currentMounted by rememberUpdatedState(onMountedChange)
    val currentHover by rememberUpdatedState(onHoverChanged)
    DisposableEffect(Unit) {
        currentMounted(true)
        onDispose { currentHover(false); currentMounted(false) }
    }
    LaunchedEffect(show) { if (!show) currentHover(false) }
    val density = LocalDensity.current
    val viewport = LocalWindowInfo.current.containerSize
    val currentAnchor = anchor.takeIf { show } ?: retainedAnchor
    val margin = with(density) { StickerPickerShadowPadding.roundToPx() }
    // Transparent native Popup bounds still participate in pointer routing; the original
    // 10dp pointer gap belongs outside the complete shadow window, not just its body.
    val gap = with(density) { (StickerPickerShadowPadding + 10.dp).roundToPx() }
    // TabbedPanel: 345px body, .75 available-height ratio, 278..640px content,
    // and 10px shadow margins. Its smaller shadow fits that actual transparent space.
    val sideMargin = with(density) { 10.dp.roundToPx() }
    val available = currentAnchor?.top?.roundToInt()?.coerceIn(0, viewport.height) ?: viewport.height
    val preferred = with(density) { IntSize(345.dp.roundToPx(),
        (44.dp.roundToPx() + ((available - 44.dp.roundToPx()).coerceAtLeast(0) * .75f).roundToInt()
            .minus(margin * 2).coerceIn(278.dp.roundToPx(), 640.dp.roundToPx())) ) }
    val placement = stickerPickerPlacement(viewport, currentAnchor, preferred, margin, gap, sideMargin)
    val position = remember(currentAnchor, margin, gap, sideMargin) {
        StickerPickerPopupPosition(currentAnchor, margin, gap, sideMargin)
    }
    val origin = TransformOrigin(0.9f, if (placement.opensAbove) 1f else 0f)
    val shadowShape = rememberMiuixSquircleShape(20.dp)
    // ConversationPane mounts this native Popup outside the timeline's backdrop local.
    // Capture the workspace source here and carry it explicitly into the Popup root.
    val menuBackdrop = LocalWorkspaceMenuBackdrop.current ?: LocalChatBackdrop.current
    Popup(popupPositionProvider = position, onDismissRequest = { if (show) onDismiss() },
        properties = composerPopupProperties(focusable = show)) {
        CompositionLocalProvider(LocalChatBackdrop provides menuBackdrop) {
        AnimatedVisibility(visibleState = visibility,
            enter = fadeIn(tween(140)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.94f, transformOrigin = origin),
            exit = fadeOut(tween(170)) + scaleOut(tween(200, easing = FastOutSlowInEasing), targetScale = 0.97f, transformOrigin = origin)) {
            Box(Modifier.padding(StickerPickerShadowPadding)) {
            Box(Modifier.size(with(density) { placement.size.width.toDp() }, with(density) { placement.size.height.toDp() })
                .dropShadow(shadowShape, StickerPickerDropShadow)
                .onPointerEvent(PointerEventType.Enter) { currentHover(show) }
                .onPointerEvent(PointerEventType.Exit) { currentHover(false) }
                .onPreviewKeyEvent {
                    if (!show) true else if (it.key == Key.Escape) { if (it.type == KeyEventType.KeyDown) onDismiss(); true } else false
                }.pointerInput(show) {
                    if (!show) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }.then(if (show) Modifier else Modifier.clearAndSetSemantics {})) {
                // Timeline activity is suspended by this popup's own backdrop ownership.
                StickerPickerPanel(shown, features, interactive = show, onDismiss = onDismiss, onSent = onSent)
            }
            }
        }
        }
    }
}

internal class StickerPickerPopupPosition(private val anchor: Rect?, private val margin: Int, private val gap: Int,
    private val sideMargin: Int = margin) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val body = IntSize((popupContentSize.width - sideMargin * 2).coerceAtLeast(1),
            (popupContentSize.height - margin * 2).coerceAtLeast(1))
        val offset = stickerPickerPlacement(windowSize, anchor, body, margin, gap, sideMargin).offset
        return IntOffset((offset.x - sideMargin).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (offset.y - margin).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

/** Native sections share one scroll surface; pack icons navigate to section offsets. */
@Composable
internal fun StickerPickerPanel(snapshot: ChatSnapshot, features: ChatFeatureActions,
    interactive: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(), onSent: (ChatToken) -> Unit = {}) {
    var query by remember(snapshot.token) { mutableStateOf(TextFieldValue()) }
    val sections = remember(snapshot.stickerSections, snapshot.stickers, snapshot.stickerSets,
        snapshot.selectedStickerSetId, snapshot.stickersLoading, snapshot.stickersError) {
        snapshot.stickerSections.ifEmpty {
            listOf(StickerSection(null, "最近", if (snapshot.selectedStickerSetId == null) snapshot.stickers else emptyList(),
                loading = snapshot.selectedStickerSetId == null && snapshot.stickersLoading,
                loaded = snapshot.selectedStickerSetId == null, error = snapshot.stickersError.takeIf { snapshot.selectedStickerSetId == null })) +
                snapshot.stickerSets.map { pack -> StickerSection(pack.id, pack.title,
                    if (snapshot.selectedStickerSetId == pack.id) snapshot.stickers else emptyList(),
                    loading = snapshot.selectedStickerSetId == pack.id && snapshot.stickersLoading,
                    loaded = snapshot.selectedStickerSetId == pack.id,
                    error = snapshot.stickersError.takeIf { snapshot.selectedStickerSetId == pack.id }) }
        }
    }
    val showRecentIcon = sections.firstOrNull { it.setId == null }?.let {
        it.stickers.isNotEmpty() || !it.loaded || it.loading || it.error != null
    } == true
    val filtered = remember(sections, query.text) { sections.filter {
        it.setId != null || it.stickers.isNotEmpty() || !it.loaded || it.loading || it.error != null || sections.size == 1
    }.map {
        val items = if (it.setId == null) it.stickers.take(20) else it.stickers
        it.copy(stickers = filterLoadedStickers(items, query.text))
    }
        .filter { query.text.isBlank() || it.stickers.isNotEmpty() } }
    val grid = gridState
    val footer = rememberLazyListState()
    var navigation by remember(snapshot.token) { mutableStateOf<StickerSectionNavigation?>(null) }
    var navigationRevision by remember(snapshot.token) { mutableIntStateOf(0) }
    val visibleKeys by remember { derivedStateOf {
        val layout = grid.layoutInfo
        layout.visibleItemsInfo.filter { it.offset.y + it.size.height > layout.viewportStartOffset && it.offset.y < layout.viewportEndOffset }
            .mapNotNull { it.key as? StickerGridKey }.toSet()
    } }
    val visibleSections = visibleKeys.map { it.setId }.distinct()
    val currentSection = visibleKeys.firstOrNull { it.kind != 3 }?.setId
    val currentFeatures by rememberUpdatedState(features)
    LaunchedEffect(snapshot.token, visibleSections, interactive) {
        if (interactive) visibleSections.forEach { currentFeatures.ensureStickerSection(snapshot.token, it) }
    }
    LaunchedEffect(snapshot.token, query.text) { grid.scrollToItem(0) }
    // Compatibility for standalone previews with only the original single-page model.
    LaunchedEffect(snapshot.selectedStickerSetId) {
        if (snapshot.stickerSections.isEmpty() && snapshot.selectedStickerSetId != null) {
            var start = 1
            filtered.forEach { section ->
                if (section.setId == snapshot.selectedStickerSetId) grid.scrollToItem(start)
                start += 1 + section.stickers.size.coerceAtLeast(1)
            }
        }
    }
    val footerIndex = if (currentSection == null && showRecentIcon) 0 else
        snapshot.stickerSets.indexOfFirst { it.id == currentSection }.coerceAtLeast(0) + if (showRecentIcon) 1 else 0
    LaunchedEffect(footerIndex) {
        if (footer.layoutInfo.visibleItemsInfo.none { it.index == footerIndex }) footer.scrollToItem(footerIndex.coerceAtLeast(0))
    }
    val focused = LocalWindowInfo.current.isWindowFocused
    val colors = MiuixTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxSize().chatGlassSurface(20.dp, shadow = false).semantics { contentDescription = "贴纸选择面板" }) {
        val available = constraints.maxDpSize(LocalDensity.current)
        // countDesiredHeight: the selector's 7px margins leave square cells at least 64px wide.
        val availableWidth = (available.width - 19.dp).value.coerceAtLeast(1f)
        val columns = (availableWidth / 64f).toInt().coerceAtLeast(1)
        val cellSize = (availableWidth / columns).toInt().coerceAtLeast(1).dp
        val rightPadding = ((available.width.value - 1 - columns * cellSize.value) / 2).toInt().coerceAtLeast(7).dp
        val leftPadding = (available.width - cellSize * columns - rightPadding).coerceAtLeast(0.dp)
        val slotCounts = filtered.associate { section -> section.setId to stickerSectionSlotCount(section,
            snapshot.stickerSets.firstOrNull { it.id == section.setId }?.size ?: 0, columns) }
        val lastSection = filtered.lastOrNull()
        val lastCount = lastSection?.let { slotCounts.getValue(it.setId) } ?: 0
        val lastRows = ((lastCount + columns - 1) / columns).coerceAtLeast(1)
        val lastHeight = (if (lastSection?.setId == null) 11.dp else 33.dp) + cellSize * lastRows
        // Official countDesiredHeight gives the final section at least one viewport of scroll space.
        val bottomPadding = 11.dp + (available.height - 36.dp - 11.dp - lastHeight).coerceAtLeast(0.dp)
        val sectionStarts = buildMap<Long?, Int> {
            var offset = 1
            filtered.forEach { section -> put(section.setId, offset); offset += 1 + slotCounts.getValue(section.setId) }
        }
        fun navigate(setId: Long?) {
            if (!interactive) return
            if (query.text.isNotBlank()) query = TextFieldValue()
            if (snapshot.stickerSections.isEmpty()) features.loadStickers(snapshot.token, setId)
            else if (sections.firstOrNull { it.setId == setId }?.loaded != true) features.ensureStickerSection(snapshot.token, setId)
            navigation = StickerSectionNavigation(setId, ++navigationRevision)
        }
        LaunchedEffect(navigation, query.text, sectionStarts) {
            val target = navigation
            if (target != null && query.text.isBlank()) {
                grid.scrollToItem(sectionStarts[target.setId] ?: 0)
                navigation = null
            }
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "贴纸网格" }, contentAlignment = Alignment.Center) {
                LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid,
                    userScrollEnabled = interactive, contentPadding = PaddingValues(start = leftPadding, end = rightPadding, bottom = bottomPadding),
                    modifier = Modifier.fillMaxSize()) {
                    item(key = StickerGridKey(null, kind = 3), span = { GridItemSpan(maxLineSpan) }, contentType = "sticker-search") {
                        // StickersOnly has no tab/title strip. Keep search and utility actions on its one 33px row.
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 9.dp, top = 11.dp, bottom = 5.dp).height(33.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TextField(query, { query = it }, label = "按表情筛选", useLabelAsPlaceholder = true,
                                singleLine = true, enabled = interactive,
                                modifier = Modifier.weight(1f).height(33.dp).semantics { contentDescription = "筛选当前贴纸" },
                                insideMargin = DpSize(8.dp, 3.dp), cornerRadius = 15.dp, textStyle = MiuixTheme.textStyles.main.copy(fontSize = 13.sp))
                            if (snapshot.stickersLoading || snapshot.stickersError != null || sections.any { it.error != null }) Text("重新加载",
                                fontSize = 11.sp, color = colors.primary,
                                modifier = Modifier.height(30.dp).chatSquircleSurface(colors.surfaceContainer, 15.dp)
                                    .clickable(enabled = interactive && !snapshot.stickersLoading, role = Role.Button) {
                                        features.loadStickers(snapshot.token, currentSection)
                                    }.padding(horizontal = 7.dp, vertical = 7.dp))
                            Box(Modifier.size(30.dp).clip(CircleShape).clickable(enabled = interactive, role = Role.Button,
                                onClickLabel = "关闭贴纸", onClick = onDismiss), contentAlignment = Alignment.Center) {
                                Icon(MiuixIcons.Regular.Close, "关闭贴纸", Modifier.size(18.dp))
                            }
                        }
                    }
                    filtered.forEach { section ->
                        item(key = StickerGridKey(section.setId), span = { GridItemSpan(maxLineSpan) }, contentType = "section-header") {
                            if (section.setId == null) Spacer(Modifier.height(11.dp).semantics { contentDescription = "贴纸分组 最近" })
                            else Box(Modifier.fillMaxWidth().height(33.dp).padding(horizontal = 4.dp)
                                .semantics { contentDescription = "贴纸分组 ${section.title}" }, contentAlignment = Alignment.CenterStart) {
                                Text(section.title, fontSize = 12.sp, color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (section.stickers.isNotEmpty() || !section.loaded) {
                            // Native pack counts reserve the same square slots before and after metadata arrives.
                            // A single tall status item loses its lazy anchor when replaced at the bottom of a pack.
                            items(count = slotCounts.getValue(section.setId),
                                key = { StickerGridKey(section.setId, it, 1) }, contentType = { "native-sticker" }) { index ->
                                val sticker = section.stickers.getOrNull(index)
                                if (sticker != null) key(sticker.id, sticker.media.file.id) {
                                    StickerPickerCell(sticker, snapshot, features,
                                        StickerGridKey(section.setId, index, 1) in visibleKeys, focused, interactive,
                                        onSend = {
                                            features.sendSticker(snapshot.token, sticker)
                                            onDismiss()
                                            onSent(snapshot.token)
                                        })
                                } else Box(Modifier.fillMaxWidth().aspectRatio(1f)
                                    .then(if (section.error != null) Modifier.semantics {
                                        contentDescription = "重新加载贴纸包 ${section.title}"
                                    }.clickable(enabled = interactive, role = Role.Button) {
                                        features.loadStickers(snapshot.token, section.setId)
                                    } else Modifier), contentAlignment = Alignment.TopCenter) {
                                    if (index == 0) Text(when {
                                        section.error != null -> "加载失败，点击重试"
                                        section.loading -> "正在加载贴纸……"
                                        else -> "等待加载贴纸……"
                                    }, fontSize = 12.sp, color = colors.onSurfaceVariantSummary, maxLines = 2,
                                        modifier = Modifier.padding(top = 12.dp, start = 2.dp, end = 2.dp))
                                }
                            }
                        } else item(key = StickerGridKey(section.setId, kind = 2), span = { GridItemSpan(maxLineSpan) }, contentType = "section-status") {
                            Box(Modifier.fillMaxWidth().height(cellSize)
                                .clickable(enabled = interactive && section.error != null, role = Role.Button) {
                                    features.loadStickers(snapshot.token, section.setId)
                                }, contentAlignment = Alignment.TopCenter) {
                                Text(when {
                                    section.error != null -> "加载失败，点击重试"
                                    section.loading -> "正在加载贴纸……"
                                    !section.loaded -> "等待加载贴纸……"
                                    query.text.isNotBlank() -> "当前贴纸没有匹配项"
                                    else -> "暂无贴纸"
                                }, fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    if (filtered.isEmpty()) item(key = "empty-filter", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                            Text("当前贴纸没有匹配项", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                        }
                    }
                }
                if (snapshot.stickersLoading && filtered.any { it.stickers.isNotEmpty() }) Text("正在加载贴纸……", fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary, modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp))
            }
            LazyRow(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "贴纸包栏" }, state = footer,
                contentPadding = PaddingValues(horizontal = 3.dp), horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically, userScrollEnabled = interactive) {
                if (showRecentIcon) item(key = "recent-pack") { StickerPackTab("最近贴纸", currentSection == null, interactive, { navigate(null) }) {
                    val color = if (currentSection == null) colors.primary else colors.onSurfaceVariantSummary
                    Canvas(Modifier.size(20.dp)) {
                        drawCircle(color, radius = size.minDimension * .4f, style = Stroke(1.5.dp.toPx()))
                        drawLine(color, center, Offset(center.x, size.height * .23f), 1.5.dp.toPx())
                        drawLine(color, center, Offset(size.width * .68f, center.y), 1.5.dp.toPx())
                    }
                } }
                items(snapshot.stickerSets, key = { it.id }) { pack ->
                    StickerPackTab("贴纸包 ${pack.title}", currentSection == pack.id, interactive, { navigate(pack.id) }) {
                        val cover = pack.cover ?: sections.firstOrNull { it.setId == pack.id }?.stickers?.firstOrNull()
                        if (cover != null) StickerPackArtwork(cover, snapshot, features, interactive)
                        else Text("◈", fontSize = 18.sp, color = colors.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

private data class StickerSectionNavigation(val setId: Long?, val revision: Int)

private data class StickerGridKey(val setId: Long?, val slotIndex: Int? = null, val kind: Int = 0) {
    override fun toString() = "StickerGridKey(<redacted>)"
}

/** Counts reserve layout only; a slot never creates a sticker identity or grants native file/send access. */
internal fun stickerSectionSlotCount(section: StickerSection, nativeCount: Int, columns: Int): Int =
    if (section.stickers.isNotEmpty()) section.stickers.size
    else if (section.loaded) 1
    else if (nativeCount > 0) nativeCount.coerceAtMost(MAX_STICKER_PLACEHOLDER_SLOTS)
    else columns.coerceAtLeast(1)

internal fun filterLoadedStickers(stickers: List<StickerItem>, query: String): List<StickerItem> =
    if (query.isBlank()) stickers else stickers.filter { it.emoji.contains(query.trim(), ignoreCase = true) }

@Composable
private fun StickerPackTab(label: String, selected: Boolean, interactive: Boolean, onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.size(30.dp).chatSquircleSurface(if (selected) colors.tertiaryContainer else Color.Transparent, 15.dp)
        .semantics { contentDescription = label; this.selected = selected }
        .clickable(enabled = interactive, role = Role.Tab, onClick = onClick).padding(1.dp),
        contentAlignment = Alignment.Center, content = content)
}

@Composable
private fun StickerPackArtwork(sticker: StickerItem, snapshot: ChatSnapshot, features: ChatFeatureActions, interactive: Boolean) {
    val media = sticker.media
    val original = snapshot.mediaFiles[media.file.id] ?: media.file
    val thumbnail = media.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }?.takeIf { media.thumbnailFormat != null }
    val file = thumbnail ?: original
    MediaArtworkVisibility(snapshot, features, file, interactive)
    StickerCellMediaPreview(file.localPath?.takeIf { file.downloaded }, if (thumbnail != null) media.thumbnailFormat else media.format,
        null, media.format, false, modifier = Modifier.fillMaxSize().clearAndSetSemantics {}, placeholder = {
            Text(sticker.emoji.ifBlank { "◈" }, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Clip)
        })
}

@Composable
private fun StickerPickerCell(sticker: StickerItem, snapshot: ChatSnapshot, features: ChatFeatureActions,
    visible: Boolean, active: Boolean, interactive: Boolean, onSend: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val media = sticker.media
    val file = snapshot.mediaFiles[media.file.id] ?: media.file
    val thumbnail = media.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }?.takeIf {
        media.thumbnailFormat != null && (it.downloaded || it.canDownload || it.downloading) && it.id !in snapshot.fileErrors
    }
    val still = thumbnail ?: file
    val stillFormat = if (thumbnail == null) media.format else media.thumbnailFormat
    val animate = visible && active && interactive && hovered
    var composedVisible by remember(snapshot.token, sticker.id) { mutableStateOf(false) }
    SideEffect { if (visible) composedVisible = true }
    MediaArtworkVisibility(snapshot, features, still, visible && interactive)
    if (file.id != still.id) MediaArtworkVisibility(snapshot, features, file, animate)
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth().aspectRatio(1f).chatSquircleSurface(if (hovered) colors.surfaceContainer else Color.Transparent, 15.dp)
        .semantics { contentDescription = "发送贴纸 ${sticker.emoji}" }.hoverable(interaction)
        .clickable(enabled = interactive && snapshot.enabled && snapshot.selected?.id == snapshot.token.chatId &&
            !snapshot.sendPending && snapshot.selected?.canSendStickers == true,
            role = Role.Button, interactionSource = interaction, indication = LocalIndication.current, onClick = onSend)
        .padding(3.dp), contentAlignment = Alignment.Center) {
        StickerCellMediaPreview(still.localPath?.takeIf { composedVisible && still.downloaded }, stillFormat,
            file.localPath?.takeIf { composedVisible && file.downloaded }, media.format, animate,
            modifier = Modifier.fillMaxSize().clearAndSetSemantics {}, placeholder = {
                Text(if (file.id in snapshot.fileErrors || still.id in snapshot.fileErrors) "加载失败" else sticker.emoji.ifBlank { "…" },
                    fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariantSummary)
            })
    }
}
