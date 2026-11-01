package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.settings.NotificationScope
import com.badnng.moe.settings.NotificationSoundVolumeTarget
import com.badnng.moe.settings.PrivacyBase
import com.badnng.moe.settings.PrivacyKey
import com.badnng.moe.settings.PrivacyRuleKind
import com.badnng.moe.settings.PrivacyRules
import com.badnng.moe.settings.PrivacyDraft
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.settings.SettingsSection
import com.badnng.moe.settings.SettingsSecuritySection
import com.badnng.moe.settings.SettingsSnapshot
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** The enclosing workspace owns scrolling and the engine generation's saveable-state bucket. */
@Composable
internal fun SettingsProfilePage(
    snapshot: SettingsSnapshot,
    actions: SettingsActions,
    chatSnapshot: ChatSnapshot,
    features: ChatFeatureActions,
) {
    val pageActive = LocalSettingsPageActive.current
    val profile = snapshot.profile ?: run {
        if (SettingsSection.PROFILE !in snapshot.loading) SettingsNote("个人资料尚未读取。")
        return
    }
    val generation = snapshot.token.generation
    val enabled = pageActive && snapshot.canEdit(SettingsSection.PROFILE)
    val latestProfileEnabled by rememberUpdatedState(enabled)
    var editing by rememberSaveable(generation, profile.userId) { mutableStateOf(false) }
    var formRevision by rememberSaveable(generation, profile.userId) { mutableStateOf(0L) }
    var editorRevision by rememberSaveable(generation, profile.userId) { mutableStateOf(0L) }
    val latestEditing by rememberUpdatedState(editing)
    val latestEditorRevision by rememberUpdatedState(editorRevision)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestActions by rememberUpdatedState(actions)
    var firstName by rememberSaveable(generation, profile.userId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(profile.firstName))
    }
    var lastName by rememberSaveable(generation, profile.userId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(profile.lastName))
    }
    var username by rememberSaveable(generation, profile.userId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(profile.username))
    }
    var usernameContentRevision by rememberSaveable(generation, profile.userId) { mutableStateOf(0L) }
    var bio by rememberSaveable(generation, profile.userId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(profile.bio))
    }
    // The saveable bucket retains one frozen batch across category changes and width reparenting.
    val saveState = rememberSaveable(generation, profile.userId, saver = SettingsProfileFormSaveState.Saver) {
        SettingsProfileFormSaveState()
    }
    fun begin() {
        if (editing || latestEditing) return
        firstName = TextFieldValue(profile.firstName); lastName = TextFieldValue(profile.lastName)
        username = TextFieldValue(profile.username); bio = TextFieldValue(profile.bio)
        usernameContentRevision = 0L; ++formRevision; ++editorRevision
        saveState.cancel(); editing = true
    }
    fun validForm(): Boolean {
        val currentProfile = latestSnapshot.profile ?: return false
        val namesValid = firstName.text.isNotBlank() && firstName.text.settingsCharacterCount() <= 64 &&
            lastName.text.settingsCharacterCount() <= 64 &&
            (firstName.text + lastName.text).none { it == '\n' || it == '\r' }
        val bioChanged = bio.text != currentProfile.bio
        val bioValid = !bioChanged || currentProfile.bioLengthMax?.let { bio.text.settingsCharacterCount() <= it } == true
        return namesValid && bioValid && bio.text.none { it == '\n' || it == '\r' } &&
            validSettingsUsernameCandidate(settingsUsernameCandidate(username.text))
    }
    fun submit() {
        if (!editing || !latestEditing || !latestProfileEnabled || saveState.pending || !validForm()) return
        val current = latestSnapshot
        val base = current.profile ?: return
        val edits = settingsProfileFormEdits(base, firstName.text, lastName.text, bio.text, username.text)
        if (edits.isEmpty()) return
        saveState.begin(current.token, edits, formRevision)
        saveState.next(current)?.let { latestActions.saveProfile(current.token, it) }
    }
    LaunchedEffect(snapshot, saveState.pending, pageActive) {
        if (saveState.reconcile(snapshot, formRevision) && editing) { editing = false; ++editorRevision }
        if (editing && enabled) saveState.next(snapshot)?.let { actions.saveProfile(snapshot.token, it) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val avatar: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { size ->
            val label = listOf(profile.firstName, profile.lastName).filter(String::isNotBlank).joinToString(" ")
            profile.avatar?.let { avatar ->
                RemoteMedia(MessageMedia(avatar), chatSnapshot, features, visible = pageActive && snapshot.enabled,
                    animate = false, compact = true, modifier = Modifier.size(size).clip(CircleShape),
                    cornerRadius = 0.dp, retainWhenInactive = true, contentScale = ContentScale.Crop, highQuality = true,
                    placeholder = { IdentityAvatarPlaceholder(profile.userId, label, size) })
            } ?: IdentityAvatarPlaceholder(profile.userId, label, size)
        }
        SettingsProfileHeader(profile, avatar)
        val profileEditorOwner = editorRevision
        val profileEditor: @Composable () -> Unit = {
            val owner = profileEditorOwner
            val hint = remember(snapshot.token, profile.userId, profile.username, owner) {
                SettingsUsernameAvailabilityUiState(snapshot.token, profile.userId, profile.username,
                    username.text, usernameContentRevision)
            }
            val inputCurrent = hint.captureInputLease()
            fun interactive() = editing && editorRevision == owner && latestEditing &&
                latestEditorRevision == owner && latestProfileEnabled && inputCurrent()
            val fieldsEnabled = enabled && latestEditing && latestEditorRevision == owner
            val changeFirstName: (TextFieldValue) -> Unit = { next ->
                if (interactive()) { if (next.text != firstName.text) ++formRevision; firstName = next }
            }
            val changeLastName: (TextFieldValue) -> Unit = { next ->
                if (interactive()) { if (next.text != lastName.text) ++formRevision; lastName = next }
            }
            val changeBio: (TextFieldValue) -> Unit = { next ->
                if (interactive()) { if (next.text != bio.text) ++formRevision; bio = next }
            }
            val changeUsername: (TextFieldValue) -> Unit = { next ->
                if (interactive()) {
                    if (next.text != username.text) {
                        hint.changeText(next.text); ++usernameContentRevision; ++formRevision
                    }
                    username = next
                }
            }
            val cancelForm: () -> Unit = {
                if (interactive() && !saveState.pending) {
                    hint.close(); editing = false; ++editorRevision; ++formRevision; saveState.cancel()
                }
            }
            val saveForm: () -> Unit = { if (interactive()) submit() }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(firstName, changeFirstName, label = "名字", singleLine = true, enabled = fieldsEnabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "名字编辑框" })
                TextField(lastName, changeLastName, label = "姓氏（可选）", singleLine = true, enabled = fieldsEnabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "姓氏编辑框" })
                val namesValid = firstName.text.isNotBlank() && firstName.text.settingsCharacterCount() <= 64 &&
                    lastName.text.settingsCharacterCount() <= 64 &&
                    (firstName.text + lastName.text).none { it == '\n' || it == '\r' }
                if (!namesValid) SettingsNote("名字不能为空，名字和姓氏各不超过 64 个字符。", error = true)
                TextField(bio, changeBio, label = "简介", singleLine = true, enabled = fieldsEnabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "简介编辑框" })
                val length = bio.text.settingsCharacterCount()
                val maximum = profile.bioLengthMax
                if (maximum == null) SettingsNote("简介长度限制尚未读取，请刷新后保存简介。")
                else SettingsNote("$length/$maximum", error = length > maximum)
                if (bio.text.any { it == '\n' || it == '\r' }) SettingsNote("简介不能包含换行。", error = true)
                TextField(username, changeUsername, label = "用户名", singleLine = true, enabled = fieldsEnabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "用户名编辑框" })
                val candidate = settingsUsernameCandidate(username.text)
                SettingsNote("使用 5–32 位英文字母、数字和下划线，以字母开头，无需填写 @。留空可移除用户名。",
                    error = !validSettingsUsernameCandidate(candidate))
                SettingsUsernameAvailabilityUi(hint, username.text,
                    pageActive && snapshot.enabled && latestEditing && latestEditorRevision == owner, actions)
                when {
                    saveState.interrupted ->
                        SettingsNote("保存结果尚未确认，请刷新个人资料后检查。你的编辑仍保留。", error = true)
                    saveState.awaiting && SettingsSection.PROFILE !in snapshot.saving &&
                        snapshot.errors[SettingsSection.PROFILE] != null ->
                        SettingsNote("保存结果尚未确认，请刷新个人资料后继续。你的编辑仍保留。", error = true)
                    saveState.pending -> SettingsNote(if (saveState.acceptedCount > 0) "部分更改已保存，正在保存其余更改……" else "正在保存更改……")
                    saveState.failed -> SettingsNote(if (saveState.acceptedCount > 0)
                        "部分更改已保存，其余更改未保存。请检查后重试。" else "更改未保存，请检查后重试。", error = true)
                }
                val changes = settingsProfileFormEdits(profile, firstName.text, lastName.text, bio.text, username.text)
                ProfileEditorActions(fieldsEnabled, !saveState.pending && validForm() && changes.isNotEmpty(),
                    canCancel = !saveState.pending,
                    onCancel = cancelForm, onSave = saveForm)
                val observer = LocalSettingsUsernameEditorUiProbe.current
                val payload = if (observer != null) SettingsUsernameEditorUiProbe(hint, username, usernameContentRevision,
                    changeUsername, saveForm, cancelForm) else null
                SideEffect { if (payload != null && latestEditing && latestEditorRevision == owner) observer?.invoke(payload) }
            }
        }
        SettingsProfileExtrasContent(snapshot.token, profile.userId, snapshot.profileExtras, actions.profileExtras, enabled,
            readEnabled = pageActive, avatar = avatar,
            overview = { avatarCard ->
                SettingsProfileCards(details = {
                    SettingsProfileDetailsCard(profile, enabled, editor = profileEditor.takeIf { editing }) {
                        if (latestProfileEnabled) begin()
                    }
                }, avatar = avatarCard)
            })
        SettingsProfileColorsControls(snapshot.token, profile, snapshot.profileColors, actions.profileColors,
            active = pageActive && snapshot.enabled)
    }
}

