package de.rasier.speicher.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.rasier.speicher.app.graph
import de.rasier.speicher.app.service.MonitorService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (graph.settings.backgroundServiceEnabled) MonitorService.start(this)
        setContent { RasierTheme { RasierRoot() } }
    }

    override fun onStart() {
        super.onStart()
        graph.polling.acquire()
    }

    override fun onStop() {
        graph.polling.release()
        super.onStop()
    }
}

private enum class Tab(val label: String, val symbol: String) {
    OVERVIEW("Übersicht", "⚡"),
    BATTERIES("Speicher", "🔋"),
    STATS("Statistik", "📊"),
    CALIBRATION("Kalibrierung", "🔄"),
    SETTINGS("Einstellungen", "⚙"),
}

@Composable
private fun RasierRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = context.graph
    val state by graph.hub.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t.symbol) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (Tab.entries[tab]) {
                Tab.OVERVIEW -> OverviewScreen(state, onRefresh = { graph.polling.refreshNow() })
                Tab.BATTERIES -> BatteriesScreen(state, graph)
                Tab.STATS -> StatsScreen(state)
                Tab.CALIBRATION -> CalibrationScreen(state, graph)
                Tab.SETTINGS -> SettingsScreen(state, graph)
            }
        }
    }
}
