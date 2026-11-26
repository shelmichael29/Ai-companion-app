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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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

/** The one continuous dashboard surface: sections sit inside it, separated by hairlines. */
@Composable
fun DiySurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val k = diyTokens()
    Column(
        modifier
            .fillMaxWidth()
            .clip(DiyDimens.PanelShape)
            .background(k.surface)
            .border(1.dp, k.hairline, DiyDimens.PanelShape),
        content = content,
    )
}

/** A section of the [DiySurface]: a hairline on top (unless [divider] is false) and 16/12 dp padding. */
@Composable
fun DiySection(
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val k = diyTokens()
    Column(modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(k.hairline))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DiyDimens.L, vertical = DiyDimens.M),
            verticalArrangement = Arrangement.spacedBy(DiyDimens.M),
            content = content,
        )
    }
}

/** A thin vertical hairline between inline values. */
@Composable
fun DiyVerticalRule(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(diyTokens().hairline))
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
            style = DiyType.sectionLabel,
            color = k.textSecondary,
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
    outlined: Boolean = true,
) {
    val k = diyTokens()
    Row(
        modifier
            .heightIn(min = DiyDimens.MinTouch)
            .widthIn(min = DiyDimens.MinTouch)
            .clip(DiyDimens.ChipShape)
            .then(if (outlined) Modifier.background(k.raised).border(1.dp, k.hairline, DiyDimens.ChipShape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = if (outlined) DiyDimens.M else DiyDimens.S, vertical = DiyDimens.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiyDimens.S, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = k.accent, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = k.textPrimary)
    }
}

/** A small inline status: icon and words (never color alone), with no box around it. */
@Composable
fun DiyStatusTag(text: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    val k = diyTokens()
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiyDimens.Xs + 2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = k.textPrimary)
    }
}
