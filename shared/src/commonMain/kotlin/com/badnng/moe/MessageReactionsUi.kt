package com.badnng.moe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.badnng.moe.chat.*
import com.badnng.moe.media.ReactionMediaPreview
import com.badnng.moe.media.ReactionEffectPreview
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private val LocalReactionEmojiMeasurer = staticCompositionLocalOf<TextMeasurer?> { null }

/** Keep the theme hue, but give the small selected-count label a solid, readable surface. */
internal fun reactionSelectedSurface(primary: Color, onPrimary: Color): Color {
    val base = primary.copy(alpha = 1f)
    fun contrast(surface: Color): Float {
        val background = surface.luminance()
        val foreground = onPrimary.compositeOver(surface).luminance()
        return (maxOf(background, foreground) + 0.05f) / (minOf(background, foreground) + 0.05f)
    }
    val target = 4.8f
    if (contrast(base) >= target) return base
    // Miuix's light foreground uses the darker endpoint. A custom theme with a
    // dark onPrimary may require the opposite endpoint to keep its label readable.
    val dark = Color(0f, 0f, 0f, 1f, base.colorSpace)
    val light = Color(1f, 1f, 1f, 1f, base.colorSpace)
    val end = if (contrast(dark) >= target || contrast(dark) >= contrast(light)) dark else light
    fun shade(amount: Float) = Color(
        base.red + (end.red - base.red) * amount,
        base.green + (end.green - base.green) * amount,
        base.blue + (end.blue - base.blue) * amount,
        1f, base.colorSpace,
    )
    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2f
        if (contrast(shade(middle)) >= target) high = middle else low = middle
    }
    return shade(high)
}

/** Keep a bounded font-layout cache while the quick bar expands into the full picker. */
@Composable
internal fun ReactionEmojiCache(content: @Composable () -> Unit) {
    val inherited = LocalReactionEmojiMeasurer.current
    val measurer = inherited ?: rememberTextMeasurer(cacheSize = 96)
    CompositionLocalProvider(LocalReactionEmojiMeasurer provides measurer, content = content)
}

@Composable
internal fun MessageReactionRow(message: ChatMessage, snapshot: ChatSnapshot, features: ChatFeatureActions,
    active: Boolean = false, fillWidth: Boolean = true, alignRight: Boolean = false, metadata: @Composable () -> Unit = {}) {
    val canReact = message.id > 0 && message.delivery == MessageDelivery.SENT
    val intents = LocalReactionAdditionIntents.current
    val intent = intents?.current?.takeIf { it.messageId == message.id }
    // In groups the official client substitutes portraits only for a complete,
    // small native recent list; a partial list must not hide the actual count.
    val showRecentAvatars = snapshot.selected?.privateUserId != null ||
        message.reactions.sumOf { it.totalCount.toLong() } <= 3L &&
        message.reactions.all { it.totalCount == it.recentSenders.size }
    var footerBounds by remember { mutableStateOf(Rect.Zero) }
    val chipBounds = remember(snapshot.token, message.id) { mutableStateMapOf<ReactionKey, Rect>() }
    val chipArtworkBounds = remember(snapshot.token, message.id) { mutableStateMapOf<ReactionKey, Rect>() }
    val additions = remember(snapshot.token, message.id) { MessageReactionAddition(message.reactions) }
    var sequence by remember(snapshot.token, message.id) { mutableLongStateOf(0) }
    var playbacks by remember(snapshot.token, message.id) { mutableStateOf(emptyMap<ReactionKey, Long>()) }
    // The flying artwork is a sibling of the footer. Its ready center must own the
    // stationary glyph too; hiding only the flyer's own fallback leaves two bodies.
    var flightCenterOwner by remember(snapshot.token, message.id) { mutableStateOf<Pair<Long, ReactionKey>?>(null) }
    LaunchedEffect(message.reactions, active) {
        val added = additions.update(message.reactions, active)
        playbacks = if (!active) emptyMap() else playbacks.filterKeys { key ->
            message.reactions.any { it.key == key && it.isChosen && it.canToggle }
        } + added.filterNot { intents?.acknowledge(message.id, it) == true }.associateWith { ++sequence }
    }
    val widthModifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    Box(widthModifier.onGloballyPositioned { footerBounds = it.boundsInWindow(clipBounds = false) }) {
        MessageReactionFooter(widthModifier, alignRight = alignRight) {
            message.reactions.forEach { reaction ->
                key(reaction.key, reaction.isPaid) {
                    MessageReactionChip(reaction, snapshot, features, canReact, active, playbacks[reaction.key],
                        showRecentAvatars = showRecentAvatars,
                        centerOwnedByFlight = {
                            active && intent != null && flightCenterOwner == (intent.id to reaction.key)
                        },
                        onArtworkBounds = { chipArtworkBounds[reaction.key] = it },
                        onBounds = { chipBounds[reaction.key] = it }, onClick = {
                            if (!reaction.isChosen) intents?.request(message.id, reaction.key, chipBounds[reaction.key])
                            features.chooseReaction(snapshot.token, message.id, reaction.key, !reaction.isChosen)
                        })
                }
            }
            Box { metadata() }
        }
        if (intent != null && active) {
            ReactionAdditionFlight(intent, footerBounds, chipBounds[intent.key], snapshot, features,
                chipArtwork = chipArtworkBounds[intent.key],
                onCenterRendering = { rendering ->
                    val owner = intent.id to intent.key
                    if (rendering) flightCenterOwner = owner
                    else if (flightCenterOwner == owner) flightCenterOwner = null
                }) { intents.finish(intent.id) }
        }
    }
    if (snapshot.reactionErrorMessageId == message.id) snapshot.reactionError?.let { ChatErrorText(it) }
}

