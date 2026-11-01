package com.badnng.moe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.settings.SettingsProfile
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsProfileHeader(profile: SettingsProfile, avatar: @Composable (Dp) -> Unit) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val active = LocalSettingsPageActive.current
    val latestActive by rememberUpdatedState(active)
    val latestUserId by rememberUpdatedState(profile.userId)
    var copyRevision by remember(profile.userId) { mutableStateOf(0) }
    var copyResult by remember(profile.userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(copyRevision) {
        if (copyRevision > 0) { delay(2000); copyResult = null }
    }
    val label = listOf(profile.firstName, profile.lastName).filter(String::isNotBlank).joinToString(" ")
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp)
        .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 18.dp).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        avatar(72.dp)
        Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (profile.userId > 0) "ID: ${profile.userId}" else "ID: 尚未读取",
            Modifier.chatSquircleSurface(Color.Transparent, 15.dp)
                .clickable(enabled = active && profile.userId > 0, role = Role.Button, onClickLabel = "复制账号 ID") {
                    if (latestActive && latestUserId == profile.userId && profile.userId > 0) {
                        copyResult = try { clipboard.setText(AnnotatedString(profile.userId.toString())); "已复制 ID" }
                        catch (_: Exception) { "复制失败，请重试" }
                        ++copyRevision
                    }
                }.semantics { contentDescription = "复制账号 ID" }.padding(horizontal = 12.dp, vertical = 5.dp),
            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        copyResult?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) }
    }
}

@Composable
internal fun SettingsProfileCards(details: @Composable () -> Unit, avatar: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 620.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { details() }
            Box(Modifier.weight(1f)) { avatar() }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            details()
            avatar()
        }
    }
}

@Composable
internal fun SettingsProfileDetailsCard(
    profile: SettingsProfile,
    enabled: Boolean,
    editor: (@Composable () -> Unit)? = null,
    onEdit: () -> Unit,
) {
    // Keep the single outgoing form until its fade/height transition finishes. The owner
    // gates callbacks as soon as editing closes, including a quick close/reopen reversal.
    var retainedEditor by remember(profile.userId) { mutableStateOf<(@Composable () -> Unit)?>(null) }
    SideEffect { if (editor != null) retainedEditor = editor }
    val editing = editor != null
    SettingsProfileCard("个人资料") {
        Box(Modifier.fillMaxWidth().heightIn(min = 194.dp).testTag("SettingsProfileDetailsCard")) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 50.dp, end = 22.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ProfileValue("手机号码", profile.phoneNumber.let { if (it.isNotBlank() && !it.startsWith('+')) "+$it" else it }
                    .ifBlank { "未提供" })
                AnimatedContent(
                    targetState = editing,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopStart,
                    transitionSpec = {
                        // Finish the outgoing labels before the form fades in, so text
                        // at different heights never ghosts across the input fields.
                        (fadeIn(tween(190, delayMillis = 50)) togetherWith
                            fadeOut(tween(50))).using(
                            SizeTransform { _, _ -> tween(240, easing = FastOutSlowInEasing) })
                    },
                    label = "Profile details editor",
                ) { showEditor ->
                    if (showEditor) {
                        Box(Modifier.fillMaxWidth().workspacePageInput(editing)) {
                            (editor ?: retainedEditor)?.invoke()
                        }
                    } else Column(Modifier.fillMaxWidth().testTag("SettingsProfileSummary"),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        ProfileValue("简介", profile.bio.ifBlank { "未设置" })
                        ProfileValue("用户名", profile.username.takeIf(String::isNotBlank)?.let { "@$it" } ?: "未设置")
                    }
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                ProfileCardIcon(MiuixIcons.Regular.Edit, "更改个人资料", enabled, onEdit)
            }
        }
    }
}

@Composable
private fun ProfileValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).testTag("SettingsProfileValue:$label"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text(value, Modifier.weight(.8f), fontSize = 13.sp,
            color = MiuixTheme.colorScheme.primary, textAlign = TextAlign.End)
    }
}

