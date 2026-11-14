package com.aicarcompanion.app.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicarcompanion.app.ui.theme.Penelope
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pearlescent background: a soft vertical gradient with two blurred-looking glow blobs and,
 * optionally, slow flowing light waves. Waves are static when motion is reduced.
 */
@Composable
fun PenelopeBackground(modifier: Modifier = Modifier, waves: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    val t = Penelope.tokens
    val phase = if (waves && !t.reducedMotion) {
        rememberInfiniteTransition(label = "waves").animateFloat(
            0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "wavePhase",
        ).value
    } else 0.25f
    Box(modifier.fillMaxSize().background(t.background)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(t.glowA, Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.18f), radius = size.width * 0.8f),
                radius = size.width * 0.8f, center = Offset(size.width * 0.1f, size.height * 0.18f),
            )
            drawCircle(
                Brush.radialGradient(listOf(t.glowB, Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.55f), radius = size.width * 0.9f),
                radius = size.width * 0.9f, center = Offset(size.width * 0.95f, size.height * 0.55f),
            )
            if (waves) {
                val baseY = size.height * 0.36f
                listOf(0f to 0.55f, 0.33f to 0.35f, 0.66f to 0.22f).forEachIndexed { i, (offset, alpha) ->
                    val path = Path()
                    val amp = size.height * (0.025f + i * 0.008f)
                    val steps = 48
                    for (s in 0..steps) {
                        val x = size.width * s / steps
                        val y = baseY + i * 14f + amp * sin(2 * PI * (s / steps.toFloat() * 1.3 + phase + offset)).toFloat()
                        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path,
                        Brush.horizontalGradient(listOf(Color.Transparent) + t.brandColors.map { it.copy(alpha = alpha) } + Color.Transparent),
                        style = Stroke(width = 2.dp.toPx() - i * 0.4f, cap = StrokeCap.Round),
                    )
                }
            }
        }
        content()
    }
}

/** Translucent "glass" surface with a soft luminous edge. (A true backdrop blur isn't available in Compose; see docs.) */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    strong: Boolean = false,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = Penelope.tokens
    Column(
        modifier
            .shadow(if (t.isDark) 0.dp else 10.dp, shape, ambientColor = t.brandColors[2].copy(alpha = 0.18f), spotColor = t.brandColors[1].copy(alpha = 0.18f))
            .clip(shape)
            .background(if (strong) t.glassStrong else t.glassFill)
            .border(1.dp, Brush.verticalGradient(listOf(t.glassBorder, t.glassBorder.copy(alpha = 0.2f))), shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Primary call-to-action: rounded pill with the brand gradient and press feedback. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    contentDescription: String? = null,
) {
    val t = Penelope.tokens
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !t.reducedMotion) 0.97f else 1f, label = "press")
    Row(
        modifier
            .scale(scale)
            .heightIn(min = 58.dp)
            .shadow(if (enabled) 14.dp else 0.dp, RoundedCornerShape(50), spotColor = t.brandColors[2].copy(alpha = 0.5f), ambientColor = t.brandColors[0].copy(alpha = 0.3f))
            .clip(RoundedCornerShape(50))
            .background(if (enabled) t.brandGradient else SolidColor(Color.Gray.copy(alpha = 0.35f)))
            .clickable(interaction, indication = androidx.compose.material3.ripple(color = Color.White), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Box(Modifier.size(10.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

/** Round translucent icon button (48dp touch target minimum). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = Penelope.tokens.textPrimary,
) {
    val t = Penelope.tokens
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(t.glassStrong)
            .border(1.dp, t.glassBorder, CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/** Compact pill used for suggested questions and small actions. */
@Composable
fun SuggestionPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val t = Penelope.tokens
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(t.glassFill)
            .border(1.dp, Brush.linearGradient(t.brandColors.map { it.copy(alpha = 0.45f) }), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = t.brandColors[1], modifier = Modifier.size(18.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = t.textPrimary)
    }
}

/** The assistant's name as a blue-to-violet gradient wordmark. */
@Composable
fun BrandWordmark(name: String, modifier: Modifier = Modifier, fontSizeSp: Int = 26) {
    val t = Penelope.tokens
    Text(
        name,
        modifier = modifier.semantics { heading() },
        style = TextStyle(
            brush = Brush.linearGradient(listOf(t.brandColors[1], t.brandColors[2])),
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
        ),
    )
}

/** Small logo: a luminous badge with a road-like arc, hinting at cars without looking like a scanner. */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val t = Penelope.tokens
    Canvas(modifier.size(size)) {
        drawCircle(Brush.linearGradient(t.brandColors, start = Offset.Zero, end = Offset(this.size.width, this.size.height)))
        val w = this.size.width
        val road = Path().apply {
            moveTo(w * 0.22f, w * 0.68f)
            cubicTo(w * 0.38f, w * 0.40f, w * 0.62f, w * 0.40f, w * 0.78f, w * 0.68f)
        }
        drawPath(road, Color.White, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round))
        drawCircle(Color.White, radius = w * 0.07f, center = Offset(w * 0.5f, w * 0.36f))
    }
}

/** Section header used inside screens. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Penelope.tokens.textPrimary,
        modifier = modifier.semantics { heading() },
    )
}

/** "Want more details?" style expandable section. */
@Composable
fun ExpandableSection(title: String, modifier: Modifier = Modifier, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val t = Penelope.tokens
    Column(modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "hide details" else "show details") { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "expanded" else "collapsed" }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = t.brandColors[1], modifier = Modifier.weight(1f))
            Icon(
                if (expanded) PenelopeIcons.ChevronUp else PenelopeIcons.ChevronDown,
                contentDescription = null, tint = t.brandColors[1], modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

/** Transparent top bar for secondary screens. */
@Composable
fun PenelopeTopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Penelope.tokens.textPrimary,
            modifier = Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp).semantics { heading() },
        )
        actions()
    }
}

