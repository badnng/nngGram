package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.resolvePeerNamePalette
import com.badnng.moe.chat.resolvePeerProfilePalette
import com.badnng.moe.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Resource roles are explicit: a thumbnail never implicitly authorizes a full preview download. */
internal enum class SettingsProfileColorArtworkRole { OPTION_THUMBNAIL, NAME_PREVIEW, PROFILE_PREVIEW }

internal interface SettingsProfileColorPatternPreview {
    @Composable
    fun Content(emoji: SettingsBackgroundEmoji?, customEmojiId: Long, part: SettingsProfileColorPart,
        role: SettingsProfileColorArtworkRole, tint: Color, active: Boolean, modifier: Modifier,
        giftModelCustomEmojiId: Long = 0)
}

private object MetadataOnlyProfileColorPatternPreview : SettingsProfileColorPatternPreview {
    @Composable
    override fun Content(emoji: SettingsBackgroundEmoji?, customEmojiId: Long, part: SettingsProfileColorPart,
        role: SettingsProfileColorArtworkRole, tint: Color, active: Boolean, modifier: Modifier,
        giftModelCustomEmojiId: Long) {
        // Metadata is not artwork. C3 supplies the bounded, visible resource owner and real alpha mask.
        Box(modifier)
    }
}

internal val LocalSettingsProfileColorPatternPreview = staticCompositionLocalOf<SettingsProfileColorPatternPreview> {
    MetadataOnlyProfileColorPatternPreview
}

/** Content identity changes even when the user returns to a previously submitted pair. */
internal data class SettingsProfileColorUiDraft(
    val base: SettingsProfileColorValue,
    val value: SettingsProfileColorValue = base,
    val contentRevision: Long = 0,
    val dirty: Boolean = false,
    val replaceGiftIntent: Boolean = false,
    val submitted: SettingsProfileColorEdit? = null,
    val afterAttempt: Long = 0,
) {
    fun change(value: SettingsProfileColorValue, replaceGift: Boolean = false): SettingsProfileColorUiDraft {
        val intent = replaceGiftIntent || replaceGift
        return if (value == this.value && intent == replaceGiftIntent) this else copy(value = value,
            contentRevision = contentRevision + 1, dirty = value != base || intent, replaceGiftIntent = intent)
    }
}

internal class SettingsProfileColorsUiDrafts(values: SettingsProfileColorsValues) {
    var name by mutableStateOf(SettingsProfileColorUiDraft(values.name))
        private set
    var profile by mutableStateOf(SettingsProfileColorUiDraft(values.profile))
        private set

    fun get(part: SettingsProfileColorPart) = if (part == SettingsProfileColorPart.NAME) name else profile
    private fun put(part: SettingsProfileColorPart, value: SettingsProfileColorUiDraft) {
        if (part == SettingsProfileColorPart.NAME) name = value else profile = value
    }
    fun change(part: SettingsProfileColorPart, value: SettingsProfileColorValue, replaceGift: Boolean = false) =
        put(part, get(part).change(value, replaceGift && part == SettingsProfileColorPart.NAME))
    fun adopt(part: SettingsProfileColorPart, value: SettingsProfileColorValue) {
        val old = get(part)
        put(part, SettingsProfileColorUiDraft(value, contentRevision = old.contentRevision + 1))
    }
    fun submit(part: SettingsProfileColorPart, replaceGift: Boolean, afterAttempt: Long): SettingsProfileColorEdit {
        val draft = get(part)
        val edit = SettingsProfileColorEdit(part, draft.base, draft.value, draft.contentRevision, replaceGift)
        put(part, draft.copy(submitted = edit, afterAttempt = afterAttempt))
        return edit
    }
    fun reconcile(snapshot: SettingsProfileColorsSnapshot) {
        val data = snapshot.data ?: return
        SettingsProfileColorPart.entries.forEach { part ->
            val draft = get(part)
            val actual = data.values.value(part)
            val submitted = draft.submitted
            val outcome = snapshot.outcomes[part]?.takeIf {
                submitted != null && it.attempt.id > draft.afterAttempt && it.attempt.edit == submitted
            }
            when {
                outcome != null && !outcome.locallyAccepted -> put(part, draft.copy(submitted = null))
                outcome != null && part !in snapshot.readbackPending -> {
                    // Acceptance can advance the base, but cannot erase an edit made after submission.
                    if (submitted != null && outcome.error == null && actual == submitted.desired) {
                        val sameRevision = draft.contentRevision == submitted.contentRevision && draft.value == submitted.desired
                        put(part, draft.copy(base = actual, value = if (sameRevision) actual else draft.value,
                            dirty = !sameRevision, replaceGiftIntent = if (sameRevision) false else draft.replaceGiftIntent,
                            submitted = null))
                    } else put(part, draft.copy(submitted = null))
                }
                submitted == null && !draft.dirty && draft.base != actual ->
                    put(part, draft.copy(base = actual, value = actual))
            }
        }
    }
}

