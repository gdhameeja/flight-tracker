package com.gdhameeja.flighttracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gdhameeja.flighttracker.BuildConfig
import com.gdhameeja.flighttracker.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    serverUrl: String,
    token: String,
    snackbar: SnackbarHostState,
    onBack: (() -> Unit)?,
    onConnect: (String, String) -> Unit,
    onPair: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstall: () -> Unit,
    onShown: () -> Unit,
    onCheckedBags: (Boolean) -> Unit = {},
) {
    var url by remember(serverUrl) { mutableStateOf(serverUrl) }
    var tok by remember(token) { mutableStateOf(token) }
    var code by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onShown() }
    val tones = LocalTones.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // ── App version and updates ─────────────────────────────────────
            SectionTitle("App")
            DetailCard {
                DetailRow("Version", BuildConfig.VERSION_NAME)
                Column(Modifier.padding(vertical = 12.dp)) {
                    val rel = state.update
                    if (rel != null) {
                        Text("Update available: ${rel.name}", style = MaterialTheme.typography.titleMedium)
                        if (rel.notes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(rel.notes.trim().take(400), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        val p = state.updateProgress
                        if (p != null) {
                            ProgressBar(p)
                            Spacer(Modifier.height(6.dp))
                            Text("Downloading… ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Button(onClick = onInstall, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Download and install") }
                        }
                    } else {
                        FilledTonalButton(onClick = onCheckUpdate, enabled = !state.updateChecking, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            if (state.updateChecking) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text("Check for updates")
                        }
                    }
                }
            }

            // ── Server ─────────────────────────────────────────────────────
            SectionTitle("Server")
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.connected) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                            tint = if (state.connected) tones.good else tones.warn,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.connected) "Connected" else "Not connected yet", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedTextField(
                        value = code, onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                        label = { Text("Pairing code") }, placeholder = { Text("6 digits from your laptop") }, singleLine = true,
                        textStyle = MaterialTheme.typography.headlineSmall,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { onPair(code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text(if (state.connected) "Pair again" else "Connect")
                    }
                    androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "Hide advanced" else "Advanced: server address and token")
                    }
                    if (advanced) {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it }, label = { Text("Server address") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = tok, onValueChange = { tok = it }, label = { Text("App token") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(onClick = { onConnect(url, tok) }, enabled = url.isNotBlank() && tok.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Text("Save and test")
                        }
                    }
                }
            }

            // ── How you travel ───────────────────────────────────────────
            state.userSettings?.let { us ->
                SectionTitle("How you travel")
                DetailCard {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("I usually check a bag", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Changes connection times and when to be at the airport. Off: cabin bag only.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(checked = us.checkedBags, onCheckedChange = onCheckedBags)
                    }
                }
            }

            // ── Data budget ────────────────────────────────────────────────
            state.usage?.aerodatabox?.let { u ->
                SectionTitle("Flight data this month")
                DetailCard {
                    val limit = u.monthlyLimit ?: 400
                    val left = u.unitsRemaining
                    DetailRow("AeroDataBox", if (left != null) "$left of $limit units left" else "Not used yet")
                    if (left != null) {
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(left.toFloat() / limit)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "About 40 units per flight. Automatic checks pause below 40 units, so the plan is never exceeded.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