@Composable
private fun MessageReactionChip(reaction: MessageReaction, snapshot: ChatSnapshot, features: ChatFeatureActions,
    canReact: Boolean, active: Boolean, playbackId: Long?, showRecentAvatars: Boolean, centerOwnedByFlight: () -> Boolean,
    onArtworkBounds: (Rect) -> Unit, onBounds: (Rect) -> Unit, onClick: () -> Unit) {
    val label = when {
        reaction.isPaid -> "⭐"
        reaction.customEmojiId != 0L -> "自定义表情"
        else -> reaction.emoji
    }
    val colors = MiuixTheme.colorScheme
    val avatars = remember(showRecentAvatars, reaction.totalCount, reaction.recentSenders,
        snapshot.senderAvatars, snapshot.knownChats, snapshot.account) {
        if (!showRecentAvatars || reaction.totalCount != reaction.recentSenders.size) emptyList()
        else reaction.recentSenders.mapNotNull { sender -> snapshot.reactionAvatar(sender) }
            .takeIf { it.size == reaction.totalCount && it.size <= 3 }.orEmpty()
    }
    val enabled = canReact && reaction.canToggle && !snapshot.reactionPending
    val selectedSurface = remember(colors.primary, colors.onPrimary) {
        reactionSelectedSurface(colors.primary, colors.onPrimary)
    }
    val surface = if (reaction.isChosen) selectedSurface else colors.surfaceContainer
    val foreground = if (reaction.isChosen) colors.onPrimary else colors.onSurfaceVariantSummary
    var centerFinished by remember(playbackId) { mutableStateOf(false) }
    var centerReady by remember(playbackId) { mutableStateOf(false) }
    var aroundFinished by remember(playbackId) { mutableStateOf(false) }
    var expired by remember(playbackId) { mutableStateOf(false) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(playbackId) {
        if (playbackId != null) {
            pulse.snapTo(0.72f)
            pulse.animateTo(1.12f, tween(150))
            pulse.animateTo(1f, tween(150))
            delay(3700)
            expired = true
        } else pulse.snapTo(1f)
    }
    val adding = active && playbackId != null && !expired
    val leftPadding = 5.dp
    val rightPadding = if (avatars.isEmpty()) 7.dp else 1.dp
    Box(Modifier.zIndex(if (adding) 1f else 0f).onGloballyPositioned {
        onBounds(it.boundsInWindow(clipBounds = false))
    }) {
    Row(Modifier.height(22.dp).graphicsLayer {
        scaleX = if (adding) pulse.value else 1f; scaleY = if (adding) pulse.value else 1f
        transformOrigin = TransformOrigin(0f, 0.5f)
    }
        .chatSquircleSurface(surface, 11.dp)
        .semantics {
            contentDescription = "$label ${reaction.totalCount} 条回应"
            selected = reaction.isChosen
        }.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(start = leftPadding, end = rightPadding),
        horizontalArrangement = Arrangement.spacedBy(if (avatars.isEmpty()) 3.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (reaction.isPaid) Text(label, fontSize = 16.sp, color = foreground)
        else Box(Modifier.size(reactionInlineSlotEdge).onGloballyPositioned {
            onArtworkBounds(it.boundsInWindow(clipBounds = false))
        }) {
            ReactionArtwork(reaction.key, snapshot, features, animate = false, Modifier.fillMaxSize(), fontSize = 16,
                foreground = foreground,
                hiddenWhile = { centerOwnedByFlight() || adding && !centerFinished && centerReady })
        }
        // Native recent-user portraits replace the count, as in InlineList's userpics branch.
        if (avatars.isNotEmpty()) NativeAvatarStack(avatars, snapshot, features,
            size = 20.dp, overlap = 7.dp, surfaceColor = surface)
        else Text(reaction.totalCount.toString(), fontSize = 12.sp, color = foreground)
    }
    // InlineList paints both streams in its outer effect pass. The native
    // 32/64 dp canvases must escape the 22 dp Miuix pill's subtree mask.
    if (adding && (!centerFinished || !aroundFinished)) Box(Modifier.matchParentSize().clearAndSetSemantics {}
        .graphicsLayer {
            scaleX = pulse.value; scaleY = pulse.value
            transformOrigin = TransformOrigin(0f, 0.5f)
        }) {
        if (!centerFinished) ReactionAdditionArtwork(reaction.key, snapshot, features,
            checkNotNull(playbackId), around = false,
            Modifier.align(Alignment.CenterStart).offset(x = leftPadding).requiredSize(reactionInlineSlotEdge),
            onReady = { centerReady = it }) {
            centerFinished = true
        }
        if (!aroundFinished) ReactionAdditionArtwork(reaction.key, snapshot, features, checkNotNull(playbackId), around = true,
            Modifier.align(Alignment.CenterStart)
                .offset(x = leftPadding + (reactionInlineSlotEdge - reactionInlineAroundEdge) / 2)
                .requiredSize(reactionInlineAroundEdge)) {
            aroundFinished = true
        }
    }
    }
}

@Composable
internal fun MessageQuickReactionBar(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    onMore: () -> Unit,
    onSelected: () -> Unit,
    embedded: Boolean = false,
    interactive: Boolean = true,
) {
    val current = snapshot.reactionMessageId == message.id
    val options = snapshot.reactionOptions.takeIf { current }
    val chosen = remember(message.reactions) { message.reactions.filter { it.canToggle && it.isChosen }.map { it.key }.toSet() }
    val emojis = remember(options, chosen) {
        (options?.allChoices().orEmpty() + chosen.map { ReactionChoice(it) }).distinctBy { it.key }
            .filter { it.key in chosen || options?.canAdd == true && !it.needsPremium }.take(6)
    }
    val colors = MiuixTheme.colorScheme
    val intents = LocalReactionAdditionIntents.current
    ReactionEmojiCache {
        Row(Modifier.fillMaxWidth().then(if (embedded) Modifier else Modifier.chatSquircleSurface(colors.surfaceContainer, 26.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (emojis.isEmpty()) Text(if (!current || snapshot.reactionsLoading) "正在加载表情……" else "表情回应",
                fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            emojis.forEach { choice ->
                val emoji = choice.key
                ReactionEmojiCell(emoji, emoji in chosen, interactive && !snapshot.reactionPending,
                    modifier = Modifier.size(29.dp), fontSize = 21, snapshot = snapshot, features = features) {
                    if (emoji !in chosen) intents?.request(message.id, emoji, it)
                    features.chooseReaction(snapshot.token, message.id, emoji, emoji !in chosen)
                    onSelected()
                }
            }
            Box(Modifier.size(29.dp).chatSquircleSurface(colors.onSurface.copy(alpha = 0.06f), 15.dp)
                .clickable(role = Role.Button, onClickLabel = "全部表情", onClick = onMore), contentAlignment = Alignment.Center) {
                Icon(MiuixIcons.Regular.More, "全部表情", Modifier.size(18.dp), tint = colors.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
internal fun MessageReactionPicker(message: ChatMessage?, snapshot: ChatSnapshot, features: ChatFeatureActions, onDismiss: () -> Unit,
    anchor: Rect? = null) {
    val expanded = message != null
    val visibility = remember(snapshot.token) { MutableTransitionState(false) }
    var retainedMessage by remember(snapshot.token) { mutableStateOf<ChatMessage?>(null) }
    var retainedSnapshot by remember(snapshot.token) { mutableStateOf<ChatSnapshot?>(null) }
    var retainedAnchor by remember(snapshot.token) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(expanded) { visibility.targetState = expanded }
    LaunchedEffect(expanded, visibility.isIdle, visibility.currentState) {
        if (!expanded && visibility.isIdle && !visibility.currentState) {
            retainedMessage = null
            retainedSnapshot = null
            retainedAnchor = null
        }
    }
    SideEffect {
        if (message != null) {
            retainedMessage = message
            retainedSnapshot = snapshot
            retainedAnchor = anchor
        }
    }
    val shownMessage = message ?: retainedMessage ?: return
    if (!expanded && !visibility.currentState && visibility.isIdle) return
    val shownSnapshot = if (expanded) snapshot else retainedSnapshot ?: snapshot
    ContextMenuBackdropRegistration(expanded)
    val density = LocalDensity.current
    val viewport = LocalWindowInfo.current.containerSize
    val margin = with(density) { 12.dp.roundToPx() }
    val shadowPadding = with(density) { ChatShortcutShadowPadding.roundToPx() }
    val popupWidth = if (viewport.width > 0) with(density) { (viewport.width - shadowPadding * 2).coerceAtLeast(1).toDp() }.coerceAtMost(320.dp) else 320.dp
    val popupHeight = if (viewport.height > 0) with(density) { (viewport.height - shadowPadding * 2).coerceAtLeast(1).toDp() }.coerceAtMost(440.dp) else 440.dp
    val position = remember(anchor, retainedAnchor, margin, shadowPadding) {
        ChatShortcutPopupPosition(ReactionPickerPosition(anchor ?: retainedAnchor, margin), shadowPadding)
    }
    Popup(popupPositionProvider = position, onDismissRequest = { if (expanded) onDismiss() },
        properties = PopupProperties(focusable = expanded)) {
        AnimatedVisibility(visibleState = visibility,
            enter = fadeIn(tween(130)) + scaleIn(tween(170), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = fadeOut(tween(220, easing = FastOutSlowInEasing)) + scaleOut(tween(240, easing = FastOutSlowInEasing),
                targetScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 1f))) {
            ChatShortcutShadowSpace {
            Box(Modifier.width(popupWidth).heightIn(max = popupHeight).onPreviewKeyEvent {
                if (!expanded) true else if (it.key == Key.Escape) {
                    if (it.type == KeyEventType.KeyDown) onDismiss()
                    true
                } else false
            }.pointerInput(expanded) {
                if (!expanded) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }.then(if (expanded) Modifier else Modifier.clearAndSetSemantics {})) {
                MessageReactionPickerPanel(shownMessage, shownSnapshot, features, onDismiss, expanded)
            }
            }
        }
    }
}

@Composable
internal fun MessageReactionPickerPanel(message: ChatMessage, snapshot: ChatSnapshot, features: ChatFeatureActions,
    onDismiss: () -> Unit, interactive: Boolean, modifier: Modifier = Modifier, embedded: Boolean = false,
    focusSearch: Boolean = interactive) {
    val intents = LocalReactionAdditionIntents.current
    val current = snapshot.reactionMessageId == message.id
    val options = snapshot.reactionOptions.takeIf { current }
    val chosen = remember(message.reactions) { message.reactions.filter { it.canToggle && it.isChosen }.map { it.key }.toSet() }
    var search by remember(snapshot.token, message.id) { mutableStateOf(TextFieldValue()) }
    var category by remember(snapshot.token, message.id) { mutableStateOf(ReactionEmojiCategory.ALL) }
    var selectedPack by remember(snapshot.token, message.id) { mutableStateOf<Long?>(null) }
    var browsingGroups by remember(snapshot.token, message.id) { mutableStateOf(false) }
    var selectedGroup by remember(snapshot.token, message.id) { mutableStateOf<Long?>(null) }
    val sourceGroups = remember(snapshot.knownChats, snapshot.token.chatId) {
        snapshot.knownChats.filter { it.id != snapshot.token.chatId && it.supergroupId != null && !it.isChannel && it.isJoinedGroup }
    }
    LaunchedEffect(snapshot.token, message.id) { features.loadReactionPacks(snapshot.token, null) }
    val ordinaryChosen = remember(chosen) { chosen.filter { it.customEmojiId == 0L }.map { it.emoji }.toSet() }
    val availableChoices = remember(options, chosen) {
        (options?.allChoices().orEmpty() + chosen.map { ReactionChoice(it) }).distinctBy { it.key }
    }
    val available = remember(availableChoices) { availableChoices.map { it.key.emoji }.filter(String::isNotBlank) }
    val categories = remember(available) {
        val present = available.mapTo(mutableSetOf(), ::reactionEmojiCategory)
        ReactionEmojiCategory.entries.filter { it == ReactionEmojiCategory.ALL || it in present }
    }
    LaunchedEffect(categories) { if (category !in categories) category = ReactionEmojiCategory.ALL }
    val emojis = remember(availableChoices, ordinaryChosen, search.text, category, selectedPack, selectedGroup, browsingGroups,
        snapshot.reactionPackEmojis, snapshot.reactionPackId, snapshot.reactionGroupSourceId, snapshot.reactionGroupLoading, snapshot.reactionVisuals) {
        val filteredOrdinary = filterReactionEmojis(available, ordinaryChosen, search.text, category).toSet()
        val source = when {
            browsingGroups -> snapshot.reactionPackEmojis.takeIf { selectedGroup != null &&
                snapshot.reactionGroupSourceId == selectedGroup && !snapshot.reactionGroupLoading }.orEmpty()
            selectedPack != null -> snapshot.reactionPackEmojis.takeIf { snapshot.reactionPackId == selectedPack &&
                snapshot.reactionGroupSourceId == null }.orEmpty()
            else -> availableChoices
        }
        source.filter { choice ->
            if (choice.key.customEmojiId == 0L) choice.key.emoji in filteredOrdinary
            else category == ReactionEmojiCategory.ALL && (search.text.isBlank() ||
                snapshot.reactionVisuals[choice.key]?.staticIcon?.emoji?.contains(search.text, ignoreCase = true) == true)
        }
    }
    val grid = key(snapshot.token, message.id) { rememberLazyGridState() }
    LaunchedEffect(snapshot.token, message.id, search.text, category, selectedPack, selectedGroup) { grid.scrollToItem(0) }
    val searchFocus = remember { FocusRequester() }
    val colors = MiuixTheme.colorScheme
    LaunchedEffect(interactive, focusSearch) { if (interactive && focusSearch) searchFocus.requestFocus() }
    ReactionEmojiCache {
        Column(modifier.fillMaxWidth().then(if (embedded) Modifier else Modifier.chatShortcutShadow(18.dp)
            .chatSquircleSurface(colors.surfaceContainer, 18.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("表情回应", fontSize = 16.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "关闭表情选择", onClick = onDismiss),
                    contentAlignment = Alignment.Center) { Icon(MiuixIcons.Regular.Close, "关闭", Modifier.size(18.dp)) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                categories.forEach { tab ->
                    Text(tab.label, fontSize = 12.sp, color = if (category == tab) colors.primary else colors.onSurfaceVariantSummary,
                        modifier = Modifier.chatSquircleSurface(if (category == tab) colors.tertiaryContainer else colors.surfaceContainer, 15.dp)
                            .semantics { selected = category == tab }.clickable(role = Role.Tab) { category = tab }
                            .padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            if (snapshot.reactionPacks.isNotEmpty() || sourceGroups.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (sourceGroups.isNotEmpty()) Text("其他群表情", fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.chatSquircleSurface(if (browsingGroups) colors.tertiaryContainer else colors.surfaceContainer, 15.dp)
                        .semantics { selected = browsingGroups }.clickable(enabled = interactive, role = Role.Tab) {
                            browsingGroups = true
                            selectedGroup = null
                            selectedPack = null
                            category = ReactionEmojiCategory.ALL
                            search = TextFieldValue()
                            features.loadReactionGroupPack(snapshot.token, null)
                        }.padding(horizontal = 10.dp, vertical = 7.dp))
                val packs = listOf<StickerPack?>(null) + snapshot.reactionPacks
                packs.forEach { pack ->
                    Text(pack?.title ?: "最近 / 推荐", fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.chatSquircleSurface(if (!browsingGroups && selectedPack == pack?.id) colors.tertiaryContainer else colors.surfaceContainer, 15.dp)
                            .semantics { selected = !browsingGroups && selectedPack == pack?.id }.clickable(enabled = interactive, role = Role.Tab) {
                                browsingGroups = false
                                selectedGroup = null
                                selectedPack = pack?.id
                                category = ReactionEmojiCategory.ALL
                                search = TextFieldValue()
                                if (pack == null) features.loadReactionGroupPack(snapshot.token, null)
                                else features.loadReactionPacks(snapshot.token, pack.id)
                            }.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            if (browsingGroups && selectedGroup != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(sourceGroups.firstOrNull { it.id == selectedGroup }?.title ?: "群表情", fontSize = 12.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChatButton("返回群列表", enabled = interactive, onClick = {
                    selectedGroup = null
                    search = TextFieldValue()
                    features.loadReactionGroupPack(snapshot.token, null)
                })
            }
            TextField(search, { search = it }, label = if (browsingGroups && selectedGroup == null) "搜索群" else "搜索表情", useLabelAsPlaceholder = true, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus), insideMargin = DpSize(12.dp, 8.dp), cornerRadius = 14.dp)
            when {
                (!current || snapshot.reactionsLoading) && options == null -> Text("正在获取可用表情……", fontSize = 12.sp)
                current && snapshot.reactionErrorMessageId == message.id && snapshot.reactionError != null -> {
                    ChatErrorText(snapshot.reactionError)
                    ChatButton("重试", enabled = !snapshot.reactionPending,
                        onClick = { features.loadReactions(snapshot.token, message.id) })
                }
                options != null && !options.canAdd -> Text("此消息不能添加回应，已选回应仍可取消。", fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary)
                !browsingGroups && selectedPack == null && availableChoices.isEmpty() -> Text("此消息暂无可用表情回应。", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            }
            if (browsingGroups && selectedGroup == null) {
                val groups = sourceGroups.filter { search.text.isBlank() || it.title.contains(search.text, ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groups, key = { it.id }) { source ->
                        Text(source.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().chatSquircleSurface(colors.surfaceContainer, 15.dp)
                                .clickable(enabled = interactive, role = Role.Button, onClickLabel = "查看群表情") {
                                    selectedGroup = source.id
                                    category = ReactionEmojiCategory.ALL
                                    search = TextFieldValue()
                                    features.loadReactionGroupPack(snapshot.token, source.id)
                                }.padding(horizontal = 12.dp, vertical = 12.dp))
                    }
                }
                if (groups.isEmpty()) Text("没有匹配的群", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            } else if (emojis.isNotEmpty()) LazyVerticalGrid(columns = GridCells.Adaptive(36.dp), state = grid,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 300.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(emojis, key = { it.key }, contentType = { "reaction" }) { choice ->
                    val emoji = choice.key
                    val listed = availableChoices.firstOrNull { it.key == emoji }
                    val permitted = listed?.needsPremium == false || listed == null && emoji.customEmojiId != 0L &&
                        options?.allowCustomEmoji == true && snapshot.account?.isPremium == true
                    ReactionEmojiCell(emoji, emoji in chosen, interactive && !snapshot.reactionPending &&
                        (emoji in chosen || options?.canAdd == true && permitted),
                        Modifier.fillMaxWidth().height(36.dp), fontSize = 25, snapshot = snapshot, features = features) {
                        if (emoji !in chosen) intents?.request(message.id, emoji, it)
                        features.chooseReaction(snapshot.token, message.id, emoji, emoji !in chosen)
                        onDismiss()
                    }
                }
            } else if (browsingGroups && snapshot.reactionGroupLoading) Text("正在加载群表情……", fontSize = 12.sp)
            else if (browsingGroups && snapshot.reactionGroupError != null) {
                if (snapshot.reactionGroupError == ChatError.UNSUPPORTED) Text("此群没有可用的专属表情包", fontSize = 12.sp)
                else ChatErrorText(snapshot.reactionGroupError)
                ChatButton("重试", enabled = interactive, onClick = { selectedGroup?.let { features.loadReactionGroupPack(snapshot.token, it) } })
            } else if (snapshot.reactionPacksLoading) Text("正在加载表情包……", fontSize = 12.sp)
            else if (availableChoices.isNotEmpty()) Text("没有匹配的表情", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            Text(if (snapshot.reactionPending) "正在更新回应……" else "再次选择已选表情可取消回应", fontSize = 11.sp,
                color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal class ReactionPickerPosition(private val anchor: Rect?, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val horizontalMargin = margin.coerceAtMost(((windowSize.width - width) / 2).coerceAtLeast(0))
        val verticalMargin = margin.coerceAtMost(((windowSize.height - height) / 2).coerceAtLeast(0))
        if (anchor == null) return IntOffset(((windowSize.width - width) / 2).coerceAtLeast(0),
            ((windowSize.height - height) / 2).coerceAtLeast(0))
        val gap = (margin * 2 / 3).coerceAtLeast(1)
        val preferredX = when {
            anchor.right.roundToInt() + gap + width <= windowSize.width - horizontalMargin -> anchor.right.roundToInt() + gap
            anchor.left.roundToInt() - gap - width >= horizontalMargin -> anchor.left.roundToInt() - gap - width
            else -> anchor.left.roundToInt()
        }
        val preferredY = when {
            anchor.top.roundToInt() - gap - height >= verticalMargin -> anchor.top.roundToInt() - gap - height
            anchor.bottom.roundToInt() + gap + height <= windowSize.height - verticalMargin -> anchor.bottom.roundToInt() + gap
            else -> anchor.top.roundToInt()
        }
        return IntOffset(preferredX.coerceIn(horizontalMargin, (windowSize.width - width - horizontalMargin).coerceAtLeast(horizontalMargin)),
            preferredY.coerceIn(verticalMargin, (windowSize.height - height - verticalMargin).coerceAtLeast(verticalMargin)))
    }
}

@Composable
private fun ReactionEmojiCell(key: ReactionKey, selected: Boolean, enabled: Boolean, modifier: Modifier, fontSize: Int,
    snapshot: ChatSnapshot, features: ChatFeatureActions, onClick: (Rect?) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val label = key.emoji.ifEmpty { "自定义表情" }
    Box(modifier.onGloballyPositioned { bounds = it.boundsInWindow(clipBounds = false) }
        .chatSquircleSurface(if (selected) colors.tertiaryContainer else colors.surfaceContainer, 15.dp)
        .semantics { contentDescription = label; this.selected = selected }
        .hoverable(interaction)
        .clickable(enabled = enabled, interactionSource = interaction, indication = LocalIndication.current,
            role = Role.Button, onClickLabel = label, onClick = { onClick(bounds) }),
        contentAlignment = Alignment.Center) {
        ReactionArtwork(key, snapshot, features, animate = enabled && hovered && LocalWindowInfo.current.isWindowFocused,
            Modifier.fillMaxSize(), fontSize)
    }
}

@Composable
internal fun ReactionArtwork(key: ReactionKey, snapshot: ChatSnapshot, features: ChatFeatureActions, animate: Boolean,
    modifier: Modifier, fontSize: Int, hidden: Boolean = false, foreground: Color? = null,
    hiddenWhile: (() -> Boolean)? = null) {
    val visual = snapshot.reactionVisuals[key]
    listOfNotNull(visual?.centerAnimation, visual?.aroundAnimation).distinctBy { it.file.id }.forEach { media ->
        androidx.compose.runtime.key(media.file.id) {
            MediaArtworkVisibility(snapshot, features, snapshot.mediaFiles[media.file.id] ?: media.file, animate)
        }
    }
    LaunchedEffect(snapshot.token, key, visual, snapshot.reactionVisualLoading.size) {
        if (visual == null && key !in snapshot.reactionVisualLoading && key !in snapshot.reactionVisualErrors)
            features.loadReactionVisual(snapshot.token, key)
    }
    val media = if (animate) visual?.selectAnimation ?: visual?.staticIcon else visual?.staticIcon ?: visual?.selectAnimation
    val file = media?.file?.let { snapshot.mediaFiles[it.id] ?: it }
    val still = visual?.staticIcon?.let { icon -> snapshot.mediaFiles[icon.file.id] ?: icon.file }
    MediaArtworkVisibility(snapshot, features, file, true)
    val stillPath = still?.localPath?.takeIf { still.downloaded }
    val animation = visual?.selectAnimation
    val animatedFile = animation?.file?.let { snapshot.mediaFiles[it.id] ?: it }
    val animationPath = animatedFile?.localPath?.takeIf { animatedFile.downloaded }
    Box(modifier.clearAndSetSemantics {}.graphicsLayer {
        alpha = if (hidden || hiddenWhile?.invoke() == true) 0f else 1f
    }, contentAlignment = Alignment.Center) {
        ReactionMediaPreview(stillPath, visual?.staticIcon?.format, animationPath, animation?.format,
            animate = animate, modifier = Modifier.fillMaxSize().padding(if (fontSize <= 16) 0.dp else 2.dp),
            placeholder = { ReactionFallbackGlyph(key, fontSize, foreground) })
    }
}

private fun reactionEffectMedia(key: ReactionKey, snapshot: ChatSnapshot, around: Boolean): MessageMedia? =
    snapshot.reactionVisuals[key]?.let { visual ->
        if (around) visual.aroundAnimation else visual.centerAnimation ?: visual.selectAnimation
    }

private fun reactionEffectFile(key: ReactionKey, snapshot: ChatSnapshot, around: Boolean): MediaFile? =
    reactionEffectMedia(key, snapshot, around)?.file?.let { snapshot.mediaFiles[it.id] ?: it }

/** Matches ReactionFlyAnimation's center + around paths; select is the official center fallback. */
@Composable
internal fun ReactionAdditionArtwork(key: ReactionKey, snapshot: ChatSnapshot, features: ChatFeatureActions,
    playbackId: Long, around: Boolean, modifier: Modifier, onReady: (Boolean) -> Unit = {}, onFinished: () -> Unit) {
    val media = reactionEffectMedia(key, snapshot, around)
    val file = reactionEffectFile(key, snapshot, around)
    MediaArtworkVisibility(snapshot, features, file, true)
    val path = file?.localPath?.takeIf { file.downloaded }
    val ready by rememberUpdatedState(onReady)
    // A path changing/disappearing must relinquish center ownership before a new
    // decoder is ready. Around artwork never calls the center's callback.
    DisposableEffect(snapshot.token, key, playbackId, around, path, media?.format) {
        onDispose { ready(false) }
    }
    if (media != null && path != null) {
        val edge = reactionAdditionArtworkEdge(key, snapshot.reactionVisuals[key]?.centerAnimation != null, around)
        // requiredSize keeps the native canvas centered on the measured glyph
        // while leaving the pill's layout and hit target at their natural size.
        ReactionEffectPreview(path, media.format, playbackId, modifier.requiredSize(edge).clearAndSetSemantics {},
            onReady, onFinished)
    }
}

/** Readable fallback while TDLib resolves artwork, and for explicit offline synthetic fixtures. */
@Composable
private fun ReactionFallbackGlyph(key: ReactionKey, fontSize: Int, foreground: Color? = null) {
    val colors = MiuixTheme.colorScheme
    val emoji = key.emoji.ifEmpty { "◇" }
    val measurer = LocalReactionEmojiMeasurer.current ?: rememberTextMeasurer(cacheSize = 16)
    // Preserve the original code points (including VS16/ZWJ/skin tones). Selection and
    // pending updates change the surface/alpha, not the expensive color-font layout.
    val glyph = remember(measurer, emoji, fontSize) {
        measurer.measure(emoji, style = TextStyle(fontSize = fontSize.sp), softWrap = false, maxLines = 1)
    }
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val inset = if (fontSize <= 16) 0f else 2.dp.toPx()
        val fit = minOf(1f, (size.width - inset * 2).coerceAtLeast(0f) / glyph.size.width.coerceAtLeast(1),
            (size.height - inset * 2).coerceAtLeast(0f) / glyph.size.height.coerceAtLeast(1))
        translate((size.width - glyph.size.width * fit) / 2f, (size.height - glyph.size.height * fit) / 2f) {
            scale(fit, fit, pivot = Offset.Zero) {
                drawText(glyph, color = foreground ?: colors.onSurface)
            }
        }
    }
}

private fun ChatFeatureActions.chooseReaction(token: ChatToken, messageId: Long, key: ReactionKey, add: Boolean) {
    if (key.customEmojiId != 0L) setCustomReaction(token, messageId, key.customEmojiId, add)
    else setReaction(token, messageId, key.emoji, add)
}

/** Only a native photo file creates a portrait; no initials stand in for a missing reader/reactor photo. */
@Composable
internal fun NativeAvatarStack(avatars: List<MediaFile>, snapshot: ChatSnapshot, features: ChatFeatureActions,
    size: Dp = 21.dp, overlap: Dp = 6.dp, surfaceColor: Color? = null) {
    val surface = surfaceColor ?: MiuixTheme.colorScheme.surfaceContainer
    Row(horizontalArrangement = Arrangement.spacedBy(-overlap), verticalAlignment = Alignment.CenterVertically) {
        avatars.take(3).forEach { avatar ->
            RemoteMedia(MessageMedia(avatar), snapshot, features, visible = true, animate = false, compact = true,
                modifier = Modifier.size(size).clip(CircleShape).background(surface, CircleShape)
                    .border(1.dp, surface, CircleShape),
                cornerRadius = size / 2, retainWhenInactive = true, placeholder = {},
                contentScale = ContentScale.Crop, highQuality = true)
        }
    }
}

private fun ChatSnapshot.reactionAvatar(sender: MessageReactionSender): MediaFile? = when {
    sender.senderIsChat -> knownChats.firstOrNull { it.id == sender.id }?.avatar
    sender.id == account?.userId -> account?.avatar
    else -> senderAvatars[sender.id] ?: knownChats.firstOrNull { it.privateUserId == sender.id }?.avatar
}
