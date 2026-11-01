package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider

/** The actual picker owns preparation until it transfers the caller-owned lease to onPrepared. */
internal interface SettingsPreparedSoundPickerHost {
    @Composable fun Content(show: Boolean, limits: NotificationSoundLimits, onDismiss: () -> Unit,
        onPrepared: (SettingsPreparedNotificationSound) -> Unit)
}

private object NativeSettingsPreparedSoundPickerHost : SettingsPreparedSoundPickerHost {
    @Composable override fun Content(show: Boolean, limits: NotificationSoundLimits, onDismiss: () -> Unit,
        onPrepared: (SettingsPreparedNotificationSound) -> Unit) =
        SettingsNotificationSoundPicker(show, limits, onDismiss, onPrepared)
}

internal val LocalPreparedSoundPickerHost = staticCompositionLocalOf<SettingsPreparedSoundPickerHost> {
    NativeSettingsPreparedSoundPickerHost
}

/** A shared saved-sound catalogue for account scopes and the existing per-chat rich draft. */
@Composable
internal fun SettingsSavedNotificationSoundPicker(
    show: Boolean,
    accountToken: SettingsToken,
    snapshot: SavedNotificationSoundsSnapshot,
    actions: SavedNotificationSoundsActions,
    selectedSoundId: Long,
    onSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    active: Boolean = LocalSettingsPageActive.current,
    volumeTarget: NotificationSoundVolumeTarget? = null,
) {
    val pageActive = show && active && LocalSettingsPageActive.current
    val lease = remember(pageActive, accountToken, volumeTarget) {
        SavedSoundPickerLease(accountToken.generation, snapshot, pageActive)
    }
    val latestSnapshot = rememberUpdatedState(snapshot)
    val latestActions = rememberUpdatedState(actions)
    val latestOnDismiss = rememberUpdatedState(onDismiss)
    val latestActive = rememberUpdatedState(pageActive)
    var ownedToken by remember(lease) { mutableStateOf<SavedNotificationSoundsToken?>(null) }
    val prepared by lease.prepared.collectAsState()
    var pickingFile by remember(lease) { mutableStateOf(false) }
    var deleteDraft by remember(lease) { mutableStateOf<SavedSoundDeleteDraft?>(null) }
    var uploadAttempt by remember(lease) { mutableStateOf<SavedSoundUploadAttempt?>(null) }
    var choiceAttempt by remember(lease) { mutableStateOf<SavedSoundChoiceAttempt?>(null) }
    var choiceCompletion by remember(lease) { mutableStateOf<SavedSoundChoiceCompletion?>(null) }
    var choiceError by remember(lease) { mutableStateOf<SettingsError?>(null) }
    var page by remember(lease) { mutableStateOf(0) }
    val parentSelectionAtOpen = remember(lease) { selectedSoundId }
    var draftSoundId by remember(lease) { mutableStateOf(selectedSoundId) }
    var volumeReading by remember(lease) { mutableStateOf<NotificationSoundVolumeReading?>(null) }
    var draftVolume by remember(lease) { mutableStateOf<Int?>(null) }
    var volumeDraftInitialized by remember(lease) { mutableStateOf(false) }
    var volumeError by remember(lease) { mutableStateOf<SettingsError?>(null) }
    var volumeReadAttempt by remember(lease) { mutableStateOf<SavedSoundVolumeReadAttempt?>(null) }
    var volumeReadCompletion by remember(lease) { mutableStateOf<SavedSoundVolumeReadCompletion?>(null) }
    var volumeSaveAttempt by remember(lease) { mutableStateOf<SavedSoundVolumeSaveAttempt?>(null) }
    var volumeSaveCompletion by remember(lease) { mutableStateOf<SavedSoundVolumeSaveCompletion?>(null) }
    var volumeGesturePending by remember(lease) { mutableStateOf(false) }
    SideEffect {
        if (pageActive && selectedSoundId != parentSelectionAtOpen) lease.close()?.let { latestActions.value.close(it) }
    }

    SideEffect { lease.observe(snapshot)?.let { if (ownedToken != it) ownedToken = it } }
    DisposableEffect(lease) {
        if (lease.active()) latestActions.value.open(accountToken, lease::active)
        onDispose { lease.close()?.let { latestActions.value.close(it) } }
    }
    fun current(): SavedNotificationSoundsSnapshot? = latestSnapshot.value.takeIf {
        latestActive.value && lease.owns(it.token) && it.opened && it.token == ownedToken &&
            it.token == snapshot.token && it.token.generation == accountToken.generation
    }
    fun dismiss() {
        lease.close()?.let { latestActions.value.close(it) }
        latestOnDismiss.value()
    }
    val owned = pageActive && snapshot.opened && snapshot.token == ownedToken && lease.owns(snapshot.token)
    val oversized = owned && (snapshot.data?.sounds?.size ?: 0) > 4096
    val data = snapshot.data.takeIf { owned && !oversized }
    val editable = owned && data != null && !snapshot.loading && !snapshot.saving && !snapshot.choosing &&
        !snapshot.refreshRequired && !snapshot.readbackPending && choiceAttempt == null
    val volumeReady = volumeTarget == null || volumeReading != null && volumeReadAttempt == null
    fun readVolume() {
        val target = volumeTarget ?: return
        val latest = current() ?: return
        val account = latest.data?.accountUserId ?: return
        if (volumeReadAttempt != null || choiceAttempt != null || latest.saving) return
        val attempt = SavedSoundVolumeReadAttempt(latest.token, account, target)
        volumeReadAttempt = attempt
        volumeError = null
        latestActions.value.readVolume(latest.token, target) { attempt.result.compareAndSet(null, it) }
    }
    LaunchedEffect(owned, data?.accountUserId, lease) {
        if (owned && data != null && volumeTarget != null && volumeReading == null && volumeError == null) readVolume()
    }
    LaunchedEffect(volumeReadAttempt, lease) {
        val attempt = volumeReadAttempt ?: return@LaunchedEffect
        val result = attempt.result.filterNotNull().first()
        if (volumeReadAttempt === attempt) volumeReadCompletion = SavedSoundVolumeReadCompletion(attempt, result)
    }
    val completedVolumeRead = volumeReadCompletion
    SideEffect {
        val completion = completedVolumeRead ?: return@SideEffect
        val attempt = completion.attempt
        if (volumeReadAttempt !== attempt || !attempt.delivered.compareAndSet(false, true)) return@SideEffect
        volumeReadCompletion = null
        volumeReadAttempt = null
        if (!owned || !lease.owns(attempt.token) || snapshot.token != attempt.token || volumeTarget != attempt.target ||
            selectedSoundId != parentSelectionAtOpen || data?.accountUserId != attempt.accountUserId) return@SideEffect
        val value = completion.result.value
        if (completion.result.error != null || value == null || value.accountUserId != attempt.accountUserId ||
            value.target != attempt.target || value.overridePercent?.let { it !in 1..100 } == true) {
            volumeError = completion.result.error ?: SettingsError.CONFLICT
        } else {
            volumeReading = value
            if (!volumeDraftInitialized) { draftVolume = value.overridePercent; volumeDraftInitialized = true }
            volumeError = null
        }
    }
    SideEffect {
        // Match the official missing-item fallback only after a complete successful reading.
        // Pending uploads/removals and unrelated list updates never overwrite a valid local draft.
        if (owned && data != null && !snapshot.loading && !snapshot.saving && !snapshot.readbackPending &&
            !snapshot.refreshRequired && snapshot.error == null && draftSoundId != 0L && draftSoundId != -1L &&
            data.sounds.none { it.id == draftSoundId }) draftSoundId = -1L
    }

    LaunchedEffect(choiceAttempt, lease) {
        val attempt = choiceAttempt ?: return@LaunchedEffect
        val result = attempt.result.filterNotNull().first()
        if (choiceAttempt !== attempt) return@LaunchedEffect
        // The collector may resume before a parent's simultaneous state change recomposes.
        // Queue the result for a committed composition instead of reading remembered old props.
        choiceCompletion = SavedSoundChoiceCompletion(attempt, result)
    }
    val completedChoice = choiceCompletion
    SideEffect {
        val completion = completedChoice ?: return@SideEffect
        val attempt = completion.attempt
        if (choiceAttempt !== attempt || !attempt.delivered.compareAndSet(false, true)) return@SideEffect
        choiceCompletion = null
        choiceAttempt = null
        // These are this composition's actual props, including a parent update from the same
        // input turn as the native callback. Own choosing/result revision changes remain valid.
        if (!pageActive || !snapshot.opened || !lease.owns(snapshot.token) || snapshot.token != ownedToken ||
            snapshot.token != attempt.token || snapshot.token.generation != accountToken.generation ||
            selectedSoundId != attempt.parentSelection || volumeTarget != attempt.volumeTarget) {
            choiceAttempt = null
            return@SideEffect
        }
        val actualData = snapshot.data ?: return@SideEffect
        if (actualData.accountUserId != attempt.accountUserId) return@SideEffect
        val result = completion.result
        val id = result.value
        if (result.error != null || id == null || id != attempt.soundId ||
            id != 0L && id != -1L && actualData.sounds.none { it.id == id }) {
            choiceError = result.error ?: SettingsError.CONFLICT
            choiceAttempt = null
            return@SideEffect
        }
        val expectedVolume = attempt.volumeExpected
        if (attempt.volumeTarget != null && expectedVolume != null && expectedVolume.overridePercent != attempt.desiredVolume) {
            val saving = SavedSoundVolumeSaveAttempt(attempt, expectedVolume)
            choiceAttempt = attempt
            volumeSaveAttempt = saving
            volumeError = null
            latestActions.value.saveVolume(attempt.token, expectedVolume, attempt.desiredVolume) { saving.result.compareAndSet(null, it) }
            return@SideEffect
        }
        choiceAttempt = null
        onSelected(id)
        dismiss()
    }
    LaunchedEffect(volumeSaveAttempt, lease) {
        val attempt = volumeSaveAttempt ?: return@LaunchedEffect
        val result = attempt.result.filterNotNull().first()
        if (volumeSaveAttempt === attempt) volumeSaveCompletion = SavedSoundVolumeSaveCompletion(attempt, result)
    }
    val completedVolumeSave = volumeSaveCompletion
    SideEffect {
        val completion = completedVolumeSave ?: return@SideEffect
        val attempt = completion.attempt
        val choice = attempt.choice
        if (volumeSaveAttempt !== attempt || !attempt.delivered.compareAndSet(false, true)) return@SideEffect
        volumeSaveCompletion = null
        volumeSaveAttempt = null
        choiceAttempt = null
        if (!owned || !lease.owns(choice.token) || snapshot.token != choice.token || volumeTarget != choice.volumeTarget ||
            selectedSoundId != choice.parentSelection || data?.accountUserId != choice.accountUserId) return@SideEffect
        val value = completion.result.value
        if (completion.result.error != null || value == null || value.accountUserId != choice.accountUserId ||
            value.target != choice.volumeTarget || value.overridePercent != choice.desiredVolume) {
            volumeError = completion.result.error ?: SettingsError.CONFLICT
            return@SideEffect
        }
        volumeReading = value
        val currentData = data ?: return@SideEffect
        if (choice.soundId != 0L && choice.soundId != -1L && currentData.sounds.none { it.id == choice.soundId }) {
            choiceError = SettingsError.CONFLICT
            return@SideEffect
        }
        onSelected(choice.soundId)
        dismiss()
    }
    // An accepted upload may still await list readback. Never offer to upload that file again.
    LaunchedEffect(snapshot.token, snapshot.acceptedRevision, prepared, uploadAttempt, deleteDraft) {
        if (!owned) return@LaunchedEffect
        uploadAttempt?.takeIf { it.token == snapshot.token && snapshot.acceptedRevision > it.acceptedRevision }?.let {
            snapshot.lastAddedSoundId?.takeIf { id -> id != 0L && id != -1L }?.let { id -> draftSoundId = id }
            if (prepared === it.prepared) lease.releasePrepared()
            uploadAttempt = null
        }
        deleteDraft?.takeIf { it.submitted && it.token == snapshot.token &&
            snapshot.acceptedRevision > it.acceptedRevision }?.let { deleteDraft = null }
    }
    LaunchedEffect(prepared) { if (prepared != null) pickingFile = false }

    fun choose(id: Long) {
        val latest = current() ?: return
        val actualData = latest.data ?: return
        if (!editable || !volumeReady || latest.loading || latest.saving || latest.choosing || latest.refreshRequired || latest.readbackPending ||
            choiceAttempt != null || id != 0L && id != -1L && actualData.sounds.none { it.id == id }) return
        val attempt = SavedSoundChoiceAttempt(latest.token, actualData.accountUserId, id, parentSelectionAtOpen,
            volumeTarget, volumeReading, draftVolume)
        choiceAttempt = attempt
        choiceError = null
        latestActions.value.choose(latest.token, latest.revision, id) { result -> attempt.result.compareAndSet(null, result) }
    }
    fun preview(id: Long) {
        if (!volumeReady) return
        current()?.takeIf { !it.saving && !it.loading && it.data != null &&
            (id == -1L || it.data.sounds.any { sound -> sound.id == id }) }?.let {
            latestActions.value.preview(it.token, id, draftVolume ?: 100)
        }
    }
    fun selectDraft(id: Long) {
        if (!editable) return
        draftSoundId = id
        choiceError = null
        if (id == 0L) current()?.let { latestActions.value.stopPreview(it.token) } else preview(id)
    }

    SecurityConfirmation(show = pageActive, title = when {
        prepared != null -> "添加通知声音"
        deleteDraft != null -> "删除通知声音？"
        else -> "选择通知声音"
    }, maxWidth = 540.dp, onDismissRequest = ::dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!owned || snapshot.loading) SettingsNote("正在读取保存的声音…")
            if (owned && snapshot.saving) SettingsNote("正在提交更改；关闭后已提交的更改仍会处理。")
            if (owned && snapshot.readbackPending) SettingsNote("更改已提交，尚未读回当前铃声列表。")
            if (owned && snapshot.refreshRequired) SettingsNote("铃声列表已变化，请重新读取后再操作。", error = true)
            if (owned) snapshot.error?.let { SettingsNote(settingsErrorText(it), error = true) }
            if (oversized) SettingsNote("保存的声音列表过大，暂无法显示。请重新读取后重试。", error = true)
            choiceError?.let { SettingsNote(settingsErrorText(it), error = true) }
            if (owned && snapshot.previewLoading) SettingsNote("正在准备试听…")
            if (owned && snapshot.previewDefaultFallback) SettingsNote("所选声音不可用，本次试听使用默认声音。")
            if (owned) snapshot.previewError?.let { SettingsNote("试听未完成：${settingsErrorText(it)}", error = true) }
            if (owned && (snapshot.previewSoundId != null || snapshot.previewLoading)) SettingsAction("停止试听") {
                current()?.let { latestActions.value.stopPreview(it.token) }
            }

            val upload = prepared
            val removal = deleteDraft
            when {
                upload != null -> {
                    val limits = data?.limits
                    val validUpload = limits?.known == true && data?.limitsError == null && upload.upload.sizeBytes > 0 && upload.upload.durationMillis > 0 &&
                        upload.upload.sizeBytes <= (limits.maximumBytes ?: 0) &&
                        upload.upload.durationMillis <= (limits.maximumDurationSeconds?.toLong() ?: 0L) * 1000L &&
                        (data?.sounds?.size ?: Int.MAX_VALUE) < (limits.maximumCount ?: 0)
                    SettingsRow("已选音频", "${upload.upload.sizeBytes} 字节",
                        summary = "时长 ${upload.upload.durationMillis} 毫秒。确认后添加到账号保存的声音。")
                    if (limits?.known != true || data?.limitsError != null) SettingsNote("添加限制尚未读取，请重新读取后再添加。", error = true)
                    else if (!validUpload) SettingsNote("所选音频超出当前添加限制，请取消后重新选择。", error = true)
                    ChatButton("确认添加声音", enabled = editable && validUpload && !pickingFile, primary = true) {
                        current()?.takeIf { editable && validUpload && !it.saving && !it.loading && !it.readbackPending && !it.refreshRequired }?.let {
                            uploadAttempt = SavedSoundUploadAttempt(it.token, it.acceptedRevision, upload)
                            latestActions.value.upload(it.token, it.revision, upload)
                        }
                    }
                    ChatButton("取消添加", enabled = !snapshot.saving) { lease.releasePrepared(); uploadAttempt = null }
                }
                removal != null -> {
                    SettingsRow(removal.sound.title.ifBlank { "未命名声音" }, "${removal.sound.durationSeconds} 秒")
                    SettingsNote("这会从账号保存的声音中移除此项。")
                    ChatButton("确认删除声音", enabled = editable, primary = true) {
                        current()?.takeIf { editable && !it.saving && !it.loading && !it.readbackPending && !it.refreshRequired }?.let {
                            deleteDraft = removal.copy(submitted = true, acceptedRevision = it.acceptedRevision)
                            latestActions.value.remove(it.token, it.revision, removal.sound)
                        }
                    }
                    ChatButton("取消删除", enabled = !snapshot.saving) { deleteDraft = null }
                }
                else -> {
                    SettingsNote("点击声音可试听，保存后应用所选声音。")
                    if (volumeTarget != null) {
                        SettingsNote("本机音量与账号声音分别保存。")
                        if (volumeReadAttempt != null) SettingsNote("正在读取本机铃声音量…")
                        volumeError?.let {
                            SettingsNote(if (it == SettingsError.CONFLICT) "本机音量已变化，请重新读取后重试。"
                                else "本机音量读取或保存失败，请重试。", error = true)
                        }
                        if (volumeReading == null) SettingsNote("读取后才能保存本机音量。")
                        if (draftSoundId != 0L) {
                            SettingsNote("本机铃声音量：${draftVolume ?: 100}%")
                            if (volumeReading != null && draftVolume == null) SettingsNote("未单独设置，显示默认 100%。")
                            Slider(value = (draftVolume ?: 100).toFloat(), valueRange = 1f..100f,
                                enabled = editable && volumeReady, onValueChange = {
                                    if (it.isFinite()) {
                                        draftVolume = it.roundToInt().coerceIn(1, 100)
                                        volumeGesturePending = true
                                    }
                                }, onValueChangeFinished = {
                                    if (volumeGesturePending) {
                                        volumeGesturePending = false
                                        preview(draftSoundId)
                                    }
                                }, modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "本机铃声音量"
                                    // Miuix's accessibility action has no pointer-finished callback.
                                    setProgress {
                                        if (!editable || !volumeReady || !it.isFinite()) false
                                        else {
                                            draftVolume = it.roundToInt().coerceIn(1, 100)
                                            volumeGesturePending = false
                                            preview(draftSoundId)
                                            true
                                        }
                                    }
                                }.onPreviewKeyEvent { event ->
                                    if (!editable || !volumeReady || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed ||
                                        event.key != Key.DirectionLeft && event.key != Key.DirectionRight) false
                                    else {
                                        if (event.type == KeyEventType.KeyDown) {
                                            val before = draftVolume ?: 100
                                            val after = (before + if (event.key == Key.DirectionLeft) -1 else 1).coerceIn(1, 100)
                                            if (after != before) { draftVolume = after; preview(draftSoundId) }
                                        }
                                        true
                                    }
                                }.focusable(editable && volumeReady))
                            SettingsAction("恢复默认本机音量", enabled = editable && volumeReady) {
                                volumeGesturePending = false
                                draftVolume = null
                            }
                        }
                        SettingsAction("重新读取本机音量", enabled = owned && !snapshot.saving && choiceAttempt == null && volumeReadAttempt == null) { readVolume() }
                        if (volumeSaveAttempt != null) SettingsNote("正在保存本机音量…")
                    }
                    SettingsAccountChoice("默认声音", draftSoundId == -1L, editable) { selectDraft(-1) }
                    SettingsAccountChoice("无声", draftSoundId == 0L, editable) { selectDraft(0) }
                    if (data != null) {
                        val sounds = data.sounds
                        val lastPage = ((sounds.size - 1).coerceAtLeast(0)) / 32
                        val visiblePage = page.coerceIn(0, lastPage)
                        SettingsNote("已保存 ${sounds.size} 个声音，第 ${visiblePage + 1}/${lastPage + 1} 页。")
                        if (sounds.isEmpty()) SettingsNote("还没有保存的声音。")
                        sounds.subList((visiblePage * 32).coerceAtMost(sounds.size), ((visiblePage + 1) * 32).coerceAtMost(sounds.size)).forEach { sound ->
                            val title = sound.title.ifBlank { "未命名声音" }
                            SettingsRow(title, if (draftSoundId == sound.id) "已选择" else null,
                                summary = "${sound.durationSeconds} 秒", enabled = editable) { selectDraft(sound.id) }
                        }
                        if (visiblePage > 0) SettingsAction("上一页声音", enabled = !snapshot.saving) { page = visiblePage - 1 }
                        if (visiblePage < lastPage) SettingsAction("下一页声音", enabled = !snapshot.saving) { page = visiblePage + 1 }
                        SettingsAction("试听所选声音", enabled = owned && volumeReady && !snapshot.loading && !snapshot.saving && draftSoundId != 0L &&
                            (draftSoundId == -1L || sounds.any { it.id == draftSoundId })) { preview(draftSoundId) }
                        val selected = sounds.singleOrNull { it.id == draftSoundId }
                        SettingsAction("删除所选声音", enabled = editable && selected != null) {
                            val target = selected ?: return@SettingsAction
                            current()?.takeIf { editable && !it.saving &&
                                it.data?.sounds?.any { actual -> actual == target } == true }?.let {
                                deleteDraft = SavedSoundDeleteDraft(it.token, it.revision, target, it.acceptedRevision)
                            }
                        }
                        data.limitsError?.let { SettingsNote("声音上传限制尚未读取：${settingsErrorText(it)}", error = true) }
                        val limits = data.limits
                        if (limits.known) SettingsNote("最多 ${limits.maximumCount} 个声音，每个不超过 ${limits.maximumBytes} 字节、${limits.maximumDurationSeconds} 秒。")
                        else SettingsNote("添加限制尚未读取，暂不能添加声音。")
                        SettingsAction("添加声音", enabled = editable && limits.known && data.limitsError == null && sounds.size < (limits.maximumCount ?: 0) && !pickingFile) {
                            current()?.takeIf { editable && !it.saving && it.data?.limits?.known == true && it.data.limitsError == null }?.let { pickingFile = true }
                        }
                    }
                    if (pickingFile) SettingsNote("请选择要添加的音频…")
                    ChatButton("保存声音选择", enabled = editable && volumeReady && !pickingFile, primary = true) { choose(draftSoundId) }
                }
            }
            SettingsAction("重新读取声音列表", enabled = owned && !snapshot.loading && !snapshot.saving && !snapshot.choosing) {
                current()?.takeIf { !it.loading && !it.saving && !it.choosing }?.let { latestActions.value.refresh(it.token) }
            }
            ChatButton("关闭声音选择", onClick = ::dismiss)
        }
    }
    LocalPreparedSoundPickerHost.current.Content(pickingFile && owned, data?.limits ?: NotificationSoundLimits(),
        onDismiss = { pickingFile = false }, onPrepared = lease::receivePrepared)
}

