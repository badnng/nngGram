package com.badnng.moe.settings

/**
 * An optional synchronous native clipboard capability for an explicitly requested proxy QR.
 * [modules] is a row-major QR matrix without its quiet zone. Preparation never touches the
 * clipboard, retains no input and queues no work; the caller closes the returned object.
 */
interface SettingsProxyQrClipboard {
    fun prepareProxyQr(size: Int, modules: BooleanArray): SettingsProxyQrPrepared?
}

/** A short-lived encoded image, usable only inside the owner's synchronous commit closure. */
interface SettingsProxyQrPrepared {
    /** Immediately before native publication the host must call [isCurrent]. */
    fun publishIfCurrent(isCurrent: () -> Boolean): Boolean
    fun close()
}

/** Prepares bounded text outside the share owner's lock without touching the clipboard. */
interface SettingsProxyTextClipboard {
    fun prepareProxyText(value: String): SettingsProxyTextPrepared?
}

/** A short-lived encoded text payload, usable only in the owner's synchronous commit closure. */
interface SettingsProxyTextPrepared {
    fun publishIfCurrent(isCurrent: () -> Boolean): Boolean
    fun close()
}
