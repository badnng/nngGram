package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalSettingsStorageSnapshot = staticCompositionLocalOf { SettingsStorageSnapshot() }
internal val LocalSettingsStorageActions = staticCompositionLocalOf { SettingsStorageActions() }

/** Workspace navigation closes this visit before an outgoing page can submit again. */
internal class SettingsStoragePageOwner {
    var current by mutableStateOf<SettingsStorageVisit?>(null)
        private set
    var revision by mutableLongStateOf(0L)
        private set

    fun install(visit: SettingsStorageVisit) {
        if (current !== visit) {
            val previous = current
            current = visit
            previous?.close()
        }
    }
    fun owns(visit: SettingsStorageVisit?): Boolean = visit != null && current === visit && visit.active()
    fun remove(visit: SettingsStorageVisit) {
        if (current === visit) current = null
        visit.close()
    }
    fun invalidate() {
        val previous = current
        current = null
        revision++
        previous?.close()
    }
}

internal sealed interface SettingsStorageObservation {
    data object Pending : SettingsStorageObservation
    data class Accepted(val token: SettingsStorageToken) : SettingsStorageObservation
    data object Revoked : SettingsStorageObservation
}

internal class SettingsStorageVisit {
    private val lease = settingsStoragePageLease()
    private var handle: SettingsStorageHandle? = null
    /** Each visit has one bounded state; Revoked is irreversible even across callback races. */
    private val mutableObservation = MutableStateFlow<SettingsStorageObservation>(SettingsStorageObservation.Pending)
    val observations = mutableObservation.asStateFlow()
    /** Engine receives only this raw witness, avoiding a cycle through handle.current(). */
    fun leaseActive(): Boolean = lease.active()
    fun active(): Boolean = leaseActive() && runCatching { handle?.current() ?: true }.getOrDefault(false)
    /** Engine may call from another thread, including before the handle is returned. */
    fun observe(token: SettingsStorageToken?) {
        if (token == null) {
            lease.close()
            mutableObservation.value = SettingsStorageObservation.Revoked
        } else if (lease.active()) {
            mutableObservation.compareAndSet(SettingsStorageObservation.Pending, SettingsStorageObservation.Accepted(token))
        }
    }
    fun install(value: SettingsStorageHandle?) {
        handle = value
        if (!active()) close()
    }
    fun close() {
        lease.close()
        mutableObservation.value = SettingsStorageObservation.Revoked
        val previous = handle
        handle = null
        previous?.close()
    }
}

