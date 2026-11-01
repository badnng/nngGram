package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.badnng.moe.chat.*
import com.badnng.moe.media.MediaPreview
import com.badnng.moe.media.AccountAvatarPhoto
import com.badnng.moe.media.rememberAccountAvatarPhoto
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.presentation
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

private data class AccountAvatarPhotoBinding(
    val generation: Long,
    val userId: Long?,
    val file: MediaFile?,
    val photo: AccountAvatarPhoto?,
) {
    override fun toString() = "AccountAvatarPhotoBinding(<redacted>)"
}

private val LocalAccountAvatarPhoto = staticCompositionLocalOf<AccountAvatarPhotoBinding?> { null }

/** Hoist one decoded photo above the independently entering menu and profile dialog. */
@Composable
internal fun AccountAvatarPhotos(snapshot: ChatSnapshot, content: @Composable () -> Unit) {
    val source = snapshot.account?.avatar
    val file = source?.let { snapshot.mediaFiles[it.id] ?: it }
    val photo = rememberAccountAvatarPhoto(snapshot.token.generation, snapshot.account?.userId, file)
    val binding = remember(snapshot.token.generation, snapshot.account?.userId, file, photo) {
        AccountAvatarPhotoBinding(snapshot.token.generation, snapshot.account?.userId, file, photo)
    }
    CompositionLocalProvider(LocalAccountAvatarPhoto provides binding, content = content)
}

@Composable
internal fun AccountAvatarButton(snapshot: ChatSnapshot, features: ChatFeatureActions, expanded: Boolean,
    onBounds: (Rect) -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier, hideContent: Boolean = false) {
    val compact = LocalDesktopChatLayout.current.compact
    val measured = modifier.size(if (compact) 40.dp else 52.dp).onGloballyPositioned { onBounds(it.boundsInWindow()) }
    if (hideContent) {
        // The workspace keeps the real trigger and its decoded photo outside the blur layer.
        // This source position owns only layout, with no second decoder or hidden click target.
        Spacer(measured.clearAndSetSemantics { hideFromAccessibility() })
        return
    }
    IconButton(onClick, measured.clip(CircleShape).semantics { contentDescription = if (expanded) "关闭账号菜单" else "打开账号菜单" },
        holdDownState = expanded, backgroundColor = if (expanded) MiuixTheme.colorScheme.tertiaryContainer else Color.Transparent,
        minHeight = 40.dp, minWidth = 40.dp, cornerRadius = if (compact) 20.dp else 26.dp) {
        AccountAvatar(snapshot, features, Modifier.size(if (compact) 36.dp else 44.dp), compact = compact)
    }
}

@Composable
private fun AccountAvatar(snapshot: ChatSnapshot, features: ChatFeatureActions, modifier: Modifier, compact: Boolean = false) {
    val account = snapshot.account
    val source = account?.avatar
    val file = source?.let { snapshot.mediaFiles[it.id] ?: it }
    MediaArtworkVisibility(snapshot, features, file, active = true)
    val path = file?.localPath?.takeIf { file.downloaded }
    val sharedPhoto = LocalAccountAvatarPhoto.current?.takeIf {
        it.generation == snapshot.token.generation && it.userId == account?.userId && it.file == file
    }?.photo
    // A single path clip avoids stacking an offscreen shader mask over the native
    // Skia photo while the whole menu is also being scaled and alpha-composited.
    if (path != null && sharedPhoto != null) sharedPhoto.Content(modifier.clip(CircleShape), "我的头像")
    else if (path != null) MediaPreview(path, MediaFormat.PHOTO, modifier.clip(CircleShape),
        contentDescription = "我的头像", animate = false, contentScale = ContentScale.Crop, highQuality = true)
    else Box(modifier.background(if (account == null) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.primary,
        CircleShape), contentAlignment = Alignment.Center) {
        if (account == null) Icon(MiuixIcons.ContactsCircle, "账号资料暂不可用", Modifier.size(if (compact) 23.dp else 28.dp), tint = MiuixTheme.colorScheme.primary)
        else Text(account.displayName.firstOrNull()?.uppercase() ?: "我", fontSize = if (compact) 17.sp else 21.sp,
            fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onPrimary)
    }
}

