package com.aicarcompanion.app.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import com.aicarcompanion.app.ui.theme.OrbColors
import com.aicarcompanion.app.ui.theme.Penelope
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
internal data class OrbLook(
    val energy: Float,
    val core: Color,
    val body: Color,
    val ribbonA: Color,
    val ribbonB: Color,
    val rim: Color,
    val halo: Color,
)

/**
 * The look for each state, built from the color style's [OrbColors]. Paused is a calm slate;
 * error is a soft amber (noticeable, not alarming) and also carries a "!" mark.
 */
internal fun lookFor(mode: OrbMode, o: OrbColors): OrbLook = when (mode) {
    OrbMode.IDLE -> OrbLook(0.3f, o.core, o.body, o.ribbonA, o.ribbonB, o.rim, o.halo)
    OrbMode.LISTENING -> OrbLook(0.75f, o.core, o.body, o.ribbonA, o.ribbonB, Color.White, o.ribbonA)
    OrbMode.THINKING -> OrbLook(0.6f, o.core, o.body, o.ribbonB, o.ribbonA, o.rim, o.halo)
    OrbMode.SPEAKING -> OrbLook(0.95f, o.core, lerp(o.body, o.ribbonA, 0.25f), o.ribbonA, o.ribbonB, Color.White, o.ribbonB)
    OrbMode.PAUSED -> OrbLook(0.12f, Color(0xFF0F1722), Color(0xFF2A3442), Color(0xFF6B7A8C), Color(0xFF8A97A8), Color(0xFFB8C2CE), Color(0xFF4B5563))
    OrbMode.ERROR -> OrbLook(0.18f, Color(0xFF2A1A06), Color(0xFF5A3A0E), Color(0xFFFBBF24), Color(0xFFFCD34D), Color(0xFFFDE68A), Color(0xFFF59E0B))
}

/**
 * Penelope's voice orb: a dark glass sphere with flowing ribbons of light, a bright crescent rim
 * and a soft halo, in the active color style.
 *
 * - **Real state only:** the look comes from the voice session ([mode]); while listening, ripples
 *   follow the real microphone [level].
 * - **Speaking:** Android's speech engine doesn't report loudness, so speaking uses a steady
 *   rhythmic animation rather than a made-up waveform.
 * - **Efficient:** animation values are read only while drawing, so the orb redraws each frame
 *   without rebuilding the screen.
 * - **Reduced motion:** the orb is completely still.
 * - **Not color alone:** each state also has its own shape (ripples, comet arc, waves, pause bars, "!").
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
    val look = lookFor(mode, t.orb)
    val colorSpec = if (reduced) snap<Color>() else tween(700)
    val core = animateColorAsState(look.core, colorSpec, label = "core")
    val body = animateColorAsState(look.body, colorSpec, label = "body")
    val ribbonA = animateColorAsState(look.ribbonA, colorSpec, label = "ribbonA")
    val ribbonB = animateColorAsState(look.ribbonB, colorSpec, label = "ribbonB")
    val rim = animateColorAsState(look.rim, colorSpec, label = "rim")
    val halo = animateColorAsState(look.halo, colorSpec, label = "halo")
    val energy = animateFloatAsState(look.energy, if (reduced) snap() else tween(650), label = "energy")
    val smoothedLevel = animateFloatAsState(if (reduced) 0f else level, spring(stiffness = 300f), label = "level")

    val still = remember { StillClock() }
    val clock: OrbClock = if (reduced) still else {
        val transition = rememberInfiniteTransition(label = "orb")
        OrbClock(
            breath = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.IDLE) 3_800 else 1_700), RepeatMode.Reverse), label = "breath"),
            drift = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "drift"),
            swirl = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.THINKING) 1_400 else 5_200, easing = LinearEasing)), label = "swirl"),
            wave = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.SPEAKING) 900 else 1_900, easing = LinearEasing)), label = "wave"),
        )
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
            // All State reads happen here, in the draw phase.
            drawOrb(
                mode,
                OrbFrame(
                    clock.breath.value, clock.drift.value, clock.swirl.value, clock.wave.value, smoothedLevel.value, energy.value,
                    core.value, body.value, ribbonA.value, ribbonB.value, rim.value, halo.value,
                ),
                t.isDark,
                reduced,
            )
        }
    }
}

private open class OrbClock(val breath: State<Float>, val drift: State<Float>, val swirl: State<Float>, val wave: State<Float>)

/** Fixed positions for reduced motion: a pleasant still frame. */
private class StillClock : OrbClock(mutableFloatStateOf(0.5f), mutableFloatStateOf(0.12f), mutableFloatStateOf(0.3f), mutableFloatStateOf(0.3f))

