package com.badnng.moe.media

import com.badnng.moe.chat.*

/** Complete Windows executable group from core/mime_type.cpp at Desktop a7f4536. */
internal val officialWindowsExecutableSuffixes = setOf(
    "ad",
    "ade",
    "adp",
    "ahk",
    "app",
    "application",
    "appref-ms",
    "asp",
    "aspx",
    "asx",
    "bas",
    "bat",
    "bin",
    "cab",
    "cdxml",
    "cer",
    "cfg",
    "cgi",
    "chi",
    "chm",
    "cmd",
    "cnt",
    "com",
    "conf",
    "cpl",
    "crt",
    "csh",
    "der",
    "diagcab",
    "dll",
    "drv",
    "eml",
    "exe",
    "fon",
    "fxp",
    "gadget",
    "grp",
    "hlp",
    "hpj",
    "hta",
    "htt",
    "inf",
    "ini",
    "ins",
    "inx",
    "isp",
    "isu",
    "its",
    "jar",
    "jnlp",
    "job",
    "js",
    "jse",
    "jsp",
    "key",
    "ksh",
    "lexe",
    "library-ms",
    "lnk",
    "local",
    "lua",
    "mad",
    "maf",
    "mag",
    "mam",
    "manifest",
    "maq",
    "mar",
    "mas",
    "mat",
    "mau",
    "mav",
    "maw",
    "mcf",
    "mda",
    "mdb",
    "mde",
    "mdt",
    "mdw",
    "mdz",
    "mht",
    "mhtml",
    "mjs",
    "mmc",
    "mof",
    "msc",
    "msg",
    "msh",
    "msh1",
    "msh1xml",
    "msh2",
    "msh2xml",
    "mshxml",
    "msi",
    "msp",
    "mst",
    "ops",
    "osd",
    "paf",
    "pcd",
    "phar",
    "php",
    "php-s",
    "php3",
    "php4",
    "php5",
    "php7",
    "phps",
    "pht",
    "phtml",
    "pif",
    "pl",
    "plg",
    "pm",
    "pod",
    "prf",
    "prg",
    "ps1",
    "ps1xml",
    "ps2",
    "ps2xml",
    "psc1",
    "psc2",
    "psd1",
    "psm1",
    "pssc",
    "pst",
    "py",
    "py3",
    "pyc",
    "pyd",
    "pyi",
    "pyo",
    "pyw",
    "pyz",
    "pyzw",
    "rb",
    "reg",
    "rgs",
    "scf",
    "scr",
    "sct",
    "search-ms",
    "settingcontent-ms",
    "sh",
    "shb",
    "shs",
    "slk",
    "swf",
    "sys",
    "t",
    "tmp",
    "u3p",
    "url",
    "vb",
    "vbe",
    "vbp",
    "vbs",
    "vbscript",
    "vdx",
    "vsd",
    "vsdm",
    "vsdx",
    "vsmacros",
    "vss",
    "vssm",
    "vssx",
    "vst",
    "vstm",
    "vstx",
    "vsw",
    "vsx",
    "vtx",
    "website",
    "wlua",
    "ws",
    "wsc",
    "wsf",
    "wsh",
    "xbap",
    "xll",
    "xlsb",
    "xlsm",
    "xnk",
    "xs",
)

internal fun executableAutoMediaName(name: String): Boolean {
    fun dangerous(value: String): Boolean {
        val dot = value.lastIndexOf('.')
        val separator = maxOf(value.lastIndexOf('/'), value.lastIndexOf('\\'))
        return dot > separator && value.substring(dot + 1).lowercase() in officialWindowsExecutableSuffixes
    }
    val clear = name.map { if (it in "\u200e\u200f\u202a\u202b\u202d\u202e\u2066\u2067") '_' else it }.joinToString("")
    // Classify the actual Windows basename before any protective .download suffix is appended.
    return dangerous(clear) || dangerous(AttachmentFiles.safeName(clear))
}

/** Native image_ PNG is the ordinary-document cache exception represented by this project. */
internal fun ordinaryDocumentCached(media: MessageMedia, file: MediaFile): Boolean =
    media.format == MediaFormat.FILE && file.size in 0L until 10L * MIB &&
        media.mimeType == "image/png" && media.fileName.startsWith("image_")

