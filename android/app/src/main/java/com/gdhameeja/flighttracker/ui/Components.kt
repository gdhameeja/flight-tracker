package com.gdhameeja.flighttracker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gdhameeja.flighttracker.data.Flight
import com.gdhameeja.flighttracker.data.FlightEnd

/** Material "flight" glyph (not in the core icon set). Points up; rotate 90° to fly right. */
val PlaneIcon: ImageVector by lazy {
    ImageVector.Builder("plane", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M21,16v-2l-8,-5V3.5c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5l8,2.5z"),
        fill = SolidColor(Color.Black),
    ).build()
}

@Composable
fun StatusPill(info: StatusInfo, modifier: Modifier = Modifier) {
    val color by animateColorAsState(LocalTones.current.of(info.tone), label = "status")
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(info.label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Two-letter airline badge, coloured from the code so each airline keeps its colour. */
@Composable
fun AirlineBadge(code: String?, size: Int = 36) {
    val palette = listOf(0xFF1E4FD8, 0xFF7B3FE4, 0xFF0E8C7F, 0xFFD9480F, 0xFFC2185B, 0xFF2E7D32, 0xFF5D4037, 0xFF00838F)
    val c = Color(palette[((code ?: "").hashCode() and 0x7fffffff) % palette.size])
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(c), contentAlignment = Alignment.Center) {
        Text(code ?: "✈", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.36).sp)
    }
}

/** Big time with the scheduled one struck through above it when they differ. */
@Composable
fun EndTime(end: FlightEnd, align: Alignment.Horizontal) {
    val tz = end.airport.timeZone
    val late = Fmt.delayMinutes(end) ?: 0
    val tones = LocalTones.current
    Column(horizontalAlignment = align) {
        Text(end.airport.code, style = CodeStyle)
        if (late >= 5 || late <= -10) {
            Text(
                Fmt.time(end.scheduled, tz), style = MaterialTheme.typography.bodySmall,
                textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(Fmt.time(end.best, tz), style = TimeStyle, color = if (late >= 15) tones.warn else tones.good)
        } else {
            Text(Fmt.time(end.best, tz), style = TimeStyle)
        }
        Text(
            end.airport.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (align == Alignment.End) TextAlign.End else TextAlign.Start,
        )
    }
}

/** Dashed route line with the plane on it; the plane moves along while in the air. */
@Composable
fun RouteLine(flight: Flight, modifier: Modifier = Modifier) {
    val progress = flight.progress()
    val color = MaterialTheme.colorScheme.outlineVariant
    val done = MaterialTheme.colorScheme.primary
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val y = size.height / 2
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)), cap = StrokeCap.Round)
                if (progress != null) drawLine(done, Offset(0f, y), Offset(size.width * progress, y), strokeWidth = 5f, cap = StrokeCap.Round)
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val x = (maxWidth - 22.dp) * (progress ?: 0.5f)
                Icon(PlaneIcon, null, tint = if (progress != null) done else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = x).size(22.dp).rotate(90f))
            }
        }
        Fmt.flightMinutes(flight)?.let {
            Text(Fmt.duration(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FlightCard(flight: Flight, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, dim: Boolean = false) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AirlineBadge(flight.airline.iata)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(flight.number, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(flight.airline.name, Fmt.day(flight.departure.scheduled, flight.departure.airport.timeZone)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(flight.statusInfo())
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { EndTime(flight.departure, Alignment.Start) }
                RouteLine(flight, Modifier.weight(1.1f).padding(top = 8.dp, start = 6.dp, end = 6.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) { EndTime(flight.arrival, Alignment.End) }
            }
            val chips = buildList {
                flight.departure.terminal?.let { add("T$it") }
                flight.departure.gate?.let { add("Gate $it") }
                flight.arrival.baggageBelt?.let { add("Belt $it") }
            }
            val countdown = when {
                flight.isFinished() -> null
                flight.isInAir() -> "Lands ${Fmt.relative(flight.arrival.best)}"
                else -> "Departs ${Fmt.relative(flight.departure.best)}"
            }
            if (chips.isNotEmpty() || countdown != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { SmallChip(it) }
                    Spacer(Modifier.weight(1f))
                    countdown?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
    val m = modifier.fillMaxWidth().alpha(if (dim) 0.6f else 1f)
    if (onClick != null) ElevatedCard(onClick = onClick, modifier = m, shape = RoundedCornerShape(20.dp)) { content() }
    else ElevatedCard(modifier = m, shape = RoundedCornerShape(20.dp)) { content() }
}

@Composable
fun SmallChip(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Terminal / Gate / Belt tile on the flight screen. */
@Composable
fun InfoTile(label: String, value: String?, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), letterSpacing = 1.sp)
            Spacer(Modifier.height(2.dp))
            Text(value ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun ProgressBar(p: Float) = LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
