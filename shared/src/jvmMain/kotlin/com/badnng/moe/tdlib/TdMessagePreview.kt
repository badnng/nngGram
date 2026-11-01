package com.badnng.moe.tdlib

import com.badnng.moe.chat.MessageLinkPreview
import com.badnng.moe.chat.MessageLinkPreviewOptions
import org.drinkless.tdlib.TdApi

internal fun captionAboveMedia(content: TdApi.MessageContent?): Boolean = when (content) {
    is TdApi.MessagePhoto -> content.showCaptionAboveMedia
    is TdApi.MessageVideo -> content.showCaptionAboveMedia
    is TdApi.MessageAnimation -> content.showCaptionAboveMedia
    else -> false
}

internal fun mapLinkPreviewOptions(options: TdApi.LinkPreviewOptions) = MessageLinkPreviewOptions(
    options.isDisabled, options.url.orEmpty(), options.forceSmallMedia, options.forceLargeMedia, options.showAboveText,
)

internal fun TdApi.LinkPreviewOptions.copyPreviewOptions() = TdApi.LinkPreviewOptions(
    isDisabled, url.orEmpty(), forceSmallMedia, forceLargeMedia, showAboveText,
)

internal fun mapLinkPreview(preview: TdApi.LinkPreview) = MessageLinkPreview(
    preview.url.orEmpty(), preview.displayUrl.orEmpty(), preview.siteName.orEmpty(), preview.title.orEmpty(),
    mapRichText(preview.description), preview.showAboveText,
)
