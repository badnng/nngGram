package com.badnng.moe

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private data class FolderNavigationEntry(val list: ChatListRef, val name: String, val iconName: String?)

@Composable
private fun folderNavigationEntries(snapshot: ChatSnapshot): List<FolderNavigationEntry> =
    remember(snapshot.folders, snapshot.mainListPosition) {
        snapshot.folders.map { FolderNavigationEntry(ChatListRef(it.id), it.name, it.iconName) }.toMutableList().apply {
            add(snapshot.mainListPosition.coerceIn(0, size), FolderNavigationEntry(ChatListRef(), "全部", null))
        }
    }

@Composable
internal fun ChatFolderRail(
    snapshot: ChatSnapshot, onSelect: (ChatListRef) -> Unit, modifier: Modifier = Modifier,
    features: ChatFeatureActions, accountMenuOpen: Boolean, onAccountBounds: (Rect) -> Unit, onAccount: () -> Unit,
    preferences: ChatFolderPreferences = ChatFolderPreferences(),
    hideAccountAvatar: Boolean = false,
) {
    val entries = folderNavigationEntries(snapshot)
    val colors = MiuixTheme.colorScheme
    val compact = LocalDesktopChatLayout.current.compact
    val state = rememberLazyListState()
    val selectedIndex = entries.indexOfFirst { it.list == snapshot.activeList }
    val position by animateFloatAsState(selectedIndex.coerceAtLeast(0).toFloat(),
        tween(300, easing = FastOutSlowInEasing), label = "folder rail moving selection")
    val itemPadding = if (compact) 5.dp else 7.dp
    val iconHeight = if (compact) 32.dp else 40.dp
    val iconWidth = if (compact) 44.dp else 56.dp
    val measured = remember(entries, compact, preferences.appearance) { mutableMapOf<Int, Float>() }
    val layout = state.layoutInfo
    val visible = layout.visibleItemsInfo.filter { it.index < entries.size }
        .map { FolderItemMeasure(it.index, it.offset.toFloat(), it.size.toFloat()) }
    SideEffect { visible.forEach { measured[it.index] = it.size } }
    val density = LocalDensity.current
    val bounds = folderIndicatorBounds(position, entries.size, visible, measured,
        with(density) { (if (compact) 76.dp else 98.dp).toPx() })
    LaunchedEffect(selectedIndex, entries, preferences.appearance) {
        if (selectedIndex >= 0) {
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
            if (item == null || item.offset < state.layoutInfo.viewportStartOffset ||
                item.offset + item.size > state.layoutInfo.viewportEndOffset) state.animateScrollToItem(selectedIndex)
        }
    }
    Box(modifier.background(colors.surface)) {
        Column(Modifier.fillMaxSize().padding(vertical = if (compact) 12.dp else 16.dp)) {
            AccountAvatarButton(snapshot, features, accountMenuOpen, onAccountBounds, onAccount,
                Modifier.align(Alignment.CenterHorizontally), hideContent = hideAccountAvatar)
            Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (compact) 4.dp else 8.dp).clipToBounds().overScrollVertical()) {
                if (selectedIndex >= 0 && bounds != null) {
                    // One surface moves through every intermediate item, including variable-height labels.
                    Box(Modifier.align(Alignment.TopCenter)
                        .offset { IntOffset(0, (bounds.offset + with(density) { itemPadding.toPx() }).roundToInt()) }
                        .width(iconWidth).height(iconHeight).chatSquircleBackground(colors.tertiaryContainer, 16.dp))
                }
                LazyColumn(Modifier.fillMaxSize().scrollEndHaptic(), state = state) {
                    itemsIndexed(entries, key = { _, entry -> entry.list.folderId }) { _, entry ->
                        FolderRailItem(entry, entry.list == snapshot.activeList,
                            deviceFolderUnread(snapshot, entry.list)?.takeIf { it > 0 }, preferences.appearance) { onSelect(entry.list) }
                    }
                    item { Spacer(Modifier.height(if (compact) 12.dp else 16.dp).navigationBarsPadding()) }
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight()
            .background(colors.dividerLine.copy(alpha = 0.65f)))
    }
}

