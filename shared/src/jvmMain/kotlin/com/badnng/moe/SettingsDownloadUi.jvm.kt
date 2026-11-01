package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badnng.moe.media.LocalNativeDialogOwner
import com.badnng.moe.media.LocalNativeDirectoryPicker
import com.badnng.moe.media.resolveDownloadDirectory
import com.badnng.moe.media.LocalDownloadDestinationRootsResolver
import com.badnng.moe.media.NativeSaveTargets
import com.badnng.moe.media.resolveCheckedDownloadDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun SettingsDownloadControls(generation: Long, accountDataPath: String?) {
    val active = LocalSettingsPageActive.current
    val picker = LocalNativeDirectoryPicker.current
    val access = LocalDownloadPreferencesAccess.current
    val accountUserId = LocalDownloadAccountUserId.current
    val resolveRoots = LocalDownloadDestinationRootsResolver.current
    val hwnd = LocalNativeDialogOwner.current
    val scope = rememberCoroutineScope()
    val nativeTargetRevision by NativeSaveTargets.registry.revision.collectAsState()
    val preferenceRevision = access.revision.collectAsState().value
    val generationOwner = remember(generation, accountDataPath, accountUserId, nativeTargetRevision, active) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val targetBoundary = remember(generationOwner) { NativeSaveTargets.capture() }
    DisposableEffect(generationOwner) { onDispose { generationOwner.set(false) } }
    var preferences by remember { mutableStateOf<SettingsDownloadPreferences?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember(generationOwner) { mutableStateOf(false) }
    var draft by remember(generationOwner) { mutableStateOf<SettingsDownloadPreferences?>(null) }
    var draftExpected by remember(generationOwner) { mutableStateOf<SettingsDownloadPreferences?>(null) }
    var editorOwner by remember(generationOwner) { mutableStateOf(0L) }
    var choosing by remember(generationOwner) { mutableStateOf(false) }
    var roots by remember(accountDataPath, accountUserId, resolveRoots) { mutableStateOf<com.badnng.moe.media.DownloadDestinationRoots?>(null) }
    LaunchedEffect(accountDataPath, accountUserId, resolveRoots) {
        roots = withContext(Dispatchers.IO) { resolveRoots(accountDataPath, accountUserId) }
    }
    LaunchedEffect(active, generationOwner, access, preferenceRevision) {
        if (active) {
            val read = access.read()
            if (!generationOwner.get() || !targetBoundary.current() || access.revision.value != preferenceRevision) return@LaunchedEffect
            when (read) {
                is DownloadPreferencesRead.Value -> { preferences = read.preferences; if (draft == null) error = null }
                DownloadPreferencesRead.Failed -> {
                    if (draft == null) preferences = null
                    error = "无法读取下载设置；请检查本地配置目录权限。"
                }
            }
        } else { draft = null; draftExpected = null; editorOwner++; choosing = false }
    }
    fun save(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences, owner: Long? = null) {
        if (!active || busy) return
        busy = true
        scope.launch {
            val rejected = try {
                withContext(Dispatchers.IO) {
                    if (!desired.askEveryTime) {
                        val directory = resolveCheckedDownloadDirectory(desired, resolveRoots(accountDataPath, accountUserId), targetBoundary)
                        require(directory != null)
                    }
                }
                false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { true }
            if (!generationOwner.get() || !targetBoundary.current()) return@launch
            if (rejected) {
                busy = false
                error = "下载路径未保存：目标位于缓存扫描目录内，或当前原生目录无法核实。原设置保留，请重新选择。"
                return@launch
            }
            val priorRevision = access.revision.value
            val result = access.writeIfCurrent(expected, desired) { generationOwner.get() && targetBoundary.current() }
            if (!generationOwner.get() || !targetBoundary.current()) return@launch
            busy = false
            when (result) {
                DownloadPreferencesWrite.SAVED -> {
                    // Revision-aware stores re-read the newest disk state. A late result
                    // must not overwrite a newer completed writer's displayed value.
                    if (access.revision.value == priorRevision) preferences = desired
                    error = null
                    if (owner != null && owner == editorOwner) { draft = null; draftExpected = null }
                }
                DownloadPreferencesWrite.CONFLICT -> error = "下载设置已在其他位置改变，请重新读取后再保存。"
                DownloadPreferencesWrite.FAILED -> error = "下载设置未保存；原设置保留，请检查配置目录权限。"
            }
        }
    }
    val current = preferences
    SettingsGroup("下载") {
        error?.let { SettingsRow("下载设置", summary = it, enabled = false) }
        if (current != null) {
            SettingsToggle("每次下载都询问保存位置", current.askEveryTime, active && !busy) {
                save(current, current.copy(askEveryTime = it))
            }
            if (!current.askEveryTime) SettingsRow("下载路径", when (current.directory) {
                SettingsDownloadDirectory.DOWNLOADS -> "系统下载目录"
                SettingsDownloadDirectory.ACCOUNT_TEMP -> "账号临时目录"
                SettingsDownloadDirectory.CUSTOM -> "自定义目录"
            }, summary = roots?.let { resolveDownloadDirectory(current, it)?.toString() } ?: "正在读取目录…",
                enabled = active && !busy) { draft = current; draftExpected = current; editorOwner++; error = null }
            SettingsRow("保存行为", summary = "仅在明确保存附件时应用。已有缓存、后台缩略图和流式播放不会弹出保存对话框。")
        }
    }
    val shownDraft = draft
    LocalSettingsSecurityConfirmationHost.current.Content(active && shownDraft != null, title = "下载路径", summary = null, maxWidth = 520.dp, onDismissRequest = {
        if (!busy) { draft = null; draftExpected = null; editorOwner++ }
    }) {
        if (shownDraft != null && current != null) Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsDownloadDirectory.entries.forEach { mode ->
                SettingsRow(when (mode) {
                    SettingsDownloadDirectory.DOWNLOADS -> "系统下载目录"
                    SettingsDownloadDirectory.ACCOUNT_TEMP -> "账号临时目录"
                    SettingsDownloadDirectory.CUSTOM -> "自定义目录"
                }, if (shownDraft.directory == mode) "已选中" else null, enabled = !busy && !choosing) {
                    if (mode != SettingsDownloadDirectory.CUSTOM || shownDraft.customPath.isNotBlank()) draft = shownDraft.copy(directory = mode)
                    else {
                        val owner = editorOwner
                        choosing = true
                        scope.launch {
                            try {
                                val selected = picker(hwnd)
                                if (generationOwner.get() && owner == editorOwner && draft != null && selected != null) {
                                    draft = draft!!.copy(directory = mode, customPath = selected)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (owner == editorOwner) error = "无法打开目录选择器，请重试。" }
                            finally { if (owner == editorOwner) choosing = false }
                        }
                    }
                }
            }
            if (shownDraft.directory == SettingsDownloadDirectory.CUSTOM) {
                SettingsRow("所选目录", summary = shownDraft.customPath)
                SettingsAction("选择其他目录", !busy && !choosing) {
                    val owner = editorOwner
                    choosing = true
                    scope.launch {
                        try {
                            val selected = picker(hwnd)
                            if (generationOwner.get() && owner == editorOwner && draft != null && selected != null) draft = draft!!.copy(customPath = selected)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { if (owner == editorOwner) error = "无法打开目录选择器，请重试。" }
                        finally { if (owner == editorOwner) choosing = false }
                    }
                }
            }
            if (shownDraft.directory == SettingsDownloadDirectory.ACCOUNT_TEMP) SettingsNote(
                if (accountUserId == null || accountUserId <= 0) "尚未取得当前账号身份，暂时不能使用账号临时目录。"
                else "副本位于当前账号 downloads-temp 的身份隔离子目录；保存时仍会校验已知原生扫描根，无法核实时不创建新副本。")
            error?.let { SettingsNote(it, error = true) }
            ChatButton("取消", enabled = !busy, onClick = { draft = null; draftExpected = null; editorOwner++ })
            ChatButton("保存下载路径", enabled = !busy && !choosing && validDownloadPreferences(shownDraft) &&
                roots?.let { resolveDownloadDirectory(shownDraft, it) != null } == true, primary = true) {
                save(draftExpected ?: current, shownDraft, editorOwner)
            }
        }
    }
}