private class OrbFrame(
    val breath: Float, val drift: Float, val swirl: Float, val wave: Float, val level: Float, val energy: Float,
    val core: Color, val body: Color, val ribbonA: Color, val ribbonB: Color, val rim: Color, val halo: Color,
)

private fun DrawScope.drawOrb(mode: OrbMode, f: OrbFrame, dark: Boolean, reduced: Boolean) {
    val c = center
    val maxR = min(size.width, size.height) / 2f
    val scale = 1f + f.breath * (0.015f + f.energy * 0.025f) + f.level * 0.08f
    val r = maxR * 0.58f * scale

    // Halo: atmospheric light around the sphere, stronger with energy.
    drawCircle(
        Brush.radialGradient(
            listOf(f.halo.copy(alpha = (if (dark) 0.42f else 0.28f) + f.energy * 0.2f), f.halo.copy(alpha = 0.10f), Color.Transparent),
            center = c, radius = maxR,
        ),
        radius = maxR, center = c,
    )

    // State shapes outside the sphere.
    when (mode) {
        OrbMode.LISTENING -> for (i in 0 until 3) {
            val p = if (reduced) 0.3f + i * 0.22f else (f.wave + i / 3f) % 1f
            val ringR = r * (1.06f + p * (0.36f + f.level * 0.34f))
            drawCircle(f.ribbonA.copy(alpha = (1f - p) * (0.30f + f.level * 0.5f)), ringR, c, style = Stroke(2.dp.toPx()))
        }
        OrbMode.THINKING -> rotate(f.swirl * 360f, c) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, f.ribbonB, f.rim, Color.Transparent), c),
                startAngle = 0f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(c.x - r * 1.18f, c.y - r * 1.18f), size = Size(r * 2.36f, r * 2.36f),
                style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        OrbMode.SPEAKING -> for (layer in 0 until 2) {
            val path = Path()
            val steps = 96
            val amp = r * (0.045f + layer * 0.03f) * (0.6f + f.energy * 0.5f)
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
            drawPath(path, Brush.sweepGradient(listOf(f.ribbonA, f.ribbonB, f.rim, f.ribbonA), c), alpha = if (layer == 0) 0.8f else 0.35f, style = Stroke(2.dp.toPx()))
        }
        OrbMode.ERROR -> drawCircle(f.ribbonA.copy(alpha = 0.55f), r * 1.14f, c, style = Stroke(2.dp.toPx()))
        else -> Unit
    }

    // Sphere body: dark glass, a little lighter toward the lit side.
    val lit = Offset(c.x + r * 0.30f, c.y + r * 0.35f)
    drawCircle(Brush.radialGradient(listOf(lerp(f.body, f.ribbonA, 0.22f), f.body, lerp(f.body, f.core, 0.7f), f.core), center = lit, radius = r * 1.7f), r, c)

    val sphere = Path().apply { addOval(Rect(c, r)) }
    clipPath(sphere) {
        // Sweeping bands of light inside the glass (the references' liquid swirls).
        for (i in 0 until 3) {
            val band = bandPath(c, r, phase = f.drift, twist = f.swirl, index = i, energy = f.energy)
            val color = if (i == 1) f.ribbonB else f.ribbonA
            val from = Offset(c.x - r, c.y - r * (0.4f - i * 0.3f))
            val to = Offset(c.x + r, c.y + r * (0.4f - i * 0.2f))
            val glow = 0.10f + f.energy * 0.08f
            drawPath(band, Brush.linearGradient(listOf(color.copy(alpha = glow), f.rim.copy(alpha = glow * 0.8f), color.copy(alpha = glow * 0.3f)), from, to), style = Stroke(r * 0.46f, cap = StrokeCap.Round))
            drawPath(band, Brush.linearGradient(listOf(color.copy(alpha = glow * 2.6f), f.rim.copy(alpha = glow * 2.2f), color.copy(alpha = 0.05f)), from, to), style = Stroke(r * 0.15f, cap = StrokeCap.Round))
            if (i != 2) {
                drawPath(band, Brush.linearGradient(listOf(Color.Transparent, f.rim.copy(alpha = 0.55f + f.energy * 0.35f), Color.Transparent), from, to), style = Stroke(r * 0.022f, cap = StrokeCap.Round))
            }
        }
        // Inner light where the ribbons meet.
        drawCircle(Brush.radialGradient(listOf(f.ribbonA.copy(alpha = 0.18f + f.energy * 0.15f), Color.Transparent), center = lit, radius = r * 0.9f), r * 0.9f, lit)
        // Glassy specular highlight.
        val spec = Offset(c.x - r * 0.38f, c.y - r * 0.45f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), center = spec, radius = r * 0.45f), r * 0.45f, spec)
    }

    // Crescent rim light, slowly turning: the bright edge in the references.
    rotate(f.drift * 360f + 30f, c) {
        drawCircle(
            Brush.sweepGradient(
                listOf(Color.Transparent, f.rim.copy(alpha = 0.95f), f.ribbonA.copy(alpha = 0.6f), Color.Transparent, Color.Transparent, f.ribbonB.copy(alpha = 0.35f), Color.Transparent),
                c,
            ),
            r, c, style = Stroke(r * 0.045f),
        )
    }
    drawCircle(f.rim.copy(alpha = 0.18f), r, c, style = Stroke(1.dp.toPx()))

    // Shape cues for states that also change color.
    val w = r * 0.11f
    when (mode) {
        OrbMode.ERROR -> {
            drawLine(Color.White.copy(alpha = 0.92f), Offset(c.x, c.y - r * 0.34f), Offset(c.x, c.y + r * 0.10f), strokeWidth = w, cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.92f), w * 0.6f, Offset(c.x, c.y + r * 0.32f))
        }
        OrbMode.PAUSED -> {
            drawLine(Color.White.copy(alpha = 0.9f), Offset(c.x - r * 0.15f, c.y - r * 0.24f), Offset(c.x - r * 0.15f, c.y + r * 0.24f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.9f), Offset(c.x + r * 0.15f, c.y - r * 0.24f), Offset(c.x + r * 0.15f, c.y + r * 0.24f), strokeWidth = w, cap = StrokeCap.Round)
        }
        else -> Unit
    }
}