/** The workspace supplies one reveal progress for this card and its blurred background. */
@Composable
internal fun AccountMenuOverlay(
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    connection: ConnectionPhase,
    anchor: Rect,
    viewport: IntSize,
    darkTheme: Boolean,
    onDarkTheme: (Boolean) -> Unit,
    showPhone: Boolean,
    onShowPhone: (Boolean) -> Unit,
    onProfile: () -> Unit,
    onSavedMessages: (() -> Unit)?,
    onArchive: () -> Unit,
    onManageFolders: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    progress: Float = 1f,
    interactive: Boolean = true,
    showAvatar: Boolean = true,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val position = accountMenuPosition(viewport, anchor, density.density)
    val maxHeight = with(density) { position.maxHeight.toDp() }
    val arrowHeight = 10.dp.coerceAtMost(maxHeight)
    val bodyMaxHeight = (maxHeight - arrowHeight).coerceAtLeast(0.dp)
    val squircleEnabled = isSquircleEnabled()
    val menuShape = remember(position.arrowCenter, arrowHeight, squircleEnabled) {
        AccountMenuShape(position.arrowCenter, arrowHeight, squircleEnabled)
    }
    // A blurred outline mask avoids the spot-shadow occluder seam on this concave arrow shape.
    val menuShadow = remember { Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.22f), offset = DpOffset(0.dp, 6.dp)) }
    val reveal = progress.coerceIn(0f, 1f)
    val focus = remember { FocusRequester() }
    var ownsFocus by remember { mutableStateOf(false) }
    val backdropInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(interactive) {
        if (interactive) focus.requestFocus() else if (ownsFocus) focusManager.clearFocus(force = true)
    }
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (!interactive) true else if (it.key == Key.Escape) {
            if (it.type == KeyEventType.KeyUp) onDismiss()
            true
        } else false
    }.pointerInput(interactive) {
        if (!interactive) awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }.onFocusChanged { ownsFocus = it.hasFocus }.focusRequester(focus).focusable(interactive)
        .then(if (interactive) Modifier.semantics { paneTitle = "账号菜单" } else Modifier.clearAndSetSemantics {})) {
        // This also covers the trigger, so a second avatar click closes the menu exactly once.
        Box(Modifier.matchParentSize().clickable(interactionSource = backdropInteraction, indication = null,
            onClick = { if (interactive) onDismiss() }).semantics { contentDescription = "关闭账号菜单" })
        if (showAvatar) AccountAvatarButton(snapshot, features, expanded = true, onBounds = {},
            onClick = { if (interactive) onDismiss() },
            modifier = Modifier.offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) })
        Column(Modifier.offset { IntOffset(position.left.roundToInt(), position.top.roundToInt()) }
            .width(with(density) { position.width.toDp() }).heightIn(max = maxHeight)
            // LayerOutsets writes its alpha paint into the parent display list. Refresh that
            // list as reveal changes, or Compose/Skia can retain the first translucent frame.
            .drawWithContent { if (reveal > 0f) drawContent() }
            .graphicsLayer {
                alpha = reveal
                outsets = LayerOutsets(40.dp)
                scaleX = 0.92f + 0.08f * reveal
                scaleY = scaleX
                translationY = -8.dp.toPx() * (1f - reveal)
                transformOrigin = TransformOrigin(if (position.width > 0f) (position.arrowCenter / position.width).coerceIn(0f, 1f) else 0.5f, 0f)
            }
            .dropShadow(menuShape, menuShadow).clip(menuShape)) {
            Canvas(Modifier.fillMaxWidth().height(arrowHeight)) {
                drawPath(Path().apply {
                    moveTo(position.arrowCenter - 10.dp.toPx(), size.height)
                    lineTo(position.arrowCenter, 0f)
                    lineTo(position.arrowCenter + 10.dp.toPx(), size.height)
                    close()
                }, colors.surfaceContainer)
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = bodyMaxHeight)
                .background(colors.surfaceContainer)
                .clickable(interactionSource = cardInteraction, indication = null, onClick = {})) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).scrollEndHaptic().padding(vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccountAvatar(snapshot, features, Modifier.size(48.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(snapshot.account?.displayName?.ifBlank { "我的账号" } ?: "我的账号", fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(connection.presentation().label, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            Text("表情状态 · 暂未开放", fontSize = 10.sp, color = colors.onSurfaceVariantSummary.copy(alpha = 0.55f),
                                modifier = Modifier.semantics { disabled() })
                            if (showPhone && snapshot.account?.phoneNumber?.isNotBlank() == true) {
                                Text(formatAccountPhone(snapshot.account.phoneNumber), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            }
                        }
                    }
                    AccountMenuRule()
                    AccountMenuRow("我的资料", MiuixIcons.ContactsCircle, snapshot.account != null,
                        unavailable = "暂不可用", onClick = onProfile)
                    AccountMenuRow("新建群组", MiuixIcons.Community)
                    AccountMenuRow("新建超级群组", MiuixIcons.Contacts)
                    AccountMenuRow("新建频道", MiuixIcons.Promotions)
                    AccountMenuRule()
                    AccountMenuRow("联系人", MiuixIcons.ContactsBook)
                    AccountMenuRow("通话", MiuixIcons.Phone)
                    AccountMenuRow("我的收藏", MiuixIcons.Favorites, onSavedMessages != null,
                        unavailable = "尚未载入", onClick = onSavedMessages ?: {})
                    AccountMenuRow("已归档聊天", MiuixIcons.FileDownloads, snapshot.enabled,
                        unavailable = "暂不可用", onClick = onArchive)
                    AccountMenuRow("管理文件夹", MiuixIcons.Folder, snapshot.enabled,
                        unavailable = "暂不可用", onClick = onManageFolders)
                    AccountMenuRow("设置", MiuixIcons.Settings, true, onClick = onSettings)
                    AccountMenuRule()
                    AccountMenuToggle("夜间模式", MiuixIcons.Theme, darkTheme, true, onDarkTheme)
                    AccountMenuToggle("显示电话号码", MiuixIcons.Show, showPhone,
                        snapshot.account?.phoneNumber?.isNotBlank() == true, onShowPhone,
                        unavailable = "暂不可用")
                    AccountMenuToggle("截屏模式", MiuixIcons.ScreenCapture, false, false, {})
                }
            }
        }
    }
}

