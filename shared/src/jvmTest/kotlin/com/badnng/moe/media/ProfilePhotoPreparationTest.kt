package com.badnng.moe.media

import com.badnng.moe.ProfilePhotoPreviewOwner
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.*
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.file.Files
import kotlin.test.*

class ProfilePhotoPreparationTest {
    @Test fun allEightExifOrientationsUseTheActualOrientedPixels() {
        val source = byteArrayOf(20, 0, 0, -1, 60, 0, 0, -1, 100, 0, 0, -1, -116, 0, 0, -1)
        val expected = mapOf(
            EncodedOrigin.TOP_LEFT to listOf(20, 60, 100, 140), EncodedOrigin.TOP_RIGHT to listOf(60, 20, 140, 100),
            EncodedOrigin.BOTTOM_RIGHT to listOf(140, 100, 60, 20), EncodedOrigin.BOTTOM_LEFT to listOf(100, 140, 20, 60),
            EncodedOrigin.LEFT_TOP to listOf(20, 100, 60, 140), EncodedOrigin.RIGHT_TOP to listOf(100, 20, 140, 60),
            EncodedOrigin.RIGHT_BOTTOM to listOf(140, 60, 100, 20), EncodedOrigin.LEFT_BOTTOM to listOf(60, 140, 20, 100),
        )
        expected.forEach { (origin, red) ->
            val result = cropAreaPixels(source, 2, 2, origin, 2, 2, ProfilePhotoCrop(), 2)
            assertEquals(red, (result.indices step 4).map { result[it].toInt() and 255 }, origin.name)
        }
    }
    @Test fun cropUsesPositivePixelAreaAndFlattensPremultipliedTransparencyOnWhite() {
        val source = byteArrayOf(100, 0, 0, -128)
        val result = cropAreaPixels(source, 1, 1, EncodedOrigin.TOP_LEFT, 1, 1, ProfilePhotoCrop(), 1)
        assertEquals(listOf(227, 127, 127, 255), result.map { it.toInt() and 255 })
        val checker = ByteArray(16 * 16 * 4) { index -> if (index % 4 == 3 || (index / 4 % 16 + index / 64) % 2 == 0) -1 else 0 }
        val average = cropAreaPixels(checker, 16, 16, EncodedOrigin.TOP_LEFT, 16, 16, ProfilePhotoCrop(), 1)
        assertEquals(listOf(128, 128, 128, 255), average.map { it.toInt() and 255 })
    }
    @Test fun preparedJpegIsBoundedSquareAndChangedPreviewBytesAreRejected() {
        val file = Files.createTempFile("synthetic-profile-source-", ".png")
        try {
            Files.write(file, png(64, 32))
            val source = ProfilePhotoPreparation.read(file.toString())
            val photo = ProfilePhotoPreparation.prepare(source, ProfilePhotoCrop(x = 0f, zoom = 2f))
            val target = java.nio.file.Path.of(photo.path)
            assertEquals(16, photo.dimension)
            assertTrue(photo.size in 1..ProfilePhotoPreparation.MAX_OUTPUT_BYTES)
            assertTrue(validatePreparedProfilePhoto(photo))
            val retained = assertNotNull(photo.lease.retain())
            photo.lease.release()
            assertTrue(Files.exists(target), "UI disposal must not remove a retained upload.")
            Files.write(target, byteArrayOf(0, 1, 2))
            assertFalse(validatePreparedProfilePhoto(photo))
            retained.release()
            assertFalse(Files.exists(target))
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun invalidInputAndNonFiniteCropCannotCreateAProfileUpload() {
        val file = Files.createTempFile("synthetic-profile-invalid-", ".png")
        try {
            Files.write(file, byteArrayOf(1, 2, 3))
            assertFails { ProfilePhotoPreparation.read(file.toString()) }
            Files.write(file, png(15, 16))
            assertFails { ProfilePhotoPreparation.read(file.toString()) }
            Files.write(file, png(16, 16))
            val source = ProfilePhotoPreparation.read(file.toString())
            listOf(ProfilePhotoCrop(Float.NaN), ProfilePhotoCrop(zoom = 0f), ProfilePhotoCrop(x = 2f)).forEach { crop ->
                assertFails { ProfilePhotoPreparation.prepare(source, crop) }
            }
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun disposedPreviewOwnerRejectsLatePreparationAndReleasesEachHandleOnce() {
        var released = 0
        fun photo() = SettingsPreparedProfilePhoto("synthetic.jpg", 1, 1, "00".repeat(32), ownedAttachmentLease { released++ })
        val owner = ProfilePhotoPreviewOwner()
        val first = photo()
        assertTrue(owner.replace(first)); assertTrue(owner.replace(first))
        assertEquals(0, released)
        assertTrue(owner.replace(photo()))
        assertEquals(1, released)
        owner.close(); owner.close()
        assertEquals(2, released)
        assertFalse(owner.replace(photo()))
        assertEquals(3, released)
    }
    @Test fun birthdayCalendarRetainsUnknownYearAndExistingFutureDateWithoutAllowingNewFutureDate() {
        val today = SettingsCalendarDate(25, 10, 2026)
        assertTrue(SettingsBirthdate(29, 2).isValidProfileBirthdate(today))
        assertFalse(SettingsBirthdate(29, 2, 1900).isValidProfileBirthdate(today))
        assertTrue(SettingsBirthdate(29, 2, 2000).isValidProfileBirthdate(today))
        assertFalse(SettingsBirthdate(26, 10, 2026).isValidProfileBirthdate(today))
        assertTrue(SettingsBirthdate(26, 10, 2026).isValidProfileBirthdate(today, SettingsBirthdate(27, 10, 2026)))
        assertFalse(SettingsBirthdate(28, 10, 2026).isValidProfileBirthdate(today, SettingsBirthdate(27, 10, 2026)))
        assertFalse(SettingsBirthdate(1, 1, 1800).isValidProfileBirthdate(today))
        assertFalse(SettingsBirthdate(1, 1, 2101).isValidProfileBirthdate(today, SettingsBirthdate(1, 1, 2200)))
    }
    private fun png(width: Int, height: Int): ByteArray {
        val pixels = ByteArray(width * height * 4) { index -> if (index % 4 == 0 || index % 4 == 3) -1 else 0 }
        return Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), pixels, width * 4).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
        }
    }
}
