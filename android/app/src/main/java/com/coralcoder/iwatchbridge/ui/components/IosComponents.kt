package com.coralcoder.iwatchbridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coralcoder.iwatchbridge.ui.theme.Ios

/** Large-title navigation header, as in iOS settings screens. */
@Composable
fun LargeTitle(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = Ios.colors.label, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Inline (small) navigation bar with a back button. */
@Composable
fun NavBar(title: String, back: String? = null, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(44.dp)) {
        if (onBack != null) {
            Row(
                Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = Ios.colors.tint, modifier = Modifier.size(18.dp))
                if (back != null) Text(back, color = Ios.colors.tint, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ios.colors.label, modifier = Modifier.align(Alignment.Center))
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { trailing() }
    }
}

/** An inset grouped section: optional header, rounded card of rows, optional footer. */
fun LazyListScope.section(
    header: String? = null,
    footer: String? = null,
    rows: List<@Composable () -> Unit>,
) {
    item {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = if (header != null) 18.dp else 24.dp)) {
            if (header != null) {
                Text(
                    header.uppercase(), style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondaryLabel,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                )
            }
            Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Ios.colors.cell)) {
                rows.forEachIndexed { i, row ->
                    row()
                    if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Ios.colors.separator)
                }
            }
            if (footer != null) {
                Text(
                    footer, style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondaryLabel,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }
        }
    }
}

/** Rounded-square SF-Symbols-style icon tile used at the start of settings rows. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 29.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(7.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
fun ListRow(
    title: String,
    value: String? = null,
    icon: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    chevron: Boolean = true,
    checked: Boolean = false,
    destructive: Boolean = false,
    tint: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> Ios.colors.tertiaryLabel
                    destructive -> Ios.colors.red
                    tint -> Ios.colors.tint
                    else -> Ios.colors.label
                },
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondaryLabel)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyLarge, color = Ios.colors.secondaryLabel)
        if (checked) Icon(Icons.Filled.Check, null, tint = Ios.colors.tint, modifier = Modifier.size(20.dp))
        else if (chevron && onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ios.colors.tertiaryLabel)
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, icon: (@Composable () -> Unit)? = null, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Ios.colors.label else Ios.colors.tertiaryLabel)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondaryLabel)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Ios.colors.green, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Ios.colors.fill, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Filled pill button (iOS "borderedProminent"). */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Ios.colors.tint else Ios.colors.fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else Ios.colors.tertiaryLabel, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun TextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = Ios.colors.tint, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        modifier = modifier.clickable(onClick = onClick).padding(12.dp),
    )
}

@Composable
fun FootnoteText(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondaryLabel, textAlign = TextAlign.Center, modifier = modifier)

/**
 * A drawn Apple Watch, used as the hero image on My Watch and in pairing. [ultra] gives the
 * flat-sapphire, orange-button Ultra look; otherwise the rounded Series look.
 */
@Composable
fun WatchIllustration(modifier: Modifier = Modifier, ultra: Boolean = false, faceColor: Color = Color(0xFF0B0B0C), showRings: Boolean = true) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val caseW = w * 0.62f; val caseH = h * 0.58f
        val left = (w - caseW) / 2; val top = (h - caseH) / 2
        val band = Color(if (ultra) 0xFFE36A2E else 0xFF2C2C2E)
        val metal = Color(if (ultra) 0xFFB9B6AE else 0xFF3A3A3C)
        // bands
        drawRoundRect(band, Offset(left + caseW * 0.14f, 0f), Size(caseW * 0.72f, top + 10f), CornerRadius(12f))
        drawRoundRect(band, Offset(left + caseW * 0.14f, top + caseH - 10f), Size(caseW * 0.72f, h - top - caseH + 10f), CornerRadius(12f))
        // case
        val r = if (ultra) caseW * 0.16f else caseW * 0.24f
        drawRoundRect(metal, Offset(left, top), Size(caseW, caseH), CornerRadius(r))
        // crown and button
        drawRoundRect(metal, Offset(left + caseW - 2f, top + caseH * 0.22f), Size(caseW * 0.07f, caseH * 0.16f), CornerRadius(6f))
        drawRoundRect(metal, Offset(left + caseW - 2f, top + caseH * 0.48f), Size(caseW * 0.045f, caseH * 0.2f), CornerRadius(6f))
        if (ultra) drawRoundRect(Color(0xFFFF6A00), Offset(left - caseW * 0.04f, top + caseH * 0.3f), Size(caseW * 0.05f, caseH * 0.22f), CornerRadius(6f))
        // display
        val inset = caseW * 0.06f
        drawRoundRect(faceColor, Offset(left + inset, top + inset), Size(caseW - inset * 2, caseH - inset * 2), CornerRadius(r * 0.8f))
        if (showRings) {
            val c = Offset(w / 2, h / 2)
            val stroke = caseW * 0.06f
            listOf(Color(0xFFFA114F) to 0.30f, Color(0xFF92E82A) to 0.22f, Color(0xFF1EEAEF) to 0.14f).forEachIndexed { i, (col, rad) ->
                val rr = caseW * rad
                drawArc(col.copy(alpha = 0.25f), 0f, 360f, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(stroke))
                drawArc(col, -90f, listOf(290f, 230f, 320f)[i], false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

val ListPadding = PaddingValues(bottom = 32.dp)

@Composable
fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ios.colors.label,
    modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