private class ProfileColorsPageLease(
    private val generation: Long, private val userId: Long, private val initial: SettingsProfileColorsToken,
    active: Boolean,
) {
    private val alive = MutableStateFlow(active)
    private val owned = MutableStateFlow<SettingsProfileColorsToken?>(null)
    fun active() = alive.value
    fun observe(snapshot: SettingsProfileColorsSnapshot): SettingsProfileColorsToken? {
        if (!active() || !snapshot.opened || snapshot.token.generation != generation ||
            snapshot.token.userId != userId || snapshot.token.owner <= 0) return owned.value
        if (owned.value == null && snapshot.token != initial &&
            (initial.generation != generation || snapshot.token.owner > initial.owner)) owned.value = snapshot.token
        return owned.value
    }
    fun close(): SettingsProfileColorsToken? = if (alive.compareAndSet(true, false)) owned.value else null
}

private data class ProfileColorGiftConfirmation(
    val token: SettingsProfileColorsToken, val data: SettingsProfileColorsData, val edit: SettingsProfileColorEdit,
)

/** This editor owns one native page, two drafts and no parent profile-field save state. */
@Composable
internal fun SettingsProfileColorsControls(
    accountToken: SettingsToken, profile: SettingsProfile?, snapshot: SettingsProfileColorsSnapshot,
    actions: SettingsProfileColorsActions, active: Boolean = LocalSettingsPageActive.current,
) {
    val pageActive = active && LocalSettingsPageActive.current
    val userId = profile?.userId ?: 0L
    var editing by remember(accountToken, userId) { mutableStateOf(false) }
    var part by remember(accountToken, userId) { mutableStateOf(SettingsProfileColorPart.NAME) }
    val lease = remember(accountToken, userId, pageActive, editing) {
        ProfileColorsPageLease(accountToken.generation, userId, snapshot.token, pageActive && editing && userId > 0)
    }
    val latestActions by rememberUpdatedState(actions)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestActive by rememberUpdatedState(pageActive)
    var owned by remember(lease) { mutableStateOf<SettingsProfileColorsToken?>(null) }
    SideEffect { lease.observe(snapshot)?.let { if (owned != it) owned = it } }
    DisposableEffect(lease) {
        if (lease.active()) latestActions.open(accountToken, userId, lease::active)
        onDispose { lease.close()?.let { latestActions.close(it) } }
    }
    LaunchedEffect(pageActive) { if (!pageActive) editing = false }

    val matches = pageActive && editing && lease.active() && owned == snapshot.token && snapshot.opened
    val data = snapshot.data?.takeIf { matches && it.userId == userId }
    val drafts = remember(owned, data != null) { data?.let { SettingsProfileColorsUiDrafts(it.values) } }
    SideEffect { if (matches) drafts?.reconcile(snapshot) }
    var confirmation by remember(lease) { mutableStateOf<ProfileColorGiftConfirmation?>(null) }

    fun current(): SettingsProfileColorsSnapshot? = latestSnapshot.takeIf {
        latestActive && lease.active() && owned == it.token && it.opened && it.token.userId == userId &&
            it.token.generation == accountToken.generation
    }
    fun change(selected: SettingsProfileColorPart, value: SettingsProfileColorValue) {
        val fresh = current() ?: return
        val freshData = fresh.data ?: return
        if (part != selected || !freshData.premium) return
        drafts?.change(selected, value, replaceGift = selected == SettingsProfileColorPart.NAME && freshData.values.gift != null)
    }
    fun canSubmit(current: SettingsProfileColorsSnapshot, selected: SettingsProfileColorPart): Boolean {
        val actual = current.data ?: return false
        val draft = drafts?.get(selected) ?: return false
        val catalogError = if (selected == SettingsProfileColorPart.NAME) actual.catalog.nameError else actual.catalog.profileError
        val available = if (selected == SettingsProfileColorPart.NAME) actual.catalog.availableNameColorIds else actual.catalog.availableProfileColorIds
        val reset = if (selected == SettingsProfileColorPart.NAME) SettingsProfileColorValue((actual.userId % 7).toInt())
            else SettingsProfileColorValue(-1)
        val colorValid = draft.value.colorId == draft.base.colorId || draft.value == reset || draft.value.colorId in available.orEmpty()
        val emojiValid = draft.value.backgroundCustomEmojiId == 0L || draft.value.backgroundCustomEmojiId == draft.base.backgroundCustomEmojiId ||
            current.backgrounds.any { it.customEmojiId == draft.value.backgroundCustomEmojiId }
        return actual.premium && !current.busy && !current.loading && !current.refreshRequired &&
            current.readbackPending.isEmpty() && current.activeSave == null && draft.submitted == null &&
            catalogError == null && colorValid && emojiValid && draft.base == actual.values.value(selected) && draft.dirty
    }
    fun send(selected: SettingsProfileColorPart, replaceGift: Boolean) {
        val fresh = current() ?: return
        if (!canSubmit(fresh, selected)) return
        val edit = drafts?.submit(selected, replaceGift, fresh.outcomes[selected]?.attempt?.id ?: 0) ?: return
        latestActions.save(fresh.token, edit)
    }

    val selectedPart = part
    SettingsGroup("资料颜色") {
        if (!editing) {
            SettingsRow("名字与资料颜色", "选择", summary = "分别设置账号名字颜色、资料背景和背景图案。",
                enabled = pageActive && userId > 0) { editing = true }
        } else {
            TabRowWithContour(tabs = listOf("名字颜色", "资料背景"), selectedTabIndex = selectedPart.ordinal,
                onTabSelected = { if (lease.active()) part = SettingsProfileColorPart.entries[it] },
                modifier = Modifier.padding(horizontal = 12.dp), minWidth = 112.dp, maxWidth = 180.dp)
            if (data == null || drafts == null) {
                SettingsNote(if (matches && snapshot.loading) "正在读取资料颜色…" else "资料颜色尚未读取。")
            } else {
                val draft = drafts.get(selectedPart)
                val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
                val editable = matches && data.premium
                ProfileColorPreview(profile, data, draft, selectedPart, dark, snapshot.backgrounds,
                    active = matches && !snapshot.loading)
                if (!data.premium) SettingsNote("修改资料颜色需要当前账号具备 Telegram Premium。此处只读，不提供购买流程。")
                val remote = data.values.value(selectedPart)
                if (draft.dirty && remote != draft.base) SettingsNote("此项已在其他位置更改，草稿已保留。请重新读取并确认最新值。", error = true)
                ProfileColorChoices(data, draft, selectedPart, dark, enabled = editable,
                    onChange = { colorId ->
                        val fresh = current()
                        if (part == selectedPart && fresh?.data?.premium == true) {
                            change(selectedPart, drafts.get(selectedPart).value.copy(colorId = colorId))
                        }
                    })
                val reset = if (selectedPart == SettingsProfileColorPart.NAME) SettingsProfileColorValue((userId % 7).toInt())
                    else SettingsProfileColorValue(-1)
                SettingsAction("恢复${if (selectedPart == SettingsProfileColorPart.NAME) "名字" else "资料"}默认颜色", enabled = editable) {
                    if (current()?.data?.premium == true) change(selectedPart, reset)
                }
                val giftPattern = selectedPart == SettingsProfileColorPart.NAME && !draft.replaceGiftIntent &&
                    data.values.gift?.symbolCustomEmojiId?.let { it != 0L } == true
                SettingsRow("背景图案", if (giftPattern) "礼物图案" else if (draft.value.backgroundCustomEmojiId == 0L) "无" else "已选择原生图案",
                    enabled = editable && !snapshot.backgroundsLoading) {
                    current()?.takeIf { it.data?.premium == true && !it.backgroundsLoading }?.let { latestActions.openBackgrounds(it.token) }
                }
                if (draft.value.backgroundCustomEmojiId != 0L || giftPattern) {
                    SettingsAction("移除背景图案", enabled = editable) {
                        if (part == selectedPart && current()?.data?.premium == true) {
                            change(selectedPart, drafts.get(selectedPart).value.copy(backgroundCustomEmojiId = 0))
                        }
                    }
                }
                if (snapshot.backgroundsOpened) {
                    ProfileColorBackgroundChoices(snapshot, selectedPart, draft, enabled = editable,
                        active = matches, onSelected = { id ->
                            val fresh = current()
                            if (part == selectedPart && fresh?.data?.premium == true && fresh.backgroundsOpened &&
                                fresh.backgrounds.any { it.customEmojiId == id }) {
                                change(selectedPart, drafts.get(selectedPart).value.copy(backgroundCustomEmojiId = id))
                            }
                        })
                    if (draft.value.backgroundCustomEmojiId != 0L && snapshot.backgrounds.none { it.customEmojiId == draft.value.backgroundCustomEmojiId }) {
                        SettingsAction("读取当前图案", enabled = editable && !snapshot.backgroundsLoading) {
                            val fresh = current() ?: return@SettingsAction
                            val id = drafts.get(selectedPart).value.backgroundCustomEmojiId
                            val values = fresh.data?.values ?: return@SettingsAction
                            if (!fresh.backgroundsLoading && id != 0L && id in listOf(values.name.backgroundCustomEmojiId,
                                    values.profile.backgroundCustomEmojiId, values.gift?.symbolCustomEmojiId, values.gift?.modelCustomEmojiId)) {
                                latestActions.resolveBackgrounds(fresh.token, listOf(id))
                            }
                        }
                    }
                    SettingsAction("收起图案") { current()?.let { latestActions.closeBackgrounds(it.token) } }
                }
                if (data.values.gift != null && selectedPart == SettingsProfileColorPart.NAME) {
                    SettingsNote("当前名字使用礼物外观。保存名字颜色将替换该礼物外观，需要再次确认。")
                }
                if (snapshot.readbackPending.isNotEmpty()) SettingsNote("修改已接受，最新值尚未读回。请重新读取，勿重复保存。")
                if (snapshot.refreshRequired) SettingsNote("当前值需要重新核实；草稿仍保留。", error = true)
                snapshot.error?.let { SettingsNote(settingsErrorText(it), error = true) }
                if (snapshot.busy || draft.submitted != null && snapshot.readbackPending.isEmpty()) SettingsNote("正在提交颜色更改…")
                SettingsAction("重新读取资料颜色", enabled = matches && !snapshot.busy && !snapshot.loading) {
                    current()?.takeIf { !it.busy && !it.loading }?.let { latestActions.refresh(it.token) }
                }
                if (draft.dirty || draft.base != remote) SettingsAction("采用最新读取值", enabled = matches && !snapshot.busy &&
                    !snapshot.loading && snapshot.readbackPending.isEmpty()) {
                    current()?.takeIf { !it.busy && !it.loading && it.readbackPending.isEmpty() }?.data?.let {
                        drafts.adopt(selectedPart, it.values.value(selectedPart))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton("取消颜色编辑", Modifier.weight(1f), onClick = { confirmation = null; editing = false })
                    ChatButton("保存${if (selectedPart == SettingsProfileColorPart.NAME) "名字颜色" else "资料背景"}", Modifier.weight(1f),
                        enabled = canSubmit(snapshot, selectedPart), primary = true, onClick = {
                            val fresh = current() ?: return@ChatButton
                            if (!canSubmit(fresh, selectedPart)) return@ChatButton
                            val freshData = fresh.data ?: return@ChatButton
                            if (selectedPart == SettingsProfileColorPart.NAME && freshData.values.gift != null) {
                                val selected = drafts.get(selectedPart)
                                confirmation = ProfileColorGiftConfirmation(fresh.token, freshData,
                                    SettingsProfileColorEdit(selectedPart, selected.base, selected.value, selected.contentRevision, true))
                            } else send(selectedPart, false)
                        })
                }
            }
            if (data == null) {
                if (matches) snapshot.error?.let { SettingsNote(settingsErrorText(it), error = true) }
                SettingsAction("重新读取资料颜色", enabled = matches && !snapshot.loading && !snapshot.busy) {
                    current()?.takeIf { !it.loading && !it.busy }?.let { latestActions.refresh(it.token) }
                }
                SettingsAction("取消颜色编辑") { editing = false }
            }
        }
    }

    val frozen = confirmation
    SecurityConfirmation(show = matches && frozen != null, title = "替换礼物名字外观？", maxWidth = 440.dp,
        onDismissRequest = { confirmation = null }) {
        SettingsNote("将以已确认的名字颜色和背景图案替换当前礼物外观。资料背景不受影响。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatButton("保留礼物", Modifier.weight(1f), onClick = { confirmation = null })
            ChatButton("确认替换礼物", Modifier.weight(1f), primary = true, onClick = {
                val fresh = current()
                val draft = drafts?.get(SettingsProfileColorPart.NAME)
                if (frozen != null && fresh != null && draft != null && fresh.token == frozen.token && fresh.data == frozen.data &&
                    draft.base == frozen.edit.base && draft.value == frozen.edit.desired &&
                    draft.contentRevision == frozen.edit.contentRevision && canSubmit(fresh, SettingsProfileColorPart.NAME)) {
                    send(SettingsProfileColorPart.NAME, true)
                }
                confirmation = null
            })
        }
    }
}

@Composable
private fun ProfileColorChoices(data: SettingsProfileColorsData, draft: SettingsProfileColorUiDraft,
    part: SettingsProfileColorPart, dark: Boolean, enabled: Boolean, onChange: (Int) -> Unit) {
    val catalog = data.catalog
    val ids = if (part == SettingsProfileColorPart.NAME) catalog.availableNameColorIds else catalog.availableProfileColorIds
    val error = if (part == SettingsProfileColorPart.NAME) catalog.nameError else catalog.profileError
    when {
        error != null -> SettingsNote("颜色目录无效，请重新读取后选择。", error = true)
        ids == null -> SettingsNote("颜色目录尚未收到；当前颜色可保留，暂不能选择新颜色。")
        ids.isEmpty() -> SettingsNote("原生目录当前没有可选颜色。")
    }
    if (draft.value.colorId !in ids.orEmpty() && draft.value.colorId != -1) SettingsNote("当前颜色未列入可选目录；可保留已有值。")
    if (ids != null && error == null && ids.isNotEmpty()) {
        LazyVerticalGrid(GridCells.Adaptive(58.dp), Modifier.fillMaxWidth().heightIn(min = 68.dp, max = 150.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ids, key = { it }) { id ->
                val colors = if (part == SettingsProfileColorPart.NAME) resolvePeerNamePalette(SettingsProfileColorValue(id), catalog, dark)?.let {
                    it.replyColors.ifEmpty { listOf(it.nameRgb) }
                }
                    else resolvePeerProfilePalette(id, catalog, dark)?.paletteColors
                Column(Modifier.height(64.dp).chatSquircleSurface(Color.Transparent, 15.dp)
                    .selectable(draft.value.colorId == id, enabled && colors != null, role = Role.RadioButton,
                        onClick = { onChange(id) }).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Canvas(Modifier.size(32.dp)) {
                        colors?.forEachIndexed { index, rgb ->
                            drawArc(rgb.profileColor(), index * 360f / colors.size - 90, 360f / colors.size,
                                useCenter = true)
                        }
                    }
                    Text("颜色 $id", fontSize = 10.sp, color = if (draft.value.colorId == id) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ProfileColorBackgroundChoices(snapshot: SettingsProfileColorsSnapshot, part: SettingsProfileColorPart,
    draft: SettingsProfileColorUiDraft, enabled: Boolean, active: Boolean, onSelected: (Long) -> Unit) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val optionTint = snapshot.data?.let { data ->
        if (part == SettingsProfileColorPart.PROFILE)
            resolvePeerProfilePalette(draft.value.colorId, data.catalog, dark)?.paletteColors?.firstOrNull()
        else resolvePeerNamePalette(draft.value, data.catalog, dark,
            data.values.gift.takeUnless { draft.replaceGiftIntent })?.nameRgb
    }?.profileColor() ?: MiuixTheme.colorScheme.onSurface
    if (snapshot.backgroundsLoading) SettingsNote("正在读取原生图案目录…")
    snapshot.backgroundsError?.let { SettingsNote(settingsErrorText(it), error = true) }
    if (!snapshot.backgroundsLoading && snapshot.backgrounds.isEmpty()) SettingsNote("原生图案目录暂无内容。")
    if (snapshot.backgrounds.isNotEmpty()) LazyVerticalGrid(GridCells.Adaptive(64.dp),
        Modifier.fillMaxWidth().heightIn(min = 76.dp, max = 168.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(snapshot.backgrounds, key = { it.customEmojiId }) { emoji ->
            Column(Modifier.height(70.dp).chatSquircleSurface(Color.Transparent, 15.dp)
                .selectable(draft.value.backgroundCustomEmojiId == emoji.customEmojiId, enabled, role = Role.RadioButton,
                    onClick = { onSelected(emoji.customEmojiId) }).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                LocalSettingsProfileColorPatternPreview.current.Content(emoji, emoji.customEmojiId, part,
                    SettingsProfileColorArtworkRole.OPTION_THUMBNAIL, optionTint, active, Modifier.size(36.dp),
                    giftModelCustomEmojiId = 0)
                }
                Text("图案 ${emoji.customEmojiId}", fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ProfileColorPreview(profile: SettingsProfile?, data: SettingsProfileColorsData, draft: SettingsProfileColorUiDraft,
    part: SettingsProfileColorPart, dark: Boolean, backgrounds: List<SettingsBackgroundEmoji>, active: Boolean) {
    val colors = MiuixTheme.colorScheme
    val label = listOfNotNull(profile?.firstName, profile?.lastName).filter(String::isNotBlank).joinToString(" ").ifBlank { "我的资料" }
    val gift = data.values.gift.takeIf { part == SettingsProfileColorPart.NAME && !draft.replaceGiftIntent }
    val nameValue = if (part == SettingsProfileColorPart.NAME) draft.value else data.values.name
    val namePalette = resolvePeerNamePalette(nameValue, data.catalog, dark, if (part == SettingsProfileColorPart.NAME) gift else data.values.gift)
    val profilePalette = if (part == SettingsProfileColorPart.PROFILE) resolvePeerProfilePalette(draft.value.colorId, data.catalog, dark) else null
    val emojiId = if (part == SettingsProfileColorPart.NAME) namePalette?.backgroundCustomEmojiId ?: draft.value.backgroundCustomEmojiId
        else draft.value.backgroundCustomEmojiId
    val emoji = backgrounds.firstOrNull { it.customEmojiId == emojiId }
    val bg = profilePalette?.backgroundColors
    val brush = if (bg != null && bg.size == 2) Brush.radialGradient(listOf(bg[1].profileColor(), bg[0].profileColor()))
        else null
    val surface = if (part == SettingsProfileColorPart.NAME) namePalette?.nameRgb?.profileColor()?.copy(alpha = .12f)
        ?: colors.surfaceContainer else bg?.firstOrNull()?.profileColor() ?: colors.surfaceContainer
    val height = if (part == SettingsProfileColorPart.NAME) 108.dp else 180.dp
    val foreground = if (part == SettingsProfileColorPart.PROFILE && bg != null) {
        if (bg.first().profileColor().luminance() > .45f) Color.Black else Color.White
    } else colors.onSurface
    Box(Modifier.fillMaxWidth().padding(12.dp).height(height).chatSquircleBackground(surface, 15.dp)
        .chatSquircleClip(15.dp).then(if (brush != null) Modifier.background(brush) else Modifier)
        .semantics { contentDescription = if (part == SettingsProfileColorPart.NAME) "名字颜色预览" else "资料背景预览" }) {
        if (emojiId != 0L || part == SettingsProfileColorPart.NAME && draft.replaceGiftIntent) CompositionLocalProvider(
            LocalPeerProfilePatternOverlay provides (profilePalette != null),
            LocalProfileEmojiArtworkIdentity provides (data.nativeRevision to (draft.contentRevision to part))) {
            LocalSettingsProfileColorPatternPreview.current.Content(emoji, emojiId, part,
            if (part == SettingsProfileColorPart.NAME) SettingsProfileColorArtworkRole.NAME_PREVIEW else SettingsProfileColorArtworkRole.PROFILE_PREVIEW,
            if (part == SettingsProfileColorPart.NAME) namePalette?.nameRgb?.profileColor() ?: colors.onSurface
                else if (dark) Color(.5f,.5f,.5f,.6f) else Color(.3f,.3f,.3f,.6f),
            active, Modifier.matchParentSize(),
            giftModelCustomEmojiId = if (part == SettingsProfileColorPart.NAME && namePalette != null) gift?.modelCustomEmojiId ?: 0 else 0)
        }
        if (part == SettingsProfileColorPart.NAME) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = namePalette?.nameRgb?.profileColor() ?: colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("名字与回复颜色预览", color = foreground, fontSize = 13.sp)
            if (namePalette == null) Text("当前颜色未知", fontSize = 11.sp, color = foreground)
        } else {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 32.dp).size(64.dp).clip(CircleShape)
                .background(colors.surfaceContainer), contentAlignment = Alignment.Center) {
                Text(label.take(1), fontSize = 23.sp, color = namePalette?.nameRgb?.profileColor() ?: colors.onSurface)
            }
            Column(Modifier.align(Alignment.TopCenter).padding(top = 112.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold, color = foreground, fontSize = 15.sp)
                Text(if (draft.value.colorId == -1) "默认资料背景" else if (profilePalette == null) "当前资料颜色未知" else "资料背景预览",
                    color = foreground, fontSize = 12.sp)
            }
        }
    }
}

private fun Int.profileColor() = Color(0xff000000L or toLong())