internal fun automaticMediaDecision(policy: AutoMediaPolicy, chat: ChatItem, message: ChatMessage,
    file: MediaFile, askEveryTime: Boolean, destinationKnown: Boolean): AutoMediaDecision {
    fun denied(reason: AutoMediaDecisionReason) = AutoMediaDecision(cacheReason = reason,
        streamReason = reason, copyReason = reason)
    val source = chat.autoMediaSource() ?: return denied(AutoMediaDecisionReason.INVALID_CONTEXT)
    val media = message.media ?: return denied(AutoMediaDecisionReason.INVALID_CONTEXT)
    if (chat.id != message.chatId || media.file.id != file.id || message.delivery != MessageDelivery.SENT || file.size < 0)
        return denied(AutoMediaDecisionReason.INVALID_CONTEXT)
    if (policy.peers[chat.id] == AutoMediaOverride.DENY) return denied(AutoMediaDecisionReason.PEER_DENIED)
    if (media.format != MediaFormat.PHOTO && executableAutoMediaName(media.fileName))
        return denied(AutoMediaDecisionReason.EXECUTABLE)
    if (!file.downloaded && (!file.canDownload || file.uploading)) return denied(AutoMediaDecisionReason.NATIVE_UNAVAILABLE)
    val type = when (media.format) {
        MediaFormat.PHOTO -> AutoMediaType.PHOTO
        MediaFormat.VIDEO -> AutoMediaType.VIDEO
        MediaFormat.ANIMATION -> AutoMediaType.GIF
        MediaFormat.FILE -> AutoMediaType.FILE
        else -> return denied(AutoMediaDecisionReason.UNSUPPORTED)
    }
    val bySize = policy.allows(source, chat.id, type, file.size)
    val sizeReason = if (bySize) AutoMediaDecisionReason.ALLOWED else AutoMediaDecisionReason.SIZE_OR_DISABLED
    val copyReason = when {
        media.format != MediaFormat.FILE || ordinaryDocumentCached(media, file) -> AutoMediaDecisionReason.UNSUPPORTED
        !bySize -> AutoMediaDecisionReason.SIZE_OR_DISABLED
        !message.canBeSaved || chat.hasProtectedContent -> AutoMediaDecisionReason.SAVE_FORBIDDEN
        askEveryTime -> AutoMediaDecisionReason.ASK_LOCATION
        !destinationKnown -> AutoMediaDecisionReason.DESTINATION_UNKNOWN
        !file.downloaded || file.localPath.isNullOrBlank() -> AutoMediaDecisionReason.NOT_COMPLETED
        else -> AutoMediaDecisionReason.ALLOWED
    }
    val cacheReason = when (media.format) {
        MediaFormat.PHOTO -> sizeReason
        // Should(Document) permits GIF cache independently from the autoplay size.
        MediaFormat.ANIMATION -> AutoMediaDecisionReason.ALLOWED
        // Unknown-size video obtains exact length through finite AUTO windows. Known
        // video uses its independently owned stream, avoiding a second full request.
        MediaFormat.VIDEO -> if (file.size == 0L) sizeReason else AutoMediaDecisionReason.UNSUPPORTED
        MediaFormat.FILE -> when {
            !bySize -> AutoMediaDecisionReason.SIZE_OR_DISABLED
            ordinaryDocumentCached(media, file) -> AutoMediaDecisionReason.ALLOWED
            !message.canBeSaved || chat.hasProtectedContent -> AutoMediaDecisionReason.SAVE_FORBIDDEN
            askEveryTime -> AutoMediaDecisionReason.ASK_LOCATION
            !destinationKnown -> AutoMediaDecisionReason.DESTINATION_UNKNOWN
            else -> AutoMediaDecisionReason.ALLOWED
        }
        else -> AutoMediaDecisionReason.UNSUPPORTED
    }
    val streamReason = if (media.format == MediaFormat.VIDEO || media.format == MediaFormat.ANIMATION)
        sizeReason else AutoMediaDecisionReason.UNSUPPORTED
    return AutoMediaDecision(cacheReason == AutoMediaDecisionReason.ALLOWED,
        streamReason == AutoMediaDecisionReason.ALLOWED, copyReason == AutoMediaDecisionReason.ALLOWED,
        cacheReason, streamReason, copyReason)
}

/** Compatibility entry for the actor's AUTO full-cache lease; external copy uses a separate permit. */
internal fun automaticFileAllowed(policy: AutoMediaPolicy, chat: ChatItem, message: ChatMessage,
    file: MediaFile, askEveryTime: Boolean, destinationKnown: Boolean): Boolean =
    automaticMediaDecision(policy, chat, message, file, askEveryTime, destinationKnown).autoCacheAllowed
