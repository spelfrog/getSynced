package dev.spelfrog.getsynced.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.sync.StepState
import dev.spelfrog.getsynced.sync.SyncStep
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * One row per step, drawn like the annotations in a field sketch: a thin leader line with a dot marker
 * per step. The running step's dot glows; a failed one is drawn in the error colour.
 */
@Composable
fun StepList(steps: List<SyncStep>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        steps.forEachIndexed { i, step ->
            StepRow(step, isFirst = i == 0, isLast = i == steps.lastIndex)
        }
    }
}

@Composable
private fun StepRow(step: SyncStep, isFirst: Boolean, isLast: Boolean) {
    val colors = MaterialTheme.colorScheme
    val greyed = step.state == StepState.PENDING || step.state == StepState.SKIPPED
    val failed = step.state == StepState.FAILED
    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Marker(step.state, isFirst, isLast, Modifier.width(24.dp).fillMaxHeight())
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 5.dp)) {
            Text(
                text = step.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (greyed) colors.onSurfaceVariant else colors.onSurface,
            )
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = if (failed) FontStyle.Normal else FontStyle.Italic),
                    color = if (failed) colors.error else colors.onSurfaceVariant,
                    maxLines = if (failed) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val started = step.startedAt
        Column(Modifier.padding(top = 6.dp)) {
            when {
                step.state == StepState.RUNNING && started != null -> ElapsedText(started)
                step.durationMs != null -> DurationText(step.durationMs)
            }
        }
    }
}

/** Dot marker on a vertical leader line. Running: pulsing glow. Pending/skipped: hollow. */
@Composable
private fun Marker(state: StepState, isFirst: Boolean, isLast: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val leader = colors.outline
    val dotColor = when (state) {
        StepState.OK -> colors.primary
        StepState.RUNNING -> colors.primary
        StepState.FAILED -> colors.error
        StepState.PENDING, StepState.SKIPPED -> colors.outline
    }
    val pulse = if (state == StepState.RUNNING) {
        val t = rememberInfiniteTransition(label = "glow")
        t.animateFloat(
            initialValue = 0.25f, targetValue = 0.75f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "glowAlpha",
        ).value
    } else 0f
    val dashed = state == StepState.PENDING || state == StepState.SKIPPED
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = 14.dp.toPx()
        val stroke = 1.2.dp.toPx()
        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null
        if (!isFirst) drawLine(leader, Offset(cx, 0f), Offset(cx, cy - 6.dp.toPx()), stroke, pathEffect = effect)
        if (!isLast) drawLine(leader, Offset(cx, cy + 6.dp.toPx()), Offset(cx, size.height), stroke, pathEffect = effect)
        if (pulse > 0f) {
            drawCircle(dotColor.copy(alpha = pulse * 0.5f), radius = 9.dp.toPx(), center = Offset(cx, cy))
            drawCircle(dotColor.copy(alpha = pulse), radius = 6.dp.toPx(), center = Offset(cx, cy))
        }
        when (state) {
            StepState.OK, StepState.RUNNING, StepState.FAILED ->
                drawCircle(dotColor, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
            StepState.PENDING, StepState.SKIPPED ->
                drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(cx, cy), style = Stroke(stroke))
        }
        if (state == StepState.FAILED) {
            // small cross through the marker, like a struck-out annotation
            val r = 6.dp.toPx()
            drawLine(dotColor, Offset(cx - r, cy - r), Offset(cx + r, cy + r), stroke)
            drawLine(dotColor, Offset(cx - r, cy + r), Offset(cx + r, cy - r), stroke)
        }
        if (state == StepState.SKIPPED) {
            drawLine(Color.Transparent, Offset(cx, cy), Offset(cx, cy), 0f)
        }
    }
}

@Composable
private fun ElapsedText(startedAt: Long) {
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            delay(250)
            value = System.currentTimeMillis()
        }
    }
    DurationText((now - startedAt).coerceAtLeast(0))
}

@Composable
private fun DurationText(ms: Long) {
    Text(
        text = formatDuration(ms),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "0.3 s", "12.4 s", "2 min 05 s". */
fun formatDuration(ms: Long): String =
    if (ms < 60_000) String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)
    else String.format(Locale.getDefault(), "%d min %02d s", ms / 60_000, (ms / 1000) % 60)
