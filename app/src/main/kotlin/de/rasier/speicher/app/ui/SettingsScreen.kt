package de.rasier.speicher.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.rasier.speicher.app.AppGraph
import de.rasier.speicher.app.ble.BlePermissions
import de.rasier.speicher.app.ble.BleScanner
import de.rasier.speicher.app.ble.FoundDevice
import de.rasier.speicher.app.service.MonitorService
import de.rasier.speicher.cloud.LoginResult
import de.rasier.speicher.cloud.MarstekCloudClient
import de.rasier.speicher.hub.HubState
import de.rasier.speicher.settings.AppSettings
import de.rasier.speicher.settings.DataMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SettingsScreen(state: HubState, graph: AppGraph) {
    val context = LocalContext.current
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var scanFor by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var serviceOn by remember { mutableStateOf(graph.settings.backgroundServiceEnabled) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        message = if (result.values.all { it }) "Berechtigungen erteilt." else "Ohne Bluetooth-Berechtigung können die Speicher nicht gelesen werden."
    }

    fun save(s: AppSettings) {
        graph.settings.save(s)
        graph.polling.launch { graph.hub.updateSettings(s) }
        message = "Gespeichert."
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        message?.let { InfoCard(it) }

        SectionCard("Datenquelle") {
            ModeOption("Demo (simulierte Geräte)", draft.mode == DataMode.DEMO) { draft = draft.copy(mode = DataMode.DEMO) }
            ModeOption("Echte Geräte", draft.mode == DataMode.LIVE) { draft = draft.copy(mode = DataMode.LIVE) }
            if (!BlePermissions.granted(context)) {
                OutlinedButton(onClick = {
                    val perms = BlePermissions.required.toMutableList()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
                    permissionLauncher.launch(perms.toTypedArray())
                }) { Text("Bluetooth-Berechtigung erteilen") }
            }
        }

        SectionCard("Speicher (Bluetooth)") {
            Text(
                "Die Speicher werden per Bluetooth gelesen, wenn das Handy in der Nähe ist. Während einer Abfrage " +
                    "kann die Marstek-App nicht per Bluetooth verbinden (WLAN/Cloud der Marstek-App bleibt unberührt).",
                style = MaterialTheme.typography.bodySmall,
            )
            draft.batteries.forEachIndexed { i, b ->
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = b.name, onValueChange = { v -> draft = draft.copy(batteries = draft.batteries.replaced(i) { it.copy(name = v) }) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = b.bleAddress,
                        onValueChange = { v -> draft = draft.copy(batteries = draft.batteries.replaced(i) { it.copy(bleAddress = v.trim().uppercase()) }) },
                        label = { Text("Bluetooth-Adresse") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scanFor = i }) { Text("Suchen") }
                }
            }
        }

        SectionCard("CT002 (WLAN)") {
            OutlinedTextField(
                value = draft.ct002Ip, onValueChange = { draft = draft.copy(ct002Ip = it.trim()) },
                label = { Text("IP-Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text(
                "Der CT002 wird nur gelesen (passiv), die Regelung der Speicher wird nicht beeinflusst. " +
                    "Funktioniert nur, wenn das Handy im Heim-WLAN ist.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Marstek-Konto (Rückfall von unterwegs)") {
            OutlinedTextField(
                value = draft.email, onValueChange = { draft = draft.copy(email = it.trim()) },
                label = { Text("E-Mail") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(if (graph.settings.hasPassword) "Passwort (gespeichert – nur zum Ändern)" else "Passwort") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(
                "Gespeichert wird nur ein Hash des Passworts, verschlüsselt im Android-Keystore. Tipp: ein zweites " +
                    "Marstek-Konto anlegen und die Geräte dafür freigeben – ein Login hier kann sonst die offizielle App abmelden.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (password.isNotBlank()) graph.settings.setPassword(password)
                    password = ""
                    val md5 = graph.settings.passwordMd5()
                    if (md5 == null || draft.email.isBlank()) {
                        message = "E-Mail und Passwort eingeben."
                    } else {
                    message = "Anmeldung läuft…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { MarstekCloudClient(draft.cloudBaseUrl).login(draft.email, md5) }
                        }
                        message = result.fold(
                            onSuccess = { r ->
                                when (r) {
                                    is LoginResult.Success -> {
                                        // Link cloud devices to the configured batteries by order.
                                        val b2500 = r.devices.filter { it.isB2500 }
                                        draft = draft.copy(
                                            batteries = draft.batteries.mapIndexed { i, b ->
                                                b.copy(cloudDevid = b.cloudDevid ?: b2500.getOrNull(i)?.devid)
                                            },
                                        )
                                        "Anmeldung OK. Geräte: " + r.devices.joinToString { "${it.name} (${it.type})" } +
                                            "\nZum Übernehmen „Speichern“ tippen."
                                    }
                                    LoginResult.WrongPassword -> "Falsches Passwort."
                                    LoginResult.UnknownAccount -> "Konto in dieser Region unbekannt."
                                    is LoginResult.Failed -> "Anmeldung fehlgeschlagen: ${r.reason}"
                                }
                            },
                            onFailure = { "Cloud nicht erreichbar: ${MarstekCloudClient.redact(it.message)}" },
                        )
                    }
                    }
                }) { Text("Anmeldung testen") }
                TextButton(onClick = { graph.settings.setPassword(null); message = "Passwort gelöscht." }) { Text("Passwort löschen") }
            }
        }

        SectionCard("Stromtarif") {
            NumberField("Bezugspreis (ct/kWh)", draft.tariff.importCentPerKwh) { draft = draft.copy(tariff = draft.tariff.copy(importCentPerKwh = it)) }
            NumberField("Einspeisevergütung (ct/kWh)", draft.tariff.feedInCentPerKwh) { draft = draft.copy(tariff = draft.tariff.copy(feedInCentPerKwh = it)) }
            NumberField("Grundgebühr (€/Monat)", draft.tariff.baseFeeEuroPerMonth) { draft = draft.copy(tariff = draft.tariff.copy(baseFeeEuroPerMonth = it)) }
        }

        SectionCard("Anlage") {
            NumberField("Wechselrichter max. Leistung (W)", draft.system.inverterMaxW.toDouble()) {
                draft = draft.copy(system = draft.system.copy(inverterMaxW = it.toInt()))
            }
            NumberField("Wechselrichter-Wirkungsgrad (%)", draft.system.inverterEfficiency * 100) {
                draft = draft.copy(system = draft.system.copy(inverterEfficiency = (it / 100).coerceIn(0.5, 1.0)))
            }
            NumberField("Abfrage-Intervall (Sekunden)", draft.pollIntervalSeconds.toDouble()) {
                draft = draft.copy(pollIntervalSeconds = it.toInt().coerceIn(10, 3600))
            }
        }

        SectionCard("Automatik") {
            SwitchRow("Automatik darf Einstellungen ändern", draft.automationsMayWrite) { draft = draft.copy(automationsMayWrite = it) }
            Text(
                "Aus: Die Automatik macht nur Vorschläge. An: Kalibrierung und Ladestand-Ausgleich ändern DoD und " +
                    "Lademodus selbst (höchstens 8 Änderungen pro Speicher und Tag, um den Speicher-Flash zu schonen).",
                style = MaterialTheme.typography.bodySmall,
            )
            val bal = draft.automation.balancer
            SwitchRow("Ladestände ausgleichen", bal.enabled) {
                draft = draft.copy(automation = draft.automation.copy(balancer = bal.copy(enabled = it)))
            }
            NumberField("Ausgleich ab Unterschied (%)", bal.startSpreadPercent.toDouble()) {
                draft = draft.copy(automation = draft.automation.copy(balancer = bal.copy(startSpreadPercent = it.toInt().coerceIn(5, 60))))
            }
            val cal = draft.automation.calibration
            NumberField("Kalibrierung alle … Tage (0 = nur manuell)", cal.intervalDays.toDouble()) {
                draft = draft.copy(automation = draft.automation.copy(calibration = cal.copy(intervalDays = it.toInt().coerceIn(0, 365))))
            }
            NumberField("Kalibrierung: entladen bis DoD (%)", cal.dischargeDodPercent.toDouble()) {
                draft = draft.copy(automation = draft.automation.copy(calibration = cal.copy(dischargeDodPercent = it.toInt().coerceIn(50, 100))))
            }
        }

        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = {
                val ids = draft.batteries.map { it.bleAddress }.filter { it.isNotBlank() }
                save(draft.copy(automation = draft.automation.copy(batteryIds = ids)))
            }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
        }

        SectionCard("Hintergrund") {
            SwitchRow("Im Hintergrund weiterlaufen", serviceOn) { on ->
                serviceOn = on
                graph.settings.backgroundServiceEnabled = on
                if (on) MonitorService.start(context) else MonitorService.stop(context)
            }
            Text(
                "Nötig für Statistik und Automatik, wenn die App geschlossen ist. Android zeigt dafür eine dauerhafte Benachrichtigung.",
                style = MaterialTheme.typography.bodySmall,
            )
            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("Akku-Optimierung für die App abschalten") }
            }
        }
    }

    scanFor?.let { index ->
        ScanDialog(
            onPick = { device ->
                draft = draft.copy(batteries = draft.batteries.replaced(index) { it.copy(bleAddress = device.address) })
                scanFor = null
            },
            onDismiss = { scanFor = null },
        )
    }
}

