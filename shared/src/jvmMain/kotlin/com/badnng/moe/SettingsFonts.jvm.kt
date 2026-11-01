package com.badnng.moe

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle as ComposeFontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FontMgr
import kotlin.coroutines.coroutineContext

internal actual fun platformSettingsFontService(): SettingsFontService = desktopSettingsFontService
private val desktopSettingsFontService: SettingsFontService = DesktopSettingsFontService()

/** Same Skia font source as Compose. No AWT font database, OS writes or font installation. */
@OptIn(ExperimentalTextApi::class)
internal class DesktopSettingsFontService(
    private val familyCount: () -> Int = { FontMgr.default.familiesCount },
    private val familyName: (Int) -> String = { FontMgr.default.getFamilyName(it) },
    private val hasFamily: (String) -> Boolean = { name -> FontMgr.default.matchFamily(name).use { it.count() > 0 } },
    private val preload: suspend (SettingsChatFont) -> FontFamily = ::preloadDesktopSettingsFont,
) : SettingsFontService {
    private val worker = Semaphore(1)
    private var installed: SettingsFontCatalog? = null
    private val families = linkedMapOf<SettingsChatFont, SettingsFontLoad>()

    override suspend fun catalog(): SettingsFontCatalog = withContext(Dispatchers.Default) {
        worker.withPermit { installed ?: enumerate().also { installed = it } }
    }

    private suspend fun enumerate(): SettingsFontCatalog {
        val count = familyCount()
        val names = ArrayList<String>()
        for (index in 0 until count.coerceIn(0, MAX_FONT_FAMILIES)) {
            coroutineContext.ensureActive()
            val name = familyName(index)
            if (isValidSettingsFontName(name)) names += name
        }
        return SettingsFontCatalog(names.distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
            truncated = count > MAX_FONT_FAMILIES)
    }

    override suspend fun load(font: SettingsChatFont): SettingsFontLoad {
        if (font.mode == SettingsChatFontMode.DEFAULT_OPEN_SANS && font.valid) return SettingsFontLoad(font)
        return withContext(Dispatchers.Default) {
            worker.withPermit {
                coroutineContext.ensureActive()
                families[font]?.let { return@withPermit it }
                if (!font.valid) return@withPermit SettingsFontLoad(font, SettingsFontLoadStatus.FAILED)
                try {
                    // matchFamily is exact and cannot silently substitute an unrelated font.
                    if (font.mode == SettingsChatFontMode.INSTALLED && !hasFamily(font.familyName!!)) {
                        return@withPermit SettingsFontLoad(font, SettingsFontLoadStatus.MISSING)
                    }
                    val family = preload(font)
                    coroutineContext.ensureActive()
                    SettingsFontLoad(font, SettingsFontLoadStatus.READY, family).also {
                        while (families.size >= MAX_LOADED_FONT_FAMILIES) families.remove(families.keys.first())
                        families[font] = it
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SettingsFontLoad(font, SettingsFontLoadStatus.FAILED)
                }
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
private suspend fun preloadDesktopSettingsFont(font: SettingsChatFont): FontFamily {
    val family = if (font.mode == SettingsChatFontMode.SYSTEM_DEFAULT) FontFamily.Default else FontFamily(font.familyName!!)
    val resolver = createFontFamilyResolver()
    resolver.preload(family)
    // Generic families have no preload list; resolve the four faces used by normal UI/messages here.
    for (weight in listOf(FontWeight.Normal, FontWeight.SemiBold)) for (style in listOf(ComposeFontStyle.Normal, ComposeFontStyle.Italic)) {
        coroutineContext.ensureActive()
        resolver.resolve(family, weight, style).value
    }
    return family
}

internal const val MAX_FONT_FAMILIES = 4096
internal const val MAX_LOADED_FONT_FAMILIES = 16
