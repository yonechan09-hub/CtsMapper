package com.example.ctsmapper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Look & feel only. Palette, type ramp and shared components for every screen.
 * Battery rules: near-black background (OLED pixels stay off), flat surfaces, no gradients, blurs or shadows,
 * and no looping animations anywhere. Motion is limited to the short built-in transitions that follow a tap.
 */

object Ct {
    val Bg = Color(0xFF0B0B0E)
    val Card = Color(0xFF15151B)
    val Raised = Color(0xFF1D1D25)
    val Hairline = Color(0xFF26262E)

    val Teal = Color(0xFF2EE6B6)
    val TealDim = Color(0xFF122E26)
    val Peri = Color(0xFF8B9DFF)
    val PeriDim = Color(0xFF1C2138)

    val Ink = Color(0xFFF2F2F5)
    val Ink2 = Color(0xFF9A9AA6)

    val Danger = Color(0xFFFF6B6B)
    val DangerDim = Color(0xFF2C161A)
    val Warn = Color(0xFFFFC56B)
    val WarnDim = Color(0xFF2C2414)
}

// Noto Sans Display + Noto Sans Mono, Latin subset bundled in res/font (other scripts fall back to the system font).
val CtSans = FontFamily(
    Font(R.font.ct_sans_regular, FontWeight.Normal),
    Font(R.font.ct_sans_medium, FontWeight.Medium),
    Font(R.font.ct_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ct_sans_bold, FontWeight.Bold)
)
val CtMono = FontFamily(Font(R.font.ct_mono_medium, FontWeight.Medium))

private val CtScheme = darkColorScheme(
    primary = Ct.Teal,
    onPrimary = Ct.Bg,
    secondary = Ct.Peri,
    onSecondary = Ct.Bg,
    background = Ct.Bg,
    onBackground = Ct.Ink,
    surface = Ct.Bg,
    onSurface = Ct.Ink,
    surfaceVariant = Ct.Card,
    onSurfaceVariant = Ct.Ink2,
    surfaceContainer = Ct.Raised,
    outline = Ct.Hairline,
    error = Ct.Danger
)

@Composable
fun CtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CtScheme) {
        Surface(Modifier.fillMaxSize(), color = Ct.Bg, contentColor = Ct.Ink, content = content)
    }
}

// ------------------------------------------------------------------ text
@Composable
fun CtText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Ct.Ink2,
    family: FontFamily = CtSans,
    spacing: Float = 0f,
    align: TextAlign? = null
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = family,
        letterSpacing = spacing.sp,
        lineHeight = (size * 1.35f).sp,
        textAlign = align
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CtText(title, size = 30, weight = FontWeight.SemiBold, color = Ct.Ink)
        if (subtitle != null) CtText(subtitle, size = 15, color = Ct.Ink2)
    }
}

@Composable
fun SectionLabel(text: String) {
    CtText(
        text.uppercase(),
        modifier = Modifier.padding(top = 12.dp),
        size = 15, weight = FontWeight.SemiBold, color = Ct.Ink2, spacing = 1.2f
    )
}

@Composable
fun CardTitle(text: String) {
    CtText(text, size = 18, weight = FontWeight.SemiBold, color = Ct.Ink)
}

// ------------------------------------------------------------------ surfaces
@Composable
fun CtCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ct.Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun CtDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ct.Hairline))
}

@Composable
fun IconChip(glyph: String, tint: Color = Ct.Teal) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Ct.Raised),
        contentAlignment = Alignment.Center
    ) {
        CtText(glyph, size = 18, weight = FontWeight.SemiBold, color = tint)
    }
}

// ------------------------------------------------------------------ state
enum class StatusTone { Ok, Warn, Bad }

private fun toneColor(t: StatusTone): Color = when (t) {
    StatusTone.Ok -> Ct.Teal
    StatusTone.Warn -> Ct.Warn
    StatusTone.Bad -> Ct.Danger
}

@Composable
fun StatusLine(tone: StatusTone, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(toneColor(tone)))
        Spacer(Modifier.width(10.dp))
        CtText(text, size = 15, weight = FontWeight.Medium, color = Ct.Ink)
    }
}

/** Static pill: dot + label. No spinner, no polling. */
@Composable
fun StatusPill(text: String, fg: Color, bg: Color) {
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(bg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        CtText(text, size = 15, weight = FontWeight.SemiBold, color = fg)
    }
}

// ------------------------------------------------------------------ controls
@Composable
fun CtSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Ct.Bg,
            checkedTrackColor = Ct.Teal,
            checkedBorderColor = Ct.Teal,
            uncheckedThumbColor = Ct.Ink2,
            uncheckedTrackColor = Ct.Hairline,
            uncheckedBorderColor = Ct.Hairline
        )
    )
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    hint: String? = null,
    tone: StatusTone? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (tone != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(toneColor(tone)))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            CtText(title, size = 17, weight = FontWeight.Medium, color = Ct.Ink)
            if (hint != null) CtText(hint, size = 14)
        }
        Spacer(Modifier.width(12.dp))
        CtSwitch(checked, onChange)
    }
}

enum class BtnKind { Primary, Secondary }

/** Pill button. Primary = teal fill; Secondary = periwinkle on dim periwinkle. [big] is the full-size call to action. */
@Composable
fun CtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Secondary,
    enabled: Boolean = true,
    big: Boolean = false
) {
    val bg = if (kind == BtnKind.Primary) Ct.Teal else Ct.PeriDim
    val fg = if (kind == BtnKind.Primary) Ct.Bg else Ct.Peri
    Box(
        modifier
            .heightIn(min = if (big) 52.dp else 40.dp)
            .clip(CircleShape)
            .background(bg)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (big) 28.dp else 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        CtText(
            text,
            size = if (big) 18 else 14,
            weight = if (kind == BtnKind.Primary) FontWeight.Bold else FontWeight.SemiBold,
            color = fg,
            align = TextAlign.Center
        )
    }
}

// ------------------------------------------------------------------ bottom navigation (Home / Log / Setup)
@Composable
fun CtBottomNav(current: String, go: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ct.Bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ct.Hairline))
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            listOf("main" to "Home", "log" to "Log", "guide" to "Setup").forEach { (id, label) ->
                val on = current == id
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { go(id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.size(10.dp).border(2.dp, if (on) Ct.Teal else Ct.Ink2, CircleShape))
                    Spacer(Modifier.height(6.dp))
                    CtText(
                        label, size = 15, weight = FontWeight.Medium,
                        color = if (on) Ct.Teal else Ct.Ink2
                    )
                }
            }
        }
    }
}
