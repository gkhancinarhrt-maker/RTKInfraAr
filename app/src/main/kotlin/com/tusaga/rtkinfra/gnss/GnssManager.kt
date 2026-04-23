package com.tusaga.rtkinfra.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Manages Android GNSS raw measurement API, location updates, and
 * satellite status callbacks. Publishes unified [RtkState] to collectors.
 *
 * Threading: GNSS callbacks arrive on a dedicated HandlerThread to avoid
 * blocking the main thread. State is published via StateFlow (thread-safe).
 */
@Singleton
class GnssManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ── Dedicated background thread for GNSS callbacks ────────────────
    private val gnssThread = HandlerThread("gnss-thread").also { it.start() }
    private val gnssHandler = Handler(gnssThread.looper)

    // ── Published state ───────────────────────────────────────────────
    private val _rtkStateFlow = MutableStateFlow(RtkState())
    val rtkStateFlow: StateFlow<RtkState> = _rtkStateFlow.asStateFlow()

    private val _measurementsFlow = MutableStateFlow<List<SatelliteMeasurement>>(emptyList())
    val measurementsFlow: StateFlow<List<SatelliteMeasurement>> = _measurementsFlow.asStateFlow()

    // ── Internal state ────────────────────────────────────────────────
    private var lastLocation: Location? = null
    private val satMeasurements = mutableMapOf<Int, SatelliteMeasurement>()
    private var gnssStatus: GnssStatus? = null
    private var dualFreqAvailable = false
    private var l5SatCount = 0

    // ──────────────────────────────────────────────────────────────────
    // Location listener – receives fused GNSS+network location updates
    // ──────────────────────────────────────────────────────────────────
    private val locationListener = LocationListener { location ->
        lastLocation = location
        publishRtkState(location)
    }

    // ──────────────────────────────────────────────────────────────────
    // GNSS Raw Measurement Callback
    // Critical: this is where we detect L1/L5 dual-frequency capability
    // ──────────────────────────────────────────────────────────────────
    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val measurements = event.measurements.mapNotNull { m ->
                parseMeasurement(m)
            }

            // Count L5 satellites to detect dual-frequency hardware
            val l5Sats = measurements.filter { it.band == GnssBand.L5 }
            l5SatCount = l5Sats.size
            dualFreqAvailable = l5SatCount >= 3   // Need ≥3 for positioning

            measurements.forEach { satMeasurements[it.svid] = it }
            _measurementsFlow.value = measurements

            Timber.v("GNSS measurements: total=${measurements.size} L5=$l5SatCount dualFreq=$dualFreqAvailable")

            // Re-publish RTK state with updated satellite info
            lastLocation?.let { publishRtkState(it) }
        }

        override fun onStatusChanged(status: Int) {
            Timber.d("GnssMeasurements status: $status")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // GNSS Status Callback – satellite constellation visibility
    // ──────────────────────────────────────────────────────────────────
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            gnssStatus = status
        }

        override fun onStarted()  { Timber.d("GNSS started") }
        override fun onStopped()  { Timber.d("GNSS stopped") }
        override fun onFirstFix(ttffMillis: Int) {
            Timber.i("First GNSS fix in ${ttffMillis}ms")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // NMEA Listener – parses GGA sentences for RTK fix quality
    // ──────────────────────────────────────────────────────────────────
    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        if (message.startsWith("\$GPGGA") || message.startsWith("\$GNGGA")) {
            parseGgaQuality(message)
        }
    }

    private var currentFixType = FixType.SINGLE
    private var hdop = 99.0
    private var vdop = 99.0
    private var ageOfDiff = 0.0
    private var refStation = ""

    // ──────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startListening() {
        Timber.i("Starting GNSS listening")
        try {
            // 1 Hz location updates via GPS provider
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,   // min interval ms
                0f,      // min distance m
                locationListener,
                gnssHandler.looper
            )

            // Raw GNSS measurements (requires ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.registerGnssMeasurementsCallback(
                    measurementsCallback, gnssHandler
                )
            } else {
                locationManager.registerGnssMeasurementsCallback(
                    gnssHandler.looper, measurementsCallback
                )
            }

            // GNSS satellite status
            locationManager.registerGnssStatusCallback(
                gnssStatusCallback, gnssHandler
            )

            // NMEA for RTK quality indicator
            locationManager.addNmeaListener(nmeaListener, gnssHandler)

        } catch (e: Exception) {
            Timber.e(e, "Failed to start GNSS listening")
        }
    }

    fun stopListening() {
        try {
            locationManager.removeUpdates(locationListener)
            locationManager.unregisterGnssMeasurementsCallback(measurementsCallback)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            locationManager.removeNmeaListener(nmeaListener)
        } catch (e: Exception) {
            Timber.e(e, "Error stopping GNSS")
        }
        gnssThread.quitSafely()
    }

    /**
     * Injects RTK fix quality from NTRIP RTCM parsing.
     * Called by NtripClientManager when a fix quality change is detected.
     */
    fun injectRtkFixType(fixType: FixType, ageSeconds: Double, stationId: String) {
        currentFixType = fixType
        ageOfDiff = ageSeconds
        refStation = stationId
        lastLocation?.let { publishRtkState(it) }
    }

    // ──────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────

    private fun publishRtkState(location: Location) {
        val status = gnssStatus
        val usedSats = satMeasurements.size
        val visibleSats = status?.satelliteCount ?: 0

        // Derive accuracy from fix type if RTK corrections present
        val accuracy = when (currentFixType) {
            FixType.FIX    -> minOf(location.accuracy, 0.03f).toDouble()  // RTK Fix ≈ 3cm
            FixType.FLOAT  -> minOf(location.accuracy, 0.30f).toDouble()  // RTK Float ≈ 30cm
            FixType.DGPS   -> minOf(location.accuracy, 1.0f).toDouble()   // DGPS ≈ 1m
            FixType.SINGLE -> location.accuracy.toDouble()
            FixType.NONE   -> 99.0
        }

        _rtkStateFlow.value = RtkState(
            fixType               = currentFixType,
            latitude              = location.latitude,
            longitude             = location.longitude,
            altitudeM             = location.altitude,
            accuracyM             = accuracy,
            verticalAccuracyM     = if (Build.VERSION.SDK_INT >= 26)
                                        location.verticalAccuracyMeters.toDouble() else 99.0,
            speedMps              = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            bearingDeg            = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
            usedSatellites        = usedSats,
            visibleSatellites     = visibleSats,
            dualFrequencyAvailable = dualFreqAvailable,
            l5SatCount            = l5SatCount,
            hdop                  = hdop,
            vdop                  = vdop,
            ageOfDifferentialSeconds = ageOfDiff,
            referenceStation      = refStation,
            timestampMs           = System.currentTimeMillis()
        )
    }

    /**
     * Parses a single [GnssMeasurement] into our domain model.
     * Detects L1 vs L5 bands using carrier frequency:
     *   L1 ≈ 1575.42 MHz
     *   L5 ≈ 1176.45 MHz
     *   GLONASS L1 ≈ 1602 MHz ± slot × 0.5625 MHz
     */
    private fun parseMeasurement(m: android.location.GnssMeasurement): SatelliteMeasurement? {
        return try {
            val band = detectBand(m)
            SatelliteMeasurement(
                svid                             = m.svid,
                constellationType                = m.constellationType,
                band                             = band,
                cn0DbHz                          = m.cn0DbHz,
                pseudorangeRateMetersPerSecond   = m.pseudorangeRateMetersPerSecond,
                receivedSvTimeNanos              = m.receivedSvTimeNanos,
                timeOffsetNanos                  = m.timeOffsetNanos,
                hasCarrierPhase                  = (m.state and
                    android.location.GnssMeasurement.STATE_CODE_LOCK) != 0,
                carrierPhaseUncertainty          = m.carrierPhaseUncertainty
            )
        } catch (e: Exception) {
            Timber.v("Could not parse measurement for svid=${m.svid}: ${e.message}")
            null
        }
    }

    private fun detectBand(m: android.location.GnssMeasurement): GnssBand {
        if (!m.hasCarrierFrequencyHz()) return GnssBand.L1  // Assume L1 if unknown
        val freqMHz = m.carrierFrequencyHz / 1_000_000.0
        return when {
            freqMHz in 1574.0..1577.0 -> GnssBand.L1   // GPS/Galileo/BeiDou L1
            freqMHz in 1175.0..1178.0 -> GnssBand.L5   // GPS L5 / Galileo E5a
            freqMHz in 1600.0..1610.0 -> GnssBand.L1   // GLONASS L1
            freqMHz in 1240.0..1250.0 -> GnssBand.L5   // GLONASS L2 (treated as L5-class)
            else -> GnssBand.UNKNOWN
        }
    }

    /**
     * Parses NMEA GGA sentence for RTK quality indicator (field 6).
     * GGA format: $GNGGA,HHMMSS.ss,Lat,N,Lon,E,Q,nSat,HDOP,Alt,M,…
     *
     * Quality field values:
     *   0 = Invalid, 1 = GPS/SPS, 2 = DGPS, 4 = RTK Fixed, 5 = RTK Float
     */
    private fun parseGgaQuality(nmea: String) {
        try {
            val parts = nmea.split(",")
            if (parts.size < 15) return

            val quality   = parts[6].toIntOrNull() ?: return
            val hdopStr   = parts[8]
            val altStr    = parts[9]

            currentFixType = FixType.fromNmea(quality)
            hdop = hdopStr.toDoubleOrNull() ?: hdop

            // Parse age of differential (field 13) and station ID (field 14)
            if (parts.size > 14) {
                ageOfDiff  = parts[13].toDoubleOrNull() ?: 0.0
                refStation = parts[14].substringBefore("*")
            }

            Timber.v("GGA quality=$quality fix=${currentFixType.label} hdop=$hdop")
        } catch (e: Exception) {
            Timber.v("GGA parse error: ${e.message}")
        }
    }

    /** Current position for NMEA GGA generation (used by NTRIP client) */
    fun getCurrentPosition(): Triple<Double, Double, Double>? {
        val loc = lastLocation ?: return null
        return Triple(loc.latitude, loc.longitude, loc.altitude)
    }
}
