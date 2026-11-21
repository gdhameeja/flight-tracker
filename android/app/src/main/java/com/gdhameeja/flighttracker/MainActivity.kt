package com.gdhameeja.flighttracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gdhameeja.flighttracker.push.Push
import com.gdhameeja.flighttracker.ui.AddFlightScreen
import com.gdhameeja.flighttracker.ui.FlightScreen
import com.gdhameeja.flighttracker.ui.FlightTheme
import com.gdhameeja.flighttracker.ui.SettingsScreen
import com.gdhameeja.flighttracker.ui.TripsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    /** Flight to open, from a tapped notification. */
    private val openFlight = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Push.createChannels(this)
        Push.register(this)
        handleIntent(intent)
        setContent { FlightTheme { App(vm, openFlight) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("flight_id")?.let { openFlight.value = it }
        handleSetupLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (vm.settings.isConnected) vm.load(quiet = true)
    }

    /** flighttracker://setup?url=https://…&token=… fills in the server settings. */
    private fun handleSetupLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "flighttracker" || uri.host != "setup") return
        val url = uri.getQueryParameter("url") ?: return
        val token = uri.getQueryParameter("token") ?: return
        vm.connect(url, token)
    }
}

@Composable
private fun App(vm: MainViewModel, openFlight: MutableState<String?>) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    // Android 13+: ask once for permission to show alerts
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(state.connected) {
        if (state.connected && Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Tapped an alert: open that flight
    val target = openFlight.value
    LaunchedEffect(target, state.connected) {
        if (target != null && state.connected) {
            vm.reload(target)
            nav.navigate("flight/$target") { popUpTo("trips") }
            openFlight.value = null
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    NavHost(
        navController = nav,
        startDestination = if (vm.settings.isConnected) "trips" else "setup",
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable("trips") {
            TripsScreen(
                state = state,
                snackbar = snackbar,
                onRefresh = { vm.load() },
                onOpen = { nav.navigate("flight/$it") },
                onAdd = { nav.navigate("add") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("flight/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            FlightScreen(
                tracked = state.flights.firstOrNull { it.id == id },
                refreshing = state.refreshing,
                snackbar = snackbar,
                onBack = { nav.popBackStack() },
                onRefresh = { vm.refresh(id) },
                onReload = { vm.reload(id) },
                onDelete = { vm.delete(id); nav.popBackStack() },
                connections = state.connections,
                loadWeather = { vm.weather(id) },
                loadAlternatives = { vm.alternatives(id) },
                loadAircraft = { vm.aircraft(id) },
            )
        }
        composable("add") {
            AddFlightScreen(
                onBack = { nav.popBackStack() },
                onAdd = { n, d, from -> vm.add(n, d, from) },
                onAdded = { id -> nav.navigate("flight/$id") { popUpTo("trips") } },
            )
        }
        // First run: settings without a back button, then on to the trips
        composable("setup") {
            SettingsScreen(
                state = state, serverUrl = vm.settings.serverUrl, token = vm.settings.token, snackbar = snackbar, onBack = null,
                onConnect = { u, t -> vm.connect(u, t) },
                onPair = { vm.pair(it) },
                onCheckUpdate = { vm.checkForUpdate(manual = true) }, onInstall = { vm.installUpdate() }, onShown = {},
            )
            // A setup link opened while here connects in the background; move on when it does
            LaunchedEffect(state.connected) { if (state.connected) nav.navigate("trips") { popUpTo("setup") { inclusive = true } } }
        }
        composable("settings") {
            SettingsScreen(
                state = state, serverUrl = vm.settings.serverUrl, token = vm.settings.token, snackbar = snackbar,
                onBack = { nav.popBackStack() },
                onConnect = { u, t -> vm.connect(u, t) },
                onPair = { vm.pair(it) },
                onCheckUpdate = { vm.checkForUpdate(manual = true) }, onInstall = { vm.installUpdate() },
                onShown = { vm.loadUsage() },
                onCheckedBags = { vm.setCheckedBags(it) },
            )
        }
    }
}
