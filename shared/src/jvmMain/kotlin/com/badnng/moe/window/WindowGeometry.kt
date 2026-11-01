package com.badnng.moe.window

data class WorkArea(val x: Int, val y: Int, val width: Int, val height: Int)
data class WindowGeometry(val x: Int, val y: Int, val width: Int, val height: Int, val minimumWidth: Int, val minimumHeight: Int)

/** AWT bounds and Compose window sizes both use logical screen coordinates. */
fun initialWindowGeometry(area: WorkArea): WindowGeometry {
    require(area.width > 0 && area.height > 0)
    val width = minOf(1160, area.width)
    val height = minOf(840, area.height)
    return WindowGeometry(
        x = area.x + (area.width - width) / 2,
        y = area.y + (area.height - height) / 2,
        width = width,
        height = height,
        minimumWidth = minOf(380, width),
        minimumHeight = minOf(540, height),
    )
}
