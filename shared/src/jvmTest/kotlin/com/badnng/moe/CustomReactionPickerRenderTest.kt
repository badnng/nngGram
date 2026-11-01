package com.badnng.moe

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.badnng.moe.chat.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class CustomReactionPickerRenderTest {
    @Test
    fun aNativeRecentCustomEmojiDispatchesItsIdentityWhileLockedChoicesStayDisabled() {
        val message = ChatMessage(2, 1)
        val snapshot = ChatSnapshot(token = ChatToken(1, 1, 1), reactionMessageId = 2,
            reactionOptions = MessageReactionOptions(canAdd = true, choices = listOf(
                ReactionChoice(ReactionKey("👍")), ReactionChoice(ReactionKey(customEmojiId = 42), source = ReactionSource.RECENT),
                ReactionChoice(ReactionKey(customEmojiId = 43), needsPremium = true, source = ReactionSource.RECENT))))
        val sent = mutableListOf<Pair<Long, Boolean>>()
        var dismissals = 0
        scene(message, snapshot, ChatFeatureActions(setCustomReaction = { _, _, id, add -> sent += id to add })) { dismissals++ }.use { scene ->
            scene.settledFrame()
            val cells = scene.customCells().sortedBy { it.left }
            assertEquals(2, cells.size)
            scene.click(cells.last())
            scene.settledFrame()
            assertTrue(sent.isEmpty())
            scene.click(cells.first())
            scene.settledFrame()
            assertEquals(listOf(42L to true), sent)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun chosenCustomEmojiStaysRemovableWhenNativeAdditionsAreDisabled() {
        val message = ChatMessage(2, 1, reactions = listOf(MessageReaction(customEmojiId = 42, isChosen = true)))
        val snapshot = ChatSnapshot(token = ChatToken(1, 1, 1), reactionMessageId = 2,
            reactionOptions = MessageReactionOptions(canAdd = false))
        val sent = mutableListOf<Pair<Long, Boolean>>()
        scene(message, snapshot, ChatFeatureActions(setCustomReaction = { _, _, id, add -> sent += id to add })) {}.use { scene ->
            scene.settledFrame()
            scene.click(assertNotNull(scene.customCells().singleOrNull()))
            scene.settledFrame()
            assertEquals(listOf(42L to false), sent)
        }
    }

    @Test
    fun packBrowsingUsesNativeCatalogIdentityAndOnlyPermitsPremiumWhereTheChatAllowsIt() {
        for (premium in listOf(false, true)) {
            val message = ChatMessage(2, 1)
            val snapshot = ChatSnapshot(token = ChatToken(1, 1, 1), reactionMessageId = 2,
                account = AccountProfile(100, "synthetic", isPremium = premium),
                reactionOptions = MessageReactionOptions(listOf("👍"), true, allowCustomEmoji = true),
                reactionPacks = listOf(StickerPack(300, "native pack")), reactionPackId = 300,
                reactionPackEmojis = listOf(ReactionChoice(ReactionKey(customEmojiId = 99), source = ReactionSource.PACK)))
            val requests = mutableListOf<Long?>()
            val sent = mutableListOf<Long>()
            scene(message, snapshot, ChatFeatureActions(loadReactionPacks = { _, id -> requests += id },
                setCustomReaction = { _, _, id, _ -> sent += id })) {}.use { scene ->
                scene.settledFrame()
                scene.click(assertNotNull(scene.semanticsBounds {
                    it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "native pack" }
                }.singleOrNull()))
                scene.settledFrame()
                assertEquals(listOf(null, 300L), requests)
                scene.click(assertNotNull(scene.customCells().singleOrNull()))
                scene.settledFrame()
                assertEquals(if (premium) listOf(99L) else emptyList(), sent)
            }
        }
    }

    @Test
    fun aLargeNativePackRequestsArtworkOnlyForComposedVisibleCells() {
        val message = ChatMessage(2, 1)
        val snapshot = ChatSnapshot(token = ChatToken(1, 1, 1), reactionMessageId = 2,
            reactionOptions = MessageReactionOptions(canAdd = true,
                choices = (1L..300L).map { ReactionChoice(ReactionKey(customEmojiId = it), source = ReactionSource.RECENT) }))
        val resources = mutableSetOf<ReactionKey>()
        scene(message, snapshot, ChatFeatureActions(loadReactionVisual = { _, key -> resources += key })) {}.use { scene ->
            scene.settledFrame()
            assertTrue(resources.size in 1..64, "A 300-entry native list must not decode or download its complete catalog.")
            assertTrue(resources.all { it.customEmojiId in 1L..300L })
        }
    }

    @Test
    fun otherGroupSelectionShowsOnlyJoinedSupergroupsAndKeepsTargetPermissionAndSourceIsolation() {
        for (premium in listOf(false, true)) {
            val message = ChatMessage(2, 1)
            val token = ChatToken(1, 1, 1)
            val state = mutableStateOf(ChatSnapshot(token = token, reactionMessageId = 2,
                account = AccountProfile(100, "synthetic", isPremium = premium),
                reactionOptions = MessageReactionOptions(listOf("👍"), true, allowCustomEmoji = true),
                knownChats = listOf(ChatItem(1, "target group", supergroupId = 101, isJoinedGroup = true),
                    ChatItem(10, "joined source", supergroupId = 110, isJoinedGroup = true),
                    ChatItem(11, "left source", supergroupId = 111),
                    ChatItem(12, "channel source", supergroupId = 112, isJoinedGroup = true, isChannel = true),
                    ChatItem(13, "basic source", basicGroupId = 113, isJoinedGroup = true), ChatItem(14, "private source"))))
            val requests = mutableListOf<Pair<ChatToken, Long?>>()
            val sent = mutableListOf<Triple<ChatToken, Long, Long>>()
            val features = ChatFeatureActions(loadReactionGroupPack = { current, id ->
                requests += current to id
                state.value = state.value.copy(reactionGroupSourceId = id, reactionGroupLoading = id != null,
                    reactionPackEmojis = emptyList(), reactionGroupError = null)
            }, setCustomReaction = { current, targetMessage, emoji, _ -> sent += Triple(current, targetMessage, emoji) })
            ComposePixelScene(320, 440) {
                val controller = remember { ThemeController(ColorSchemeMode.Light) }
                MiuixTheme(controller = controller) { MessageReactionPickerPanel(message, state.value, features, {}, true) }
            }.use { scene ->
                scene.settledFrame()
                scene.click(scene.text("其他群表情"))
                scene.settledFrame()
                for (hidden in listOf("target group", "left source", "channel source", "basic source", "private source"))
                    assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == hidden } }.isEmpty())
                scene.click(scene.text("joined source"))
                scene.settledFrame()
                assertEquals(listOf(token to null, token to 10L), requests)
                assertTrue(scene.customCells().isEmpty())
                val choices = listOf(ReactionChoice(ReactionKey(customEmojiId = 99), source = ReactionSource.PACK))
                state.value = state.value.copy(reactionGroupSourceId = 11, reactionGroupLoading = false, reactionPackEmojis = choices)
                scene.settledFrame()
                assertTrue(scene.customCells().isEmpty(), "A different source callback must never populate the selected group's grid.")
                state.value = state.value.copy(reactionGroupSourceId = 10, reactionGroupPack = StickerPack(300, "source pack"),
                    reactionPackId = 300, reactionPackEmojis = choices)
                scene.settledFrame()
                scene.click(assertNotNull(scene.customCells().singleOrNull()))
                scene.settledFrame()
                assertEquals(if (premium) listOf(Triple(token, 2L, 99L)) else emptyList(), sent)
                scene.click(scene.text("返回群列表"))
                scene.settledFrame()
                assertEquals(token to null, requests.last())
                assertTrue(scene.customCells().isEmpty())
            }
        }
    }

    private fun scene(message: ChatMessage, snapshot: ChatSnapshot, features: ChatFeatureActions, onDismiss: () -> Unit) =
        ComposePixelScene(320, 440) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) { MessageReactionPickerPanel(message, snapshot, features, onDismiss, true) }
        }
    private fun ComposePixelScene.customCells() = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            "自定义表情" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.text(value: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value }
    }.singleOrNull())
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
}
