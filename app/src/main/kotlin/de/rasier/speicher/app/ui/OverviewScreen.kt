package de.rasier.speicher.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.rasier.speicher.control.Alert
import de.rasier.speicher.hub.HubState
import de.rasier.speicher.settings.DataMode
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun OverviewScreen(state: HubState, onRefresh: () -> Unit) {
    val snap = state.snapshot
    val group = state.group
    val grid = snap?.meter?.totalW
    val acW = (group.outputW * state.settings.system.inverterEfficiency).roundToInt()
    val house = grid?.let { (it + acW).coerceAtLeast(0) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Rasier Speicher", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = onRefresh) { Text(if (state.busy) "lädt…" else "Aktualisieren") }
        }
        if (state.settings.mode == DataMode.DEMO) {
            InfoCard("Demo-Modus: Es werden simulierte Geräte angezeigt. In den Einstellungen auf „Echte Geräte“ umstellen.")
        }

        SectionCard("Jetzt") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigValue("Solar", watts(group.pvW), SolarColor)
                BigValue("Speicher", pct(group.socPercent), BatteryColor)
                BigValue("Haus", watts(house), MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            group.socPercent?.let { SocBar(it) }
            Spacer(Modifier.height(12.dp))
            ValueRow("Speicher → Wechselrichter", watts(group.outputW))
            ValueRow("Wechselrichter → Haus (geschätzt)", watts(acW))
            ValueRow(
                if (grid != null && grid < 0) "Einspeisung ins Netz" else "Bezug aus dem Netz",
                grid?.let { watts(abs(it)) } ?: "kein CT002-Wert",
                color = when {
                    grid == null -> null
                    grid < 0 -> ExportColor
                    grid > 0 -> ImportColor
                    else -> null
                },
            )
            ValueRow(
                if (group.chargeW >= 0) "Speicher lädt" else "Speicher entlädt",
                watts(abs(group.chargeW)),
            )
            group.storedWh?.let { ValueRow("Gespeichert", kwh(it)) }
        }

        state.today?.let { d ->
            SectionCard("Heute") {
                ValueRow("Ersparnis", euro(d.savingsEuro(state.settings.tariff)), BatteryColor)
                ValueRow("Solarertrag", kwh(d.pvWh))
                ValueRow("Selbst genutzt", kwh(d.selfConsumedWh))
                ValueRow("Hausverbrauch", kwh(d.houseWh))
                ValueRow("Netzbezug", kwh(d.gridImportWh), ImportColor)
                ValueRow("Einspeisung", kwh(d.gridExportWh), ExportColor)
                ValueRow("Autarkie", pct(d.autarky?.times(100)))
            }
        }

        if (state.alerts.isNotEmpty()) {
            for (a in state.alerts) {
                InfoCard(
                    "${a.title}\n${a.message}",
                    color = if (a.severity == Alert.Severity.INFO) MaterialTheme.colorScheme.secondaryContainer else Color(0x33D84315),
                )
            }
        }

        if (state.history.size > 2) {
            SectionCard("Letzte 24 Stunden") {
                val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
                val pts = state.history.filter { it.t >= cutoff }
                LineChart(
                    listOf(
                        ImportColor to pts.mapNotNull { p -> p.gridW?.let { p.t to it.toFloat() } },
                        SolarColor to pts.map { it.t to it.pvW.toFloat() },
                        BatteryColor to pts.map { it.t to it.outW.toFloat() },
                    ),
                )
                Legend(ImportColor to "Netz (+Bezug/−Einsp.)", SolarColor to "Solar", BatteryColor to "Speicherabgabe")
            }
        }

        SectionCard("Verbindungen") {
            if (state.sources.isEmpty()) Text("Noch keine Abfrage.")
            for (s in state.sources) {
                ValueRow("${if (s.ok) "✅" else "⚠️"} ${s.name}", ago(s.lastSuccessMs))
                Text(s.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
