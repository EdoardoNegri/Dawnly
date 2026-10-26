package com.example.sunwakeup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Height of the header while the page is off, used to centre it on the screen. */
private val OffHeaderHeight = 200.dp

/** The page's icon, and how it moves while it waits in the middle of the screen. */
sealed interface PageIcon {
    /** Turns slowly round, like the sun. */
    data class Spin(val vector: ImageVector) : PageIcon

    /** Rocks gently from side to side. */
    data class Rock(val vector: ImageVector) : PageIcon

    /** The sun sinks a little behind the horizon, fading, and rises again. */
    data object Sunset : PageIcon
}

/**
 * A page that hangs off one switch, like the sunrise alarm. While off, only the icon, the title with
 * its switch and [blurb] sit in the middle of the screen; switching on lifts the title to the top,
 * the icon floats out of view and [content] rises in underneath.
 */
@Composable
fun SwitchedPage(
    title: String,
    icon: PageIcon,
    blurb: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    viewport: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 32.dp is the page's own top and bottom padding.
    val centred = ((viewport - 32.dp - OffHeaderHeight) / 2).coerceAtLeast(0.dp)
    val top by animateDpAsState(
        targetValue = if (enabled) 0.dp else centred,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "header position",
    )

    // The spring overshoots a little past 0 on the way up, and padding can't be negative.
    Column(Modifier.padding(top = top.coerceAtLeast(0.dp))) {
        AnimatedVisibility(
            visible = !enabled,
            enter = fadeIn(tween(300)) + scaleIn(tween(400)) + expandVertically(tween(400)),
            // The icon rises out of view as the page switches on.
            exit = fadeOut(tween(300)) + scaleOut(tween(400), targetScale = 0.4f) +
                slideOutVertically(tween(400)) { -it } + shrinkVertically(tween(400)),
        ) {
            MovingIcon(icon, Modifier.padding(bottom = 12.dp).size(72.dp))
        }
        Row(
            Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = onEnabledChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            Switch(checked = enabled, onCheckedChange = null)
        }
        AnimatedVisibility(visible = !enabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(blurb, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(tween(500, delayMillis = 200)) { it / 6 },
        exit = fadeOut(tween(200)) + shrinkVertically(tween(350)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun MovingIcon(icon: PageIcon, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "icon")
    val tint = MaterialTheme.colorScheme.primary
    when (icon) {
        is PageIcon.Spin -> {
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing)),
                label = "icon spin",
            )
            Icon(icon.vector, contentDescription = null, tint = tint, modifier = modifier.rotate(angle))
        }
        is PageIcon.Rock -> {
            val sway by transition.animateFloat(
                initialValue = -1f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(4_000), RepeatMode.Reverse),
                label = "icon sway",
            )
            Icon(icon.vector, contentDescription = null, tint = tint, modifier = modifier.rotate(sway * 10f))
        }
        PageIcon.Sunset -> {
            val sink by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(3_500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "sun sink",
            )
            SunsetIcon(modifier, sink = sink, tint = tint)
        }
    }
}

/**
 * A half sun with its rays over a long horizon. [sink], 0..1, lowers the sun behind the horizon and
 * fades it; [lower] sets it that many grid units lower to begin with, and [sideRays] adds the flat
 * rays either side.
 */
@Composable
fun SunsetIcon(
    modifier: Modifier = Modifier,
    sink: Float = 0f,
    lower: Float = 0f,
    sideRays: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier.size(24.dp)) {
        // Drawn on the usual 24-unit icon grid.
        val u = size.minDimension / 24f
        val horizon = 17f * u
        val sun = Offset(12f * u, horizon + (lower + sink * 3.5f) * u)
        val faded = tint.copy(alpha = tint.alpha * (1f - 0.5f * sink))
        clipRect(bottom = horizon) {
            drawCircle(faded, radius = 5f * u, center = sun, style = Stroke(2f * u))
            for (degrees in listOf(45f, 90f, 135f)) {
                val a = Math.toRadians(degrees.toDouble())
                val d = Offset(cos(a).toFloat(), -sin(a).toFloat())
                drawLine(faded, sun + d * (8f * u), sun + d * (11f * u), strokeWidth = 2f * u)
            }
            // The flat rays either side sit just above the horizon.
            if (sideRays) for (side in listOf(-1f, 1f)) {
                val y = sun.y - 1f * u
                drawLine(faded, Offset(sun.x + side * 8f * u, y), Offset(sun.x + side * 11f * u, y), strokeWidth = 2f * u)
            }
        }
        drawLine(tint, Offset(1f * u, horizon + u), Offset(23f * u, horizon + u), strokeWidth = 2f * u, cap = StrokeCap.Round)
    }
}
