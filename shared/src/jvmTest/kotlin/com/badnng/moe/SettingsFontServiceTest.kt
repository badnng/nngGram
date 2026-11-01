package com.badnng.moe

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsFontServiceTest {
    @Test
    fun realSkiaCatalogAndComposePreloaderResolveSystemAndAnInstalledFamilyWithoutChangingTheHost() = runBlocking {
        val service = DesktopSettingsFontService()
        val catalog = service.catalog()
        assertTrue(catalog.families.isNotEmpty(), "The rendering host must provide an actual system font family.")
        val choices = listOf(SettingsChatFont(SettingsChatFontMode.SYSTEM_DEFAULT),
            SettingsChatFont(SettingsChatFontMode.INSTALLED, catalog.families.first()))
        for (choice in choices) {
            val loaded = service.load(choice)
            assertEquals(SettingsFontLoadStatus.READY, loaded.status)
            val family = assertNotNull(loaded.family)
            assertNotNull(createFontFamilyResolver().resolve(family).value,
                "The preloaded family must be usable by the actual Compose resolver.")
        }
    }

    @Test
    fun catalogIsBoundedCachedDeduplicatedAndReadOffTheCallingThread() = runBlocking {
        val reads = AtomicInteger()
        val caller = Thread.currentThread()
        val service = DesktopSettingsFontService(familyCount = { MAX_FONT_FAMILIES + 90 }, familyName = { index ->
            assertTrue(Thread.currentThread() !== caller)
            reads.incrementAndGet()
            when (index) { 0 -> "Beta"; 1 -> "beta"; 2 -> "\ninvalid"; else -> "Family$index" }
        })
        val catalog = service.catalog()
        assertTrue(catalog.truncated)
        assertEquals(MAX_FONT_FAMILIES, reads.get())
        assertEquals(MAX_FONT_FAMILIES - 2, catalog.families.size)
        assertEquals(catalog, service.catalog())
        assertEquals(MAX_FONT_FAMILIES, reads.get(), "Opening another chooser must not repeat system enumeration.")
    }

    @Test
    fun absentFamilyNeverCallsFallbackPreloaderAndKeepsItsRequestedIdentity() = runBlocking {
        val font = installed("Missing family")
        val service = DesktopSettingsFontService(hasFamily = { false }, preload = { error("Must not substitute another font") })
        assertEquals(SettingsFontLoad(font, SettingsFontLoadStatus.MISSING), service.load(font))
        assertEquals(SettingsFontLoad(), service.load(SettingsChatFont()))
    }

    @Test
    fun preloadIsSerializedAndReadyCacheHasABoundedNumberOfFamilies() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = AtomicInteger()
        val service = DesktopSettingsFontService(hasFamily = { true }, preload = {
            val running = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, running) }
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            active.decrementAndGet()
            FontFamily.Serif
        })
        val first = async { service.load(installed("First")) }
        started.await()
        val second = async { service.load(installed("Second")) }
        release.complete(Unit)
        assertEquals(SettingsFontLoadStatus.READY, first.await().status)
        assertEquals(SettingsFontLoadStatus.READY, second.await().status)
        assertEquals(1, maximum.get())
        service.load(installed("Second"))
        assertEquals(2, calls.get())
        repeat(MAX_LOADED_FONT_FAMILIES) { service.load(installed("Other$it")) }
        val before = calls.get()
        service.load(installed("First"))
        assertEquals(before + 1, calls.get(), "The oldest family is evicted rather than accumulating forever.")
    }

    @Test
    fun cancelledLoadCannotPopulateTheReadyCacheAndExplicitRetryCanFinish() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val service = DesktopSettingsFontService(hasFamily = { true }, preload = {
            if (calls.incrementAndGet() == 1) { entered.complete(Unit); never.await() }
            FontFamily.Serif
        })
        val pending = launch { service.load(installed("Chosen")) }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(SettingsFontLoadStatus.READY, service.load(installed("Chosen")).status)
        assertEquals(2, calls.get())
    }

    @Test
    fun serviceFailuresAndMismatchedCompletionHaveTypedFallbacksWithoutInventingSuccess() = runBlocking {
        val font = installed("Chosen")
        val failing = DesktopSettingsFontService(hasFamily = { true }, preload = { error("Synthetic failure") })
        assertEquals(SettingsFontLoadStatus.FAILED, failing.load(font).status)
        val wrong = object : SettingsFontService {
            override suspend fun catalog() = SettingsFontCatalog(emptyList())
            override suspend fun load(font: SettingsChatFont) = SettingsFontLoad(installed("Old"), SettingsFontLoadStatus.READY, FontFamily.Serif)
        }
        assertEquals(SettingsFontLoad(font, SettingsFontLoadStatus.FAILED), loadSettingsFontSafely(wrong, font))
        assertFalse(SettingsChatFont(SettingsChatFontMode.INSTALLED, "x".repeat(257)).valid)
    }

    private fun installed(name: String) = SettingsChatFont(SettingsChatFontMode.INSTALLED, name)
}