@Composable
private fun ProfileEditorActions(enabled: Boolean, canSave: Boolean, canCancel: Boolean = true,
    onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChatButton("取消", Modifier.weight(1f), enabled = enabled && canCancel, onClick = onCancel)
        ChatButton("保存", Modifier.weight(1f), enabled = enabled && canSave, primary = true, onClick = onSave)
    }
}

@Composable
internal fun SettingsNotificationsPage(snapshot: SettingsSnapshot, actions: SettingsActions) {
    val enabled = LocalSettingsPageActive.current && snapshot.canEdit(SettingsSection.NOTIFICATIONS)
    val latestSnapshot = rememberUpdatedState(snapshot)
    val latestActions = rememberUpdatedState(actions)
    val pageActive = LocalSettingsPageActive.current
    val latestActive = rememberUpdatedState(pageActive)
    var soundScope by remember(snapshot.token) { mutableStateOf<NotificationScope?>(null) }
    SettingsReactionNotificationsHost(snapshot.token, snapshot.reactionNotifications, actions.reactionNotifications,
        active = pageActive && snapshot.enabled) { reactionEntry ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SettingsNotificationExceptionControls(snapshot.token, snapshot.notificationExceptions, actions.notificationExceptions,
                active = LocalSettingsPageActive.current && snapshot.enabled,
                soundSnapshot = snapshot.savedNotificationSounds, soundActions = actions.savedNotificationSounds)
            if (!snapshot.notificationExceptions.opened) {
                NotificationScope.entries.forEach { scope ->
                    val value = snapshot.notifications[scope]
                    SettingsGroup(scope.settingsLabel()) {
                        if (value == null) SettingsRow("通知设置尚未读取")
                        else {
                            var audibleSound by rememberSaveable(snapshot.token.generation, scope.name) {
                                mutableStateOf(value.soundId.takeIf { it != 0L } ?: -1L)
                            }
                            LaunchedEffect(value.soundId) { if (value.soundId != 0L) audibleSound = value.soundId }
                            SettingsToggle("显示通知", value.enabled, enabled) { checked ->
                                actions.saveNotifications(snapshot.token, scope, value.copy(muteFor = if (checked) 0 else Int.MAX_VALUE))
                            }
                            SettingsToggle("消息预览", value.showPreview, enabled && value.enabled) { checked ->
                                actions.saveNotifications(snapshot.token, scope, value.copy(showPreview = checked))
                            }
                            SettingsToggle("声音", value.soundEnabled, enabled && value.enabled) { checked ->
                                actions.saveNotifications(snapshot.token, scope, value.copy(soundId = if (checked) audibleSound else 0L))
                            }
                            SettingsAction("选择${scope.settingsLabel()}提示音", enabled = enabled && value.enabled) { soundScope = scope }
                        }
                    }
                }
                reactionEntry()
                SettingsNote("这些设置适用于账号中的私聊、群组和频道。每个聊天的单独通知设置会保留。")
                SettingsContactSignupControls(snapshot.token, snapshot.contactSignup, actions.contactSignup)
                SettingsDeviceNotificationsContent(actions = actions.deviceNotifications)
            }
        }
        val selectedScope = soundScope
        SettingsSavedNotificationSoundPicker(show = selectedScope != null && !snapshot.notificationExceptions.opened,
            accountToken = snapshot.token, snapshot = snapshot.savedNotificationSounds, actions = actions.savedNotificationSounds,
            selectedSoundId = selectedScope?.let { snapshot.notifications[it]?.soundId } ?: -1L,
            onSelected = { id ->
                val current = latestSnapshot.value
                if (latestActive.value && current.enabled && current.token == snapshot.token && soundScope == selectedScope && selectedScope != null &&
                    current.canEdit(SettingsSection.NOTIFICATIONS)) {
                    current.notifications[selectedScope]?.let { value ->
                        latestActions.value.saveNotifications(current.token, selectedScope, value.copy(soundId = id))
                    }
                }
                soundScope = null
            }, onDismiss = { soundScope = null }, active = pageActive && snapshot.enabled,
            volumeTarget = selectedScope?.let { NotificationSoundVolumeTarget.Scope(it) })
    }
}