/** Account-scoped native statistics and cleanup; TDLib owns the scanned paths. */
@Composable
internal fun SettingsStorageControls(snapshot: SettingsStorageSnapshot, actions: SettingsStorageActions,
    pageOwner: SettingsStoragePageOwner = remember { SettingsStoragePageOwner() }, navigationRevision: Long = 0L,
) {
    val active = LocalSettingsPageActive.current
    var requested by remember(snapshot.token.generation, navigationRevision) { mutableStateOf(false) }
    var openFailed by remember(snapshot.token.generation, navigationRevision) { mutableStateOf(false) }
    val currentToken by rememberUpdatedState(snapshot.token)
    var visit by remember(snapshot.token.generation, navigationRevision) { mutableStateOf<SettingsStorageVisit?>(null) }
    val currentVisit by rememberUpdatedState(visit)
    val ownerRevision = pageOwner.revision
    val capturedVisit = visit
    var acceptedToken by remember(capturedVisit) { mutableStateOf<SettingsStorageToken?>(null) }
    var confirmation by remember(snapshot.token, capturedVisit) { mutableStateOf<SettingsStorageCleanup?>(null) }
    val ownsVisit = { pageOwner.owns(capturedVisit) }
    val observed = actions.openOwnedObserved != null
    val volumeOwner = observed && snapshot.opened && capturedVisit?.active() == true && acceptedToken == snapshot.token
    DisposableEffect(capturedVisit) {
        onDispose { capturedVisit?.let(pageOwner::remove) }
    }
    DisposableEffect(Unit) {
        onDispose {
            currentVisit?.let(pageOwner::remove)
            if (actions.openOwnedObserved == null && actions.openOwned == null) actions.close(currentToken)
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            capturedVisit?.let(pageOwner::remove)
            requested = false
            openFailed = false
            confirmation = null
            if (actions.openOwnedObserved == null && actions.openOwned == null) actions.close(currentToken)
        }
    }
    LaunchedEffect(capturedVisit, active) {
        val current = capturedVisit ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        current.observations.first { observation ->
            if (pageOwner.current !== current || currentVisit !== current) true
            else when (observation) {
                SettingsStorageObservation.Pending -> false
                is SettingsStorageObservation.Accepted -> {
                    if (current.active()) acceptedToken = observation.token
                    false
                }
                SettingsStorageObservation.Revoked -> {
                    acceptedToken = null
                    confirmation = null
                    pageOwner.remove(current)
                    visit = null
                    openFailed = true
                    true
                }
            }
        }
    }
    val openStorage = {
        if (active && ownerRevision == pageOwner.revision && !(snapshot.clearing && (!observed || volumeOwner))) {
            requested = true
            openFailed = false
            val next = SettingsStorageVisit()
            pageOwner.install(next)
            if (pageOwner.owns(next)) {
                visit = next
                val openObserved = actions.openOwnedObserved
                val openOwned = actions.openOwned
                when {
                    openObserved != null -> {
                        val handle = runCatching { openObserved(next::leaseActive, next::observe) }.getOrNull()
                        if (handle == null) next.observe(null)
                        next.install(handle)
                    }
                    openOwned != null -> {
                        val handle = runCatching { openOwned(next::leaseActive) }.getOrNull()
                        if (handle == null) next.observe(null)
                        next.install(handle)
                    }
                    else -> if (runCatching { actions.open(next::leaseActive) }.isFailure) next.observe(null)
                }
            }
        }
    }
    SettingsAction("管理本地存储", enabled = active && !(snapshot.clearing && (!observed || volumeOwner)), onClick = openStorage)
    if (!requested || !active) return
    if (openFailed) {
        SettingsGroup("本地存储") {
            SettingsRow("无法打开本地存储", summary = "请重新打开后再读取缓存和设备空间。")
            SettingsAction("重试打开本地存储", onClick = openStorage)
        }
        return
    }
    if (!ownsVisit()) return
    // Legacy previews retain their cache fixture, but cannot imply an accepted native volume.
    val cacheOwner = !observed || volumeOwner
    val busy = snapshot.loading || snapshot.clearing || !cacheOwner
    val details = snapshot.details
    val loaded = snapshot.opened && cacheOwner && details != null
    var savedSelection by remember(snapshot.token, capturedVisit) { mutableStateOf<SettingsStorageSelection?>(null) }
    val validation = settingsStorageSummary(if (loaded) details else null, emptySet())
    val selection = if (details != null && validation.totalBytes != null)
        settingsStorageSelection(savedSelection, details, snapshot.completedClearRevision) else null
    SideEffect { if (selection != null && savedSelection != selection) savedSelection = selection }
    val summary = settingsStorageSummary(if (loaded) details else null, selection?.selected.orEmpty())
    SettingsStorageVolumeGroup(if (volumeOwner) snapshot.volume else if (observed) SettingsStorageVolume.Loading else SettingsStorageVolume.Unavailable,
        enabled = active && ownsVisit() && volumeOwner,
        onRefresh = { if (ownsVisit() && acceptedToken == snapshot.token) actions.refreshVolume(snapshot.token) })
    SettingsGroup("本地媒体缓存") {
        when {
            !cacheOwner -> SettingsRow("正在打开本地存储…")
            snapshot.clearing -> SettingsRow("正在清理所选缓存…")
            snapshot.loading -> SettingsRow("正在读取实际缓存统计…")
        }
        if (cacheOwner && snapshot.refreshAfterClearFailed) SettingsRow("缓存清理已完成", summary = "清理后的统计读取失败；下方是上次读取值，请刷新。")
        if (cacheOwner) snapshot.lastClearError?.let { error ->
            val reason = when (error) {
                SettingsError.FORBIDDEN -> "没有权限完成清理。"
                SettingsError.RATE_LIMITED -> "清理过于频繁。"
                SettingsError.NOT_READY -> "账号或页面已失效。"
                SettingsError.INVALID_VALUE -> "清理请求未被接受。"
                SettingsError.UNSUPPORTED -> "当前清理请求不受支持。"
                SettingsError.CONFLICT -> "清理范围已变化。"
                SettingsError.REQUEST_FAILED -> "清理结果未确认。"
                SettingsError.RESULT_UNKNOWN -> "请求已提交，结果尚未确认；请刷新缓存统计"
            }
            SettingsRow("上次缓存清理未完成", summary = "$reason 部分文件可能已清理，请以重新读取的统计为准。")
        }
        if (cacheOwner) snapshot.error?.let {
            SettingsRow("缓存操作未完成", summary = when (it) {
                SettingsError.CONFLICT -> "统计或选择已变化，请重新读取并确认清理范围。"
                SettingsError.NOT_READY -> "账号或页面已失效，请重新打开。"
                SettingsError.RATE_LIMITED -> "操作过于频繁，请稍后重试。"
                else -> if (snapshot.refreshAfterClearFailed) "无法读取最新缓存统计。" else "未能读取或清理缓存，请检查连接后重试。"
            })
        }
        if (loaded) {
            SettingsRow("缓存总量", summary.totalBytes?.let(::formatSettingsBytes) ?: "未知",
                summary.totalCount?.let { "$it 个文件 · 实际统计" } ?: "统计无效，请刷新。")
            SettingsStorageSelectionSummary(summary)
            if (selection != null) details.categories.filter { it.count > 0 || it.bytes > 0 }.forEach { category ->
                if (category.type.removable) SettingsToggle(
                    "${category.type.label} · ${formatSettingsBytes(category.bytes)} · ${category.count} 个",
                    category.type in selection.selected, enabled = !busy && category.count > 0,
                ) { enabled ->
                    if (ownsVisit()) savedSelection = settingsStorageSelection(savedSelection, details,
                        snapshot.completedClearRevision).toggle(category.type, enabled)
                }
                else SettingsRow(category.type.label, formatSettingsBytes(category.bytes), "${category.count} 个；TDLib 不允许清除此类型。")
            }
            val chosen = details.categories.filter { it.type in selection?.selected.orEmpty() && it.type.removable && it.count > 0 }
            SettingsAction("清理所选缓存", enabled = !busy && !snapshot.refreshAfterClearFailed && !snapshot.needsFreshStatistics && chosen.isNotEmpty(), destructive = true) {
                if (ownsVisit() && !snapshot.needsFreshStatistics && selection != null) {
                    val current = settingsStorageSelection(savedSelection, details, snapshot.completedClearRevision)
                    val frozen = details.categories.filter { it.type in current.selected && it.type.removable && it.count > 0 }
                    if (frozen.isNotEmpty()) confirmation = SettingsStorageCleanup(snapshot.token, snapshot.revision, frozen.toList())
                }
            }
        } else if (cacheOwner && !snapshot.loading && !snapshot.clearing) {
            SettingsRow("缓存总量", "未知", "尚无可用统计。")
            SettingsStorageSelectionSummary(summary)
        }
        SettingsAction("刷新缓存统计", enabled = snapshot.opened && !busy && cacheOwner) {
            if (ownsVisit() && cacheOwner) actions.refresh(snapshot.token)
        }
        SettingsNote("清理所选类型的本地文件，不删除云端消息。保存在缓存目录中的副本或附件也可能被清理。")
    }
    val target = confirmation
    LocalSettingsSecurityConfirmationHost.current.Content(active && target != null, title = "清理所选缓存", summary = null, maxWidth = 520.dp, onDismissRequest = { if (!busy) confirmation = null }) {
        if (target != null) Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsNote("将清理以下类型的本地文件。统计读取后文件可能变化，实际范围和删除量以处理结果为准。")
            target.categories.forEach { SettingsRow(it.type.label, formatSettingsBytes(it.bytes), "${it.count} 个文件") }
            SettingsRow("所选范围", formatSettingsBytes(target.bytes), "${target.count} 个文件")
            SettingsNote("云端消息保留；文件能否重新下载取决于其来源。需要长期保留的副本请存放在缓存目录之外。")
            ChatButton("取消", enabled = !busy, onClick = { confirmation = null })
            ChatButton("确认清理所选缓存", enabled = !busy && !snapshot.needsFreshStatistics && !snapshot.refreshAfterClearFailed &&
                snapshot.token == target.token && snapshot.revision == target.revision,
                onClick = {
                    if (ownsVisit() && !snapshot.needsFreshStatistics && !snapshot.refreshAfterClearFailed &&
                        snapshot.token == target.token && snapshot.revision == target.revision) {
                        confirmation = null
                        actions.clear(target.token, target.revision, target.types)
                    }
                })
        }
    }
}