private data class SavedSoundDeleteDraft(val token: SavedNotificationSoundsToken, val revision: Long,
    val sound: SavedNotificationSound, val acceptedRevision: Long, val submitted: Boolean = false)
private data class SavedSoundUploadAttempt(val token: SavedNotificationSoundsToken, val acceptedRevision: Long,
    val prepared: SettingsPreparedNotificationSound)
private class SavedSoundChoiceAttempt(val token: SavedNotificationSoundsToken, val accountUserId: Long,
    val soundId: Long, val parentSelection: Long, val volumeTarget: NotificationSoundVolumeTarget?,
    val volumeExpected: NotificationSoundVolumeReading?, val desiredVolume: Int?) {
    val result = MutableStateFlow<SettingsResult<Long>?>(null)
    val delivered = MutableStateFlow(false)
}
private data class SavedSoundChoiceCompletion(val attempt: SavedSoundChoiceAttempt, val result: SettingsResult<Long>)
private class SavedSoundVolumeReadAttempt(val token: SavedNotificationSoundsToken, val accountUserId: Long,
    val target: NotificationSoundVolumeTarget) {
    val result = MutableStateFlow<SettingsResult<NotificationSoundVolumeReading>?>(null)
    val delivered = MutableStateFlow(false)
}
private data class SavedSoundVolumeReadCompletion(val attempt: SavedSoundVolumeReadAttempt,
    val result: SettingsResult<NotificationSoundVolumeReading>)