@Composable
internal fun SettingsPrivacyPage(snapshot: SettingsSnapshot, actions: SettingsActions,
    localLock: com.badnng.moe.settings.LocalLockSnapshot = com.badnng.moe.settings.LocalLockSnapshot(
        phase = com.badnng.moe.settings.LocalLockPhase.UNPROTECTED),
    onOpenLocalLock: (() -> Unit)? = null) {
    val pageActive = LocalSettingsPageActive.current
    val generation = snapshot.token.generation
    val enabled = pageActive && snapshot.canEdit(SettingsSection.PRIVACY)
    var editingKey by rememberSaveable(generation) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(generation, stateSaver = SettingsPrivacyDraftSaver) { mutableStateOf<PrivacyDraft?>(null) }
    var submittedKey by rememberSaveable(generation) { mutableStateOf<String?>(null) }
    var submittedDraftRevision by rememberSaveable(generation) { mutableStateOf(0L) }
    var submittedRevision by rememberSaveable(generation) { mutableStateOf(0L) }
    val completion = snapshot.completedSaveRevision[SettingsSection.PRIVACY] ?: 0L
    val failure = snapshot.failedSaveRevision[SettingsSection.PRIVACY] ?: 0L
    val commonUi = rememberSaveable(generation, saver = PrivacyCommonUiState.Saver) { PrivacyCommonUiState() }

    LaunchedEffect(completion, failure) {
        if (failure > submittedRevision && submittedKey != null) {
            submittedKey = null
        } else if (completion > submittedRevision && submittedKey != null) {
            if (editingKey == submittedKey && draft?.revision == submittedDraftRevision) { editingKey = null; draft = null }
            submittedKey = null
        }
    }

    if (snapshot.security.section != null) {
        SettingsSecurityPage(snapshot, actions)
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup("安全") {
            SettingsRow("两步验证", "管理", enabled = enabled) {
                actions.openSecurity(snapshot.token, SettingsSecuritySection.PASSWORD)
            }
            SettingsRow("已登录设备", "查看", enabled = enabled) {
                actions.openSecurity(snapshot.token, SettingsSecuritySection.SESSIONS)
            }
            SettingsRow("已连接网站", "查看", enabled = enabled) {
                actions.openSecurity(snapshot.token, SettingsSecuritySection.WEBSITES)
            }
            SettingsRow("黑名单", "查看", enabled = enabled) {
                actions.openSecurity(snapshot.token, SettingsSecuritySection.BLOCKED)
            }
            SettingsRow("本地密码", if (localLock.enabled) "开启" else "关闭",
                summary = if (localLock.issue == com.badnng.moe.settings.LocalLockIssue.MIGRATION_UNAVAILABLE)
                    "既有数据的安全迁移暂不可用。" else null,
                enabled = pageActive && !localLock.rootBlocked && onOpenLocalLock != null, onClick = onOpenLocalLock)
        }
        SettingsGroup("隐私") {
            PrivacyKey.entries.forEach { key ->
                val rules = snapshot.privacy[key]
                val error = snapshot.privacyErrors[key]
                SettingsRow(key.settingsLabel(), rules?.base?.settingsLabel() ?: if (error != null) "暂不可用" else "尚未读取",
                    summary = when {
                        error != null -> settingsErrorText(error)
                        rules == null -> null
                        !rules.editable -> "当前规则为只读，请使用官方客户端修改。"
                        else -> privacyExceptionSummary(rules)
                    }, enabled = enabled && rules?.editable == true && (editingKey == null || editingKey == key.name),
                    onClick = if (rules?.editable == true) ({
                        if (editingKey != key.name) {
                            editingKey = key.name
                            draft = PrivacyDraft.from(rules)
                            submittedKey = null
                        }
                    }) else null)
                val currentDraft = draft
                if (editingKey == key.name && currentDraft != null) SettingsPrivacyDraftEditor(snapshot, actions, key,
                    currentDraft, enabled, onChange = { draft = it },
                    onCancel = { editingKey = null; draft = null; submittedKey = null }, onSave = { editor ->
                        submittedKey = key.name
                        submittedDraftRevision = currentDraft.revision
                        submittedRevision = snapshot.startedSaveRevision[SettingsSection.PRIVACY] ?: 0L
                        actions.savePrivacyDraft(snapshot.token, editor, currentDraft)
                    })
            }
        }
        SettingsGroup("消息自动删除") {
            SettingsRow("新聊天自动删除消息", "设置", summary = "为之后创建的聊天设置默认消息保留时长。",
                enabled = enabled) { actions.openSecurity(snapshot.token, SettingsSecuritySection.AUTO_DELETE) }
        }
        PrivacyCommonContent(snapshot.token, snapshot.privacyCommon, actions.privacyCommon, commonUi, enabled)
    }
}

