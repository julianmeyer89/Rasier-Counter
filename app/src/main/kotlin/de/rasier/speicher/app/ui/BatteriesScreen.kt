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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.rasier.speicher.app.AppGraph
import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.hub.HubState
import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.ChargeMode

@Composable
fun BatteriesScreen(state: HubState, graph: AppGraph) {
    var pending by remember { mutableStateOf<BatteryCommand?>(null) }
    val batteries = state.snapshot?.batteries.orEmpty()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        val group = state.group
        SectionCard("Gruppe (${batteries.size} Speicher)") {
            ValueRow("Ladestand gesamt", pct(group.socPercent))
            group.totalCapacityWh?.let { ValueRow("Kapazität", kwh(it.toDouble())) }
            ValueRow("Solar", watts(group.pvW), SolarColor)
            ValueRow("Abgabe an Wechselrichter", watts(group.outputW))
            ValueRow("Unterschied der Ladestände", "${group.socSpread} %")
            state.automation.balancer.heldBatteryId?.let { id ->
                val name = batteries.firstOrNull { it.id == id }?.name ?: id
                Text("Ausgleich aktiv: $name pausiert, bis die Ladestände gleich sind.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (batteries.isEmpty()) {
            InfoCard("Keine Speicherdaten. In den Einstellungen die Bluetooth-Adressen der Speicher eintragen (Suchen-Knopf) und in der Nähe der Speicher sein.")
        }
        for (b in batteries) BatteryCard(b, onCommand = { pending = it })

        if (state.suggestions.isNotEmpty()) {
            SectionCard("Vorschläge der Automatik") {
                Text(
                    "Automatisches Schreiben ist ausgeschaltet. Diese Änderungen würde die Automatik jetzt vornehmen:",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (c in state.suggestions) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(describe(c, batteries), Modifier.weight(1f))
                        TextButton(onClick = { pending = c }) { Text("Ausführen") }
                    }
                }
            }
        }
    }

    pending?.let { cmd ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Einstellung ändern?") },
            text = {
                Text(
                    "${describe(cmd, batteries)}\n\nDie Änderung wird per Bluetooth gesendet und im Speicher dauerhaft " +
                        "gespeichert. Die Marstek-App darf währenddessen nicht per Bluetooth verbunden sein.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    graph.polling.launch { graph.hub.sendManual(cmd) }
                }) { Text("Senden") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun BatteryCard(b: BatteryState, onCommand: (BatteryCommand) -> Unit) {
    SectionCard(b.name) {
        SocBar(b.socPercent.toDouble())
        Spacer(Modifier.height(8.dp))
        ValueRow("Ladestand", "${b.socPercent} %")
        b.capacityWh?.let { ValueRow("Gespeichert", kwh(it * b.socPercent / 100.0)) }
        b.pvInputW.forEachIndexed { i, w -> ValueRow("PV-Eingang ${i + 1}", watts(w), SolarColor) }
        b.outputW.forEachIndexed { i, w ->
            val enabled = b.outputEnabled.getOrNull(i)
            ValueRow("Ausgang ${i + 1}${if (enabled == false) " (aus)" else ""}", watts(w))
        }
        ValueRow("Entladetiefe (DoD)", b.dodPercent?.let { "$it % (bis ${100 - it} %)" } ?: "–")
        ValueRow("Lademodus", chargeModeLabel(b.chargeMode))
        ValueRow("Adaptiver Modus (CT002)", when (b.adaptiveMode) { true -> "an"; false -> "aus"; null -> "–" })
        b.dailyPvWh?.let { ValueRow("PV heute", kwh(it.toDouble())) }
        b.dailyOutputWh?.let { ValueRow("Abgabe heute", kwh(it.toDouble())) }
        b.temperatureC?.let { ValueRow("Temperatur", "$it °C") }
        ValueRow("Firmware", b.firmware ?: "–")
        ValueRow("Aktualisiert", ago(b.timestampMs))

        Spacer(Modifier.height(12.dp))
        var dod by remember(b.id, b.dodPercent) { mutableFloatStateOf((b.dodPercent ?: 90).toFloat()) }
        Text("Entladetiefe einstellen: ${dod.toInt()} %", style = MaterialTheme.typography.labelLarge)
        Slider(value = dod, onValueChange = { dod = it }, valueRange = 10f..100f, steps = 17)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = dod.toInt() != b.dodPercent,
                onClick = { onCommand(BatteryCommand.SetDod(b.id, dod.toInt())) },
            ) { Text("DoD setzen") }
            OutlinedButton(onClick = {
                val next = if (b.chargeMode == ChargeMode.CHARGE_THEN_DISCHARGE) ChargeMode.SIMULTANEOUS else ChargeMode.CHARGE_THEN_DISCHARGE
                onCommand(BatteryCommand.SetChargeMode(b.id, next))
            }) { Text("Lademodus wechseln") }
        }
        if (b.adaptiveMode == false) {
            OutlinedButton(onClick = { onCommand(BatteryCommand.SetAdaptiveMode(b.id, true)) }) {
                Text("Adaptiven Modus (CT002) einschalten")
            }
        }
    }
}

fun chargeModeLabel(m: ChargeMode) = when (m) {
    ChargeMode.SIMULTANEOUS -> "gleichzeitig laden und entladen"
    ChargeMode.CHARGE_THEN_DISCHARGE -> "erst voll laden, dann entladen"
    ChargeMode.UNKNOWN -> "–"
}

fun describe(c: BatteryCommand, batteries: List<BatteryState>): String {
    val name = batteries.firstOrNull { it.id == c.batteryId }?.name ?: c.batteryId
    return when (c) {
        is BatteryCommand.SetDod -> "$name: Entladetiefe auf ${c.percent} %"
        is BatteryCommand.SetChargeMode -> "$name: Lademodus „${chargeModeLabel(c.mode)}“"
        is BatteryCommand.SetAdaptiveMode -> "$name: adaptiven Modus ${if (c.enabled) "einschalten" else "ausschalten"}"
    }
}
