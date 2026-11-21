package com.gdhameeja.flighttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gdhameeja.flighttracker.data.FlightEnd
import com.gdhameeja.flighttracker.data.TrackedFlight
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightScreen(
    tracked: TrackedFlight?,
    refreshing: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReload: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // While open, re-read the server's copy every minute (free; the server does the checking)
    LaunchedEffect(tracked?.id) {
        while (true) {
            delay(60_000)
            onReload()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tracked?.flight?.number ?: "Flight") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Check now") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Remove flight") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (tracked == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Flight not found") }
            return@Scaffold
        }
        val f = tracked.flight
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
                item { FlightCard(f) }

                item {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (f.isInAir() || f.status == "arrived") {
                            InfoTile("Arr. terminal", f.arrival.terminal, Modifier.weight(1f))
                            InfoTile("Belt", f.arrival.baggageBelt, Modifier.weight(1f), highlight = f.arrival.baggageBelt != null)
                        } else {
                            InfoTile("Terminal", f.departure.terminal, Modifier.weight(1f))
                            InfoTile("Gate", f.departure.gate, Modifier.weight(1f), highlight = f.departure.gate != null)
                            InfoTile("Check-in", f.departure.checkInDesk, Modifier.weight(1f))
                        }
                    }
                }

                tracked.booking?.takeIf { it.pnr != null || it.seats != null || it.passengers.isNotEmpty() }?.let { b ->
                    item { SectionTitle("Booking") }
                    item {
                        DetailCard {
                            b.pnr?.let { DetailRow("Booking reference", it) }
                            b.seats?.let { DetailRow("Seat", it) }
                            if (b.passengers.isNotEmpty()) DetailRow(if (b.passengers.size == 1) "Passenger" else "Passengers", b.passengers.joinToString(", "))
                        }
                    }
                }

                item { SectionTitle("Departure") }
                item { EndDetails(f.departure, isDeparture = true) }
                item { SectionTitle("Arrival") }
                item { EndDetails(f.arrival, isDeparture = false) }

                if (f.aircraft.model != null || f.aircraft.reg != null) {
                    item { SectionTitle("Aircraft") }
                    item {
                        DetailCard {
                            f.aircraft.model?.let { DetailRow("Type", it) }
                            f.aircraft.reg?.let { DetailRow("Registration", it) }
                        }
                    }
                }

                if (tracked.events.isNotEmpty()) {
                    item { SectionTitle("Updates") }
                    items(tracked.events.reversed()) { e ->
                        Row(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                            Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(e.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Fmt.relative(e.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    val next = tracked.nextCheckAt?.let { " · next check ${Fmt.relative(it)}" } ?: ""
                    Text(
                        "Updated ${Fmt.relative(f.fetchedAt)}$next",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${tracked?.flight?.number}?") },
            text = { Text("It stops being tracked and leaves your trips.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EndDetails(end: FlightEnd, isDeparture: Boolean) {
    val tz = end.airport.timeZone
    DetailCard {
        DetailRow("Airport", listOfNotNull(end.airport.name, end.airport.code).distinct().joinToString(" · "))
        DetailRow("Scheduled", "${Fmt.time(end.scheduled, tz)}  ${Fmt.day(end.scheduled, tz)}")
        if (end.actual != null) DetailRow(if (isDeparture) "Departed" else "Landed", Fmt.time(end.actual, tz))
        else if (end.estimated != null && end.estimated != end.scheduled) DetailRow("Expected", Fmt.time(end.estimated, tz))
        Fmt.delayMinutes(end)?.takeIf { it >= 5 }?.let { DetailRow("Late by", Fmt.duration(it)) }
        end.terminal?.let { DetailRow("Terminal", it) }
        end.gate?.let { DetailRow("Gate", it) }
        if (isDeparture) end.checkInDesk?.let { DetailRow("Check-in desks", it) }
        if (!isDeparture) end.baggageBelt?.let { DetailRow("Baggage belt", it) }
        tz?.let { DetailRow("Local time zone", it.substringAfter('/').replace('_', ' ')) }
    }
}

@Composable
fun DetailCard(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
