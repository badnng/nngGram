package com.badnng.moe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.RichMessageText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsFontHostRenderTest {
    @Test
    fun explicitRetryOfTheSameSavedFamilyRecoversFallbackWithoutRecreatingItsSubtree() {
        val font = SettingsChatFont(SettingsChatFontMode.INSTALLED, "Temporarily unavailable")
        val prefs = SettingsPreferences(chatFont = font)
        var reload by mutableStateOf(0L)
        var available = false
        var identity: Any? = null
        val observed = AtomicReference<SettingsFontLoad>()
        val service = object : SettingsFontService {
            override suspend fun catalog() = SettingsFontCatalog(listOf(font.familyName!!))
            override suspend fun load(font: SettingsChatFont) = if (available)
                SettingsFontLoad(font, SettingsFontLoadStatus.READY, FontFamily.Serif)
            else SettingsFontLoad(font, SettingsFontLoadStatus.MISSING)
        }
        ComposePixelScene(300, 150) {
            MiuixTheme {
                SettingsFontHost(prefs, service = service, reloadKey = reload) {
                    val retained = remember { Any() }
                    val state = LocalSettingsFontLoad.current
                    SideEffect {
                        if (identity == null) identity = retained else assertSame(identity, retained)
                        observed.set(state)
                    }
                    Text("Recovery sample")
                }
            }
        }.use { scene ->
            scene.settledFrame()
            assertEquals(SettingsFontLoadStatus.MISSING, observed.get().status)
            available = true
            reload++
            scene.settledFrame()
            assertEquals(SettingsFontLoadStatus.READY, observed.get().status)
            assertEquals(font, observed.get().requested)
            assertEquals(FontFamily.Serif, observed.get().family)
        }
    }

    @Test
    fun fontRemeasureKeepsNativeEntitiesCodeOverrideAndAnActualMouseSelection() {
        val content = RichMessageText("Native text selection remains available", listOf(
            MessageEntity(0, 6, MessageEntityKind.TEXT_URL, "https://example.invalid/"),
            MessageEntity(22, 7, MessageEntityKind.CODE)))
        val selection = SelectionState()
        var font by mutableStateOf(SettingsFontLoad())
        var measured: TextLayoutResult? = null
        ComposePixelScene(600, 200) {
            MiuixTheme {
                CompositionLocalProvider(LocalSettingsFontLoad provides font) {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        RichMessageBody(content, selectionState = selection, onTextLayout = { measured = it })
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val layout = assertNotNull(measured)
            val bounds = scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == content.text }
            }.single()
            fun point(offset: Int): Offset = layout.getCursorRect(offset).let { Offset(bounds.left + it.left, bounds.top + it.center.y) }
            val start = point(12)
            val end = point(21)
            scene.moveTo(start.x, start.y); scene.pressAt(start.x, start.y)
            scene.dragTo(end.x, end.y); scene.releaseAt(end.x, end.y); scene.settledFrame()
            val before = selection.selectedTexts.map { it.text }
            assertEquals(content.text.substring(12, 21), before.joinToString(""))
            val annotated = layout.layoutInput.text
            assertTrue(annotated.spanStyles.any { it.start == 22 && it.end == 29 && it.item.fontFamily == FontFamily.Monospace })
            font = SettingsFontLoad(SettingsChatFont(SettingsChatFontMode.INSTALLED, "Synthetic Serif"),
                SettingsFontLoadStatus.READY, FontFamily.Serif)
            scene.settledFrame()
            assertEquals(before, selection.selectedTexts.map { it.text })
            assertEquals(annotated, assertNotNull(measured).layoutInput.text,
                "Family changes belong to layout style; entities and UTF-16 annotations stay identical.")
            assertEquals(FontFamily.Serif, assertNotNull(measured).layoutInput.style.fontFamily)
            assertEquals(13.sp, assertNotNull(measured).layoutInput.style.fontSize)
        }
    }

    @Test
    fun explicitFamilyReachesActualMiuixAndChatTextWithoutChangingDarkAccentOrDraftIdentity() {
        var preferences by mutableStateOf(SettingsPreferences())
        val observation = AtomicReference<Observation>()
        val service = immediateService()
        ComposePixelScene(500, 300) {
            val controller = rememberSettingsThemeController(ColorSchemeMode.Dark, SettingsAccentColor.ORANGE)
            MiuixTheme(controller = controller) {
                val outerPrimary = MiuixTheme.colorScheme.primary
                val outerSurface = MiuixTheme.colorScheme.surface
                val outerMode = MiuixTheme.colorSchemeMode
                val outerStyle = MiuixTheme.textStyles.main
                SettingsFontHost(preferences, service = service) {
                    val draft = remember { TextFieldValue("原文 🙂 retained", TextRange(2, 4)) }
                    val identity = remember { Any() }
                    val chatStyle = chatMessageTextStyle()
                    val innerPrimary = MiuixTheme.colorScheme.primary
                    val innerSurface = MiuixTheme.colorScheme.surface
                    val innerMode = MiuixTheme.colorSchemeMode
                    val uiStyle = MiuixTheme.textStyles.main
                    val state = LocalSettingsFontLoad.current
                    SideEffect {
                        assertEquals(outerPrimary, innerPrimary)
                        assertEquals(outerSurface, innerSurface)
                        assertEquals(outerMode, innerMode)
                        observation.set(Observation(identity, draft, chatStyle, uiStyle, outerStyle, state))
                    }
                    Column {
                        Text("Normal UI sample")
                        RichMessageBody(RichMessageText("Native message 中文 🙂"))
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val initial = observation.get()
            assertEquals(initial.outerStyle, initial.uiStyle, "Default does not replace Miuix typography.")
            assertEquals(13.sp, initial.chatStyle.fontSize)
            preferences = preferences.copy(chatFont = SettingsChatFont(SettingsChatFontMode.INSTALLED, "Synthetic Serif"))
            scene.settledFrame()
            val selected = observation.get()
            assertEquals(SettingsFontLoadStatus.READY, selected.state.status)
            assertEquals(FontFamily.Serif, selected.chatStyle.fontFamily)
            assertEquals(FontFamily.Serif, selected.uiStyle.fontFamily)
            assertEquals(initial.chatStyle.fontSize, selected.chatStyle.fontSize)
            assertEquals(initial.uiStyle.fontSize, selected.uiStyle.fontSize)
            assertSame(initial.identity, selected.identity)
            assertEquals(initial.draft, selected.draft)
            val layouts = mutableListOf<TextLayoutResult>()
            scene.unmergedSemanticsBounds { semantics ->
                val text = semantics.getOrNull(SemanticsProperties.Text).orEmpty()
                if (text.any { it.text == "Native message 中文 🙂" || it.text == "Normal UI sample" })
                    semantics.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                false
            }
            assertEquals(2, layouts.size)
            layouts.forEach { assertEquals(FontFamily.Serif, it.layoutInput.style.fontFamily) }
            preferences = SettingsPreferences()
            scene.settledFrame()
            assertEquals(initial.chatStyle, observation.get().chatStyle)
            assertEquals(initial.uiStyle, observation.get().uiStyle)
            assertSame(initial.identity, observation.get().identity)
        }
    }

    @Test
    fun lateNonCancellableOldLoadCannotReplaceTheNewSelectionOrReviveAnInactiveOwner() {
        val slow = SettingsChatFont(SettingsChatFontMode.INSTALLED, "Slow")
        val fast = SettingsChatFont(SettingsChatFontMode.INSTALLED, "Fast")
        var preferences by mutableStateOf(SettingsPreferences(chatFont = slow))
        var active by mutableStateOf(true)
        val waiting = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val observed = AtomicReference<SettingsFontLoad>()
        val service = object : SettingsFontService {
            override suspend fun catalog() = SettingsFontCatalog(emptyList())
            override suspend fun load(font: SettingsChatFont): SettingsFontLoad {
                if (font == slow) withContext(NonCancellable) { started.complete(Unit); waiting.await() }
                return SettingsFontLoad(font, SettingsFontLoadStatus.READY,
                    if (font == slow) FontFamily.Serif else FontFamily.SansSerif)
            }
        }
        ComposePixelScene(300, 150) {
            MiuixTheme {
                SettingsFontHost(preferences, active, service) {
                    val state = LocalSettingsFontLoad.current
                    SideEffect { observed.set(state) }
                    Text("Loading ownership sample")
                }
            }
        }.use { scene ->
            scene.settledFrame()
            assertTrue(started.isCompleted)
            preferences = preferences.copy(chatFont = fast)
            scene.settledFrame()
            assertEquals(fast, observed.get().requested)
            assertEquals(FontFamily.SansSerif, observed.get().family)
            active = false
            scene.settledFrame()
            waiting.complete(Unit)
            scene.settledFrame()
            assertEquals(fast, observed.get().requested)
            assertNotEquals(FontFamily.Serif, observed.get().family)
        }
    }

    private fun immediateService() = object : SettingsFontService {
        override suspend fun catalog() = SettingsFontCatalog(emptyList())
        override suspend fun load(font: SettingsChatFont) = if (font.mode == SettingsChatFontMode.DEFAULT_OPEN_SANS)
            SettingsFontLoad(font) else SettingsFontLoad(font, SettingsFontLoadStatus.READY, FontFamily.Serif)
    }

    private data class Observation(val identity: Any, val draft: TextFieldValue, val chatStyle: TextStyle,
        val uiStyle: TextStyle, val outerStyle: TextStyle, val state: SettingsFontLoad)
}
