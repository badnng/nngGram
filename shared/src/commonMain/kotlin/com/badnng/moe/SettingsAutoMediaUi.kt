package com.badnng.moe

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.AutoMediaPolicy
import com.badnng.moe.chat.AutoMediaCopyNotice
import com.badnng.moe.chat.AutoMediaSettings
import com.badnng.moe.chat.AutoMediaSource
import com.badnng.moe.chat.AutoMediaSourceRules
import com.badnng.moe.chat.AutoMediaType
import com.badnng.moe.chat.MIB
import com.badnng.moe.chat.autoMediaSizeByIndex
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val downloadTypes = setOf(AutoMediaType.PHOTO, AutoMediaType.FILE)
private val playTypes = setOf(AutoMediaType.VIDEO, AutoMediaType.GIF)

internal data class AutoMediaDraftSubmission(val identity: Long, val policy: AutoMediaPolicy)

/** Transient child draft. Unsupported native types and untouched exact limits survive unchanged. */
internal class AutoMediaSourceDraft(val source: AutoMediaSource, private val original: AutoMediaSourceRules) {
    var downloadLimit by mutableStateOf(downloadTypes.maxOf(original::limit).takeIf { it > 0 } ?: 10L * MIB)
        private set
    var playLimit by mutableStateOf(playTypes.maxOf(original::limit).takeIf { it > 0 } ?: 50L * MIB)
        private set
    private var selected by mutableStateOf((downloadTypes + playTypes).filter { original.limit(it) > 0 }.toSet())
    private var downloadEdited by mutableStateOf(false)
    private var playEdited by mutableStateOf(false)

    fun enabled(type: AutoMediaType) = type in selected
    fun toggle(type: AutoMediaType, enabled: Boolean) {
        require(type in downloadTypes || type in playTypes)
        selected = if (enabled) selected + type else selected - type
        if (type in downloadTypes) downloadEdited = true else playEdited = true
    }
    fun size(download: Boolean, index: Int) {
        val bytes = autoMediaSizeByIndex(index)
        if (download) { downloadLimit = bytes; downloadEdited = true }
        else { playLimit = bytes; playEdited = true }
    }
    fun rules(): AutoMediaSourceRules {
        var result = original
        if (downloadEdited) downloadTypes.forEach { result = result.withLimit(it, if (enabled(it)) downloadLimit else 0) }
        if (playEdited) playTypes.forEach { result = result.withLimit(it, if (enabled(it)) playLimit else 0) }
        return result
    }
}

/** This draft never writes storage; the sole commit path is the caller's revision-checked save. */
internal class AutoMediaEditorDraft(settings: AutoMediaSettings) {
    val accountUserId = settings.accountUserId
    var revision by mutableStateOf(settings.revision)
        private set
    private var original = settings.policy
    var policy by mutableStateOf(settings.policy)
        private set
    var child by mutableStateOf<AutoMediaSourceDraft?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var conflict by mutableStateOf(false)
        private set
    private var submitted: AutoMediaPolicy? = null
    private var nextSubmissionIdentity = 0L
    private var pendingSubmissionIdentity by mutableStateOf<Long?>(null)
    private var admittedSubmissionIdentity by mutableStateOf<Long?>(null)
    private var completedSubmissionIdentity by mutableStateOf<Long?>(null)
    val changed get() = policy != original