/**
 * One sweeping band of light: a soft curve across the sphere that slowly turns with [phase] and
 * bends with [twist]. Livelier [energy] bends it further.
 */
private fun bandPath(c: Offset, r: Float, phase: Float, twist: Float, index: Int, energy: Float): Path {
    val base = 2 * PI * (phase * (0.5 + index * 0.15) + index * 0.37)
    val endAngle = base + PI + 0.45 * sin(2 * PI * twist + index)
    val reach = r * 1.15f
    val start = Offset(c.x + (reach * cos(base)).toFloat(), c.y + (reach * sin(base)).toFloat())
    val end = Offset(c.x + (reach * cos(endAngle)).toFloat(), c.y + (reach * sin(endAngle)).toFloat())
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val bend = r * (0.45f + energy * 0.25f) * sin(2 * PI * (twist * 0.5 + index * 0.31)).toFloat().let { if (it >= 0) 0.4f + it * 0.6f else -0.4f + it * 0.6f }
    val c1 = Offset(start.x + dx * 0.33f + nx * bend, start.y + dy * 0.33f + ny * bend)
    val c2 = Offset(start.x + dx * 0.66f - nx * bend * 0.7f, start.y + dy * 0.66f - ny * bend * 0.7f)
    return Path().apply {
        moveTo(start.x, start.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    }
}
