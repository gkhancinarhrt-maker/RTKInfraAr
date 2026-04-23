package com.tusaga.rtkinfra.gnss

/**
 * RTK fix quality levels, ordered from worst to best.
 * Maps to NMEA GGA quality indicator values.
 */
enum class FixType(val nmeaQuality: Int, val label: String) {
    NONE   (0, "NONE"),
    SINGLE (1, "SINGLE"),
    DGPS   (2, "DGPS"),
    FLOAT  (5, "FLOAT"),   // RTK Float
    FIX    (4, "FIX");     // RTK Fixed – centimeter accuracy

    companion object {
        fun fromNmea(q: Int) = values().firstOrNull { it.nmeaQuality == q } ?: NONE
    }
}

/**
 * Satellite band flags – we prioritize dual-frequency measurements.
 */
enum class GnssBand { L1, L5, UNKNOWN }

/**
 * Snapshot of a single GNSS satellite measurement.
 * Populated from [android.location.GnssMeasurement].
 */
data class SatelliteMeasurement(
    val svid: Int,
    val constellationType: Int,   // android.location.GnssStatus.CONSTELLATION_*
    val band: GnssBand,
    val cn0DbHz: Double,          // Carrier-to-noise density – signal quality
    val pseudorangeRateMetersPerSecond: Double,
    val receivedSvTimeNanos: Long,
    val timeOffsetNanos: Double,
    val hasCarrierPhase: Boolean,
    val carrierPhaseUncertainty: Double
)

/**
 * Complete RTK positioning state emitted to UI and AR subsystems.
 */
data class RtkState(
    val fixType: FixType = FixType.NONE,
    val latitude: Double = 0.0,         // WGS84 degrees
    val longitude: Double = 0.0,        // WGS84 degrees
    val altitudeM: Double = 0.0,        // Ellipsoidal height (meters)
    val accuracyM: Double = 99.0,       // Horizontal accuracy (meters)
    val verticalAccuracyM: Double = 99.0,
    val speedMps: Double = 0.0,
    val bearingDeg: Double = 0.0,
    val usedSatellites: Int = 0,
    val visibleSatellites: Int = 0,
    val dualFrequencyAvailable: Boolean = false,
    val l5SatCount: Int = 0,
    val hdop: Double = 99.0,
    val vdop: Double = 99.0,
    val ageOfDifferentialSeconds: Double = 0.0,
    val referenceStation: String = "",
    val timestampMs: Long = 0L
) {
    /** True when RTK Fix is achieved – centimeter accuracy expected */
    val isCentimeterAccurate: Boolean
        get() = fixType == FixType.FIX && accuracyM < 0.05

    /** Human-readable accuracy string */
    val accuracyDisplay: String
        get() = when {
            accuracyM < 0.01 -> "< 1 cm"
            accuracyM < 1.0  -> "${"%.1f".format(accuracyM * 100)} cm"
            else             -> "${"%.2f".format(accuracyM)} m"
        }
}

/**
 * NTRIP / TUSAGA connection state published to UI.
 */
data class NtripState(
    val isConnected: Boolean = false,
    val host: String = "",
    val mountpoint: String = "",
    val bytesReceived: Long = 0L,
    val lastRtcmMessageMs: Long = 0L,
    val errorMessage: String? = null
)