    fun source(source: AutoMediaSource, enabled: Boolean) { policy = policy.sourceEnabled(source, enabled); failed = false }
    fun edit(source: AutoMediaSource) { child = AutoMediaSourceDraft(source, policy.rules(source)) }
    fun cancelChild() { child = null }
    fun applyChild() {
        child?.let { policy = policy.withSource(it.source, it.rules()) }
        child = null
        failed = false
    }
    fun begin(): AutoMediaDraftSubmission? {
        if (pending || conflict || child != null) return null
        pending = true
        failed = false
        submitted = policy
        val identity = ++nextSubmissionIdentity
        pendingSubmissionIdentity = identity
        admittedSubmissionIdentity = null
        completedSubmissionIdentity = null
        return AutoMediaDraftSubmission(identity, policy)
    }
    /** Admission is independent from save success; only the matching rejected request releases pending. */
    fun reportAdmission(identity: Long, accepted: Boolean) {
        if (!pending || pendingSubmissionIdentity != identity || admittedSubmissionIdentity == identity ||
            completedSubmissionIdentity == identity) return
        if (accepted) admittedSubmissionIdentity = identity
        else {
            pendingSubmissionIdentity = null
            admittedSubmissionIdentity = null
            completedSubmissionIdentity = null
            failed = true
            // Re-enable UI input only after retiring every part of this request.
            pending = false
        }
    }
    /** A successful write still waits for the matching published revision; failure releases only its request. */
    fun reportCompletion(identity: Long, succeeded: Boolean) {
        if (!pending || pendingSubmissionIdentity != identity || completedSubmissionIdentity == identity) return
        completedSubmissionIdentity = identity
        if (!succeeded) {
            pendingSubmissionIdentity = null
            admittedSubmissionIdentity = null
            failed = true
            pending = false
        }
    }
    /** Returns true only after this draft's actual policy was published at a new revision. */
    fun observe(settings: AutoMediaSettings): Boolean {
        if (settings.accountUserId != accountUserId) return false
        if (settings.revision != revision) {
            val accepted = pending && settings.revision > revision && settings.policy == submitted && !settings.saving && !settings.error
            pending = false
            pendingSubmissionIdentity = null
            admittedSubmissionIdentity = null
            completedSubmissionIdentity = null
            if (!accepted) conflict = true
            return accepted
        }
        return false
    }
    fun reload(settings: AutoMediaSettings) {
        if (settings.accountUserId != accountUserId || pending) return
        revision = settings.revision
        original = settings.policy
        policy = settings.policy
        child = null
        failed = false
        conflict = false
        submitted = null
        pendingSubmissionIdentity = null
        admittedSubmissionIdentity = null
        completedSubmissionIdentity = null
    }
}

/** One in-memory account slot; a workspace owner inherits its generation lifetime and stores no account map. */
internal class AutoMediaEditorDraftSession {
    private var accountUserId by mutableStateOf<Long?>(null)
    private var draft by mutableStateOf<AutoMediaEditorDraft?>(null)

    fun bindAccount(userId: Long?) {
        if (userId == null || userId == accountUserId) return
        accountUserId = userId
        draft = null
    }

    fun draftFor(userId: Long?): AutoMediaEditorDraft? =
        if (userId != null && userId == accountUserId) draft else null

    fun open(settings: AutoMediaSettings): AutoMediaEditorDraft? {
        val userId = settings.accountUserId ?: return null
        bindAccount(userId)
        return AutoMediaEditorDraft(settings).also { draft = it }
    }

    fun discard(target: AutoMediaEditorDraft) {
        if (draft === target) draft = null
    }

    fun consumeAccepted(target: AutoMediaEditorDraft): Boolean {
        if (draft !== target || accountUserId != target.accountUserId) return false
        draft = null
        return true
    }

    fun reportAdmission(target: AutoMediaEditorDraft, identity: Long, accepted: Boolean) {
        if (draft !== target || accountUserId != target.accountUserId) return
        target.reportAdmission(identity, accepted)
    }

    fun reportCompletion(target: AutoMediaEditorDraft, identity: Long, succeeded: Boolean) {
        if (draft !== target || accountUserId != target.accountUserId) return
        target.reportCompletion(identity, succeeded)
    }
}

