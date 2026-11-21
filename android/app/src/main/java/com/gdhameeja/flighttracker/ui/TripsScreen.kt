package com.gdhameeja.flighttracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gdhameeja.flighttracker.UiState
import com.gdhameeja.flighttracker.data.TrackedFlight
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    state: UiState,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val now = Instant.now()
    // Still "upcoming" until 6 hours after landing, so the belt and landing stay on top
    val (upcoming, past) = state.flights.partition { t ->
        val end = t.flight.endsAt()
        end == null || Duration.between(end, now).toHours() < 6
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Flights") },
                actions = {
                    IconButton(onClick = onSettings) {
                        BadgedBox(badge = { if (state.update != null || !state.connected) Badge() }) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add flight") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(if (upcoming.isEmpty()) "Upcoming" else "Upcoming (${upcoming.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(if (past.isEmpty()) "Past" else "Past (${past.size})") })
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                val list = if (tab == 0) upcoming else past.reversed()
                if (list.isEmpty()) {
                    if (tab == 0) EmptyTrips(state.connected) else EmptyPast()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (tab == 1) item { StatsCard(stats(past)) }
                        items(list, key = { it.id }) { t: TrackedFlight ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FlightCard(t.flight, onClick = { onOpen(t.id) })
                                // A connection to the next flight sits right under this one
                                if (tab == 0) state.connections.firstOrNull { it.fromId == t.id }?.let { ConnectionCard(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPast() {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Flights you've taken show up here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(48.dp),
            )
        }
    }
}

@Composable
private fun EmptyTrips(connected: Boolean) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(PlaneIcon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(20.dp))
                Text(if (connected) "No flights yet" else "Connect to your server", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (connected) "Add your next flight and it'll be tracked here, with alerts for delays, gate changes and your baggage belt."
                    else "Open Settings (top right) and enter your server address and app token, or open the setup link.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
