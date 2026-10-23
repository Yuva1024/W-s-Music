package app.wsmusic.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** Drag to reorder rows of equal height (the queue): the dragged row follows the pointer, the others make room. */
class ReorderState {
    var from by mutableStateOf<Int?>(null); private set
    var offset by mutableFloatStateOf(0f); private set
    private var rowPx by mutableFloatStateOf(1f)

    fun target(min: Int, max: Int): Int? = from?.let { (it + (offset / rowPx).roundToInt()).coerceIn(min, max) }

    /** How far row [i] is drawn from its place right now. */
    fun shift(i: Int, min: Int, max: Int): Float {
        val f = from ?: return 0f
        if (i == f) return offset
        val t = target(min, max) ?: return 0f
        return when {
            f < t && i in (f + 1)..t -> -rowPx
            f > t && i in t until f -> rowPx
            else -> 0f
        }
    }

    /** Placement of row [i]: its shift while something is dragged, drawn above the others when it is the dragged one. */
    fun row(i: Int, min: Int, max: Int): Modifier = Modifier
        .onSizeChanged { if (it.height > 0) rowPx = it.height.toFloat() }
        .zIndex(if (from == i) 1f else 0f)
        .graphicsLayer { translationY = shift(i, min, max); if (from == i) { shadowElevation = 12f; alpha = 0.95f } }

    /** The grip of row [index]; [onMove] gets (from, to) when it is dropped somewhere else. */
    fun handle(index: Int, min: Int, max: Int, onMove: (Int, Int) -> Unit): Modifier = Modifier.pointerInput(index, min, max) {
        detectDragGestures(
            onDragStart = { from = index; offset = 0f },
            onDragEnd = {
                val f = from; val t = target(min, max)
                from = null; offset = 0f
                if (f != null && t != null && t != f) onMove(f, t)
            },
            onDragCancel = { from = null; offset = 0f },
            onDrag = { change, amount -> change.consume(); offset += amount.y },
        )
    }
}
