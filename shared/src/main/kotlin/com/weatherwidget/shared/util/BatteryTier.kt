package com.weatherwidget.shared.util

/**
 * Shared battery-aware fetch scheduling thresholds.
 * Both Android and desktop use these tiers; each platform layers its own
 * charging intervals and extras on top.
 */
object BatteryTier {
    const val TIER_HIGH_THRESHOLD = 70
    const val TIER_MEDIUM_THRESHOLD = 50

    /** Minutes between fetches when battery > 70% (4 hours). */
    const val INTERVAL_HIGH_MINUTES = 240L

    /** Minutes between fetches when battery > 50% (8 hours). */
    const val INTERVAL_MEDIUM_MINUTES = 480L

    /**
     * Battery level at/above which an unplugged device may still use the aggressive "charging"
     * fetch cadence. Distinct from "is effectively charging": this is a *cadence* decision ("battery
     * is high enough to afford frequent fetches"), not a statement that the device is physically
     * plugged in or full.
     */
    const val TREAT_AS_CHARGING_THRESHOLD = 80

    /** A full battery is treated as effectively charging even when unplugged. */
    const val FULL_BATTERY_LEVEL = 100

    /**
     * Battery level at/above which a level that is *not falling* is taken as evidence that the
     * device is on a charger, even though the platform reports otherwise.
     *
     * Samsung's "Protect battery" mode holds the charge near a user-set cap and, while holding,
     * reports `plug=none status=discharging` — not merely `NOT_CHARGING`, but no charger attached
     * at all. Every branch of the platform charging check then fails, so the aggressive
     * current-temperature cadence stops on a device that is sitting on a charger. Observed on
     * SM-F936U1: the level climbed 76 -> 78 -> 80 while the framework reported `discharging`.
     * A battery does not gain four points while discharging.
     */
    const val HELD_CHARGE_MIN_LEVEL = 78

    /** Minimum battery percent for opportunistic (piggyback) network work. */
    const val OPPORTUNISTIC_MIN_BATTERY_PERCENT = 65

    /**
     * Whether an unplugged device's battery is high enough to be scheduled as if it were charging.
     * Used by forecast-fetch cadence decisions, never by the "is it physically charging" checks that
     * gate the current-temperature/non-primary loops.
     */
    fun treatAsCharging(isCharging: Boolean, batteryLevel: Int): Boolean =
        isCharging || batteryLevel >= TREAT_AS_CHARGING_THRESHOLD

    /**
     * Returns the fetch interval in minutes based on battery state,
     * or null if no scheduled fetch should occur (battery too low).
     */
    fun computeFetchInterval(isCharging: Boolean, batteryLevel: Int, chargingIntervalMinutes: Long): Long? {
        if (isCharging) return chargingIntervalMinutes
        return when {
            batteryLevel > TIER_HIGH_THRESHOLD -> INTERVAL_HIGH_MINUTES
            batteryLevel > TIER_MEDIUM_THRESHOLD -> INTERVAL_MEDIUM_MINUTES
            else -> null
        }
    }

    /**
     * Infers charging from the battery level trend, for devices whose platform charging signal is
     * unreliable (see [HELD_CHARGE_MIN_LEVEL]).
     *
     * The rule is "a high level that is not falling means something is holding it up":
     *  - below [HELD_CHARGE_MIN_LEVEL], never infer anything — return false;
     *  - a **rise** means charging;
     *  - a **drop** means discharging;
     *  - a **plateau** keeps the previous verdict, and counts as charging before any verdict exists.
     *
     * The plateau rules carry the design. Treating a plateau as charging is the only way to catch a
     * battery *held at a charge cap*, which is the case this exists for: a phone pinned at 80% by
     * Samsung's "Protect battery" never rises, so a rule that demanded a rise as proof could never
     * latch — it would sit at "not charging" for as long as the phone stayed on the charger.
     *
     * Keeping the previous verdict across plateaus is what stops that from oscillating. A phone
     * draining 80 -> 79 -> 79 -> 78 would otherwise read as discharging on each drop and charging
     * again on every plateau between drops, flapping the fetch cadence the whole way down. Holding
     * the last verdict instead means the first drop latches discharging and it stays there until a
     * genuine rise.
     *
     * The cost is bounded and self-correcting: a genuinely unplugged phone sitting above the
     * threshold is read as charging until it loses its first percentage point, then correctly reads
     * as discharging for the rest of the drain.
     *
     * @param previousLevel last observed level, or -1 when nothing has been recorded yet.
     * @param previousInference the verdict this function last returned, held across plateaus.
     */
    fun inferChargingFromLevelTrend(
        previousLevel: Int,
        currentLevel: Int,
        previousInference: Boolean,
    ): Boolean {
        if (currentLevel < HELD_CHARGE_MIN_LEVEL) return false
        if (previousLevel < 0) return true
        return when {
            currentLevel > previousLevel -> true
            currentLevel < previousLevel -> false
            else -> previousInference
        }
    }
}