/** Standalone callers use a remembered owner; SettingsWorkspace can supply its longer-lived draft owner. */
@Composable
internal fun SettingsAutoMediaControls(
    settings: AutoMediaSettings,
    onSave: (expectedRevision: Long, policy: AutoMediaPolicy,
        admission: (Boolean) -> Unit, completed: (Boolean) -> Unit,
    ) -> Unit,
    active: Boolean = LocalSettingsPageActive.current,
    hoistedDraft: AutoMediaEditorDraftSession? = null,
) {
    val localDraft = remember { AutoMediaEditorDraftSession() }
    val draftSession = hoistedDraft ?: localDraft
    var saved by remember(settings.accountUserId) { mutableStateOf(false) }
    SideEffect { draftSession.bindAccount(settings.accountUserId) }
    val latestSettings by rememberUpdatedState(settings)
    val latestActive by rememberUpdatedState(active)
    val latestSave by rememberUpdatedState(onSave)
    val form = draftSession.draftFor(settings.accountUserId)
    LaunchedEffect(settings, form, draftSession) {
        if (form?.observe(settings) == true && draftSession.consumeAccepted(form)) saved = true
    }
    val available = active && settings.loaded && settings.accountUserId != null
    val mutable = available && !settings.saving && form?.pending != true && form?.conflict != true
    fun current(): Boolean = form != null && latestActive && latestSettings.loaded && !latestSettings.saving &&
        latestSettings.accountUserId == form.accountUserId && latestSettings.revision == form.revision && !form.pending && !form.conflict

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val copyNotice = settings.copyNotice ?: if (settings.copyError) AutoMediaCopyNotice.FAILED else null
        copyNotice?.let { notice ->
            SettingsNote(when (notice) {
                AutoMediaCopyNotice.FAILED -> "自动文件副本保存失败，原生缓存仍保留。"
                AutoMediaCopyNotice.RECORD_PENDING -> "文件已写入下载目录，保存记录尚未提交；不会自动另建第二份。"
                AutoMediaCopyNotice.USER_CHANGED_TARGET -> "先前自动保存的文件已被删除或修改，当前不会自动重建。"
                AutoMediaCopyNotice.RECOVERY_AMBIGUOUS -> "先前自动保存的结果无法安全确认，当前不会改名重试或覆盖文件。"
            }, error = true)
        }
        if (form == null) {
            SettingsGroup("自动媒体") {
                AutoMediaSource.entries.forEach { source ->
                    SettingsRow(source.label, if (!settings.loaded) "未读取" else if (settings.policy.rules(source).enabled) "已开启" else "已关闭")
                }
                SettingsAction("编辑自动媒体规则", available && !settings.saving) {
                    if (latestActive && latestSettings.loaded && !latestSettings.saving && latestSettings.accountUserId != null) {
                        draftSession.open(latestSettings)
                        saved = false
                    }
                }
            }
            if (!settings.loaded) SettingsNote(if (settings.error) "自动媒体规则读取失败，暂不新增自动下载。" else "正在读取自动媒体规则……", settings.error)
            if (saved) SettingsNote("自动媒体规则已保存。")
        } else {
            if (form.pending || settings.saving) SettingsNote("正在保存自动媒体规则……")
            if (form.failed) SettingsNote("自动媒体设置未保存，草稿已保留，请重试。", error = true)
            if (form.conflict) {
                SettingsNote("自动媒体规则已变化，草稿已保留。请放弃草稿后载入当前规则。", error = true)
                SettingsAction("放弃草稿并载入当前规则", available && !settings.saving && !form.pending) {
                    if (latestActive && latestSettings.loaded && !latestSettings.saving && latestSettings.accountUserId == form.accountUserId)
                        form.reload(latestSettings)
                }
            }
            val child = form.child
            if (child == null) {
                SettingsGroup("自动媒体规则") {
                    AutoMediaSource.entries.forEach { source ->
                        SettingsToggle("${source.label}自动媒体", form.policy.rules(source).enabled, mutable) {
                            if (current()) form.source(source, it)
                        }
                        SettingsAction("编辑${source.label}规则", mutable) { if (current()) form.edit(source) }
                    }
                }
                SettingsNote("照片与文件共享下载上限；视频与 GIF 共享自动播放上限。更改仅在保存后生效。")
                AutoMediaUnsupportedContent()
                SettingsAction("保存自动媒体规则", mutable) {
                    if (current()) form.begin()?.let { submission ->
                        latestSave(form.revision, submission.policy,
                            { accepted -> draftSession.reportAdmission(form, submission.identity, accepted) },
                            { succeeded -> draftSession.reportCompletion(form, submission.identity, succeeded) })
                    }
                }
                SettingsAction("取消自动媒体更改", available && !settings.saving && !form.pending) {
                    if (latestActive && latestSettings.accountUserId == form.accountUserId && !form.pending && !latestSettings.saving)
                        draftSession.discard(form)
                }
            } else {
                SettingsGroup("${child.source.label}自动媒体规则") {
                    SettingsToggle("自动下载照片", child.enabled(AutoMediaType.PHOTO), mutable) {
                        if (current()) child.toggle(AutoMediaType.PHOTO, it)
                    }
                    SettingsToggle("自动下载文件", child.enabled(AutoMediaType.FILE), mutable) {
                        if (current()) child.toggle(AutoMediaType.FILE, it)
                    }
                    AutoMediaSizeControl("自动下载大小上限", child.downloadLimit, mutable) { if (current()) child.size(true, it) }
                    SettingsToggle("自动播放视频", child.enabled(AutoMediaType.VIDEO), mutable) {
                        if (current()) child.toggle(AutoMediaType.VIDEO, it)
                    }
                    SettingsToggle("自动播放 GIF", child.enabled(AutoMediaType.GIF), mutable) {
                        if (current()) child.toggle(AutoMediaType.GIF, it)
                    }
                    AutoMediaSizeControl("自动播放大小上限", child.playLimit, mutable) { if (current()) child.size(false, it) }
                }
                AutoMediaUnsupportedContent()
                SettingsAction("应用${child.source.label}规则", mutable) { if (current()) form.applyChild() }
                SettingsAction("取消${child.source.label}规则", available && !form.pending && !settings.saving) {
                    if (latestActive && latestSettings.accountUserId == form.accountUserId) form.cancelChild()
                }
            }
        }
    }
}

