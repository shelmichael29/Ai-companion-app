package com.aicarcompanion.app.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
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

/** Per-state look the orb animates toward, so state changes glide instead of jumping. */
private data class OrbLook(val energy: Float, val inner: Color, val mid: Color, val outer: Color, val halo: Color)

private fun lookFor(mode: OrbMode, dark: Boolean): OrbLook = when (mode) {
    OrbMode.IDLE -> OrbLook(0.25f, PenelopePalette.Mist, PenelopePalette.Lilac, PenelopePalette.ElectricPurple, PenelopePalette.ElectricPurple)
    OrbMode.LISTENING -> OrbLook(0.75f, Color.White, PenelopePalette.Lavender, PenelopePalette.Violet, PenelopePalette.Orchid)
    OrbMode.THINKING -> OrbLook(0.55f, PenelopePalette.Mist, PenelopePalette.Lilac, PenelopePalette.DeepViolet, PenelopePalette.ElectricPurple)
    OrbMode.SPEAKING -> OrbLook(0.9f, Color.White, PenelopePalette.Lilac, PenelopePalette.ElectricPurple, PenelopePalette.Violet)
    OrbMode.PAUSED -> OrbLook(0.1f, Color(0xFFD9D4E6), Color(0xFF8E86A6), Color(0xFF4B4560), Color(0xFF6B6385))
    OrbMode.ERROR -> OrbLook(0.15f, Color(0xFFFFE4E6), PenelopePalette.Coral, if (dark) Color(0xFF7F1D1D) else Color(0xFFB91C1C), PenelopePalette.Coral)
}

