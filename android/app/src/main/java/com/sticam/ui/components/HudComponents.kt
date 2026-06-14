package com.sticam.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sticam.ui.theme.*
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
//  HUD Metric Label  (top-aligned readout: "ISO  0400")
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HudMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    warning: Boolean = false,
) {
    val color = when {
        warning -> DangerRed
        active  -> TermGreen
        else    -> TermGreenDim
    }
    // Pulsing dot when active
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (active) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(color.copy(alpha = pulse), RoundedCornerShape(50))
            )
        }
        Column {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Normal,
                color = color.copy(alpha = 0.5f),
                letterSpacing = 2.sp,
            )
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                letterSpacing = 1.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  HUD Slider  (drag control for ISO, shutter, etc.)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HudSlider(
    label: String,
    value: Float,
    valueMin: Float,
    valueMax: Float,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White,
    unit: String = "",
) {
    var trackWidth by remember { mutableStateOf(0f) }
    val fraction = ((value - valueMin) / (valueMax - valueMin)).coerceIn(0f, 1f)

    // Glow animation on the thumb
    val glowAnim by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Label row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontFamily = Lalezar,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.9f),
                letterSpacing = 1.sp,
            )
            Box(
                modifier = Modifier
                    .border(2.dp, Color.White, RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$displayValue $unit".trim(),
                    fontFamily = Lalezar,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 0.5.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Stroke, RoundedCornerShape(4.dp))
                .background(Surface800)
                .pointerInput(valueMin, valueMax) {
                    trackWidth = size.width.toFloat()
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val pos = change.position.x.coerceIn(0f, trackWidth)
                        val newFrac = pos / trackWidth
                        onValueChange(valueMin + newFrac * (valueMax - valueMin))
                    }
                }
                .pointerInput(valueMin, valueMax) {
                    trackWidth = size.width.toFloat()
                    detectTapGestures { offset ->
                        val newFrac = (offset.x / trackWidth).coerceIn(0f, 1f)
                        onValueChange(valueMin + newFrac * (valueMax - valueMin))
                    }
                }
                .drawBehind {
                    trackWidth = size.width
                    drawSliderTrack(fraction, trackColor, glowAnim)
                }
        )
    }
}

private fun DrawScope.drawSliderTrack(fraction: Float, color: Color, glowAlpha: Float) {
    val fillWidth = size.width * fraction
    val cy = size.height / 2f

    // Filled region — gradient from dim to bright
    drawRect(
        brush = Brush.horizontalGradient(
            0f to color.copy(alpha = 0.2f),
            1f to color.copy(alpha = 0.7f),
            endX = fillWidth
        ),
        size = androidx.compose.ui.geometry.Size(fillWidth, size.height)
    )

    // Tick marks every 10%
    for (i in 1..9) {
        val x = size.width * i / 10f
        val isActive = x <= fillWidth
        drawLine(
            color = if (isActive) color.copy(alpha = 0.3f) else color.copy(alpha = 0.08f),
            start = Offset(x, 4f), end = Offset(x, size.height - 4f),
            strokeWidth = 1f
        )
    }

    // Thumb
    val thumbX = fillWidth.coerceIn(2f, size.width - 2f)
    drawLine(
        color = color.copy(alpha = glowAlpha),
        start = Offset(thumbX, 2f),
        end = Offset(thumbX, size.height - 2f),
        strokeWidth = 3f,
        cap = StrokeCap.Round
    )
    // Thumb glow
    drawLine(
        color = color.copy(alpha = 0.15f * glowAlpha),
        start = Offset(thumbX, 2f),
        end = Offset(thumbX, size.height - 2f),
        strokeWidth = 10f,
        cap = StrokeCap.Round
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Status Badge
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StatusBadge(
    text: String,
    color: Color = TermGreen,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "badge").animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "a"
    )
    Row(
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(5.dp).background(color.copy(alpha = pulse), RoundedCornerShape(50)))
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = color,
            letterSpacing = 2.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Corner Bracket  (HUD frame decoration)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CornerBrackets(
    modifier: Modifier = Modifier,
    color: Color = TermGreen,
    size: Dp = 20.dp,
    strokeWidth: Dp = 1.5.dp,
) {
    Box(modifier = modifier.drawBehind {
        val s = size.toPx()
        val sw = strokeWidth.toPx()
        val c = color

        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(x, y), Offset(x + dx * s, y), sw)
            drawLine(c, Offset(x, y), Offset(x, y + dy * s), sw)
        }
        corner(0f, 0f, 1f, 1f)
        corner(this.size.width, 0f, -1f, 1f)
        corner(0f, this.size.height, 1f, -1f)
        corner(this.size.width, this.size.height, -1f, -1f)
    })
}

// ─────────────────────────────────────────────────────────────────────────────
//  Mode Toggle Button  (USB ↔ Wi-Fi)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ModeToggle(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .border(1.dp, Stroke, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
    ) {
        options.forEachIndexed { idx, label ->
            val active = idx == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (active) TermGreenMuted else Color.Transparent)
                    .pointerInput(Unit) { detectTapGestures { onSelect(idx) } }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) TermGreen else TermGreenDim,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
