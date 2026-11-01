package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual Compose controls and pointer/scroll events; every native response is synthetic. */
class SettingsPrivacyExceptionsUiTest {
    @Test
    fun hidingBeforeVoiceEligibilityCompletesCancelsTheKnownOpeningRequestAndCannotCloseANewerOne() {
        val fixture = Fixture(delayOpening = true)
        fixture.onUi { fixture.key = PrivacyKey.VOICE_VIDEO_MESSAGES }
        fixture.scene().use { scene ->
            scene.settledFrame()
            val first = fixture.openingRequests.single()
            fixture.onUi { fixture.active = false }
            scene.settledFrame()
            assertTrue(first.requestId in fixture.cancelledOpenings)
            assertTrue(fixture.peerRequests.isEmpty())
            fixture.onUi { fixture.active = true }
            scene.settledFrame()
            val replacement = fixture.openingRequests.last()
            assertNotEquals(first.requestId, replacement.requestId)
            fixture.onUi { first.result(SettingsResult(first.token)) }
            scene.settledFrame()
            assertTrue(first.token in fixture.closed)
            assertTrue(replacement.token !in fixture.closed)
            assertTrue(fixture.peerRequests.isEmpty())
            fixture.onUi { replacement.result(SettingsResult(replacement.token)) }
            scene.settledFrame()
            scene.clickText("始终允许", fixture)
            assertEquals(replacement.token, scene.awaitQuery(fixture).editor)
            assertTrue(fixture.saves.isEmpty())
        }
    }

