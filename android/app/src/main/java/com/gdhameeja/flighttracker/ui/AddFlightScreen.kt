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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gdhameeja.flighttracker.data.AddResult
import com.gdhameeja.flighttracker.data.LegChoice
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFlightScreen(
    onBack: () -> Unit,
    onAdd: suspend (number: String, date: String, from: String?) -> Result<AddResult>,
    onAdded: (String) -> Unit,
) {
    val today = LocalDate.now()
    var number by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<List<LegChoice>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun submit(from: String? = null) {
        if (busy) return
        val n = number.uppercase().replace(" ", "")
        if (!Regex("^[A-Z0-9]{2}\\d{1,5}[A-Z]?$").matches(n)) {
            error = "Enter the flight number, like 6E 1406 or EK 512"
            return
        }
        busy = true
        error = null
        scope.launch {
            onAdd(n, date.toString(), from)
                .onSuccess { r ->
                    when {
                        r.id != null -> onAdded(r.id)
                        !r.choices.isNullOrEmpty() -> choices = r.choices
                        else -> error = "Couldn't add that flight"
                    }
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add flight") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.uppercase().take(9); choices = emptyList(); error = null },
                label = { Text("Flight number") },
                placeholder = { Text("6E 1406") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Text("Departure date (local)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = date == today, onClick = { date = today; choices = emptyList() }, label = { Text("Today") })
                FilterChip(selected = date == today.plusDays(1), onClick = { date = today.plusDays(1); choices = emptyList() }, label = { Text("Tomorrow") })
                val other = date != today && date != today.plusDays(1)
                FilterChip(
                    selected = other,
                    onClick = { picking = true },
                    leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) },
                    label = { Text(if (other) date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) else "Other date") },
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(onClick = { submit() }, enabled = !busy && number.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Looking it up…")
                } else Text("Find flight")
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (choices.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("This number flies several legs. Which is yours?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                choices.forEach { c ->
                    ElevatedCard(onClick = { submit(c.from) }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.from ?: "?"}  →  ${c.to ?: "?"}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text(Fmt.time(c.departure, null) + " your time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Tip: use the date of departure where you take off. Flights are checked automatically from two days before, more often near departure and landing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (picking) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); choices = emptyList() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}