@Composable
private fun FolderRailItem(
    entry: FolderNavigationEntry, isSelected: Boolean, unread: Int?, appearance: FolderTabAppearance, onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val compact = LocalDesktopChatLayout.current.compact
    val showIcon = appearance != FolderTabAppearance.TEXT_ONLY
    val showLabel = appearance != FolderTabAppearance.ICON_ONLY
    Column(Modifier.fillMaxWidth().padding(bottom = if (compact) 8.dp else 18.dp)
        .chatSquircleSurface(Color.Transparent, 16.dp).clickable(role = Role.Tab, onClick = onClick)
        .semantics { selected = isSelected; contentDescription = entry.name }
        .padding(vertical = if (compact) 5.dp else 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)) {
        Box(Modifier.width(if (compact) 44.dp else 56.dp).height(if (compact) 32.dp else 40.dp),
            contentAlignment = Alignment.Center) {
            if (showIcon) FolderNavigationIcon(entry, isSelected, Modifier.size(if (compact) 22.dp else 26.dp))
            else FolderNavigationLabel(entry.name, isSelected)
            if (unread != null) Box(Modifier.align(Alignment.TopEnd)
                .offset(x = if (compact) 3.dp else 4.dp, y = if (compact) (-2).dp else (-3).dp)) { ChatBadge(unread) }
        }
        if (showIcon && showLabel) FolderNavigationLabel(entry.name, isSelected)
    }
}

