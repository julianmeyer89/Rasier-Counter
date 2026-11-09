package de.rasier.speicher.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.rasier.speicher.app.AppGraph
import de.rasier.speicher.control.CalibrationPhase
import de.rasier.speicher.hub.HubState

@Composable
fun CalibrationScreen(state: HubState, graph: AppGraph) {
    val run = state.automation.calibration
    val cal = state.settings.automation.calibration
    val batteries = state.snapshot?.batteries.orEmpty()
    val names = batteries.associate { it.id to it.name } + state.automation.names

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        InfoCard(
            "Die Kalibrierung macht einen vollen Zyklus, damit der Speicher seinen Ladestand neu lernt: " +
                "erst auf 100 % laden und ${cal.holdFullMinutes} min halten, dann bis ${100 - cal.dischargeDodPercent} % " +
                "entladen. Danach werden deine Einstellungen wiederhergestellt. Immer nur ein Speicher, der andere " +
                "versorgt weiter das Haus. Je nach Sonne dauert das 1–3 Tage.",
        )

        SectionCard("Status") {
            if (run == null || run.finished) {
                Text(if (run == null) "Noch keine Kalibrierung." else "Letzte Kalibrierung: ${phaseLabel(run.phase)}")
                state.automation.lastCalibrationFinishedMs?.let { ValueRow("Zuletzt", time(it)) }
                if (cal.intervalDays > 0) ValueRow("Automatisch", "alle ${cal.intervalDays} Tage")
            } else {
                val id = run.currentBatteryId
                ValueRow("Speicher", id?.let { names[it] ?: it } ?: "–")
                ValueRow("Schritt", phaseLabel(run.phase))
                ValueRow("Fortschritt", "${run.index + 1} von ${run.queue.size}")
                ValueRow("Gestartet", time(run.batteryStartedMs))
                batteries.firstOrNull { it.id == id }?.let { ValueRow("Ladestand jetzt", "${it.socPercent} %") }
            }
            run?.results?.forEach { r ->
                ValueRow("${if (r.success) "✅" else "❌"} ${names[r.batteryId] ?: r.batteryId}", r.message)
            }
            if (!state.settings.automationsMayWrite) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Achtung: „Automatik darf Einstellungen ändern“ ist aus. Die Kalibrierung zeigt dann nur Vorschläge " +
                        "(Reiter Speicher), die du selbst ausführen musst.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ImportColor,
                )
            }
        }

        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (run == null || run.finished) {
                Button(
                    enabled = batteries.isNotEmpty(),
                    onClick = { graph.polling.launch { graph.hub.startCalibration(batteries.map { it.id }) } },
                ) { Text("Alle kalibrieren") }
                for (b in batteries) {
                    OutlinedButton(onClick = { graph.polling.launch { graph.hub.startCalibration(listOf(b.id)) } }) { Text(b.name) }
                }
            } else {
                OutlinedButton(onClick = { graph.polling.launch { graph.hub.abortCalibration() } }) { Text("Abbrechen") }
            }
        }

        SectionCard("Protokoll") {
            val events = state.events.takeLast(40).reversed()
            if (events.isEmpty()) Text("Keine Einträge.")
            for (e in events) Text("${time(e.t)}  ${e.text}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun phaseLabel(p: CalibrationPhase) = when (p) {
    CalibrationPhase.PREPARE -> "Vorbereitung"
    CalibrationPhase.CHARGING -> "Laden auf 100 %"
    CalibrationPhase.HOLD_FULL -> "100 % halten"
    CalibrationPhase.DISCHARGING -> "Entladen"
    CalibrationPhase.HOLD_EMPTY -> "Untergrenze halten"
    CalibrationPhase.DONE -> "abgeschlossen"
    CalibrationPhase.ABORTED -> "abgebrochen"
}
