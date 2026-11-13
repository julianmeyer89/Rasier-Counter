package de.rasier.speicher.control

import de.rasier.speicher.model.BatteryState
import kotlinx.serialization.Serializable

/** Combined view of all batteries, i.e. the "group" the Marstek app cannot build for this wiring. */
data class GroupState(
    val batteries: List<BatteryState>,
) {
    val totalCapacityWh: Int? = batteries.mapNotNull { it.capacityWh }.takeIf { it.size == batteries.size }?.sum()

    /** Capacity-weighted SOC of the group (plain average if capacities are unknown). */
    val socPercent: Double? = when {
        batteries.isEmpty() -> null
        totalCapacityWh != null && totalCapacityWh > 0 ->
            batteries.sumOf { it.socPercent.toDouble() * it.capacityWh!! } / totalCapacityWh
        else -> batteries.map { it.socPercent }.average()
    }

    val storedWh: Double? = totalCapacityWh?.let { cap -> socPercent?.let { it / 100.0 * cap } }
    val pvW: Int = batteries.sumOf { it.totalPvW }
    val outputW: Int = batteries.sumOf { it.totalOutputW }

    /** Positive = battery is charging. Approximation from PV in minus output, ignoring losses. */
    val chargeW: Int = pvW - outputW
    val socSpread: Int = if (batteries.size < 2) 0 else batteries.maxOf { it.socPercent } - batteries.minOf { it.socPercent }
}

@Serializable
data class BalancerSettings(
    val enabled: Boolean = true,
    /** Start balancing when the SOC difference reaches this value. */
    val startSpreadPercent: Int = 15,
    /** Stop balancing when the difference has shrunk to this value. */
    val stopSpreadPercent: Int = 5,
    /** Minimum time between two setting changes (protects the device's flash memory). */
    val minWriteIntervalMinutes: Int = 20,
)

@Serializable
data class BalancerState(
    val heldBatteryId: String? = null,
    val originalDod: Int? = null,
    val lastWriteMs: Long = 0,
    /** SOC of the held battery when the hold began, to tell real charging from the hold's own effect. */
    val heldSocPercent: Int? = null,
)

data class BalancerStep(val state: BalancerState, val commands: List<BatteryCommand>, val events: List<String>)

/**
 * Keeps the SOC of the batteries close together.
 *
 * Both B2500 feed the same HMS-1600-4T. If one runs empty much earlier than the other, the inverter
 * can drop out and the system loses half of its output. While discharging, the balancer therefore
 * temporarily raises the lower limit of the emptier battery to its current SOC (via DoD), so the
 * fuller battery carries the load until both are level again. The CT002 keeps doing the real-time
 * zero-export regulation; the balancer only changes settings every few minutes.
 */
class SocBalancer(private val settings: BalancerSettings = BalancerSettings()) {

    fun step(state: BalancerState, batteries: List<BatteryState>, nowMs: Long, paused: Boolean): BalancerStep {
        val held = state.heldBatteryId
        if (held != null) {
            val heldBattery = batteries.firstOrNull { it.id == held }
            val others = batteries.filter { it.id != held }
            val releaseReason = when {
                !settings.enabled || paused -> "Ausgleich pausiert"
                heldBattery == null -> null // no data: keep the state, try again later
                batteries.size < 2 -> "nur ein Speicher erreichbar"
                (others.maxOfOrNull { it.socPercent } ?: 0) - heldBattery.socPercent <= settings.stopSpreadPercent -> "Ladestände ausgeglichen"
                others.all { o -> o.floorSocPercent?.let { o.socPercent <= it + 5 } ?: false } -> "anderer Speicher fast leer"
                // Not "PV above output": holding the battery is what drives its output to zero, so
                // that test is satisfied by the hold itself. It released the battery again on the very
                // next poll - two writes to the device's flash for nothing. A rising SOC cannot be
                // caused by the hold, so it is the honest signal that the battery really is charging.
                state.heldSocPercent?.let { heldBattery.socPercent > it } == true -> "Speicher lädt wieder"
                else -> null
            }
            if (releaseReason != null && state.originalDod != null) {
                return BalancerStep(
                    BalancerState(lastWriteMs = nowMs),
                    listOf(BatteryCommand.SetDod(held, state.originalDod)),
                    listOf("Ausgleich beendet ($releaseReason): ${heldBattery?.name ?: held} wieder DoD ${state.originalDod} %"),
                )
            }
            // Carry the lowest SOC seen while holding: it fills in for a state saved before this
            // field existed, and it keeps a battery that first drifted down and then charges from
            // being compared against a value it will never reach again.
            return BalancerStep(
                if (heldBattery == null) state
                else state.copy(heldSocPercent = minOf(state.heldSocPercent ?: heldBattery.socPercent, heldBattery.socPercent)),
                emptyList(), emptyList(),
            )
        }

        if (!settings.enabled || paused || batteries.size < 2) return BalancerStep(state, emptyList(), emptyList())
        if (nowMs - state.lastWriteMs < settings.minWriteIntervalMinutes * 60_000L) return BalancerStep(state, emptyList(), emptyList())

        val low = batteries.minBy { it.socPercent }
        val high = batteries.maxBy { it.socPercent }
        val spread = high.socPercent - low.socPercent
        val lowDod = low.dodPercent ?: return BalancerStep(state, emptyList(), emptyList())
        val discharging = low.totalOutputW > low.totalPvW
        if (spread < settings.startSpreadPercent || !discharging) return BalancerStep(state, emptyList(), emptyList())

        // DoD that puts the lower limit at the current SOC, so the battery stops discharging now.
        val holdDod = (100 - low.socPercent).coerceIn(0, lowDod)
        if (holdDod >= lowDod) return BalancerStep(state, emptyList(), emptyList())
        return BalancerStep(
            BalancerState(heldBatteryId = low.id, originalDod = lowDod, lastWriteMs = nowMs, heldSocPercent = low.socPercent),
            listOf(BatteryCommand.SetDod(low.id, holdDod)),
            listOf("Ausgleich: ${low.name} (${low.socPercent} %) pausiert, ${high.name} (${high.socPercent} %) übernimmt"),
        )
    }
}