@Composable
internal fun SettingsAccountChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().chatSquircleSurface(Color.Transparent, 15.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, null, enabled = enabled)
        Text(label, fontSize = 14.sp, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
    }
}

private fun SettingsSnapshot.canEdit(section: SettingsSection): Boolean =
    enabled && section in loaded && section !in saving && section !in loading

private fun NotificationScope.settingsLabel(): String = when (this) {
    NotificationScope.PRIVATE_CHATS -> "私聊"
    NotificationScope.GROUP_CHATS -> "群组"
    NotificationScope.CHANNEL_CHATS -> "频道"
}

internal fun PrivacyBase.settingsLabel(): String = when (this) {
    PrivacyBase.EVERYBODY -> "所有人"
    PrivacyBase.CONTACTS -> "我的联系人"
    PrivacyBase.NOBODY -> "没有人"
}

private fun PrivacyKey.settingsLabel(): String = when (this) {
    PrivacyKey.PHONE_NUMBER -> "手机号码"
    PrivacyKey.FIND_BY_PHONE_NUMBER -> "通过手机号找到我"
    PrivacyKey.LAST_SEEN -> "最后上线时间与在线状态"
    PrivacyKey.PROFILE_PHOTO -> "头像"
    PrivacyKey.FORWARDED_LINK -> "转发消息中的个人链接"
    PrivacyKey.CALLS -> "通话"
    PrivacyKey.PEER_TO_PEER_CALLS -> "通话点对点连接"
    PrivacyKey.VOICE_VIDEO_MESSAGES -> "语音与视频消息"
    PrivacyKey.BIO -> "简介"
    PrivacyKey.BIRTHDATE -> "生日"
    PrivacyKey.CHAT_INVITES -> "群组与频道邀请"
}

