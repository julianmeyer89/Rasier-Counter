package de.rasier.speicher.control

import de.rasier.speicher.model.ChargeMode
import kotlinx.serialization.Serializable

/**
 * Protocol-independent commands. A transport (Bluetooth, cloud) translates them into device frames.
 * The automations only produce these commands, which keeps them testable without hardware.
 */
@Serializable
sealed interface BatteryCommand {
    val batteryId: String

    @Serializable
    data class SetDod(override val batteryId: String, val percent: Int) : BatteryCommand

    @Serializable
    data class SetChargeMode(override val batteryId: String, val mode: ChargeMode) : BatteryCommand

    @Serializable
    data class SetAdaptiveMode(override val batteryId: String, val enabled: Boolean) : BatteryCommand
}

/** Executes commands on real or simulated devices. */
interface BatteryController {
    /** Returns true when the device confirmed (or at least accepted) the command. */
    suspend fun execute(command: BatteryCommand): Boolean
}

/** A message for the user, e.g. shown in the app and as notification. */
@Serializable
data class Alert(
    val key: String,
    val severity: Severity,
    val title: String,
    val message: String,
    val timestampMs: Long,
) {
    @Serializable
    enum class Severity { INFO, WARNING, CRITICAL }
}
