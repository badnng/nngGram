package com.badnng.moe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import kotlin.math.abs

/**
 * Chat sources record unscaled workspace pixels. Consumers may sit inside an independently
 * animated menu layer (including AnimatedVisibility). Keep that layer and its shadow intact;
 * supply only its actual axis scales to Miuix's inverse sampling, without applying it again.
 */
internal class WindowAlignedChatBackdrop(private val source: Backdrop) : Backdrop {
    override val isCoordinatesDependent: Boolean get() = source.isCoordinatesDependent
    override val offsetResidualX: Float get() = source.offsetResidualX
    override val offsetResidualY: Float get() = source.offsetResidualY

    private var currentScaleX = 1f
    private var currentScaleY = 1f
    private val samplingTransform: GraphicsLayerScope.() -> Unit = {
        scaleX = currentScaleX
        scaleY = currentScaleY
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        currentScaleX = 1f
        currentScaleY = 1f
        if (coordinates != null && coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0) {
            val origin = coordinates.localToWindow(Offset.Zero)
            // Full-size bases avoid cancellation error from subtracting adjacent window pixels.
            val x = coordinates.localToWindow(Offset(coordinates.size.width.toFloat(), 0f)) - origin
            val y = coordinates.localToWindow(Offset(0f, coordinates.size.height.toFloat())) - origin
            val scaleX = x.x / coordinates.size.width
            val scaleY = y.y / coordinates.size.height
            // Existing chat animations are positive axis scales, never rotation or shear.
            if (scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f &&
                abs(x.y) <= coordinates.size.width * 0.0001f && abs(y.x) <= coordinates.size.height * 0.0001f) {
                currentScaleX = scaleX
                currentScaleY = scaleY
            }
        }
        val transform = layerBlock ?: samplingTransform.takeIf { currentScaleX != 1f || currentScaleY != 1f }
        // LayerBackdrop already resolves the transformed origin, Popup offset and translation.
        // Adding those offsets here would compensate them twice.
        with(source) { drawBackdrop(density, coordinates, transform, downscaleFactor) }
    }
}
