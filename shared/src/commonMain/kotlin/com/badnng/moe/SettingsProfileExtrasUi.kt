package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.badnng.moe.settings.*
import top.yukonga.miuix.kmp.basic.TextField

/** Only the platform picker and preparation are platform-specific; every native result stays in the snapshot. */
@Composable
internal expect fun SettingsProfilePhotoPicker(
    show: Boolean, busy: Boolean, accepted: Boolean, error: SettingsError?, onDismiss: () -> Unit,
    onUpload: (SettingsPreparedProfilePhoto) -> Unit,
    onVideo: ((String) -> Unit)? = null,
)

internal interface SettingsProfileExtrasPageLease { fun active(): Boolean; fun close() }
internal expect fun settingsProfileExtrasPageLease(): SettingsProfileExtrasPageLease

@Composable
internal fun SettingsProfileExtrasContent(
    parentToken: SettingsToken, userId: Long, extras: SettingsProfileExtrasSnapshot, actions: SettingsProfileExtrasActions,
    enabled: Boolean, readEnabled: Boolean = enabled,
    overview: (@Composable (@Composable () -> Unit) -> Unit)? = null,
    avatar: @Composable (Dp) -> Unit = {},
) {
    val pageActive = LocalSettingsPageActive.current
    val latestActions by rememberUpdatedState(actions)
    val latestToken by rememberUpdatedState(extras.token)
    var pageLease by remember(parentToken.generation, userId) { mutableStateOf<SettingsProfileExtrasPageLease?>(null) }
    val latestLease by rememberUpdatedState(pageLease)
    LaunchedEffect(parentToken, userId, pageActive) {
        pageLease?.close()
        if (pageActive) {
            val requested = settingsProfileExtrasPageLease()
            pageLease = requested
            actions.open(parentToken, userId, requested::active)
        } else actions.close(extras.token)
    }
    DisposableEffect(parentToken.generation, userId) {
        onDispose { latestLease?.close(); latestActions.close(latestToken) }
    }
    val current = extras.token.generation == parentToken.generation && extras.token.userId == userId
    val data = extras.data.takeIf { current }
    val canEdit = enabled && pageActive && current && data != null && !extras.loading && !extras.busy
    val historyUi = remember(parentToken.generation, userId) { SettingsProfilePhotoHistoryUiState() }
    val latestHistoryExtras by rememberUpdatedState(extras)
    val latestHistoryEnabled by rememberUpdatedState(enabled && pageActive)
    DisposableEffect(historyUi) {
        onDispose { historyUi.session?.let { historyUi.close(it, latestActions.history) } }
    }
    LaunchedEffect(pageActive, extras.token) {
        historyUi.session?.takeIf { !pageActive || it.parent != extras.token }?.let { historyUi.close(it, actions.history) }
    }
    var selectingPhoto by remember(parentToken.generation, userId) { mutableStateOf(false) }
    var videoOpening by remember(parentToken.generation, userId) { mutableStateOf<SettingsProfileVideoOpening?>(null) }
    val latestVideoOpening by rememberUpdatedState(videoOpening)
    val latestVideoSnapshot by rememberUpdatedState(extras)
    val latestVideoEnabled by rememberUpdatedState(enabled && pageActive)
    DisposableEffect(parentToken.generation, userId) { onDispose { latestVideoOpening?.close() } }
    LaunchedEffect(pageActive, extras.token, historyUi.session) {
        videoOpening?.takeIf { !pageActive || it.parent != extras.token || historyUi.session != null }?.let { it.close(); videoOpening = null }
    }
    val stickerScope = rememberCoroutineScope()
    var stickerOpening by remember(parentToken.generation, userId) { mutableStateOf<SettingsProfileStickerAvatarOpening?>(null) }
    val latestStickerOpening by rememberUpdatedState(stickerOpening)
    DisposableEffect(parentToken.generation, userId) { onDispose { latestStickerOpening?.close() } }
    LaunchedEffect(pageActive, extras.token, historyUi.session, selectingPhoto, videoOpening) {
        stickerOpening?.takeIf { !pageActive || it.parent != extras.token || historyUi.session != null || selectingPhoto || videoOpening != null }
            ?.let { it.close(); stickerOpening = null }
    }
    val currentPhotoUi = remember(parentToken.generation, userId) { SettingsCurrentProfilePhotoUiState() }
    val latestCurrentEnabled by rememberUpdatedState(readEnabled && pageActive)
    DisposableEffect(currentPhotoUi) {
        onDispose { currentPhotoUi.opening?.let { currentPhotoUi.close(it, latestActions.currentPhoto) } }
    }
    LaunchedEffect(pageActive, extras.token, historyUi.session, selectingPhoto, videoOpening, stickerOpening) {
        currentPhotoUi.opening?.takeIf { !pageActive || it.parent != extras.token || historyUi.session != null ||
            selectingPhoto || videoOpening != null || stickerOpening != null }
            ?.let { currentPhotoUi.close(it, actions.currentPhoto) }
    }
    var photoExpected by remember(parentToken.generation, userId) { mutableStateOf<Long?>(null) }
    var photoParent by remember(parentToken.generation, userId) { mutableStateOf<SettingsProfileExtrasToken?>(null) }
    var photoSubmittedAfter by remember(parentToken.generation, userId) { mutableStateOf<Long?>(null) }
    var editingBirthday by rememberSaveable(parentToken.generation, userId) { mutableStateOf(false) }
    var birthdateExpectedKey by rememberSaveable(parentToken.generation, userId) { mutableStateOf("") }
    var day by rememberSaveable(parentToken.generation, userId) { mutableStateOf("") }
    var month by rememberSaveable(parentToken.generation, userId) { mutableStateOf("") }
    var year by rememberSaveable(parentToken.generation, userId) { mutableStateOf("") }
    var birthdaySubmittedAfter by rememberSaveable(parentToken.generation, userId) { mutableStateOf<Long?>(null) }
    var submittedBirthdayKey by rememberSaveable(parentToken.generation, userId) { mutableStateOf("") }
    var removeBirthday by remember(parentToken.generation, userId) { mutableStateOf(false) }
    val draftKey = "$day|$month|$year"
    val expected = decodeBirthdate(birthdateExpectedKey)
    val desired = day.toIntOrNull()?.let { d -> month.toIntOrNull()?.let { m ->
        (if (year.isBlank()) 0 else year.toIntOrNull())?.let { y -> SettingsBirthdate(d, m, y) }
    } }
    val valid = data != null && desired?.isValidProfileBirthdate(data.today, expected) == true
    val photoAccepted = photoSubmittedAfter?.let { current && extras.acceptedRevision > it &&
        extras.operation == SettingsProfileExtrasOperation.UPLOAD_PHOTO } == true

    LaunchedEffect(extras.acceptedRevision, extras.failedRevision, current) {
        val submitted = birthdaySubmittedAfter
        if (current && submitted != null && extras.acceptedRevision > submitted &&
            extras.operation == SettingsProfileExtrasOperation.BIRTHDATE) {
            if (draftKey == submittedBirthdayKey) editingBirthday = false
            removeBirthday = false; birthdaySubmittedAfter = null
        } else if (current && submitted != null && extras.failedRevision > submitted) birthdaySubmittedAfter = null
        if (photoAccepted) { selectingPhoto = false; photoSubmittedAfter = null }
    }
    LaunchedEffect(pageActive, extras.token) {
        if (!pageActive || selectingPhoto && photoParent != extras.token) selectingPhoto = false
        if (!pageActive) removeBirthday = false
    }

    if (historyUi.session != null) {
        SettingsProfilePhotoHistoryUi(historyUi, extras, actions.history, enabled)
        return
    }

    val openCurrent: () -> Unit = {
        val latest = latestVideoSnapshot
        val parentLease = latestLease
        if (parentLease != null && parentLease.active() && currentPhotoUi.opening == null && stickerOpening == null &&
            videoOpening == null && historyUi.session == null && !selectingPhoto && latestCurrentEnabled && latest.token == extras.token) {
            currentPhotoUi.open(latest.token, parentLease, latestActions.currentPhoto, stickerScope)
        }
    }
    val entryObserver = LocalCurrentProfilePhotoEntryProbe.current
    SideEffect { entryObserver?.invoke(openCurrent) }
    val choosePhoto: () -> Unit = {
        val latest = latestVideoSnapshot
        if (currentPhotoUi.opening == null && stickerOpening == null && videoOpening == null && historyUi.session == null && !selectingPhoto &&
            latestVideoEnabled && latest.token == extras.token && latest.data != null && !latest.loading && !latest.busy) {
            photoExpected = latest.data.photoId; photoParent = latest.token; photoSubmittedAfter = null; selectingPhoto = true
        }
    }
    val chooseSticker: () -> Unit = {
        val latest = latestVideoSnapshot
        if (currentPhotoUi.opening == null && stickerOpening == null && !selectingPhoto && videoOpening == null && historyUi.session == null &&
            latestVideoEnabled && latest.token == extras.token && latest.data != null && !latest.loading && !latest.busy) {
            stickerOpening = SettingsProfileStickerAvatarOpening(latest.token, latest.data.photoId, latestActions.stickerAvatar, stickerScope)
        }
    }
    val viewHistory: () -> Unit = {
        val latest = latestHistoryExtras
        if (currentPhotoUi.opening == null && stickerOpening == null && videoOpening == null && !selectingPhoto && historyUi.session == null &&
            latestHistoryEnabled && latest.token == extras.token && latest.data != null && !latest.loading && !latest.busy)
            historyUi.open(latest, latestActions.history)
    }
    val avatarCard: @Composable () -> Unit = {
        SettingsProfileAvatarCard(avatar,
            canView = readEnabled && pageActive && current && latestLease?.active() == true && currentPhotoUi.opening == null &&
                stickerOpening == null && videoOpening == null && historyUi.session == null && !selectingPhoto,
            canEdit = canEdit && currentPhotoUi.opening == null && stickerOpening == null && videoOpening == null && !selectingPhoto,
            onView = openCurrent, onPick = choosePhoto, onSticker = chooseSticker, onHistory = viewHistory)
    }
    if (overview == null) avatarCard() else overview(avatarCard)
    SettingsGroup("生日") {
        SettingsRow("生日", data?.birthdate?.profileDateLabel() ?: if (data == null) "尚未读取" else "未设置",
            enabled = canEdit && !editingBirthday) {
            birthdateExpectedKey = encodeBirthdate(data?.birthdate)
            day = data?.birthdate?.day?.toString().orEmpty()
            month = data?.birthdate?.month?.toString().orEmpty()
            year = data?.birthdate?.year?.takeIf { it != 0 }?.toString().orEmpty()
            birthdaySubmittedAfter = null; editingBirthday = true
        }
        if (editingBirthday) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(day, { day = it }, label = "日", singleLine = true, enabled = canEdit,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            TextField(month, { month = it }, label = "月", singleLine = true, enabled = canEdit,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            TextField(year, { year = it }, label = "出生年份（可选）", singleLine = true, enabled = canEdit,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            SettingsNote("留空出生年份可隐藏年龄。生日可见范围在隐私设置中调整。")
            if (!valid) SettingsNote("请输入有效的日、月和出生年份；出生日期不能晚于今天。", error = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), enabled = !extras.busy, onClick = { editingBirthday = false; birthdaySubmittedAfter = null })
                ChatButton("保存生日", Modifier.weight(1f), enabled = canEdit && valid && desired != expected, primary = true,
                    onClick = {
                        birthdaySubmittedAfter = extras.startedRevision; submittedBirthdayKey = draftKey
                        actions.saveBirthdate(extras.token, expected, desired)
                    })
            }
        }
        if (data?.birthdate != null) SettingsRow("移除生日", enabled = canEdit) {
            birthdateExpectedKey = encodeBirthdate(data.birthdate); removeBirthday = true
        }
    }
    if (extras.loading && current) SettingsNote("正在读取头像和生日……")
    if (extras.busy && current) SettingsNote(if (extras.operation == SettingsProfileExtrasOperation.UPLOAD_PHOTO) "正在上传头像……" else "正在保存……")
    if (extras.error != null && current) {
        SettingsNote(when (extras.error) {
            SettingsError.CONFLICT -> "资料已在其他位置更改。请刷新后重新确认。"
            SettingsError.FORBIDDEN -> "此账号暂时无法修改这项资料。"
            SettingsError.RATE_LIMITED -> "修改过于频繁，请稍后重试。"
            SettingsError.INVALID_VALUE -> if (extras.operation == SettingsProfileExtrasOperation.UPLOAD_ANIMATION)
                "所选视频无效，未提交修改。" else "所选图片或生日无效，未提交修改。"
            else -> if (extras.acceptedRevision > extras.completedRevision) "修改已提交成功，但最新资料读取失败，请刷新。" else "未能读取或保存资料，内容已保留。"
        }, error = true)
        ChatButton("刷新头像和生日", enabled = pageActive && !extras.busy && !extras.loading) { actions.refresh(extras.token) }
    }
    SettingsPersonalChannelControls(parentToken, userId, extras, actions, enabled)
    SettingsNote("可查看当前头像，也可选择头像历史中的项目查看照片或播放视频。")

    SettingsCurrentProfilePhotoDialog(currentPhotoUi, extras, actions.currentPhoto)

    SettingsProfileStickerAvatarBuilder(stickerOpening, extras, enabled && pageActive,
        onDismiss = { opened -> if (stickerOpening === opened) { opened.close(); stickerOpening = null } })

    SettingsProfileVideoPicker(videoOpening, extras, enabled && !selectingPhoto && stickerOpening == null,
        onDismiss = { opened -> if (videoOpening === opened) { opened.close(); videoOpening = null } },
        onUpload = { opened, animation, admitted ->
            val latest = latestVideoSnapshot
            if (videoOpening === opened && stickerOpening == null && !selectingPhoto && historyUi.session == null && opened.lease.active() && latestVideoEnabled && latest.token == opened.parent &&
                latest.data != null && !latest.loading && !latest.busy && !opened.submissionPending) {
                opened.submissionPending = true
                latestActions.uploadAnimation(opened.parent, opened.expectedPhotoId, animation, admitted)
            } else admitted(null)
        })

    SettingsProfilePhotoPicker(selectingPhoto && pageActive && stickerOpening == null, extras.busy, photoAccepted,
        extras.error.takeIf { photoSubmittedAfter != null && extras.operation == SettingsProfileExtrasOperation.UPLOAD_PHOTO },
        onDismiss = { selectingPhoto = false; photoSubmittedAfter = null },
        onVideo = { path ->
            val latest = latestVideoSnapshot
            if (selectingPhoto && latestVideoEnabled && latest.token == photoParent && latest.token == extras.token && latest.data != null &&
                latest.data.photoId == photoExpected && !latest.loading && !latest.busy && videoOpening == null && stickerOpening == null &&
                historyUi.session == null && currentPhotoUi.opening == null && latestLease?.active() == true) {
                selectingPhoto = false; photoSubmittedAfter = null
                videoOpening = SettingsProfileVideoOpening(latest.token, photoExpected, initialPath = path)
            } else selectingPhoto = false
        },
        onUpload = { photo ->
            val latest = latestVideoSnapshot
            if (selectingPhoto && latestVideoEnabled && latest.token == photoParent && latest.token == extras.token &&
                latest.data != null && !latest.loading && !latest.busy &&
                latestLease?.active() == true && videoOpening == null && stickerOpening == null && historyUi.session == null &&
                currentPhotoUi.opening == null) {
                photoSubmittedAfter = latest.startedRevision
                latestActions.uploadPhoto(latest.token, photoExpected, photo)
            }
        })
    ProfileExtrasConfirmation(removeBirthday && pageActive, "移除生日？", canEdit,
        onDismiss = { removeBirthday = false }, onConfirm = {
            birthdaySubmittedAfter = extras.startedRevision; submittedBirthdayKey = draftKey
            actions.saveBirthdate(extras.token, expected, null); removeBirthday = false
        }) { SettingsNote("生日将从个人资料移除。取消不会改变当前生日。") }
}

