package de.rasier.speicher.app.service

import android.util.Log
import de.rasier.speicher.app.data.SettingsRepository
import de.rasier.speicher.hub.EnergyHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the polling loop while at least one client needs it: the visible app and/or the
 * background service. Reference counted so both can start and stop independently.
 */
class PollingController(
    private val hub: EnergyHub,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var users = 0
    private var job: Job? = null

    @Synchronized
    fun acquire() {
        users++
        if (job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    val started = System.currentTimeMillis()
                    try {
                        hub.poll()
                        notifier.showAlerts(hub.state.value.alerts)
                    } catch (e: Exception) {
                        Log.w("Rasier", "Abfrage fehlgeschlagen", e)
                    }
                    val interval = settings.settings.value.pollIntervalSeconds.coerceIn(5, 3600) * 1000L
                    delay((interval - (System.currentTimeMillis() - started)).coerceAtLeast(1000))
                }
            }
        }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            job?.cancel()
            job = null
            hub.close()
        }
    }

    /** Immediate extra poll, e.g. on pull-to-refresh. */
    fun refreshNow() {
        scope.launch { runCatching { hub.poll() } }
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("Rasier", "Aktion fehlgeschlagen", e)
            }
        }
    }
}
