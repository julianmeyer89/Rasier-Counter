package de.rasier.speicher.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.rasier.speicher.energy.DailyEnergy
import de.rasier.speicher.energy.sum
import de.rasier.speicher.hub.HubState
import java.time.LocalDate

private enum class Period(val label: String, val days: Long) {
    WEEK("7 Tage", 7), MONTH("30 Tage", 30), YEAR("Jahr", 365), ALL("Gesamt", 100_000)
}

@Composable
fun StatsScreen(state: HubState) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    val p = Period.entries[period]
    val from = LocalDate.now().minusDays(p.days - 1).toString()
    val days = state.days.filter { it.date >= from }
    val total = days.sum(p.label)
    val tariff = state.settings.tariff

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Period.entries.forEachIndexed { i, e ->
                if (i == period) Button(onClick = {}) { Text(e.label) } else OutlinedButton(onClick = { period = i }) { Text(e.label) }
            }
        }

        SectionCard("Ersparnis ${p.label}") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigValue("gespart", euro(total.savingsEuro(tariff)), BatteryColor)
                BigValue("Netzkosten", euro(total.gridCostEuro(tariff)), ImportColor)
                BigValue("Autarkie", pct(total.autarky?.times(100)), MaterialTheme.colorScheme.primary)
            }
            Text(
                "Gespart = selbst genutzter Solarstrom × Bezugspreis + Einspeisung × Vergütung. " +
                    "Tarif in den Einstellungen änderbar.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Energie ${p.label}") {
            ValueRow("Solarertrag (PV-Eingänge)", kwh(total.pvWh), SolarColor)
            ValueRow("Speicherabgabe", kwh(total.batteryOutputWh))
            ValueRow("Wechselrichter (geschätzt)", kwh(total.inverterAcWh))
            ValueRow("Hausverbrauch", kwh(total.houseWh))
            ValueRow("davon Solar", kwh(total.selfConsumedWh), BatteryColor)
            ValueRow("Netzbezug", kwh(total.gridImportWh), ImportColor)
            ValueRow("Einspeisung", kwh(total.gridExportWh), ExportColor)
            ValueRow("Messabdeckung CT002", coverage(total, days.size))
        }

        if (days.isNotEmpty()) {
            SectionCard("Tageswerte") {
                val shown = days.takeLast(31)
                BarChart(
                    shown.map { d ->
                        listOf(
                            BatteryColor to d.selfConsumedWh.toFloat(),
                            ImportColor to d.gridImportWh.toFloat(),
                            ExportColor to d.gridExportWh.toFloat(),
                        )
                    },
                )
                Legend(BatteryColor to "Solar genutzt", ImportColor to "Bezug", ExportColor to "Einspeisung")
                for (d in days.takeLast(14).reversed()) {
                    ValueRow(d.date.substring(5), "${kwh(d.houseWh)} Haus · ${euro(d.savingsEuro(tariff))}")
                }
            }
        }

        InfoCard(
            "Hinweis: Der CT002 hat keine Zähler, Bezug und Einspeisung werden aus den Leistungswerten berechnet – " +
                "nur solange das Handy im Heimnetz ist oder der Hintergrunddienst läuft. Die Leistung des " +
                "Wechselrichters wird ohne DTU aus der Speicherabgabe geschätzt.",
        )
    }
}

private fun coverage(total: DailyEnergy, dayCount: Int): String {
    if (dayCount == 0) return "–"
    val share = total.meterCoverageMs / (dayCount * 86_400_000.0) * 100
    return String.format(java.util.Locale.GERMANY, "%.0f %% der Zeit", share)
}