private class SavedSoundVolumeSaveAttempt(val choice: SavedSoundChoiceAttempt, val expected: NotificationSoundVolumeReading) {
    val result = MutableStateFlow<SettingsResult<NotificationSoundVolumeReading>?>(null)
    val delivered = MutableStateFlow(false)
}
private data class SavedSoundVolumeSaveCompletion(val attempt: SavedSoundVolumeSaveAttempt,
    val result: SettingsResult<NotificationSoundVolumeReading>)

/** Both worker preparation and controller callbacks can inspect the lease without Compose state. */
private class SavedSoundPickerLease(private val generation: Long, initial: SavedNotificationSoundsSnapshot, active: Boolean) {
    private val initialToken = initial.token
    private val initiallyOpened = initial.opened
    private val alive = MutableStateFlow(active)
    private val token = MutableStateFlow<SavedNotificationSoundsToken?>(null)
    val prepared = MutableStateFlow<SettingsPreparedNotificationSound?>(null)
    fun active() = alive.value
    fun owns(candidate: SavedNotificationSoundsToken) = active() && token.value == candidate
    fun observe(snapshot: SavedNotificationSoundsSnapshot): SavedNotificationSoundsToken? {
        if (active() && snapshot.opened && snapshot.token.generation == generation && token.value == null &&
            (snapshot.token != initialToken || !initiallyOpened)) token.value = snapshot.token
        return token.value
    }
    fun receivePrepared(value: SettingsPreparedNotificationSound) {
        if (!active() || !prepared.compareAndSet(null, value)) { value.lease.release(); return }
        if (!active() && prepared.compareAndSet(value, null)) value.lease.release()
    }
    fun releasePrepared() {
        while (true) {
            val value = prepared.value ?: return
            if (prepared.compareAndSet(value, null)) { value.lease.release(); return }
        }
    }
    fun close(): SavedNotificationSoundsToken? {
        if (!alive.compareAndSet(true, false)) return null
        releasePrepared()
        return token.value
    }
}