@Composable
private fun SettingsStorageVolumeGroup(value: SettingsStorageVolume, enabled: Boolean, onRefresh: () -> Unit) {
    val actual = (value as? SettingsStorageVolume.Available)?.takeIf(::validSettingsStorageVolume)
    SettingsGroup("文件目录所在位置") {
        SettingsRow("系统报告总容量", actual?.totalBytes?.let(::formatSettingsBytes) ?: "未知")
        SettingsRow("当前用户可用", actual?.usableBytes?.let(::formatSettingsBytes) ?: "未知")
        if (value is SettingsStorageVolume.Unavailable || value is SettingsStorageVolume.Available && actual == null)
            SettingsNote("无法读取文件目录所在位置的系统空间。")
        if (actual != null) {
            val available = formatSettingsBytes(actual.usableBytes)
            val total = formatSettingsBytes(actual.totalBytes)
            val fraction = (actual.usableBytes.toDouble() / actual.totalBytes.toDouble()).toFloat()
            val modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = "文件目录所在位置，系统报告总容量 $total，当前用户可用 $available" }
            if (fraction == 0f) Box(modifier.height(6.dp)
                .chatSquircleBackground(MiuixTheme.colorScheme.secondaryContainer, 3.dp)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(0f, 0f..1f) })
            else LinearProgressIndicator(modifier = modifier, progress = fraction)
        }
        SettingsNote("采样值会随存储和用户配额变化。")
        val loading = value is SettingsStorageVolume.Loading
        SettingsAction(if (loading) "正在等待设备空间结果…" else if (actual == null) "重试设备空间" else "刷新设备空间",
            enabled = enabled && !loading, onClick = onRefresh)
    }
}

