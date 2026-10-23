package app.wsmusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One side of a swipe: what it shows under the row, and what it does when swiped far enough. */
class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val run: () -> Unit)

/**
 * Swipe a row sideways for a quick action, like Spotify (right: add to queue). Past the line the phone ticks;
 * letting go there runs the action and the row springs back.
 */
@Composable
fun Swipeable(right: SwipeAction?, left: SwipeAction?, content: @Composable () -> Unit) {
    val dx = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    val limit = threshold * 1.6f
    Box(Modifier.fillMaxWidth()) {
        val action = if (dx.value > 0) right else if (dx.value < 0) left else null
        if (action != null) {
            val reached = abs(dx.value) >= threshold
            Row(
                Modifier.matchParentSize().background(if (reached) action.color else action.color.copy(alpha = 0.45f)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (dx.value > 0) Arrangement.Start else Arrangement.End,
            ) {
                Icon(action.icon, null, tint = Color.White)
                Text("  " + action.label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(
            Modifier.offset { IntOffset(dx.value.roundToInt(), 0) }.background(Ws.bg).pointerInput(right != null, left != null) {
                var ticked = false
                detectHorizontalDragGestures(
                    onDragStart = { ticked = false },
                    onDragEnd = {
                        val v = dx.value
                        if (v >= threshold) right?.run?.invoke() else if (v <= -threshold) left?.run?.invoke()
                        scope.launch { dx.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f) } },
                    onHorizontalDrag = { change, amount ->
                        val next = (dx.value + amount).coerceIn(if (left != null) -limit else 0f, if (right != null) limit else 0f)
                        if (next != dx.value) change.consume()
                        if (!ticked && abs(next) >= threshold) { ticked = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        if (abs(next) < threshold) ticked = false
                        scope.launch { dx.snapTo(next) }
                    },
                )
            },
        ) { content() }
    }
}
