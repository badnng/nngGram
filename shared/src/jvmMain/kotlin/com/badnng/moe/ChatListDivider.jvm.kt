package com.badnng.moe

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

private val horizontalListResizeIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

internal actual fun chatListResizePointerIcon(): PointerIcon = horizontalListResizeIcon
