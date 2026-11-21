package com.badnng.moe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.badnng.moe.chat.*
import kotlin.test.*

class MessageTextInteractionsTest {
    private val token = ChatToken(4, 9, 2)
    private val entity = MessageEntity(0, 4, MessageEntityKind.TEXT_URL, "https://example.org/actual")
    private val message = ChatMessage(20, 2, text = "查看图片", entities = listOf(entity), canBeSaved = true)
    private val snapshot = ChatSnapshot(enabled = true, token = token, selected = ChatItem(2, "synthetic"), messages = listOf(message))
    private val link = MessageTextLinkRequest(token, 2, 20, message.richText(), entity)

    @Test fun ordinaryVisibleUrlsDoNotGainAConfirmationAndHiddenTitlesDo() {
        assertTrue(messageLinkRequiresConfirmation(link.source, entity, entity.value))
        val url = "https://example.org/actual"
        val visible = RichMessageText(url, listOf(MessageEntity(0, url.length, MessageEntityKind.URL)))
        assertFalse(messageLinkRequiresConfirmation(visible, visible.entities.single(), url))
        val identical = visible.entities.single().copy(kind = MessageEntityKind.TEXT_URL, value = url)
        assertFalse(messageLinkRequiresConfirmation(visible.copy(entities = listOf(identical)), identical, url))
    }

    @Test fun hiddenLinksUseOfficialDomainBoundariesAndShortLinkExceptions() {
        fun requires(url: String, whitelist: Set<String> = OFFICIAL_DEFAULT_LINK_DOMAINS): Boolean {
            val hidden = entity.copy(value = url)
            return messageLinkRequiresConfirmation(message.richText().copy(entities = listOf(hidden)), hidden, url,
                whitelistedDomains = whitelist)
        }
        assertFalse(requires("https://telegram.org/faq"))
        assertFalse(requires("https://help.telegram.org/faq"))
        assertTrue(requires("https://TELEGRA.PH/page"))
        assertTrue(requires("https://te.legra.ph/page"))
        assertFalse(requires("https://sub.telegra.ph/page"))
        assertTrue(requires("https://eviltelegram.org/faq"))
        assertTrue(requires("https://telegram.org.attacker.example/faq"))
        assertTrue(requires("https://t.me/user"))
        assertTrue(requires("https://sub.telegram.me/user"))
        assertTrue(requires("https://telegram.dog/user"))
        assertFalse(requires("https://docs.example.org/a", setOf("example.org")))
        assertTrue(requires("https://telegram.org/faq", emptySet()))
        assertTrue(requires("https://t.me/user", setOf("t.me")))
    }

    @Test fun suspiciousHostnameUsesThePinnedScriptRules() {
        listOf("https://аpple.com", "https://例子.com", "https://раypal.рф").forEach { assertTrue(messageUrlIsSuspicious(it), it) }
        listOf("https://apple.com", "https://xn--pple-43d.com", "https://пример.рф", "https://ascii.рф",
            "https://例子.中国", "https://127.0.0.1", "https://example.org/path/а").forEach { assertFalse(messageUrlIsSuspicious(it), it) }
        val url = "https://аpple.com"
        val source = RichMessageText(url, listOf(MessageEntity(0, url.length, MessageEntityKind.URL)))
        assertTrue(messageLinkRequiresConfirmation(source, source.entities.single(), url))
    }

    @Test fun controlKeyboardFixtureBypassesConfirmationOnlyForThatClick() {
        val pressed = PointerKeyboardModifiers(isCtrlPressed = true)
        val released = PointerKeyboardModifiers()
        assertFalse(messageLinkRequiresConfirmation(link.source, entity, entity.value, pressed.isCtrlPressed))
        assertTrue(messageLinkRequiresConfirmation(link.source, entity, entity.value, released.isCtrlPressed))
    }

    @Test fun linkOpeningRechecksTokenPageMessageAndTheCompleteNativeEntities() {
        assertEquals(entity.value, validatedMessageTextLink(snapshot, link, true))
        assertNull(validatedMessageTextLink(snapshot, link, false))
        assertNull(validatedMessageTextLink(snapshot.copy(enabled = false), link, true))
        assertNull(validatedMessageTextLink(snapshot.copy(token = token.copy(selection = 10)), link, true))
        assertNull(validatedMessageTextLink(snapshot.copy(selected = ChatItem(3, "other")), link, true))
        assertNull(validatedMessageTextLink(snapshot.copy(messages = emptyList()), link, true))
        assertNull(validatedMessageTextLink(snapshot.copy(messages = listOf(message.copy(text = "已被编辑"))), link, true))
        assertNull(validatedMessageTextLink(snapshot.copy(messages = listOf(message.copy(entities = listOf(entity.copy(value = "https://example.org/new"))))), link, true))
        assertNull(validatedMessageTextLink(snapshot, link.copy(entity = entity.copy(value = "https://example.org/new")), true))
    }

