package com.badnng.moe

import java.net.URI
import java.lang.Character.UnicodeScript

internal actual fun messageUrlHost(url: String): String? = runCatching {
    val uri = URI(url)
    uri.host ?: uri.rawAuthority?.takeUnless { '@' in it }?.let { authority ->
        if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    }
}.getOrNull()

/** Port of lib_ui 79c846 UrlClickHandler::SuspiciousRanges' hostname rules. */
internal actual fun messageUrlIsSuspicious(url: String): Boolean {
    val host = Regex("^((https?|s?ftp)://)?([^/#:\\?]+)([/#:\\?]|$)", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.get(3) ?: return false
    val latinTld = Regex("^(.*)\\.[a-zA-Z]+$").matches(host)
    if (!latinTld && host.all { it.code < 0x80 }) return false
    fun asciiLetter(code: Int) = code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code
    fun digitOrHyphen(code: Int) = code in '0'.code..'9'.code || code == '-'.code
    fun plainAscii(label: String) = label.codePoints().allMatch { asciiLetter(it) || digitOrHyphen(it) }
    fun labelScript(label: String): UnicodeScript {
        var result = UnicodeScript.COMMON
        val points = label.codePoints().iterator()
        while (points.hasNext()) {
            val code = points.nextInt()
            if (digitOrHyphen(code)) continue
            val script = UnicodeScript.of(code)
            if (script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED || script == UnicodeScript.UNKNOWN ||
                (result != UnicodeScript.COMMON && result != script)) return UnicodeScript.UNKNOWN
            result = script
        }
        return result
    }
    val script = if (latinTld) UnicodeScript.LATIN else labelScript(host.substringAfterLast('.'))
    val asciiOnly = script == UnicodeScript.LATIN || script == UnicodeScript.COMMON || script == UnicodeScript.UNKNOWN
    return host.split('.').any { label ->
        if (!asciiOnly && plainAscii(label)) false
        else label.codePoints().anyMatch { code ->
            !digitOrHyphen(code) && !(if (asciiOnly) asciiLetter(code) else UnicodeScript.of(code) == script)
        }
    }
}