@Composable
private fun AutoMediaUnsupportedContent() {
    SettingsGroup("其他媒体与例外") {
        SettingsRow("语音与音乐按需流式加载", summary = "当前页面不提供自动下载开关。")
        SettingsRow("圆视频自动播放暂未支持", summary = "现有规则值会保留，当前不能在此启用。")
        SettingsRow("聊天例外暂未支持编辑", summary = "尚未接入真实聊天选择器；已保存的例外会保留。")
    }
}

@Composable
private fun AutoMediaSizeControl(label: String, bytes: Long, enabled: Boolean, onIndex: (Int) -> Unit) {
    // Only the slider's visual position is approximated; the exact draft value is not rounded.
    val index = (0..99).minBy { abs(autoMediaSizeByIndex(it) - bytes) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsValueLayout(label, autoMediaLimitText(bytes), enabled = enabled)
        Slider(value = index.toFloat(), onValueChange = { if (enabled && it.isFinite()) onIndex(it.roundToInt().coerceIn(0, 99)) },
            enabled = enabled, valueRange = 0f..99f, steps = 98,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                setProgress { value ->
                    if (!enabled || !value.isFinite()) false
                    else { onIndex(value.roundToInt().coerceIn(0, 99)); true }
                }
            }.onPreviewKeyEvent { event ->
                if (!enabled || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed ||
                    event.key != Key.DirectionLeft && event.key != Key.DirectionRight) false
                else {
                    if (event.type == KeyEventType.KeyDown) onIndex((index + if (event.key == Key.DirectionLeft) -1 else 1).coerceIn(0, 99))
                    true
                }
            }.focusable(enabled))
    }
}

/** Keep exact off-scale persisted values visible rather than advertising a rounded limit. */
internal fun autoMediaLimitText(bytes: Long): String = if (bytes % MIB == 0L) "最多 ${bytes / MIB} MB" else "最多 $bytes 字节"