/** Small status badge with text (never color alone). */
@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Box(Modifier.size(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Line icons drawn from path data (no icon library needed). 24×24 viewport, 1.8dp strokes. */
object PenelopeIcons {
    private fun line(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Home = line("home", "M4,11 L12,4.5 L20,11", "M6.5,9.5 V19.5 H17.5 V9.5", "M10,19.5 V14.5 H14 V19.5")
    val Garage = line(
        "garage",
        "M3.5,15.5 V12.2 L6,7.6 C6.3,7.1 6.8,6.8 7.4,6.8 H16.6 C17.2,6.8 17.7,7.1 18,7.6 L20.5,12.2 V15.5 Z",
        "M3.5,12.2 H20.5", "M6,15.5 V18", "M18,15.5 V18", "M7,9.8 H17",
    )
    val Chat = line("chat", "M5,5 H19 A2,2 0 0 1 21,7 V15 A2,2 0 0 1 19,17 H10 L6,20 V17 H5 A2,2 0 0 1 3,15 V7 A2,2 0 0 1 5,5 Z", "M8,10 H16", "M8,13 H13")
    val History = line("history", "M12,3.5 A8.5,8.5 0 1 1 3.5,12", "M3.5,12 A8.5,8.5 0 0 1 6,6", "M12,7.5 V12 L15,14", "M3.5,5.5 V8.5 H6.5")
    val Pause = line("pause", "M9,6.5 V17.5", "M15,6.5 V17.5")
    val Play = line("play", "M8,5.5 L18,12 L8,18.5 Z")
    val Close = line("close", "M7,7 L17,17", "M17,7 L7,17")
    val ChevronDown = line("down", "M6,9.5 L12,15 L18,9.5")
    val ChevronUp = line("up", "M6,14.5 L12,9 L18,14.5")
    val ChevronRight = line("right", "M9.5,6 L15,12 L9.5,18")
    val Sparkle = line("sparkle", "M12,3.5 L13.6,10.4 L20.5,12 L13.6,13.6 L12,20.5 L10.4,13.6 L3.5,12 L10.4,10.4 Z")
    val Photo = line("photo", "M4,7 H7.5 L9,5 H15 L16.5,7 H20 V18.5 H4 Z", "M12,9.5 A3.2,3.2 0 1 1 11.99,9.5")
    val Shield = line("shield", "M12,3.5 L19,6.5 V11.5 C19,16 16,19 12,20.5 C8,19 5,16 5,11.5 V6.5 Z")
    val Steps = line("steps", "M5,7 H5.01", "M9,7 H19", "M5,12 H5.01", "M9,12 H19", "M5,17 H5.01", "M9,17 H19")
    val Alert = line("alert", "M12,4 L21,19.5 H3 Z", "M12,10 V14", "M12,17 H12.01")
    val Info = line("info", "M12,3.5 A8.5,8.5 0 1 1 11.99,3.5", "M12,11 V16.5", "M12,7.8 H12.01")
    val Check = line("check", "M5,12.5 L10,17 L19,7.5")
    val Link = line("link", "M7,12 C7,9 9,7 12,7 H13", "M17,12 C17,15 15,17 12,17 H11", "M9,12 H15")
}
