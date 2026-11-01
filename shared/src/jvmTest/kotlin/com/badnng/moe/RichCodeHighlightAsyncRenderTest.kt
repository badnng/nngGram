package com.badnng.moe

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class RichCodeHighlightAsyncRenderTest {
    @Test
    fun pendingHighlightPaintsPlainTextAndLateResultsNeverCrossCodeBlocks() {
        val source = mutableStateOf(AnnotatedString("val first = 1"))
        val pending = ConcurrentLinkedQueue<CompletableDeferred<List<CodeSyntaxToken>>>()
        var displayed = AnnotatedString("")
        val load: suspend (String, String) -> List<CodeSyntaxToken> = { _, _ ->
            val result = CompletableDeferred<List<CodeSyntaxToken>>()
            pending.add(result)
            result.await()
        }
        ComposePixelScene(300, 80) {
            MiuixTheme {
                val highlighted = rememberHighlightedCodeText(source.value, "kotlin", false, load)
                SideEffect { displayed = highlighted }
                Text(highlighted)
            }
        }.use { scene ->
            scene.settledFrame(2)
            val first = assertNotNull(pending.poll())
            assertEquals(source.value, displayed, "Rendering must proceed while grammar work is pending.")

            source.value = AnnotatedString("val second = 2")
            scene.settledFrame(2)
            val second = assertNotNull(pending.poll())
            assertEquals(source.value, displayed, "Changing the block must immediately reset to its own plain text.")
            first.complete(listOf(CodeSyntaxToken(0, 3, 3)))
            scene.settledFrame(2)
            assertEquals(source.value, displayed, "The old request must not color the new block.")

            second.complete(listOf(CodeSyntaxToken(0, 3, 6)))
            scene.settledFrame(2)
            assertEquals(source.value.text, displayed.text)
            assertEquals(1, displayed.spanStyles.size)

            source.value = AnnotatedString.Builder(source.value).apply {
                addStyle(SpanStyle(color = Color.Transparent), 0, 3)
            }.toAnnotatedString()
            scene.settledFrame(2)
            assertTrue(pending.isEmpty(), "A spoiler annotation update must reuse the existing token analysis.")
            assertEquals(source.value, displayed, "A fresh spoiler mask must also protect already-loaded syntax tokens.")
        }
    }
}
