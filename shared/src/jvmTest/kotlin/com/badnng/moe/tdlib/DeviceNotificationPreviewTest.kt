package com.badnng.moe.tdlib

import org.drinkless.tdlib.TdApi
import kotlin.test.*

class DeviceNotificationPreviewTest {
    @Test fun plainAndNonSpoilerNativeTextRemainLiteralAndUnchanged() {
        val literal = "**native literal** https://example.invalid/a_b?x=* 中文 👩‍💻"
        assertEquals(literal, deviceNotificationPreview(TdApi.FormattedText(literal, emptyArray())))
        assertEquals(literal, deviceNotificationPreview(TdApi.FormattedText(literal,
            arrayOf(TdApi.TextEntity(0, 2, TdApi.TextEntityTypeBold())))))
    }

    @Test fun nativeUtf16RangesHideMultipleOverlappingAndTouchingSpoilersWithoutSplittingEmoji() {
        val text = "A😀 secret B hidden 👩‍💻 Z"
        val secret = text.indexOf("secret")
        val hidden = text.indexOf("hidden")
        val value = TdApi.FormattedText(text, arrayOf(spoiler(secret, 3), spoiler(secret + 2, 4), spoiler(hidden, 6)))
        assertEquals("A😀 ▚▚▚▚▚▚ B ▚▚▚▚▚▚ 👩‍💻 Z", deviceNotificationPreview(value))
        assertEquals(text, value.text, "The native formatted message must never be rewritten by preview projection.")
        assertEquals("A▚▚ Z", deviceNotificationPreview(TdApi.FormattedText("A😀 Z", arrayOf(spoiler(2, 1)))))
    }

    @Test fun invalidOrExcessiveSpoilerMetadataFailsClosedWithBoundedWork() {
        for (entity in listOf(spoiler(-1, 2), spoiler(0, 0), spoiler(1, Int.MAX_VALUE), spoiler(100, 2))) {
            assertEquals("消息含隐藏内容", deviceNotificationPreview(TdApi.FormattedText("private hidden", arrayOf(entity))))
        }
        assertEquals("新消息", deviceNotificationPreview(TdApi.FormattedText("x".repeat(16_385), emptyArray())))
        assertEquals("新消息", deviceNotificationPreview(TdApi.FormattedText("private", Array(2_049) { spoiler(0, 1) })))
    }

    @Test fun photoVideoAnimationAndFileCaptionsUseTheSameNativeEntityProjection() {
        val caption = TdApi.FormattedText("public private", arrayOf(spoiler(7, 7)))
        val contents = listOf(TdApi.MessagePhoto().apply { this.caption = caption },
            TdApi.MessageVideo().apply { this.caption = caption }, TdApi.MessageAnimation().apply { this.caption = caption },
            TdApi.MessageDocument().apply { this.caption = caption }, TdApi.MessageVoiceNote().apply { this.caption = caption },
            TdApi.MessageAudio().apply { this.caption = caption })
        contents.forEach { assertEquals("public ▚▚▚▚▚▚▚", deviceNotificationPreview(deviceNotificationFormattedText(it))) }
    }

    private fun spoiler(offset: Int, length: Int) = TdApi.TextEntity(offset, length, TdApi.TextEntityTypeSpoiler())
}
