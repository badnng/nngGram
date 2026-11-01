package com.badnng.moe.settings

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileStickerAvatarPreviewModelTest {
    @Test
    fun colorsAreBoundedAndCopiedFromAndToTheEditor() {
        assertNull(AvatarPreviewColors.from(emptyList()))
        assertNull(AvatarPreviewColors.from(List(5) { 0 }))
        assertNull(AvatarPreviewColors.from(listOf(-1)))
        assertNull(AvatarPreviewColors.from(listOf(0x1000000)))
        val external = mutableListOf(0, 0xFFFFFF)
        val colors = assertNotNull(AvatarPreviewColors.from(external))
        external[0] = 0x123456
        val returned = colors.values().toMutableList()
        returned[1] = 0
        assertEquals(listOf(0, 0xFFFFFF), colors.values())
        assertNull(colors.replace(2, 0))
        assertNull(colors.append(-1))
    }

    @Test
    fun allSevenPalettesAreTheValuesAfterTheDesktopSwap() {
        val expected = listOf(
            listOf(0x4D8DFF, 0x2BBFFF, 0x20E2CD, 0x0EE1F1),
            listOf(0x5EB6FB, 0x1FCEEB, 0x45F7B7, 0x1FF1D9),
            listOf(0x09D260, 0x5EDC40, 0xC1E526, 0x80DF2B),
            listOf(0xF5694E, 0xF5772C, 0xFFD412, 0xFFA743),
            listOf(0xF64884, 0xEF5B41, 0xF6A730, 0xFF7742),
            listOf(0xF94BA0, 0xFB5C80, 0xFFB23A, 0xFE7E62),
            listOf(0x837CFF, 0xB063FF, 0xFF72A9, 0xE269FF),
        )
        assertEquals(7, AvatarPreviewPalettes.size)
        expected.forEachIndexed { index, values -> assertEquals(values, AvatarPreviewPalettes.get(index)?.values()) }
        assertNull(AvatarPreviewPalettes.get(-1))
        assertNull(AvatarPreviewPalettes.get(7))
    }

    @Test
    fun previewBackgroundUsesSolidDiagonalAndFourColorFields() {
        val solid = assertNotNull(AvatarPreviewColors.from(listOf(0x123456)))
        assertEquals(0x123456, AvatarPreviewGradient.linear45(solid, .2f, .8f))
        val pair = assertNotNull(AvatarPreviewColors.from(listOf(0x000000, 0xFFFFFF)))
        assertEquals(0x000000, AvatarPreviewGradient.linear45(pair, 1f, 0f))
        assertEquals(0xFFFFFF, AvatarPreviewGradient.linear45(pair, 0f, 1f))
        assertEquals(0x808080, AvatarPreviewGradient.linear45(pair, .5f, .5f))
        assertNull(AvatarPreviewGradient.linear45(pair, Float.NaN, 0f))
        val three = assertNotNull(AvatarPreviewColors.from(listOf(0xFF0000, 0x00FF00, 0x0000FF)))
        val four = assertNotNull(three.append(0xFFFFFF))
        val a = assertNotNull(AvatarPreviewGradient.complex45(three))
        val b = assertNotNull(AvatarPreviewGradient.complex45(four))
        assertEquals(4096, a.size)
        assertTrue(a.toSet().size > 3)
        assertTrue(a.indices.any { a[it] != b[it] })
        assertNull(AvatarPreviewGradient.complex45(pair))
    }

    @Test
    fun foregroundFitsInsideTheCenteredSixtySevenPercentCanvas() {
        val geometry = assertNotNull(AvatarPreviewGeometry.within(300f, 200f))
        assertEquals(AvatarPreviewRect(50f, 0f, 200f, 200f), geometry.circle)
        assertClose(134f, geometry.foregroundCanvas.width)
        val wide = assertNotNull(geometry.fitArtwork(400, 100))
        assertClose(134f, wide.width)
        assertClose(33.5f, wide.height)
        assertClose(150f, wide.left + wide.width / 2f)
        assertClose(100f, wide.top + wide.height / 2f)
        val tall = assertNotNull(geometry.fitArtwork(100, 400))
        assertClose(33.5f, tall.width)
        assertClose(134f, tall.height)
        val scaled = assertNotNull(AvatarPreviewGeometry.within(375f, 250f)).foregroundCanvas
        assertClose(geometry.foregroundCanvas.width * 1.25f, scaled.width)
        assertNull(geometry.fitArtwork(0, 1))
        assertNull(AvatarPreviewGeometry.within(Float.POSITIVE_INFINITY, 10f))
    }

    @Test
    fun nestedColorEditsApplyOnlyOnConfirmationAndEnforceOneToFour() {
        val initial = assertNotNull(AvatarPreviewColors.from(listOf(1)))
        val editor = AvatarPreviewEditor<Any>(initial)
        val cancelled = assertNotNull(editor.editColors(true))
        assertFalse(editor.removeColor(cancelled, true))
        assertTrue(editor.addColor(cancelled, 2, true))
        assertTrue(editor.addColor(cancelled, 3, true))
        assertTrue(editor.addColor(cancelled, 4, true))
        assertFalse(editor.addColor(cancelled, 5, true))
        assertTrue(editor.removeColor(cancelled, true))
        assertEquals(listOf(1, 2, 3), cancelled.colors())
        assertEquals(2, cancelled.selectedIndex)
        assertTrue(editor.addColor(cancelled, 4, true))
        assertTrue(editor.changeColor(cancelled, 0xABCDEF, true))
        assertTrue(editor.finishColors(cancelled, apply = false, current = true))
        assertEquals(listOf(1), editor.colors.values())
        assertFalse(editor.changeColor(cancelled, 9, true))
        val applied = assertNotNull(editor.editColors(true))
        assertTrue(editor.addColor(applied, 7, true))
        assertTrue(editor.finishColors(applied, apply = true, current = true))
        assertEquals(listOf(1, 7), editor.colors.values())
        assertEquals(1L, editor.revision)
        assertTrue(editor.hasCustomColors)
        assertTrue(editor.choosePalette(0, true))
        assertEquals(0, editor.selectedPalette)
        assertTrue(editor.chooseCustom(true))
        assertNull(editor.selectedPalette)
        assertEquals(listOf(1, 7), editor.colors.values())
    }

    @Test
    fun confirmationFreezesTheSelectedTicketAndRejectsOldConfirmationOnReselection() {
        val editor = AvatarPreviewEditor<Any>(assertNotNull(AvatarPreviewPalettes.get(0)))
        val selection = Any()
        assertNull(editor.confirm(true))
        assertTrue(editor.select(selection, true))
        val first = assertNotNull(editor.confirm(true))
        assertSame(selection, first.selection)
        assertFalse(editor.select(selection, true))
        assertTrue(editor.cancelConfirmation(first, true))
        assertTrue(editor.select(selection, true))
        val second = assertNotNull(editor.confirm(true))
        assertNotEquals(first.revision, second.revision)
        assertNull(editor.submit(first, true))
        assertSame(selection, second.selection)
    }

    @Test
    fun admissionAndCompletionBelongToTheCurrentSingleSubmission() {
        val editor = AvatarPreviewEditor<Any>(assertNotNull(AvatarPreviewPalettes.get(0)))
        assertTrue(editor.select(Any(), true))
        val confirmation = assertNotNull(editor.confirm(true))
        assertNull(editor.submit(confirmation, false))
        val first = assertNotNull(editor.submit(confirmation, true))
        assertNull(editor.submit(confirmation, true))
        assertTrue(editor.admitted(first, null, true))
        assertFalse(editor.admitted(first, 4, true))
        val retry = assertNotNull(editor.submit(confirmation, true))
        assertTrue(editor.admitted(retry, 5, true))
        assertFalse(editor.completed(first, 5, success = true, current = true))
        assertFalse(editor.completed(retry, 4, success = true, current = true))
        assertTrue(editor.completed(retry, 5, success = false, current = true))
        assertFalse(editor.accepted)
        val final = assertNotNull(editor.submit(confirmation, true))
        assertTrue(editor.admitted(final, 6, true))
        assertTrue(editor.completed(final, 6, success = true, current = true))
        assertTrue(editor.accepted)
        assertNull(editor.submit(confirmation, true))
    }

    @Test
    fun closingAndLosingCurrentPreventsLateUiCallbacksFromChangingAReopenedEditor() {
        val colors = assertNotNull(AvatarPreviewPalettes.get(0))
        val first = AvatarPreviewEditor<Any>(colors)
        assertTrue(first.select(Any(), true))
        val confirmation = assertNotNull(first.confirm(true))
        val submission = assertNotNull(first.submit(confirmation, true))
        assertTrue(first.admitted(submission, 11, true))
        assertFalse(first.completed(submission, 11, success = true, current = false))
        first.invalidateSelection()
        assertFalse(first.completed(submission, 11, success = true, current = true))
        assertTrue(first.select(Any(), true))
        val freshConfirmation = assertNotNull(first.confirm(true))
        val freshSubmission = assertNotNull(first.submit(freshConfirmation, true))
        assertTrue(first.admitted(freshSubmission, 12, true))
        first.close()
        val reopened = AvatarPreviewEditor<Any>(colors)
        assertFalse(first.completed(freshSubmission, 12, success = true, current = true))
        assertFalse(reopened.accepted)
        assertNull(reopened.selection)
        assertNull(first.editColors(true))
    }

    private fun assertClose(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < .001f,
        "Expected $expected, got $actual")
}