@Composable
internal fun SettingsProfileAvatarCard(
    avatar: @Composable (Dp) -> Unit, canView: Boolean, canEdit: Boolean,
    onView: () -> Unit, onPick: () -> Unit, onSticker: () -> Unit, onHistory: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val cardColor = colors.surfaceContainer
    // Only the avatar and card fill are recorded. The camera is a separate consumer.
    val backdrop = if (isRuntimeShaderSupported()) rememberLayerBackdrop {
        drawRect(cardColor); drawContent()
    } else null
    SettingsProfileCard("头像") {
        Box(Modifier.fillMaxWidth().heightIn(min = 194.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 194.dp).padding(22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(112.dp)) {
                    Box(Modifier.matchParentSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
                        Box(Modifier.size(100.dp).clip(CircleShape).clickable(enabled = canView, role = Role.Button,
                            onClickLabel = "查看当前头像", onClick = onView).semantics { contentDescription = "查看当前头像" }) {
                            avatar(100.dp)
                        }
                    }
                    CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                        ProfileCameraButton(canEdit, onPick, Modifier.align(Alignment.BottomEnd))
                    }
                }
                Column(Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.fillMaxWidth().chatSquircleSurface(Color.Transparent, 15.dp)
                        .clickable(enabled = canEdit, role = Role.Button, onClick = onSticker).padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("创建表情头像", fontSize = 14.sp)
                        Text("使用表情或贴纸", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    }
                    Text("头像历史", Modifier.chatSquircleSurface(Color.Transparent, 15.dp)
                        .clickable(enabled = canEdit, role = Role.Button, onClick = onHistory).padding(vertical = 10.dp),
                        fontSize = 13.sp, color = if (canEdit) colors.primary else colors.disabledOnSurface)
                }
            }
        }
    }
}

@Composable
private fun SettingsProfileCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(horizontal = 14.dp, vertical = 5.dp).semantics { heading() },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary)
        Box(Modifier.fillMaxWidth().chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 18.dp)) { content() }
    }
}

@Composable
private fun ProfileCardIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(23.dp), tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
    }
}

/** Hypernotesuper back-button base, with a camera foreground and an actual circular input mask. */
@Composable
private fun ProfileCameraButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 1.12f else 1f, label = "ProfileCameraScale")
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val highlightAlpha = if (dark) 0.18f else 1f
    Box(modifier.size(44.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .chatGlassSurface(22.dp, circular = true).drawWithCache {
            val outline = CircleShape.createOutline(size, layoutDirection, this)
            val brush = Brush.linearGradient(listOf(Color.White.copy(alpha = highlightAlpha),
                Color.White.copy(alpha = .2f * highlightAlpha), Color.White.copy(alpha = .7f * highlightAlpha)),
                Offset.Zero, Offset(size.width, size.height))
            onDrawWithContent {
                drawContent()
                drawOutline(outline, brush, alpha = .08f, style = Stroke(5.dp.toPx()))
                drawOutline(outline, brush, alpha = .14f, style = Stroke(3.dp.toPx()))
                drawOutline(outline, brush, alpha = .85f, style = Stroke(2.dp.toPx()))
            }
        }.clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled,
            role = Role.Button, onClickLabel = "选择图片或视频头像", onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(ProfileCameraIcon, "选择图片或视频头像", Modifier.size(27.dp),
            tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
    }
}

// The pinned Miuix icon set has no camera. Keep this small vector in the app's existing icon style.
private val ProfileCameraIcon = ImageVector.Builder("ProfileCamera", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 6f); horizontalLineTo(7f); lineTo(9f, 3f); horizontalLineTo(15f); lineTo(17f, 6f)
        horizontalLineTo(20f); curveTo(21.1f, 6f, 22f, 6.9f, 22f, 8f); verticalLineTo(19f)
        curveTo(22f, 20.1f, 21.1f, 21f, 20f, 21f); horizontalLineTo(4f)
        curveTo(2.9f, 21f, 2f, 20.1f, 2f, 19f); verticalLineTo(8f)
        curveTo(2f, 6.9f, 2.9f, 6f, 4f, 6f); close()
        moveTo(16f, 13f); curveTo(16f, 15.2f, 14.2f, 17f, 12f, 17f)
        curveTo(9.8f, 17f, 8f, 15.2f, 8f, 13f); curveTo(8f, 10.8f, 9.8f, 9f, 12f, 9f)
        curveTo(14.2f, 9f, 16f, 10.8f, 16f, 13f); close()
    }
}.build()