@Composable
internal fun ProfileExtrasConfirmation(show: Boolean, title: String, enabled: Boolean, onDismiss: () -> Unit,
    onConfirm: () -> Unit, content: @Composable () -> Unit,
) {
    LocalSettingsSecurityConfirmationHost.current.Content(show, title, null, 440.dp, onDismiss) {
        ProfileExtrasDialogBody(content = content) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = onDismiss)
                ChatButton("确认", Modifier.weight(1f), enabled = enabled, primary = true, onClick = onConfirm)
            }
        }
    }
}

@Composable
internal fun ProfileExtrasDialogBody(
    content: @Composable () -> Unit,
    onContentViewport: ((Rect) -> Unit)? = null,
    onActionsViewport: ((Rect) -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val viewport = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(Modifier.fillMaxWidth().heightIn(max = (viewport - 148.dp).coerceIn(96.dp, 560.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val scroll = rememberScrollState()
        if (onContentViewport == null) Column(Modifier.weight(1f, fill = false).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        else Box(Modifier.weight(1f, fill = true).fillMaxWidth()
            .onGloballyPositioned { onContentViewport(it.boundsInWindow()) }) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
        if (onActionsViewport == null) actions()
        else Column(Modifier.fillMaxWidth().onGloballyPositioned { onActionsViewport(it.boundsInWindow()) }) { actions() }
    }
}

private fun encodeBirthdate(value: SettingsBirthdate?): String = value?.let { "${it.day},${it.month},${it.year}" }.orEmpty()
private fun decodeBirthdate(value: String): SettingsBirthdate? = value.split(',').mapNotNull(String::toIntOrNull)
    .takeIf { it.size == 3 }?.let { SettingsBirthdate(it[0], it[1], it[2]) }
private fun SettingsBirthdate.profileDateLabel() = if (year == 0) "$month 月 $day 日" else "$year 年 $month 月 $day 日"
