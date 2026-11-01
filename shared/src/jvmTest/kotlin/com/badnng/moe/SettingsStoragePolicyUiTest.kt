package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real Miuix controls with fake settings actions; only synthetic render PNGs touch the filesystem. */
class SettingsStoragePolicyUiTest {
    @Test
    fun openingAndCancellingAnEditDoesNotWriteAndKeepsTheExactCustomValues() {
        val values = knownValues(sizeKiB = 411_337, unusedSeconds = 82_800, automatic = false,
            sizeSource = SettingsStoragePolicySource.EXPLICIT, timeSource = SettingsStoragePolicySource.DEFAULT)
        val fixture = Fixture(policySnapshot(values))
        fixture.scene().use { scene ->
            scene.openAndRead(fixture)
            scene.clickText("保留 30 天", fixture)
            assertTrue(scene.semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { "当前自定义值" in it.text }
            }.isNotEmpty(), "An off-grid capacity keeps its custom-value label inside the complete capacity status text.")
            assertEquals(0, fixture.saves.size, "Opening and editing only creates a local draft.")

            scene.clickText("取消更改", fixture)
            assertTrue(fixture.saves.isEmpty(), "Cancel never calls the settings save action.")
            assertTrue(fixture.session.editor(17)!!.draft.empty)
            assertEquals(411_337L, fixture.session.editor(17)!!.baselineValues.sizeKiB.raw)
            assertEquals(82_800L, fixture.session.editor(17)!!.baselineValues.unusedSeconds.effective)
        }
    }

    @Test
    fun aRetentionChangeSubmitsOnlyItsPatchAndSuccessWaitsForNativeReadback() {
        val values = knownValues(sizeKiB = 455_551, unusedSeconds = 82_800, automatic = false,
            sizeSource = SettingsStoragePolicySource.EXPLICIT, timeSource = SettingsStoragePolicySource.DEFAULT)
        val fixture = Fixture(policySnapshot(values))
        fixture.scene().use { scene ->
            scene.openAndRead(fixture)
            scene.clickText("保留 30 天", fixture)
            scene.clickText("保存设置", fixture)
            val first = fixture.saves.single()
            assertEquals(fixture.snapshot.revision, first.revision)
            assertEquals(SettingsStoragePolicyDraft(unusedSeconds = 30L * 24 * 60 * 60), first.draft)
            assertNull(first.draft.sizeKiB, "A TTL edit cannot rewrite the custom capacity.")
            assertTrue(scene.texts("设置已保存，并已由系统读回确认。").isEmpty(), "A button press is not a successful readback.")

            val actual = values.copy(unusedSeconds = explicitNumber(30L * 24 * 60 * 60))
            val completion = confirmed(first, SettingsStoragePolicyRead(actual))
            fixture.complete(first, completion)
            scene.settledFrame()
            assertTrue(scene.texts("设置已保存，并已由系统读回确认。").isNotEmpty())
            assertTrue(fixture.session.editor(17)!!.draft.empty)

            fixture.publishReadback(first, completion)
            scene.settledFrame()
            assertTrue(scene.texts("设置已保存，并已由系统读回确认。").isNotEmpty())
        }
    }

    @Test
    fun theActualCapacitySliderCreatesOnlyAUserSelectedTotalLimit() {
        val values = knownValues(sizeKiB = 501_337, unusedSeconds = 82_800, automatic = false,
            sizeSource = SettingsStoragePolicySource.EXPLICIT, timeSource = SettingsStoragePolicySource.DEFAULT)
        val fixture = Fixture(policySnapshot(values))
        fixture.scene().use { scene ->
            scene.openAndRead(fixture)
            val editor = fixture.session.editor(17)!!
            assertNull(editor.draft.sizeKiB, "Merely displaying the slider must not round the saved value.")
            scene.dragSlider("缓存占用上限", .76f, fixture)
            val changed = editor.draft
            val officialCapacitySteps = (200L..900L step 100L).map { it * 1024L } +
                (1L..10L).map { it * 1024L * 1024L }
            assertTrue(changed.sizeKiB in officialCapacitySteps)
            assertTrue(changed.sizeKiB != values.sizeKiB.effective)
            assertNull(changed.unusedSeconds)
            assertNull(changed.automatic)

            scene.clickText("保存设置", fixture)
            assertEquals(changed, fixture.saves.single().draft)
        }
    }

    @Test
    fun unknownNativeValuesStayUnknownUntilAnExplicitRefresh() {
        val values = knownValues().copy(sizeKiB = SettingsStoragePolicyNumber(source = SettingsStoragePolicySource.UNKNOWN))
        val fixture = Fixture(policySnapshot(values).copy(
            refreshRequired = true,
            readErrors = mapOf(SettingsStoragePolicyField.SIZE to SettingsError.REQUEST_FAILED),
        ))
        fixture.scene().use { scene ->
            scene.openAndRead(fixture)
            assertTrue(scene.texts("当前值未知").isNotEmpty())
            assertTrue(scene.descriptions("缓存占用上限").isEmpty(), "Unknown size has no guessed slider position.")
            assertTrue(scene.disabledText("保存设置"))
            scene.clickText("保存设置", fixture)
            assertTrue(fixture.saves.isEmpty())

            scene.clickText("刷新设置", fixture)
            assertEquals(1, fixture.refreshes.size)
            assertTrue(fixture.snapshot.loading)
            assertTrue(fixture.saves.isEmpty())
            val current = knownValues(sizeKiB = 222_222, sizeSource = SettingsStoragePolicySource.EXPLICIT)
            fixture.publishRead(SettingsStoragePolicyRead(current))
            scene.settledFrame()
            assertTrue(scene.descriptions("缓存占用上限").isNotEmpty())
            assertTrue(fixture.saves.isEmpty(), "Refresh reads current values but does not persist the fallback or a slider value.")
        }
    }

    @Test
    fun partialReadbackPreservesIntentAndDoesNotOverwriteAnUntouchedCapacity() {
        val values = knownValues(sizeKiB = 100L * 1024, unusedSeconds = 82_800, automatic = true,
            sizeSource = SettingsStoragePolicySource.DEFAULT, timeSource = SettingsStoragePolicySource.DEFAULT,
            automaticSource = SettingsStoragePolicySource.EXPLICIT)
        val fixture = Fixture(policySnapshot(values))
        fixture.scene().use { scene ->
            scene.openAndRead(fixture)
            scene.clickText("保留 30 天", fixture)
            scene.clickText("保存设置", fixture)
            val first = fixture.saves.single()
            assertEquals(SettingsStoragePolicyDraft(unusedSeconds = 30L * 24 * 60 * 60), first.draft)
            assertNull(first.draft.automatic)

            // The expiry write landed, and another settings writer changed the untouched size while
            // the controller had temporarily disabled automatic cleanup before a later failure.
            val actual = values.copy(
                sizeKiB = explicitNumber(500L * 1024),
                unusedSeconds = explicitNumber(30L * 24 * 60 * 60),
                automatic = explicitSwitch(false),
            )
            val partial = SettingsStoragePolicyCompletion(first.token, first.requestId,
                SettingsStoragePolicyStatus.INCOMPLETE,
                steps = listOf(
                    SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.DISABLE_AUTOMATIC, accepted = true, confirmed = true),
                    SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.UNUSED_TIME, accepted = true, confirmed = true),
                    SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC, error = SettingsError.REQUEST_FAILED),
                ),
                actual = SettingsStoragePolicyRead(actual), error = SettingsError.REQUEST_FAILED, refreshRequired = true,
            )
            fixture.complete(first, partial)
            scene.settledFrame()
            assertTrue(scene.texts("有些设置未能完成读回确认；草稿已保留，请刷新后重试。").isNotEmpty())
            assertEquals(ToggleableState.On, scene.toggleState("自动清理缓存"),
                "The draft retains its intended automatic-on state while the actual readback is off.")

            fixture.publishReadback(first, partial)
            scene.settledFrame()
            scene.clickText("刷新设置", fixture)
            assertTrue(fixture.snapshot.loading)
            val refreshed = actual.copy(automatic = explicitSwitch(false))
            fixture.publishRead(SettingsStoragePolicyRead(refreshed))
            scene.settledFrame()

            val retry = fixture.session.editor(17)!!.draft
            assertNull(retry.sizeKiB, "The stale untouched 100 MB value must not overwrite the refreshed 500 MB value.")
            assertNull(retry.unusedSeconds, "The first attempt already established the requested retention value.")
            assertEquals(true, retry.automatic, "Retry explicitly restores the intended on state only after refresh.")
            assertEquals(ToggleableState.On, scene.toggleState("自动清理缓存"))
            scene.clickText("保存设置", fixture)
            assertEquals(SettingsStoragePolicyDraft(automatic = true), fixture.saves.last().draft)
        }
    }

    @Test
    fun aHoistedSessionSurvivesUnmountAndOldOwnerReceiptsCannotClearANewRequest() {
        val values = knownValues(sizeKiB = 420L * 1024, unusedSeconds = 82_800, automatic = false,
            sizeSource = SettingsStoragePolicySource.EXPLICIT, timeSource = SettingsStoragePolicySource.DEFAULT)
        val fixture = Fixture(policySnapshot(values))
        fixture.session.updateExpanded(true)
        val old = fixture.scene()
        old.settledFrame()
        old.clickText("保留 30 天", fixture)
        old.clickText("保存设置", fixture)
        val first = fixture.saves.single()
        old.close()
        assertTrue(fixture.session.expanded, "The stable editor remembers that it was open through a tree remount.")

        val closed = fixture.snapshot
        fixture.updateSnapshot { closed.copy(token = SettingsStoragePolicyToken(17, closed.token.owner + 1), opened = false,
            loading = false, saving = false) }
        val reopened = fixture.scene()
        reopened.settledFrame()
        assertTrue(fixture.opens.size >= 2, "The active remount opens a new page owner and fresh native read.")
        val newToken = fixture.snapshot.token
        assertTrue(fixture.snapshot.loading)
        fixture.publishRead(SettingsStoragePolicyRead(values))
        reopened.settledFrame()

        val rejectedOld = SettingsStoragePolicyCompletion(first.token, first.requestId, SettingsStoragePolicyStatus.REJECTED,
            error = SettingsError.NOT_READY, refreshRequired = true)
        fixture.complete(first, rejectedOld)
        reopened.settledFrame()
        assertEquals(newToken, fixture.session.editor(17)!!.token)
        assertEquals(30L * 24 * 60 * 60, fixture.session.editor(17)!!.draft.unusedSeconds)
        assertTrue(reopened.disabledText("保存设置"), "An old-owner draft waits for a fresh baseline before retry.")

        reopened.clickText("刷新设置", fixture)
        assertTrue(fixture.snapshot.loading)
        fixture.publishRead(SettingsStoragePolicyRead(values))
        reopened.settledFrame()
        reopened.clickText("保存设置", fixture)
        val second = fixture.saves.last()
        assertTrue(second.requestId != first.requestId)
        assertEquals(newToken, second.token)
        assertEquals(fixture.snapshot.revision, second.revision)
        assertEquals(SettingsStoragePolicyDraft(unusedSeconds = 30L * 24 * 60 * 60), second.draft)

        // A duplicate late receipt from request 1 cannot settle request 2 or consume its draft.
        fixture.complete(first, confirmed(first, SettingsStoragePolicyRead(values.copy(
            unusedSeconds = explicitNumber(30L * 24 * 60 * 60)))))
        reopened.settledFrame()
        val editor = fixture.session.editor(17)!!
        assertEquals(second.requestId, editor.pending?.requestId)
        assertEquals(second.draft, editor.draft)

        fixture.onUi { fixture.active = false }
        reopened.settledFrame()
        assertEquals(2, fixture.saves.size, "An inactive page cannot submit another settings request.")
        reopened.close()
    }

    @Test
    fun narrowLowLayoutsRemainScrollableAcrossThemesAndDensities() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) {
                val fixture = Fixture(policySnapshot(knownValues()), widthDp = 320, heightDp = 220, density = density, mode = mode)
                fixture.session.updateExpanded(true)
                fixture.scene().use { scene ->
                    scene.revealDescription("缓存占用上限", fixture)
                    assertTrue(scene.visibleDescription("缓存占用上限", fixture))
                    if (density == 1.25f) scene.settledFrame().save("$mode-${density}x-capacity")
                    scene.clickText("保留 30 天", fixture)
                    scene.reveal("保存设置", fixture)
                    assertTrue(scene.visibleText("保存设置", fixture))
                    scene.reveal("取消更改", fixture)
                    assertTrue(scene.visibleText("取消更改", fixture))
                    assertTrue(fixture.saves.isEmpty())
                    if (density == 1.25f) scene.settledFrame().save("$mode-${density}x-actions")
                }
            }
        }
    }

    private data class SaveRequest(
        val token: SettingsStoragePolicyToken,
        val revision: Long,
        val draft: SettingsStoragePolicyDraft,
        val requestId: Long,
        val complete: (SettingsStoragePolicyCompletion) -> Unit,
    )

    private class Fixture(
        initial: SettingsStoragePolicySnapshot,
        val widthDp: Int = 390,
        val heightDp: Int = 430,
        val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
    ) {
        val openingRead = SettingsStoragePolicyRead(initial.values, initial.readErrors)
        var snapshot by mutableStateOf(initial)
        var active by mutableStateOf(true)
        val session = SettingsStoragePolicyUiSession()
        val saves = mutableListOf<SaveRequest>()
        val refreshes = mutableListOf<SettingsStoragePolicyToken>()
        val closes = mutableListOf<SettingsStoragePolicyToken>()
        val opens = CopyOnWriteArrayList<() -> Boolean>()
        val actions = SettingsStoragePolicyActions(
            open = { generation, lease ->
                opens += lease
                if (!snapshot.opened) snapshot = snapshot.copy(
                    token = SettingsStoragePolicyToken(generation, snapshot.token.owner + 1),
                    opened = true, loading = true, saving = false, error = null,
                )
            },
            close = { token ->
                closes += token
                if (snapshot.token == token) snapshot = snapshot.copy(
                    token = token.copy(owner = token.owner + 1), opened = false, loading = false, saving = false,
                )
            },
            refresh = { token ->
                assertEquals(snapshot.token, token)
                assertFalse(snapshot.loading)
                refreshes += token
                snapshot = snapshot.copy(loading = true, error = null)
            },
            save = { token, revision, draft, requestId, complete ->
                saves += SaveRequest(token, revision, draft, requestId, complete)
                snapshot = snapshot.copy(saving = true, steps = emptyList(), lastCompletion = null, error = null)
            },
        )

        fun scene(): ComposePixelScene {
            val pixelWidth = (widthDp * density).roundToInt()
            val pixelHeight = (heightDp * density).roundToInt()
            return ComposePixelScene(pixelWidth, pixelHeight) {
                MiuixTheme(controller = remember { ThemeController(mode) }) {
                    CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active) {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsStoragePolicyControls(snapshot, actions, session)
                        }
                    }
                }
            }
        }

        fun updateSnapshot(transform: (SettingsStoragePolicySnapshot) -> SettingsStoragePolicySnapshot) = onUi {
            snapshot = transform(snapshot)
        }
        fun complete(request: SaveRequest, completion: SettingsStoragePolicyCompletion) = onUi { request.complete(completion) }
        fun publishRead(read: SettingsStoragePolicyRead) = onUi {
            assertTrue(snapshot.opened)
            assertTrue(snapshot.loading, "A fake read completes only after open or explicit refresh requested it.")
            snapshot = snapshot.copy(revision = snapshot.revision + 1, loading = false, saving = false,
                values = read.values, readErrors = read.errors, refreshRequired = !read.complete,
                error = read.errors.values.firstOrNull())
        }
        fun publishReadback(request: SaveRequest, completion: SettingsStoragePolicyCompletion) = onUi {
            val actual = assertNotNull(completion.actual)
            if (snapshot.token == request.token) snapshot = snapshot.copy(revision = snapshot.revision + 1,
                values = actual.values, readErrors = actual.errors, loading = false,
                refreshRequired = completion.refreshRequired || !actual.complete, saving = false,
                steps = completion.steps, error = completion.error, lastCompletion = completion)
        }
        fun onUi(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
        }
    }

    private fun knownValues(
        sizeKiB: Long = 411_337,
        unusedSeconds: Long = 82_800,
        automatic: Boolean = false,
        sizeSource: SettingsStoragePolicySource = SettingsStoragePolicySource.EXPLICIT,
        timeSource: SettingsStoragePolicySource = SettingsStoragePolicySource.DEFAULT,
        automaticSource: SettingsStoragePolicySource = SettingsStoragePolicySource.DEFAULT,
    ) = SettingsStoragePolicyValues(
        sizeKiB = policyNumber(sizeKiB, sizeSource),
        unusedSeconds = policyNumber(unusedSeconds, timeSource),
        automatic = if (automaticSource == SettingsStoragePolicySource.DEFAULT)
            SettingsStoragePolicySwitch(effective = false, source = SettingsStoragePolicySource.DEFAULT)
        else explicitSwitch(automatic),
        fileCount = SettingsStoragePolicyNumber(effective = 40_000, source = SettingsStoragePolicySource.DEFAULT),
        immunitySeconds = SettingsStoragePolicyNumber(effective = 3_600, source = SettingsStoragePolicySource.DEFAULT),
    )

    private fun policyNumber(value: Long, source: SettingsStoragePolicySource) = when (source) {
        SettingsStoragePolicySource.EXPLICIT -> explicitNumber(value)
        SettingsStoragePolicySource.DEFAULT -> SettingsStoragePolicyNumber(effective = value, source = source)
        SettingsStoragePolicySource.UNKNOWN -> SettingsStoragePolicyNumber(source = source)
    }

    private fun explicitNumber(value: Long) = SettingsStoragePolicyNumber(value, value, SettingsStoragePolicySource.EXPLICIT)
    private fun explicitSwitch(value: Boolean) = SettingsStoragePolicySwitch(value, value, SettingsStoragePolicySource.EXPLICIT)

    private fun policySnapshot(values: SettingsStoragePolicyValues) = SettingsStoragePolicySnapshot(
        token = SettingsStoragePolicyToken(17, 1), opened = true, revision = 1, values = values,
    )

    private fun confirmed(request: SaveRequest, actual: SettingsStoragePolicyRead) = SettingsStoragePolicyCompletion(
        token = request.token, requestId = request.requestId, status = SettingsStoragePolicyStatus.CONFIRMED,
        steps = listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.UNUSED_TIME, accepted = true, confirmed = true)),
        actual = actual,
    )

    private fun ComposePixelScene.texts(label: String): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.filter { it.width > 0 && it.height > 0 }

    private fun ComposePixelScene.disabledText(label: String): Boolean = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } &&
            it.getOrNull(SemanticsProperties.Disabled) != null
    }.isNotEmpty()

    private fun ComposePixelScene.descriptions(label: String): List<Rect> = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.width > 0 && it.height > 0 }

    private fun ComposePixelScene.toggleState(label: String): ToggleableState? {
        val states = mutableListOf<ToggleableState>()
        semanticsBounds { config ->
            if (config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }) {
                config.getOrNull(SemanticsProperties.ToggleableState)?.let(states::add)
            }
            false
        }
        return states.singleOrNull()
    }

    private fun ComposePixelScene.openAndRead(fixture: Fixture) {
        settledFrame()
        val opensBefore = fixture.opens.size
        assertTrue(fixture.saves.isEmpty())
        clickText("缓存占用上限和保留期限", fixture)
        assertEquals(opensBefore + 1, fixture.opens.size)
        val openingLease: () -> Boolean = fixture.opens[fixture.opens.size - 1]
        assertTrue(openingLease(), "The actual page lease remains active while its opening read is pending.")
        assertTrue(fixture.snapshot.opened)
        assertTrue(fixture.snapshot.loading)
        assertTrue(texts("正在读取当前清理设置…").isNotEmpty())
        assertTrue(texts("保留 30 天").isEmpty(), "Retention controls wait for the explicit native read result.")
        assertTrue(descriptions("缓存占用上限").isEmpty(), "Loading never supplies a guessed slider position.")
        assertTrue(fixture.saves.isEmpty(), "Opening and waiting for the native read must not save settings.")
        val revisionBeforeRead = fixture.snapshot.revision
        fixture.publishRead(fixture.openingRead)
        settledFrame()
        assertFalse(fixture.snapshot.loading)
        assertEquals(revisionBeforeRead + 1, fixture.snapshot.revision)
        assertEquals(fixture.openingRead.values, fixture.snapshot.values)
        assertEquals(fixture.openingRead.errors, fixture.snapshot.readErrors)
        assertTrue(fixture.saves.isEmpty())
    }

    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val directory = File(System.getProperty("nnggram.storage-policy.screenshots",
            "build/settings-workspace-validation/storage-policy-ui"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        assertTrue(ImageIO.write(image, "png", File(directory, "$name.png")))
    }

    private fun ComposePixelScene.clickText(label: String, fixture: Fixture) {
        settledFrame(2, 16)
        reveal(label, fixture)
        val rect = assertNotNull(visibleTexts(label, fixture).lastOrNull(), "Missing control: $label")
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
        settledFrame(2, 16)
    }

    private fun ComposePixelScene.visibleTexts(label: String, fixture: Fixture): List<Rect> = texts(label).filter {
        it.height >= 12 && it.top >= 1 && it.bottom <= (fixture.heightDp * fixture.density).roundToInt() - 1
    }

    private fun ComposePixelScene.visibleText(label: String, fixture: Fixture) = visibleTexts(label, fixture).isNotEmpty()

    private fun ComposePixelScene.visibleDescription(label: String, fixture: Fixture): Boolean = descriptions(label).any {
        it.height >= 12 && it.top >= 1 && it.bottom <= (fixture.heightDp * fixture.density).roundToInt() - 1
    }

    private fun ComposePixelScene.reveal(label: String, fixture: Fixture) = revealBounds(label, fixture) { texts(label) }

    private fun ComposePixelScene.revealDescription(label: String, fixture: Fixture) = revealBounds(label, fixture) {
        descriptions(label)
    }

    private fun ComposePixelScene.revealBounds(label: String, fixture: Fixture, candidates: () -> List<Rect>) {
        val height = (fixture.heightDp * fixture.density).roundToInt()
        fun visible() = candidates().any { it.width > 0 && it.height >= 12 && it.top >= 1 && it.bottom <= height - 1 }
        fun ranges(): List<Pair<Float, Float>> = buildList {
            semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { add(it.value() to it.maxValue()) }
                false
            }
        }
        val initiallyVisible = visible()
        val initial = ranges()
        var moved = false
        var direction = 5f
        var previousRanges: List<Pair<Float, Float>>? = null
        repeat(60) {
            val current = ranges()
            if (current.zip(initial).any { (now, start) -> abs(now.first - start.first) > .5f }) moved = true
            if (visible()) {
                assertTrue(initiallyVisible || moved, "Clipped control was reached by actual scrolling: $label")
                return
            }
            val scroll = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.height > 20 && it.bottom > 0 && it.top < height }
            val actualBounds = candidates().filter { it.width > 0 && it.height > 0 }
            direction = when {
                actualBounds.any { it.bottom < 1 } -> -5f
                actualBounds.any { it.top >= height } -> 5f
                previousRanges != null && current == previousRanges -> -direction
                else -> direction
            }
            previousRanges = current
            scrollAt(scroll?.center?.x ?: (fixture.widthDp * fixture.density * .7f),
                scroll?.center?.y?.coerceIn(8f, height - 8f) ?: height * .6f, deltaY = direction)
            settledFrame(4, 24)
        }
        assertTrue(visible(), "Unreachable actual control: $label; bounds=${candidates()}; initial=$initial; current=${ranges()}")
    }

    private fun ComposePixelScene.dragSlider(label: String, fraction: Float, fixture: Fixture) {
        revealDescription(label, fixture)
        val bounds = assertNotNull(descriptions(label).lastOrNull { visibleDescription(label, fixture) })
        val start = bounds.left + bounds.width * .15f
        val end = bounds.left + bounds.width * fraction
        moveTo(start, bounds.center.y)
        pressAt(start, bounds.center.y)
        settledFrame(1, 16)
        dragTo(end + 12f, bounds.center.y)
        settledFrame(1, 16)
        dragTo(end, bounds.center.y)
        settledFrame(1, 16)
        releaseAt(end, bounds.center.y)
        settledFrame(2, 16)
    }
}
