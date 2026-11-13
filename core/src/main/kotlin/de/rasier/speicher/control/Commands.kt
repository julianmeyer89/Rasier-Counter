package de.rasier.speicher.control

import de.rasier.speicher.model.BatteryState
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

    /** Software restart of the storage (cloud only). Used to recover the CT002 link after mode switches. */
    @Serializable
    data class Reboot(override val batteryId: String) : BatteryCommand
}

/**
 * Whether the device already reports what the command asked for.
 *
 * Cloud commands are fire-and-forget (MQTT QoS 0), so this is the only way to tell an applied setting
 * from a lost one. `null` means the device does not report the field, so nothing can be concluded.
 */
fun BatteryCommand.appliedTo(b: BatteryState): Boolean? = when (this) {
    is BatteryCommand.SetDod -> b.dodPercent?.let { it == percent }
    is BatteryCommand.SetChargeMode -> if (b.chargeMode == ChargeMode.UNKNOWN) null else b.chargeMode == mode
    is BatteryCommand.SetAdaptiveMode -> b.adaptiveMode?.let { it == enabled }
    is BatteryCommand.Reboot -> null // a restart leaves no lasting trace in the status frame
}

/** Executes commands on real or simulated devices. */
fun interface BatteryController {
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