@Composable
private fun FolderNavigationLabel(name: String, isSelected: Boolean) {
    Text(name, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
}

@Composable
private fun FolderNavigationIcon(entry: FolderNavigationEntry, selected: Boolean, modifier: Modifier) {
    Icon(when {
        entry.list.folderId == 0 || entry.iconName == "Groups" -> MiuixIcons.Regular.Messages
        entry.iconName == "Channels" -> MiuixIcons.Regular.VolumeUp
        else -> MiuixIcons.Regular.Folder
    }, null, modifier, tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun ChatFolderTopTabs(
    snapshot: ChatSnapshot, onSelect: (ChatListRef) -> Unit,
    preferences: ChatFolderPreferences = ChatFolderPreferences(), modifier: Modifier = Modifier,
    state: ChatFolderTopTabsState = remember { ChatFolderTopTabsState() },
) {
    val entries = folderNavigationEntries(snapshot)
    val colors = MiuixTheme.colorScheme
    val listState = state.listState
    val selectedIndex = entries.indexOfFirst { it.list == snapshot.activeList }
    val position by animateFloatAsState(selectedIndex.coerceAtLeast(0).toFloat(),
        tween(300, easing = FastOutSlowInEasing), label = "folder top moving selection")
    val measured = remember(entries, preferences.appearance) { mutableMapOf<Int, Float>() }
    val visible = listState.layoutInfo.visibleItemsInfo.filter { it.index < entries.size }
        .map { FolderItemMeasure(it.index, it.offset.toFloat(), it.size.toFloat()) }
    SideEffect { visible.forEach { measured[it.index] = it.size } }
    val density = LocalDensity.current
    val bounds = folderIndicatorBounds(position, entries.size, visible, measured,
        with(density) { 80.dp.toPx() }, with(density) { 6.dp.toPx() })
    LaunchedEffect(state, selectedIndex, entries, preferences.appearance) {
        if (state.claimSelectionReveal(snapshot, preferences.appearance) && selectedIndex >= 0) {
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
            // Clicking a visible (even partially visible) label changes its feedback,
            // without shifting the persistent header strip underneath the pointer.
            if (item == null) listState.animateScrollToItem(selectedIndex)
        }
    }
    // The scroll viewport includes real vertical room for each glass tab's exterior shadow.
    Box(modifier.height(52.dp).verticalWheelToHorizontal(listState).clipToBounds()) {
        LazyRow(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(entries, key = { _, entry -> entry.list.folderId }) { _, entry ->
                val selected = entry.list == snapshot.activeList
                Row(Modifier.height(36.dp).widthIn(min = 44.dp, max = 180.dp)
                    .chatListHeaderControlSurface(16.dp).clickable(role = Role.Tab) { onSelect(entry.list) }
                    .semantics { this.selected = selected; contentDescription = entry.name }
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (preferences.appearance == FolderTabAppearance.ICON_ONLY || preferences.appearance == FolderTabAppearance.ICON_TEXT)
                        FolderNavigationIcon(entry, selected, Modifier.size(20.dp))
                    if (preferences.appearance != FolderTabAppearance.ICON_ONLY) {
                        val labelWidth = chatTabLabelWidth(entry.name, 13.sp)
                        Text(entry.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) colors.primary else colors.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f, fill = false).width(labelWidth))
                    }
                    deviceFolderUnread(snapshot, entry.list)?.takeIf { it > 0 }?.let { ChatBadge(it) }
                }
            }
        }
        // Keep the small selection mark legible above the tabs' hover/press indication.
        // This overlay has no input handler; the actual tab remains the click target.
        if (selectedIndex >= 0 && bounds != null) Box(Modifier
            .offset { IntOffset((bounds.offset + with(density) { 12.dp.toPx() }).roundToInt(),
                with(density) { 40.dp.roundToPx() }) }
            .width(with(density) { bounds.size.toDp() - 24.dp }.coerceAtLeast(14.dp)).height(2.dp)
            .chatSquircleBackground(colors.primary, 1.dp))
    }
}

/** The workspace retains this alongside its list position while the pane is absent. */
@Stable
internal class ChatFolderTopTabsState {
    val listState = LazyListState()
    private var revealTarget: FolderTopTabsRevealTarget? = null

    internal fun claimSelectionReveal(snapshot: ChatSnapshot, appearance: FolderTabAppearance): Boolean {
        val target = FolderTopTabsRevealTarget(snapshot.activeList, snapshot.folders, snapshot.mainListPosition, appearance)
        if (revealTarget == target) return false
        // Claim before animation so a user-cancelled reveal is not retried after remount.
        revealTarget = target
        return true
    }
}

private data class FolderTopTabsRevealTarget(
    val list: ChatListRef,
    val folders: List<ChatFolderInfo>,
    val mainListPosition: Int,
    val appearance: FolderTabAppearance,
)

internal data class FolderItemMeasure(val index: Int, val offset: Float, val size: Float)
internal data class FolderIndicatorBounds(val offset: Float, val size: Float)

/** Fractional indices follow the actual measured path; offsets stay relative to the scrolling viewport. */
internal fun folderIndicatorBounds(
    position: Float, count: Int, visible: List<FolderItemMeasure>, measured: Map<Int, Float>, fallbackSize: Float,
    spacing: Float = 0f,
): FolderIndicatorBounds? {
    if (count == 0 || visible.isEmpty()) return null
    val clamped = position.coerceIn(0f, (count - 1).toFloat())
    fun size(index: Int) = visible.firstOrNull { it.index == index }?.size ?: measured[index] ?: fallbackSize
    fun offset(index: Int): Float {
        visible.firstOrNull { it.index == index }?.let { return it.offset }
        val anchor = visible.minBy { abs(it.index - index) }
        return if (index > anchor.index) anchor.offset + (anchor.index until index).sumOf { (size(it) + spacing).toDouble() }.toFloat()
        else anchor.offset - (index until anchor.index).sumOf { (size(it) + spacing).toDouble() }.toFloat()
    }
    val low = floor(clamped).toInt()
    val high = (low + 1).coerceAtMost(count - 1)
    val fraction = clamped - low
    return FolderIndicatorBounds(offset(low) + (offset(high) - offset(low)) * fraction,
        size(low) + (size(high) - size(low)) * fraction)
}
