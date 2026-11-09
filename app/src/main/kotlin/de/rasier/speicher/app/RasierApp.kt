package de.rasier.speicher.app

import android.app.Application
import android.util.Log
import de.rasier.speicher.app.ble.AndroidBleConnector
import de.rasier.speicher.app.data.SettingsRepository
import de.rasier.speicher.app.data.WifiSockets
import de.rasier.speicher.app.service.Notifier
import de.rasier.speicher.app.service.PollingController
import de.rasier.speicher.cloud.MarstekCloudClient
import de.rasier.speicher.hub.EnergyHub
import de.rasier.speicher.hub.HubDevices
import de.rasier.speicher.protocol.b2500.B2500Client
import de.rasier.speicher.protocol.ct002.Ct002Reader
import de.rasier.speicher.settings.FileStore
import java.io.File

class RasierApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Manual dependency wiring: one instance of everything per process. */
class AppGraph(app: Application) {
    val settings = SettingsRepository(app)
    private val wifi = WifiSockets(app)
    val notifier = Notifier(app)

    private val log: (String) -> Unit = { Log.i("Rasier", it) }

    val hub = EnergyHub(
        initialSettings = settings.settings.value,
        store = FileStore(File(app.filesDir, "data")),
        devices = HubDevices(
            ble = B2500Client(AndroidBleConnector(app), log = log),
            ctReaderFactory = { host, id -> Ct002Reader(host, id, socketFactory = { wifi.newSocket() }) },
            cloud = MarstekCloudClient(settings.settings.value.cloudBaseUrl),
            passwordMd5 = { settings.passwordMd5() },
            ctReaderId = { settings.ctReaderId() },
        ),
        log = log,
    )

    val polling = PollingController(hub, settings, notifier)
}

val android.content.Context.graph: AppGraph get() = (applicationContext as RasierApp).graph