@Composable
private fun AccountMenuRow(label: String, icon: ImageVector, enabled: Boolean = false,
    unavailable: String = "暂未开放", onClick: () -> Unit = {}) {
    val colors = MiuixTheme.colorScheme
    val color = if (enabled) colors.onSurface else colors.onSurfaceVariantSummary.copy(alpha = 0.55f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).chatSquircleSurface(Color.Transparent, 15.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (!enabled) disabled() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Text(label, fontSize = 14.sp, color = color, modifier = Modifier.weight(1f))
        if (!enabled) Text(unavailable, fontSize = 10.sp, color = color)
    }
}

@Composable
private fun AccountMenuToggle(label: String, icon: ImageVector, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit, unavailable: String = "暂未开放") {
    val colors = MiuixTheme.colorScheme
    val color = if (enabled) colors.onSurface else colors.onSurfaceVariantSummary.copy(alpha = 0.55f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).chatSquircleSurface(Color.Transparent, 15.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = color)
            if (!enabled) Text(unavailable, fontSize = 10.sp, color = color)
        }
        Switch(checked, null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun AccountMenuRule() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp).height(1.dp)
        .squircleBackground(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.1f), 0.dp))
}

@Composable
internal fun AccountProfileDialog(show: Boolean, snapshot: ChatSnapshot, features: ChatFeatureActions,
    showPhone: Boolean, onDismiss: () -> Unit) {
    WindowDialog(show, title = "我的资料", summary = "Telegram 账号资料", maxWidth = 420.dp, onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AccountProfileAppearanceHeader(snapshot, active = show && snapshot.enabled,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) { avatarModifier ->
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountAvatar(snapshot, features, Modifier.size(64.dp).then(avatarModifier))
                snapshot.account?.let { profile ->
                    ProfileField("名称", profile.displayName)
                    ProfileField("用户名", profile.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "未设置")
                    ProfileField("电话号码", if (showPhone) formatAccountPhone(profile.phoneNumber).ifBlank { "未提供" } else "已隐藏")
                    Text("可在设置的个人资料中修改。", fontSize = 12.sp,
                        color = LocalPeerProfileForeground.current ?: MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            }
            ChatButton("关闭", Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

/** Keeps the original naturally sized fields and circle, measuring their actual window anchor. */
@Composable
internal fun AccountProfileAppearanceHeader(snapshot: ChatSnapshot, active: Boolean, modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val appearance = snapshot.account?.appearance
    val profile = appearance?.profile
    val palette = profile?.let { resolvePeerProfilePalette(it.colorId, snapshot.appearanceCatalog, colors.surface.luminance() < .5f) }
    val density = LocalDensity.current.density
    var headerBounds by remember(snapshot.token.generation, snapshot.account?.userId) { mutableStateOf(Rect.Zero) }
    var avatarBounds by remember(snapshot.token.generation, snapshot.account?.userId) { mutableStateOf(Rect.Zero) }
    val avatar = Rect((avatarBounds.left-headerBounds.left)/density,(avatarBounds.top-headerBounds.top)/density,
        (avatarBounds.right-headerBounds.left)/density,(avatarBounds.bottom-headerBounds.top)/density)
    val foreground = palette?.backgroundColors?.firstOrNull()?.peerAccentColor()?.let {
        if (it.luminance() > .45f) Color.Black else Color.White
    }
    Box(modifier.chatSquircleClip(15.dp).onGloballyPositioned {
        headerBounds = Rect(it.positionInWindow(),Size(it.size.width.toFloat(),it.size.height.toFloat()))
    }) {
        PeerProfileBackground(palette?.backgroundColors, Modifier.matchParentSize())
        if (profile != null && profile.backgroundCustomEmojiId != 0L && avatar.width > 0 && avatar.height > 0) {
            val gray = if (colors.surface.luminance() < .5f) .5f else .3f
            CompositionLocalProvider(LocalProfileEmojiArtworkIdentity provides
                (snapshot.token.generation to (snapshot.account?.userId to appearance))) {
            PeerProfilePatternArtwork(profile.backgroundCustomEmojiId, tint = Color(gray,gray,gray,.6f), active = active,
                modifier = Modifier.matchParentSize(), avatarRectDp = avatar, overlay = palette != null)
            }
        }
        CompositionLocalProvider(LocalPeerProfileForeground provides foreground) {
            content(Modifier.onGloballyPositioned {
                avatarBounds = Rect(it.positionInWindow(),Size(it.size.width.toFloat(),it.size.height.toFloat()))
            })
        }
    }
}

private val LocalPeerProfileForeground = staticCompositionLocalOf<Color?> { null }

@Composable
internal fun PeerProfileBackground(background: List<Int>?, modifier: Modifier) {
    Canvas(modifier) {
        when (background?.size) {
            1 -> drawRect(background[0].peerAccentColor())
            2 -> drawRect(Brush.radialGradient(listOf(background[1].peerAccentColor(), background[0].peerAccentColor()),
                center = center, radius = maxOf(size.width,size.height)/2))
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = LocalPeerProfileForeground.current ?: MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 15.sp, color = LocalPeerProfileForeground.current ?: MiuixTheme.colorScheme.onSurface)
    }
}

private fun formatAccountPhone(phone: String) = if (phone.isBlank() || phone.startsWith('+')) phone else "+$phone"

private class AccountMenuShape(
    private val arrowCenter: Float,
    private val arrowHeight: Dp,
    private val squircleEnabled: Boolean,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val arrowHeight = this@AccountMenuShape.arrowHeight.toPx().coerceAtMost(size.height)
        val body = Path().apply {
            addSquircleRect(size.width, size.height - arrowHeight, 20.dp.toPx(), squircleEnabled = squircleEnabled)
            translate(Offset(0f, arrowHeight))
        }
        val arrow = Path().apply {
            moveTo(arrowCenter - 10.dp.toPx(), arrowHeight + 1f)
            lineTo(arrowCenter, 0f)
            lineTo(arrowCenter + 10.dp.toPx(), arrowHeight + 1f)
            close()
        }
        Outline.Generic(Path.combine(PathOperation.Union, body, arrow))
    }
}