    @Test fun spoilerOwnsTheFirstClickAndRevealedLinksKeepTheirNativeEntity() {
        var revealed = false
        var clicked: MessageEntity? = null
        val source = message.richText().copy(entities = listOf(entity, MessageEntity(0, 4, MessageEntityKind.SPOILER)))
        val hidden = displayRichText(source, Color.Black, Color.Blue, Color.Gray,
            onRevealSpoiler = { revealed = true }, onEntityLink = { entity, _ -> clicked = entity })
        val spoiler = assertIs<LinkAnnotation.Clickable>(hidden.getLinkAnnotations(0, 4).single().item)
        spoiler.linkInteractionListener?.onClick(spoiler)
        assertTrue(revealed)
        assertNull(clicked)
        val shown = displayRichText(source, Color.Black, Color.Blue, Color.Gray, revealSpoilers = true,
            onEntityLink = { entity, _ -> clicked = entity })
        val url = assertIs<LinkAnnotation.Url>(shown.getLinkAnnotations(0, 4).single().item)
        url.linkInteractionListener?.onClick(url)
        assertEquals(entity, clicked)
    }

    @Test fun aTextUrlLabelSplitByNativeBlocksStillResolvesItsCompleteOriginalEntity() {
        val text = "第一段\n引用段\n最后一段"
        val original = MessageEntity(0, text.length, MessageEntityKind.TEXT_URL, "https://example.org/original")
        val source = RichMessageText(text, listOf(original, MessageEntity(4, 3, MessageEntityKind.BLOCK_QUOTE)))
        val part = source.displayBlocks().single { it.kind == MessageEntityKind.BLOCK_QUOTE }
        val clipped = part.content.entities.single { it.kind == MessageEntityKind.TEXT_URL }
            .copy(offset = part.sourceOffset)
        assertEquals(original, source.originalLinkEntity(clipped))
        assertNull(source.originalLinkEntity(clipped.copy(value = "https://example.org/replaced")))
    }

    @Test fun copyFreezesTheSelectedOccurrenceAndRestoresOnlySelectedBlockSeparators() {
        val source = RichMessageText("重复\n\n重复\n尾部", listOf(MessageEntity(4, 2, MessageEntityKind.PRE)))
        val item = message.copy(text = source.text, entities = source.entities)
        val display = AnnotatedString(source.text).withQuoteSourceOffsets()
        val request = messageTextCopyRequest(token, item, listOf(display.subSequence(4, 6)))
        val current = snapshot.copy(messages = listOf(item))
        assertEquals("重复", validatedMessageTextCopy(current, request, true)?.text)
        assertEquals(4, request.selectionOffset)
        val blocks = source.displayBlocks().map { it.withoutBlockSeparator(source.text.length) }
        val selected = blocks.map { AnnotatedString(it.content.text).withQuoteSourceOffsets(it.sourceOffset) }
        assertEquals(source, validatedMessageTextCopy(current, messageTextCopyRequest(token, item, selected), true))
        assertEquals(source, validatedMessageTextCopy(current, messageTextCopyRequest(token, item, emptyList()), true))
    }

    @Test fun whitespaceSelectionDoesNotSilentlyCopyTheWholeMessage() {
        val item = message.copy(text = "前文  后文", entities = emptyList())
        val selected = AnnotatedString(item.text).withQuoteSourceOffsets().subSequence(2, 4)
        val request = messageTextCopyRequest(token, item, listOf(selected))
        assertEquals("  ", validatedMessageTextCopy(snapshot.copy(messages = listOf(item)), request, true)?.text)
        assertNull(item.richText().quoteFromSelection(listOf(selected)))
    }

