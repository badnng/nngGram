package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import top.yukonga.miuix.kmp.squircle.addSquircleRect

class MiuixSquircleShapeTest {
    @Test
    fun pathEffectsUseThePinnedMiuixOutlineAtEveryDensityAndCornerSize() {
        for (density in listOf(1f, 1.25f, 2f)) {
            for (size in listOf(Size(240f, 96f), Size(9f, 180f), Size(180f, 9f), Size(7f, 7f))) {
                for (radius in listOf(0.001f, 15f, 20f, 1_000f)) {
                    for (direction in LayoutDirection.entries) for (enabled in listOf(true, false)) {
                        val scale = Density(density)
                        val actual = assertIs<Outline.Generic>(
                            MiuixSquircleShape(radius.dp, enabled).createOutline(size, direction, scale),
                        ).path
                        val native = Path().apply {
                            addSquircleRect(size.width, size.height, with(scale) { radius.dp.toPx() },
                                squircleEnabled = enabled)
                        }
                        assertEquals(native.iterator().asSequence().toList(), actual.iterator().asSequence().toList(),
                            "The app must use Miuix's actual segments without another radius multiplier: " +
                                "$size, radius=$radius, density=$density, direction=$direction, enabled=$enabled")
                        assertEquals(Rect(0f, 0f, size.width, size.height), actual.getBounds())
                    }
                }
            }
        }
    }

    @Test
    fun zeroRadiusAndEmptyBoundsRetainMiuixDegenerateBehavior() {
        val density = Density(1.25f)
        for (size in listOf(Size(43f, 19f), Size.Zero, Size(0f, 19f), Size(43f, 0f))) {
            val actual = assertIs<Outline.Generic>(
                MiuixSquircleShape(0.dp).createOutline(size, LayoutDirection.Ltr, density),
            ).path
            val native = Path().apply { addSquircleRect(size.width, size.height, 0f) }
            assertEquals(native.iterator().asSequence().toList(), actual.iterator().asSequence().toList())
            assertEquals(native.isEmpty, actual.isEmpty,
                "Empty controls must not acquire an app-generated rectangular contour.")
        }
    }

    @Test
    fun uniformCornersKeepPhysicalBoundsWhenLayoutDirectionChanges() {
        val shape = MiuixSquircleShape(15.dp)
        val density = Density(2f)
        val size = Size(177f, 37f)
        val leftToRight = assertIs<Outline.Generic>(shape.createOutline(size, LayoutDirection.Ltr, density)).path
        val rightToLeft = assertIs<Outline.Generic>(shape.createOutline(size, LayoutDirection.Rtl, density)).path
        assertEquals(leftToRight.iterator().asSequence().toList(), rightToLeft.iterator().asSequence().toList())
        assertEquals(Rect(0f, 0f, size.width, size.height), rightToLeft.getBounds(),
            "Density changes corner geometry, not the already measured pixel bounds.")
    }
}
