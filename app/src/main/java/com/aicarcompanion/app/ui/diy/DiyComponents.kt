package com.aicarcompanion.app.ui.diy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A flat, opaque panel: one hairline border, no shadow, no gradient. */
@Composable
fun DiyPanel(
    modifier: Modifier = Modifier,
    padding: Dp = DiyDimens.L,
    content: @Composable ColumnScope.() -> Unit,
) {
    val k = diyTokens()
    Column(
        modifier
            .fillMaxWidth()
            .clip(DiyDimens.PanelShape)
            .background(k.surface)
            .border(1.dp, k.hairline, DiyDimens.PanelShape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(DiyDimens.M),
        content = content,
    )
}

/**
 * Panel title, with an optional trailing link or status. Announced as a heading. At large font sizes
 * the trailing part moves under the title so the title is never squeezed into a narrow column.
 */
@Composable
fun DiySectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val k = diyTokens()
    val titleText = @Composable { m: Modifier ->
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = k.textPrimary,
            modifier = m.semantics { heading() },
        )
    }
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
            titleText(Modifier)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DiyDimens.S)) { trailing() }
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
            titleText(Modifier.weight(1f))
            trailing()
        }
    }
}

/** Flat 48 dp round icon button with a hairline ring. */
@Composable
fun DiyIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = diyTokens().textPrimary,
    ring: Color = diyTokens().hairline,
) {
    val k = diyTokens()
    Box(
        modifier
            .size(DiyDimens.MinTouch)
            .clip(CircleShape)
            .background(k.raised)
            .border(1.dp, ring, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A link-style button: accent text, at least 48 dp tall. */
@Composable
fun DiyTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val k = diyTokens()
    Row(
        modifier
            .heightIn(min = DiyDimens.MinTouch)
            .widthIn(min = DiyDimens.MinTouch)
            .clip(DiyDimens.ChipShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DiyDimens.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiyDimens.Xs),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = k.accent, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = k.accent)
    }
}

/** The one filled button on a panel: a flat accent fill, no gradient. */
@Composable
fun DiyPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val k = diyTokens()
    Row(
        modifier
            .heightIn(min = DiyDimens.MinTouch)
            .clip(DiyDimens.ChipShape)
            .background(k.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DiyDimens.L, vertical = DiyDimens.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiyDimens.S, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = k.onAccent, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = k.onAccent)
    }
}

/**
 * A tappable chip: [label] on screen, [spoken] for TalkBack. At least 48 dp in both directions.
 */
@Composable
fun DiyChip(
    label: String,
    spoken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    clickLabel: String = "open $label",
) {
    val k = diyTokens()
    Row(
        modifier
            .heightIn(min = DiyDimens.MinTouch)
            .widthIn(min = DiyDimens.MinTouch)
            .clip(DiyDimens.ChipShape)
            .background(k.raised)
            .border(1.dp, k.hairline, DiyDimens.ChipShape)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = DiyDimens.M, vertical = DiyDimens.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiyDimens.S, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = k.accent, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = k.textPrimary)
    }
}

/** A small status label with an icon and words (never color alone). */
@Composable
fun DiyStatusTag(text: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    val k = diyTokens()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(k.raised)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = DiyDimens.M, vertical = DiyDimens.Xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiyDimens.Xs + 2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = k.textPrimary)
    }
}
