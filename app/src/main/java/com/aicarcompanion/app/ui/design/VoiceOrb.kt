package com.aicarcompanion.app.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.ui.theme.Penelope
import com.aicarcompanion.app.ui.theme.PenelopePalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Visual modes of the orb, derived only from the real voice session state. */
enum class OrbMode(val spoken: String) {
    IDLE("ready"),
    LISTENING("listening"),
    THINKING("thinking"),
    SPEAKING("speaking"),
    PAUSED("paused"),
    ERROR("needs attention"),
    ;

    companion object {
        fun from(state: VoiceState): OrbMode = when (state) {
            VoiceState.Idle -> IDLE
            is VoiceState.Listening -> LISTENING
            is VoiceState.Processing -> THINKING
            is VoiceState.Speaking -> SPEAKING
            is VoiceState.Paused -> PAUSED
            is VoiceState.Error -> ERROR
        }
    }
}

/**
 * The glowing assistant orb. Animation reflects [mode] (real session state) and, while
 * listening, the real microphone [level]. Animations run only while the orb is on screen
 * and are replaced by static, state-distinct visuals when motion is reduced.
 */
@Composable
fun VoiceOrb(
    mode: OrbMode,
    level: Float,
    onClick: (() -> Unit)?,
    actionLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val t = Penelope.tokens
    val reduced = t.reducedMotion
    val smoothedLevel by animateFloatAsState(if (reduced) 0f else level, spring(stiffness = 300f), label = "level")

    var breath = 0.5f
    var spin = 0f
    var wave = 0f
    if (!reduced && mode != OrbMode.PAUSED && mode != OrbMode.ERROR) {
        val transition = rememberInfiniteTransition(label = "orb")
        breath = transition.animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.IDLE) 3_200 else 1_400), RepeatMode.Reverse), label = "breath",
        ).value
        if (mode == OrbMode.THINKING || mode == OrbMode.SPEAKING || mode == OrbMode.LISTENING) {
            spin = transition.animateFloat(
                0f, 360f, infiniteRepeatable(tween(if (mode == OrbMode.THINKING) 1_600 else 6_000, easing = LinearEasing)), label = "spin",
            ).value
            wave = transition.animateFloat(
                0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.SPEAKING) 900 else 1_800, easing = LinearEasing)), label = "wave",
            ).value
        }
    }

    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
                } else Modifier,
            )
            .semantics {
                contentDescription = actionLabel
                stateDescription = mode.spoken
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawOrb(mode, breath, spin, wave, smoothedLevel, t.brandColors, t.isDark, reduced)
        }
    }
}

private fun DrawScope.drawOrb(
    mode: OrbMode,
    breath: Float,
    spin: Float,
    wave: Float,
    level: Float,
    brand: List<Color>,
    dark: Boolean,
    reduced: Boolean,
) {
    val c = center
    val maxR = min(size.width, size.height) / 2f
    val dim = mode == OrbMode.PAUSED
    val error = mode == OrbMode.ERROR
    val (cyan, blue, violet) = brand.map { if (dim) lerp(it, Color.Gray, 0.55f) else it }
    val scale = 1f + breath * 0.035f + level * 0.10f
    val r = maxR * 0.56f * scale

    // Outer glow.
    drawCircle(
        Brush.radialGradient(
            listOf(violet.copy(alpha = if (dark) 0.40f else 0.30f), cyan.copy(alpha = 0.16f), Color.Transparent),
            center = c, radius = maxR,
        ),
        radius = maxR, center = c,
    )

    when (mode) {
        OrbMode.LISTENING -> {
            // Ripples: stronger when the user is louder (real mic level).
            for (i in 0 until 3) {
                val p = if (reduced) 0.35f + i * 0.2f else (wave + i / 3f) % 1f
                val ringR = r * (1.04f + p * (0.45f + level * 0.35f))
                drawCircle(cyan.copy(alpha = (1f - p) * (0.35f + level * 0.5f)), ringR, c, style = Stroke(2.dp.toPx()))
            }
        }
        OrbMode.THINKING -> rotate(spin, c) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, cyan, violet, Color.Transparent), c),
                startAngle = 0f, sweepAngle = 300f, useCenter = false,
                topLeft = Offset(c.x - r * 1.18f, c.y - r * 1.18f), size = Size(r * 2.36f, r * 2.36f),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        OrbMode.SPEAKING -> {
            for (layer in 0 until 2) {
                val path = Path()
                val steps = 96
                val amp = r * (0.06f + layer * 0.03f)
                val base = r * (1.10f + layer * 0.10f)
                for (s in 0..steps) {
                    val a = 2 * PI * s / steps
                    val ripple = sin(a * (5 + layer * 2) + 2 * PI * (wave + layer * 0.3)) * amp
                    val rr = base + ripple.toFloat()
                    val x = c.x + (rr * cos(a)).toFloat()
                    val y = c.y + (rr * sin(a)).toFloat()
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, Brush.sweepGradient(listOf(cyan, blue, violet, cyan), c), alpha = if (layer == 0) 0.85f else 0.45f, style = Stroke(2.5.dp.toPx()))
            }
        }
        OrbMode.ERROR -> drawCircle(PenelopePalette.Coral.copy(alpha = 0.85f), r * 1.14f, c, style = Stroke(4.dp.toPx()))
        else -> drawCircle(Brush.sweepGradient(listOf(cyan, violet, cyan), c), r * 1.08f, c, alpha = 0.35f, style = Stroke(1.5.dp.toPx()))
    }

    // Pearlescent core.
    val highlight = Offset(c.x - r * 0.35f, c.y - r * 0.4f)
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = if (dim) 0.5f else 0.95f), cyan, blue, violet), center = highlight, radius = r * 1.75f),
        radius = r, center = c,
    )
    // Soft inner sheen that drifts with the spin.
    val sheenAngle = Math.toRadians((spin + 210).toDouble())
    val sheen = Offset(c.x + (r * 0.45f * cos(sheenAngle)).toFloat(), c.y + (r * 0.45f * sin(sheenAngle)).toFloat())
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center = sheen, radius = r * 0.7f), radius = r, center = c)
    if (error) {
        // An "!" mark so the error state isn't conveyed by color alone.
        val w = r * 0.12f
        drawLine(Color.White, Offset(c.x, c.y - r * 0.38f), Offset(c.x, c.y + r * 0.12f), strokeWidth = w, cap = StrokeCap.Round)
        drawCircle(Color.White, w * 0.6f, Offset(c.x, c.y + r * 0.36f))
    }
}