@Composable
private fun SettingsStorageSelectionSummary(summary: SettingsStorageSummary) {
    val selected = summary.selectedBytes
    val percent = summary.percentTenths
    val description = when {
        selected == null -> "占比未知"
        summary.totalBytes == 0L -> "${summary.selectedCount} 个文件 · 暂无缓存量可比较"
        percent != null -> "${summary.selectedCount} 个文件 · 约 ${percent / 10}.${percent % 10}%"
        else -> "占比未知"
    }
    SettingsRow("已选缓存", selected?.let(::formatSettingsBytes) ?: "未知", description)
    val fraction = summary.fraction
    if (fraction != null) {
        val accessibility = "已选缓存 ${formatSettingsBytes(checkNotNull(selected))}，缓存总量 ${formatSettingsBytes(checkNotNull(summary.totalBytes))}，$description"
        val modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = accessibility }
        // The Miuix indicator draws a minimum foreground dot at zero. A plain track
        // preserves the meaningful distinction between no selected bytes and some.
        if (fraction == 0f) Box(modifier.height(6.dp)
            .chatSquircleBackground(MiuixTheme.colorScheme.secondaryContainer, 3.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(0f, 0f..1f) })
        else LinearProgressIndicator(modifier = modifier, progress = fraction)
    }
}

internal interface SettingsStoragePageLease { fun active(): Boolean; fun close() }
internal expect fun settingsStoragePageLease(): SettingsStoragePageLease
