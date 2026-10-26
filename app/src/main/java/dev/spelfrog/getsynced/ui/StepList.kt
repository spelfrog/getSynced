package dev.spelfrog.getsynced.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.sync.StepState
import dev.spelfrog.getsynced.sync.SyncStep
import kotlinx.coroutines.delay
import java.util.Locale

/** Vertical padding above the step name; the marker centre is derived from it and the name's line height. */
private val RowTopPadding = 5.dp

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
    val nameStyle = MaterialTheme.typography.bodyLarge
    val pending = step.state == StepState.PENDING
    val failed = step.state == StepState.FAILED
    val running = step.state == StepState.RUNNING

    // Centre of the first text line, so the dot stays level with the name at any font scale.
    val markerCenterPx = with(LocalDensity.current) {
        val line = if (nameStyle.lineHeight.isSpecified) nameStyle.lineHeight else nameStyle.fontSize * 1.5f
        RowTopPadding.toPx() + line.toPx() / 2f
    }

    val stateWord = stringResource(
        when (step.state) {
            StepState.PENDING -> R.string.step_state_pending
            StepState.RUNNING -> R.string.step_state_running
            StepState.OK -> R.string.step_state_done
            StepState.FAILED -> R.string.step_state_failed
            StepState.SKIPPED -> R.string.step_state_skipped
        }
    )
    // The running row is a live region, so leave its ticking time out of the description.
    val description = listOfNotNull(
        step.name,
        stateWord,
        step.durationMs?.takeIf { !running }?.let { formatDuration(it) },
        step.detail.takeIf { it.isNotBlank() },
    ).joinToString(", ")

    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (running) liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.Top,
    ) {
        Marker(step.state, isFirst, isLast, markerCenterPx, Modifier.width(24.dp).fillMaxHeight())
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f).padding(top = RowTopPadding, bottom = 5.dp)) {
            Row {
                Text(
                    text = step.name,
                    style = nameStyle,
                    color = if (pending) colors.onSurfaceVariant else colors.onSurface,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                Spacer(Modifier.width(8.dp))
                val started = step.startedAt
                Column(Modifier.alignByBaseline()) {
                    when {
                        running && started != null -> ElapsedText(started)
                        step.durationMs != null -> DurationText(step.durationMs)
                    }
                }
            }
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
    }
}

/**
 * Dot marker on a vertical leader line. Running: pulsing glow. Done: filled. Skipped: filled in the idle
 * outline tint (done, nothing to do). Pending: hollow, on a dashed line.
 */
@Composable
private fun Marker(state: StepState, isFirst: Boolean, isLast: Boolean, centerYPx: Float, modifier: Modifier) {
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
    val dashed = state == StepState.PENDING
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = centerYPx
        val stroke = 1.2.dp.toPx()
        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null
        if (!isFirst) drawLine(leader, Offset(cx, 0f), Offset(cx, cy - 6.dp.toPx()), stroke, pathEffect = effect)
        if (!isLast) drawLine(leader, Offset(cx, cy + 6.dp.toPx()), Offset(cx, size.height), stroke, pathEffect = effect)
        if (pulse > 0f) {
            drawCircle(dotColor.copy(alpha = pulse * 0.5f), radius = 9.dp.toPx(), center = Offset(cx, cy))
            drawCircle(dotColor.copy(alpha = pulse), radius = 6.dp.toPx(), center = Offset(cx, cy))
        }
        when (state) {
            StepState.OK, StepState.RUNNING, StepState.FAILED, StepState.SKIPPED ->
                drawCircle(dotColor, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
            StepState.PENDING ->
                drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(cx, cy), style = Stroke(stroke))
        }
        if (state == StepState.FAILED) {
            // small cross through the marker, like a struck-out annotation
            val r = 6.dp.toPx()
            drawLine(dotColor, Offset(cx - r, cy - r), Offset(cx + r, cy + r), stroke)
            drawLine(dotColor, Offset(cx - r, cy + r), Offset(cx + r, cy - r), stroke)
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
        maxLines = 1,
    )
}

/** "0.3 s", "12.4 s", "2 min 05 s". Always Locale.US, like every number in the app. */
fun formatDuration(ms: Long): String =
    if (ms < 60_000) String.format(Locale.US, "%.1f s", ms / 1000.0)
    else String.format(Locale.US, "%d min %02d s", ms / 60_000, (ms / 1000) % 60)