/**
 * Penelope's voice orb: a glowing purple sphere with slow "aurora" light inside. Its state comes
 * only from the real voice session ([mode]) and, while listening, the real microphone [level].
 * Colors and energy animate between states. With reduced motion everything is static, and every
 * state stays distinct by shape as well as color (rings, arc, waves, pause bars, "!" mark).
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
    val look = lookFor(mode, t.isDark)
    val colorSpec = if (reduced) snap<Color>() else tween(700)
    val inner by animateColorAsState(look.inner, colorSpec, label = "inner")
    val mid by animateColorAsState(look.mid, colorSpec, label = "mid")
    val outer by animateColorAsState(look.outer, colorSpec, label = "outer")
    val halo by animateColorAsState(look.halo, colorSpec, label = "halo")
    val energy by animateFloatAsState(look.energy, if (reduced) snap() else tween(600), label = "energy")
    val smoothedLevel by animateFloatAsState(if (reduced) 0f else level, spring(stiffness = 300f), label = "level")

    var breath = 0.5f
    var slow = 0.15f
    var fast = 0f
    var wave = 0.3f
    if (!reduced) {
        val transition = rememberInfiniteTransition(label = "orb")
        breath = transition.animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.IDLE) 3_400 else 1_600), RepeatMode.Reverse), label = "breath",
        ).value
        slow = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "slow").value
        fast = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_500, easing = LinearEasing)), label = "fast").value
        wave = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.SPEAKING) 900 else 1_800, easing = LinearEasing)), label = "wave").value
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
            drawOrb(mode, OrbFrame(breath, slow, fast, wave, smoothedLevel, energy, inner, mid, outer, halo), t.isDark, reduced)
        }
    }
}

private class OrbFrame(
    val breath: Float, val slow: Float, val fast: Float, val wave: Float, val level: Float, val energy: Float,
    val inner: Color, val mid: Color, val outer: Color, val halo: Color,
)

private fun DrawScope.drawOrb(mode: OrbMode, f: OrbFrame, dark: Boolean, reduced: Boolean) {
    val c = center
    val maxR = min(size.width, size.height) / 2f
    val scale = 1f + f.breath * (0.02f + f.energy * 0.03f) + f.level * 0.10f
    val r = maxR * 0.56f * scale

    // Halo: soft purple light around the sphere, stronger with energy.
    drawCircle(
        Brush.radialGradient(
            listOf(f.halo.copy(alpha = (if (dark) 0.38f else 0.26f) + f.energy * 0.22f), f.outer.copy(alpha = 0.12f), Color.Transparent),
            center = c, radius = maxR,
        ),
        radius = maxR, center = c,
    )

    // State-specific shapes around the sphere (meaning isn't carried by color alone).
    when (mode) {
        OrbMode.LISTENING -> for (i in 0 until 3) {
            val p = if (reduced) 0.3f + i * 0.22f else (f.wave + i / 3f) % 1f
            val ringR = r * (1.05f + p * (0.42f + f.level * 0.38f))
            drawCircle(f.mid.copy(alpha = (1f - p) * (0.35f + f.level * 0.5f)), ringR, c, style = Stroke(2.dp.toPx()))
        }
        OrbMode.THINKING -> rotate(f.fast * 360f, c) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, f.mid, f.inner, Color.Transparent), c),
                startAngle = 0f, sweepAngle = 280f, useCenter = false,
                topLeft = Offset(c.x - r * 1.2f, c.y - r * 1.2f), size = Size(r * 2.4f, r * 2.4f),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        OrbMode.SPEAKING -> for (layer in 0 until 2) {
            val path = Path()
            val steps = 96
            val amp = r * (0.05f + layer * 0.03f) * (0.6f + f.energy * 0.6f)
            val base = r * (1.12f + layer * 0.11f)
            for (s in 0..steps) {
                val a = 2 * PI * s / steps
                val ripple = sin(a * (5 + layer * 2) + 2 * PI * (f.wave + layer * 0.3)) * amp
                val rr = base + ripple.toFloat()
                val x = c.x + (rr * cos(a)).toFloat()
                val y = c.y + (rr * sin(a)).toFloat()
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, Brush.sweepGradient(listOf(f.inner, f.mid, f.outer, f.inner), c), alpha = if (layer == 0) 0.85f else 0.4f, style = Stroke(2.5.dp.toPx()))
        }
        OrbMode.ERROR -> drawCircle(f.mid.copy(alpha = 0.8f), r * 1.15f, c, style = Stroke(3.dp.toPx()))
        else -> drawCircle(Brush.sweepGradient(listOf(f.mid, f.outer, f.mid), c), r * 1.09f, c, alpha = 0.32f, style = Stroke(1.5.dp.toPx()))
    }

    // Sphere body.
    val highlight = Offset(c.x - r * 0.35f, c.y - r * 0.42f)
    drawCircle(Brush.radialGradient(listOf(f.inner, f.mid, f.outer, f.outer.copy(alpha = 0.95f)), center = highlight, radius = r * 1.8f), r, c)

    // Aurora: three soft lights drifting inside the sphere; livelier with energy.
    val clip = Path().apply { addOval(Rect(c, r)) }
    clipPath(clip) {
        val lights = listOf(PenelopePalette.Orchid to 0f, PenelopePalette.Lavender to 0.33f, f.outer to 0.66f)
        lights.forEachIndexed { i, (color, offset) ->
            val angle = 2 * PI * (f.slow * (1 + i * 0.5) + offset)
            val orbit = r * (0.25f + f.energy * 0.25f)
            val p = Offset(c.x + (orbit * cos(angle)).toFloat(), c.y + (orbit * sin(angle)).toFloat())
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.22f + f.energy * 0.18f), Color.Transparent), center = p, radius = r * 0.75f), r * 0.75f, p)
        }
        // Glassy top highlight.
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.40f), Color.Transparent), center = highlight, radius = r * 0.6f), r * 0.6f, highlight)
    }

    // Shape cues for states that also change color.
    val w = r * 0.12f
    when (mode) {
        OrbMode.ERROR -> {
            drawLine(Color.White, Offset(c.x, c.y - r * 0.38f), Offset(c.x, c.y + r * 0.12f), strokeWidth = w, cap = StrokeCap.Round)
            drawCircle(Color.White, w * 0.6f, Offset(c.x, c.y + r * 0.36f))
        }
        OrbMode.PAUSED -> {
            drawLine(Color.White.copy(alpha = 0.9f), Offset(c.x - r * 0.16f, c.y - r * 0.25f), Offset(c.x - r * 0.16f, c.y + r * 0.25f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.9f), Offset(c.x + r * 0.16f, c.y - r * 0.25f), Offset(c.x + r * 0.16f, c.y + r * 0.25f), strokeWidth = w, cap = StrokeCap.Round)
        }
        else -> Unit
    }
}