    @Test
    fun aFullUserOrGroupBudgetStillAllowsMovingTheSameNativeIdentityAcrossLists() {
        for (isGroup in listOf(false, true)) {
            val rules = if (isGroup) PrivacyRules(PrivacyBase.CONTACTS, listOf(
                PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, (-19L..-1L).toList()),
                PrivacyRule(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, listOf(-20)),
                PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS)))
            else PrivacyRules(PrivacyBase.CONTACTS, listOf(
                PrivacyRule(PrivacyRuleKind.ALLOW_USERS, (1L..999L).toList()),
                PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(1000)),
                PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS)))
            val fixture = Fixture(initialRules = rules)
            val identity = PrivacyPeerId(if (isGroup) -20 else 1000, isGroup)
            val title = if (isGroup) "Synthetic group already counted in deny" else "Synthetic user already counted in deny"
            fixture.scene().use { scene ->
                scene.settledFrame()
                scene.clickText("始终允许", fixture)
                fixture.reply(scene.awaitQuery(fixture), SettingsPrivacyPeers(listOf(SettingsPrivacyPeer(identity, title, isGroup)), false))
                scene.settledFrame()
                scene.clickText(title, fixture)
                scene.clickText("保存选择", fixture)
                assertTrue(identity in fixture.draft.selected(PrivacyExceptionTarget.ALLOW))
                assertTrue(fixture.draft.selected(PrivacyExceptionTarget.DENY).isEmpty())
                assertEquals(if (isGroup) 20 else 1000, fixture.draft.selected(PrivacyExceptionTarget.ALLOW).size)
                assertNotNull(fixture.draft.replacement(PrivacyKey.LAST_SEEN))
                assertTrue(fixture.saves.isEmpty())
            }
        }
    }

    @Test
    fun cancellingAChildSelectionLeavesItsParentUnchangedAndParentCancellationRetiresTheOwner() {
        val fixture = Fixture()
        val original = fixture.draft
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("始终允许", fixture)
            val pending = scene.awaitQuery(fixture)
            fixture.reply(pending, peers("Synthetic conflicting user", 51))
            scene.settledFrame()
            scene.clickText("Synthetic conflicting user", fixture)
            scene.clickText("取消选择", fixture)
            assertEquals(original, fixture.draft)
            assertEquals(0, fixture.changes)
            assertTrue(fixture.saves.isEmpty())
            assertTrue(fixture.oldBaseWrites.isEmpty())
            assertTrue(fixture.peerRequests.any { it.query.source == PrivacyPeerSource.SELECTED && it.query.selected.isEmpty() },
                "Disposing the picker must invalidate its actor query without saving a rule.")
            fixture.reply(pending, peers("Late cancelled identity", 99))
            scene.settledFrame()
            assertTrue(scene.texts("Late cancelled identity").isEmpty())
            scene.clickText("取消", fixture)
            assertEquals(1, fixture.parentCancels)
            assertTrue(fixture.closed.contains(fixture.opened.single()))
            assertEquals(original, fixture.draft)
            assertTrue(fixture.saves.isEmpty())
        }
    }

    @Test
    fun theRealPrivacyPageSubmitsOnlyAfterParentSaveAndRetainsItsDraftAfterFailure() {
        val fixture = Fixture(page = true, height = 900)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("最后上线时间与在线状态", fixture)
            assertEquals(PrivacyKey.LAST_SEEN, fixture.opened.single().key)
            scene.clickText("始终允许", fixture)
            fixture.reply(scene.awaitQuery(fixture), peers("Synthetic conflicting user", 51))
            scene.settledFrame()
            scene.clickText("Synthetic conflicting user", fixture)
            scene.clickText("保存选择", fixture)
            assertTrue(fixture.saves.isEmpty(), "Child Save must update only the parent draft.")
            assertTrue(fixture.oldBaseWrites.isEmpty())
            scene.clickText("保存", fixture)
            val submitted = fixture.saves.single()
            assertEquals(fixture.settings.token, submitted.settings)
            assertEquals(PrivacyKey.LAST_SEEN, submitted.editor.key)
            assertEquals(listOf(11L, 51L), submitted.draft.allowUsers)
            assertTrue(submitted.draft.denyUsers.isEmpty(), "Selecting an opposite-list user must move that actual identity.")
            assertEquals(listOf(-20L), submitted.draft.allowChats)
            assertEquals(fixture.initialRules, submitted.draft.source)
            scene.clickText("保存", fixture)
            assertEquals(1, fixture.saves.size, "The real page cannot duplicate a pending native parent submission.")

            fixture.failSave()
            scene.settledFrame()
            scene.clickText("保存", fixture)
            assertEquals(2, fixture.saves.size, "Failure retains the parent draft for an explicit retry.")
            assertEquals(submitted.draft, fixture.saves.last().draft)
            assertTrue(fixture.oldBaseWrites.isEmpty(), "Exception editing must use its rich draft action, not the old base-only action.")

            fixture.failSave(SettingsError.CONFLICT)
            scene.settledFrame()
            scene.scrollTo("原生规则已变化，当前草稿仍保留。请取消本次编辑并重新读取后再修改。", fixture)
            assertNotNull(scene.text("原生规则已变化，当前草稿仍保留。请取消本次编辑并重新读取后再修改。"))
            scene.clickText("保存", fixture)
            assertEquals(2, fixture.saves.size, "A native source conflict retains the draft but requires explicit reload before writing.")
        }
    }

    @Test
    fun cancelledQueriesAndAnEarlierPrivacyKeyCannotReplaceTheCurrentPicker() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("始终允许", fixture)
            val oldQuery = scene.awaitQuery(fixture)
            scene.clickText("取消选择", fixture)
            scene.clickText("始终允许", fixture)
            val currentQuery = scene.awaitQuery(fixture)
            assertTrue(currentQuery.query.queryRevision > oldQuery.query.queryRevision)
            fixture.reply(currentQuery, peers("Current picker identity", 61))
            fixture.reply(oldQuery, peers("Late old query identity", 62))
            scene.settledFrame()
            assertNotNull(scene.text("Current picker identity"))
            assertTrue(scene.texts("Late old query identity").isEmpty())

            val oldEditor = fixture.opened.single()
            fixture.onUi { fixture.key = PrivacyKey.PROFILE_PHOTO }
            scene.settledFrame()
            assertTrue(fixture.closed.contains(oldEditor))
            assertTrue(scene.texts("Current picker identity").isEmpty())
            scene.clickText("始终允许", fixture)
            val newKeyQuery = scene.awaitQuery(fixture)
            assertEquals(PrivacyKey.PROFILE_PHOTO, newKeyQuery.editor.key)
            assertTrue(newKeyQuery.editor != oldEditor)
            fixture.reply(currentQuery, peers("Late former key identity", 63))
            fixture.reply(newKeyQuery, peers("Current profile identity", 64))
            scene.settledFrame()
            assertNotNull(scene.text("Current profile identity"))
            assertTrue(scene.texts("Late former key identity").isEmpty())
            assertTrue(fixture.saves.isEmpty())
        }
    }

    @Test
    fun realPickerAndParentActionsRemainReachableInWideNarrowAndLowScaledViewports() {
        for ((width, height, density) in listOf(Triple(1040, 800, 1f), Triple(390, 360, 1.25f), Triple(640, 360, 1f))) {
            val fixture = Fixture(width = width, height = height, density = density)
            fixture.scene().use { scene ->
                scene.settledFrame()
                scene.clickText("始终允许", fixture)
                val title = "合成联系人姓名及群组成员说明用于检查窄窗口中的真实长文本换行"
                fixture.reply(scene.awaitQuery(fixture), peers(title, 51))
                scene.settledFrame()
                scene.scrollTo(title, fixture)
                val titleBounds = scene.text(title)
                assertTrue(titleBounds.width > 0 && titleBounds.height > 0)
                assertTrue(titleBounds.left >= 0 && titleBounds.right <= width)
                scene.click(titleBounds)
                scene.settledFrame()
                scene.scrollTo("保存选择", fixture)
                scene.assertActionInsideViewport("保存选择", fixture)
                scene.settledFrame().save("picker-${width}x$height-${density}")
                scene.clickText("保存选择", fixture)
                assertEquals(listOf(11L, 51L), fixture.draft.allowUsers)
                assertTrue(fixture.draft.denyUsers.isEmpty())
                assertTrue(fixture.saves.isEmpty())
                scene.scrollTo("保存", fixture)
                scene.assertActionInsideViewport("保存", fixture)
                scene.settledFrame().save("parent-${width}x$height-${density}")
                scene.clickText("保存", fixture)
                assertEquals(1, fixture.saves.size)
                assertEquals(fixture.draft, fixture.saves.single().draft)
            }
        }
    }

    private data class PeerRequest(val editor: PrivacyEditorToken, val query: PrivacyPeerQuery,
        val result: (SettingsResult<SettingsPrivacyPeers>) -> Unit)
    private data class Save(val settings: SettingsToken, val editor: PrivacyEditorToken, val draft: PrivacyDraft)
    private data class OpeningRequest(val requestId: Long, val token: PrivacyEditorToken,
        val result: (SettingsResult<PrivacyEditorToken>) -> Unit)
    private class Fixture(val width: Int = 640, val height: Int = 900, val density: Float = 1f, val page: Boolean = false,
        val initialRules: PrivacyRules = PrivacyRules(PrivacyBase.CONTACTS, listOf(
            PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11)),
            PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, listOf(-20)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(51)),
            PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS), PrivacyRule(PrivacyRuleKind.RESTRICT_ALL))),
        val delayOpening: Boolean = false,
    ) {
        var key by mutableStateOf(PrivacyKey.LAST_SEEN)
        var draft by mutableStateOf(assertNotNull(PrivacyDraft.from(initialRules)))
        var settings by mutableStateOf(SettingsSnapshot(token = SettingsToken(17, 1), enabled = true,
            loaded = setOf(SettingsSection.PRIVACY), privacy = PrivacyKey.entries.associateWith { initialRules }))
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var changes = 0
        var parentCancels = 0
        val opened = mutableListOf<PrivacyEditorToken>()
        val closed = mutableListOf<PrivacyEditorToken>()
        val openingRequests = mutableListOf<OpeningRequest>()
        val cancelledOpenings = mutableListOf<Long>()
        val peerRequests = mutableListOf<PeerRequest>()
        val queries = LinkedBlockingQueue<PeerRequest>()
        val saves = mutableListOf<Save>()
        val oldBaseWrites = mutableListOf<Pair<PrivacyKey, PrivacyBase>>()
        private var nextOwner = 1L
        val actions = SettingsActions(
            openPrivacyEditor = { settingsToken, privacyKey, requestId, result ->
                val token = PrivacyEditorToken(settingsToken.generation, nextOwner++, privacyKey)
                opened += token
                openingRequests += OpeningRequest(requestId, token, result)
                if (!delayOpening) result(SettingsResult(token))
            },
            cancelPrivacyEditorOpening = { _, requestId -> cancelledOpenings += requestId },
            closePrivacyEditor = { _, token -> closed += token },
            privacyPeers = { _, editor, query, result ->
                val request = PeerRequest(editor, query, result)
                peerRequests += request
                if (query.source != PrivacyPeerSource.SELECTED || query.selected.isNotEmpty()) queries.offer(request)
            },
            savePrivacy = { _, privacyKey, base -> oldBaseWrites += privacyKey to base },
            savePrivacyDraft = { settingsToken, editor, value ->
                saves += Save(settingsToken, editor, value)
                settings = settings.copy(saving = setOf(SettingsSection.PRIVACY), startedSaveRevision =
                    mapOf(SettingsSection.PRIVACY to ((settings.startedSaveRevision[SettingsSection.PRIVACY] ?: 0L) + 1)))
            })

        fun reply(request: PeerRequest, peers: SettingsPrivacyPeers) = onUi { request.result(SettingsResult(peers)) }
        fun failSave(error: SettingsError = SettingsError.REQUEST_FAILED) = onUi {
            settings = settings.copy(saving = emptySet(), failedSaveRevision = settings.startedSaveRevision,
                errors = mapOf(SettingsSection.PRIVACY to error))
        }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene() = ComposePixelScene(width, height) {
            val holder = rememberSaveableStateHolder()
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active) {
                MiuixTheme(controller = theme) {
                    if (mounted) holder.SaveableStateProvider("privacy") {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(16.dp)) {
                            if (page) SettingsPrivacyPage(settings, actions)
                            else SettingsPrivacyDraftEditor(settings, actions, key, draft,
                                enabled = active && SettingsSection.PRIVACY !in settings.saving,
                                onChange = { changes++; draft = it },
                                onCancel = { parentCancels++; mounted = false },
                                onSave = { actions.savePrivacyDraft(settings.token, it, draft) })
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }
    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).firstOrNull(), label)
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
    private fun ComposePixelScene.clickText(label: String, fixture: Fixture) {
        scrollTo(label, fixture)
        click(text(label))
        settledFrame()
    }
    private fun ComposePixelScene.awaitQuery(fixture: Fixture): PeerRequest {
        val deadline = System.nanoTime() + 3_000_000_000L
        do {
            fixture.queries.poll()?.let { return it }
            settledFrame(2, 16)
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        return assertNotNull(fixture.queries.poll(), "The actual debounced picker query must start.")
    }
    private fun ComposePixelScene.scrollTo(label: String, fixture: Fixture) {
        fun visible() = texts(label).any { it.width > 0 && it.height > 0 && it.top >= 8 && it.bottom <= fixture.height - 8 }
        repeat(60) {
            if (visible()) return
            val candidates = texts(label)
            val direction = if (candidates.any { it.width > 0 && it.height > 0 && it.bottom < 8 }) -5f else 5f
            // The outer gutter belongs to the actual page scroller, outside the picker's inner list.
            scrollAt(fixture.width - 6f, fixture.height * .55f, deltaY = direction)
            settledFrame(5, 32)
        }
        assertTrue(visible(), "Privacy action is unreachable at ${fixture.width}×${fixture.height}/${fixture.density}: $label; ${texts(label)}")
    }
    private fun ComposePixelScene.assertActionInsideViewport(label: String, fixture: Fixture) {
        val bounds = text(label)
        assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= fixture.width)
        assertTrue(bounds.top >= 0 && bounds.bottom <= fixture.height,
            "The actual $label action must remain visible after real page scrolling.")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-privacy-exceptions-validation/frames/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(image, "png", output)
    }
    private fun peers(title: String, id: Long) = SettingsPrivacyPeers(listOf(SettingsPrivacyPeer(PrivacyPeerId(id, false), title)), false)
}