@Composable
private fun ScanDialog(onPick: (FoundDevice) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<FoundDevice>() }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(15_000) {
            BleScanner(context).scan()
                .catch { error = it.message }
                .collect { d ->
                    val i = found.indexOfFirst { it.address == d.address }
                    if (i >= 0) found[i] = d else found += d
                }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speicher suchen") },
        text = {
            Column {
                error?.let { Text(it, color = ImportColor) }
                if (found.isEmpty()) Text("Suche läuft… Speicher in der Nähe? Marstek-App geschlossen?")
                val sorted = found.sortedWith(compareByDescending<FoundDevice> { BleScanner.looksLikeB2500(it.name) }.thenByDescending { it.rssi })
                for (d in sorted.take(12)) {
                    TextButton(onClick = { onPick(d) }) {
                        Text("${if (BleScanner.looksLikeB2500(d.name)) "🔋 " else ""}${d.name}  ${d.address}  (${d.rssi} dBm)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Double, onChange: (Double) -> Unit) {
    // Keyed by label only, so typing "12," is not immediately reformatted.
    var text by remember(label) { mutableStateOf(formatNumber(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun formatNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

private fun <T> List<T>.replaced(index: Int, f: (T) -> T): List<T> = mapIndexed { i, t -> if (i == index) f(t) else t }