internal fun PrivacyKey.settingsQuestion(): String = when (this) {
    PrivacyKey.PHONE_NUMBER -> "谁可以看到我的手机号码？"
    PrivacyKey.FIND_BY_PHONE_NUMBER -> "谁可以通过手机号码找到我？"
    PrivacyKey.LAST_SEEN -> "谁可以看到我的最后上线时间与在线状态？"
    PrivacyKey.PROFILE_PHOTO -> "谁可以看到我的头像？"
    PrivacyKey.FORWARDED_LINK -> "谁转发我的消息时可以附上我的个人链接？"
    PrivacyKey.CALLS -> "谁可以给我打电话？"
    PrivacyKey.PEER_TO_PEER_CALLS -> "与谁通话时可以使用点对点连接？"
    PrivacyKey.VOICE_VIDEO_MESSAGES -> "谁可以给我发送语音与视频消息？"
    PrivacyKey.BIO -> "谁可以看到我的简介？"
    PrivacyKey.BIRTHDATE -> "谁可以看到我的生日？"
    PrivacyKey.CHAT_INVITES -> "谁可以将我添加到群组与频道？"
}

private fun privacyExceptionSummary(rules: PrivacyRules): String {
    val categories = buildList {
        fun count(kind: PrivacyRuleKind) = rules.rules.filter { it.kind == kind }.sumOf { it.ids.size }
        count(PrivacyRuleKind.ALLOW_USERS).takeIf { it > 0 }?.let { add("允许 $it 个用户") }
        count(PrivacyRuleKind.RESTRICT_USERS).takeIf { it > 0 }?.let { add("禁止 $it 个用户") }
        count(PrivacyRuleKind.ALLOW_CHAT_MEMBERS).takeIf { it > 0 }?.let { add("允许 $it 个群组的成员") }
        count(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS).takeIf { it > 0 }?.let { add("禁止 $it 个群组的成员") }
        if (rules.rules.any { it.kind == PrivacyRuleKind.ALLOW_PREMIUM }) add("允许高级版用户")
        if (rules.rules.any { it.kind == PrivacyRuleKind.ALLOW_BOTS }) add("允许机器人")
        if (rules.rules.any { it.kind == PrivacyRuleKind.RESTRICT_BOTS }) add("禁止机器人")
        if (rules.rules.any { it.kind == PrivacyRuleKind.RESTRICT_CONTACTS }) add("禁止联系人")
    }
    return if (categories.isEmpty()) "没有指定例外" else "${rules.exceptionCount} 项原生例外：${categories.joinToString("；")}"
}

/** TDLib validates text lengths in Unicode code points rather than UTF-16 code units. */
private fun String.settingsCharacterCount(): Int {
    var index = 0
    var count = 0
    while (index < length) {
        val first = this[index++]
        if (first in '\uD800'..'\uDBFF' && index < length && this[index] in '\uDC00'..'\uDFFF') index++
        count++
    }
    return count
}