    @Test fun copyRejectsChangedSourcesProtectionOrOwnershipAfterTheMenuOpened() {
        val request = messageTextCopyRequest(token, message, emptyList())
        assertEquals(message.richText(), validatedMessageTextCopy(snapshot, request, true))
        assertNull(validatedMessageTextCopy(snapshot, request, false))
        assertNull(validatedMessageTextCopy(snapshot.copy(token = token.copy(generation = 5)), request, true))
        assertNull(validatedMessageTextCopy(snapshot.copy(selected = snapshot.selected!!.copy(hasProtectedContent = true)), request, true))
        assertNull(validatedMessageTextCopy(snapshot.copy(messages = listOf(message.copy(canBeSaved = false))), request, true))
        assertNull(validatedMessageTextCopy(snapshot.copy(messages = listOf(message.copy(text = "已被编辑"))), request, true))
        assertNull(validatedMessageTextCopy(snapshot.copy(messages = emptyList()), request, true))
        assertNull(validatedMessageTextCopy(snapshot, request.copy(selectionOffset = 1,
            selection = RichMessageText("不属于原文")), true))
        val unlocatedSelection = messageTextCopyRequest(token, message, listOf(AnnotatedString("查看")))
        assertNull(validatedMessageTextCopy(snapshot, unlocatedSelection, true))
    }

    @Test fun translatedCopyUsesTheDisplayedTextAndKeepsRepeatedUtf16SelectionOccurrence() {
        val item = message.copy(text = "原文内容", entities = emptyList())
        val translated = "重复 🐈 重复"
        val displayed = AnnotatedString(translated).withQuoteSourceOffsets()
        val second = displayed.subSequence(6, displayed.length)
        val request = messageTextCopyRequest(token, item, listOf(second), translated)

        assertTrue(request.copyTranslation)
        assertEquals(6, request.selectionOffset)
        assertEquals("重复", validatedMessageTextCopy(snapshot.copy(messages = listOf(item)), request, true, translated)?.text)
        assertEquals(translated, validatedMessageTextCopy(snapshot.copy(messages = listOf(item)),
            messageTextCopyRequest(token, item, emptyList(), translated), true, translated)?.text)
    }

    @Test fun originalSelectionRemainsNativeWhenTranslationAndOriginalAreBothShown() {
        val source = RichMessageText("原文 🐈 原文", emptyList())
        val item = message.copy(text = source.text, entities = source.entities)
        val translated = "translated text"
        val original = AnnotatedString(source.text).withQuoteSourceOffsets().subSequence(0, 5)
        val request = messageTextCopyRequest(token, item, listOf(original), translated, showOriginal = true)

        assertFalse(request.copyTranslation)
        assertEquals("原文 🐈", validatedMessageTextCopy(snapshot.copy(messages = listOf(item)), request, true,
            translated, showOriginal = true)?.text)
    }

    @Test fun translatedSelectionRequiresTheSameDisplayModeAndContent() {
        val item = message.copy(text = "原文", entities = emptyList())
        val translated = "译文"
        val selected = AnnotatedString(translated).withQuoteSourceOffsets()
        val request = messageTextCopyRequest(token, item, listOf(selected), translated, showOriginal = true,
            copyTranslation = true)
        val current = snapshot.copy(messages = listOf(item))

        assertEquals(translated, validatedMessageTextCopy(current, request, true, translated, showOriginal = true)?.text)
        assertNull(validatedMessageTextCopy(current, request, true, translated, showOriginal = false))
        assertNull(validatedMessageTextCopy(current, request, true, "别的译文", showOriginal = true))
        assertNull(validatedMessageTextCopy(current, request, true, null, showOriginal = true))
    }

    @Test fun translatedCopyRetainsProtectionAndNativeSourceOwnershipChecks() {
        val item = message.copy(text = "原文", entities = emptyList())
        val translated = "译文"
        val request = messageTextCopyRequest(token, item, emptyList(), translated)
        val current = snapshot.copy(messages = listOf(item))

        assertEquals(translated, validatedMessageTextCopy(current, request, true, translated)?.text)
        assertNull(validatedMessageTextCopy(current.copy(selected = current.selected!!.copy(hasProtectedContent = true)),
            request, true, translated))
        assertNull(validatedMessageTextCopy(current.copy(token = token.copy(selection = 8)), request, true, translated))
        assertNull(validatedMessageTextCopy(current.copy(messages = listOf(item.copy(text = "已改动"))), request, true, translated))
        assertNull(validatedMessageTextCopy(current.copy(messages = listOf(item.copy(canBeSaved = false))),
            request, true, translated))
    }

    @Test fun interactionDiagnosticsNeverIncludeSourceOrTargets() {
        val copy = messageTextCopyRequest(token, message, emptyList())
        listOf(copy.toString(), link.toString()).forEach { assertFalse("查看图片" in it); assertFalse("example.org" in it) }
    }
}
