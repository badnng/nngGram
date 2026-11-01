package com.badnng.moe

import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFolderPreferencesTest {
    @Test
    fun preferencesRoundTripOnlyTheTwoPresentationEnums() {
        val directory = Files.createTempDirectory("nnggram-folder-prefs-test-")
        try {
            val path = directory.resolve("nested/ui-folders.properties")
            val expected = ChatFolderPreferences(FolderTabPlacement.TOP, FolderTabAppearance.ICON_TEXT)
            assertTrue(writeFolderPreferences(path, expected))
            assertEquals(expected, readFolderPreferences(path))
            val values = Properties().apply { Files.newBufferedReader(path).use(::load) }
            assertEquals(setOf("placement", "appearance"), values.stringPropertyNames())
            assertTrue(writeFolderPreferences(path, ChatFolderPreferences()))
            assertEquals(ChatFolderPreferences(), readFolderPreferences(path))
            Files.list(path.parent).use { assertEquals(1L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun missingAndFutureValuesFallBackIndependentlyWithoutCreatingAFile() {
        val directory = Files.createTempDirectory("nnggram-folder-prefs-test-")
        try {
            val path = directory.resolve("ui-folders.properties")
            assertEquals(ChatFolderPreferences(), readFolderPreferences(path))
            assertFalse(Files.exists(path))
            Files.writeString(path, "placement=FUTURE_PLACEMENT\nappearance=ICON_ONLY\n")
            assertEquals(ChatFolderPreferences(FolderTabPlacement.LEFT, FolderTabAppearance.ICON_ONLY), readFolderPreferences(path))
            Files.writeString(path, "placement=TOP\nappearance=FUTURE_APPEARANCE\n")
            assertEquals(ChatFolderPreferences(FolderTabPlacement.TOP, FolderTabAppearance.DEFAULT), readFolderPreferences(path))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun aFailedWriteReturnsFalseAndCleansTheTemporaryFile() {
        val directory = Files.createTempDirectory("nnggram-folder-prefs-test-")
        try {
            val path = Files.createDirectory(directory.resolve("occupied.properties"))
            Files.writeString(path.resolve("keep.txt"), "synthetic")
            assertFalse(writeFolderPreferences(path, ChatFolderPreferences()))
            assertEquals("synthetic", Files.readString(path.resolve("keep.txt")))
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
