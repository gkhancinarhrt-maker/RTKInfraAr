package com.tusaga.rtkinfra.ntrip

import com.tusaga.rtkinfra.data.repository.SettingsRepository
import com.tusaga.rtkinfra.gnss.FixType
import com.tusaga.rtkinfra.gnss.GnssManager
import com.tusaga.rtkinfra.gnss.NmeaGenerator
import com.tusaga.rtkinfra.gnss.NtripState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full NTRIP lifecycle:
 *  - Connects to TUSAGA-Aktif caster
 *  - Streams RTCM corrections
 *  - Sends NMEA GGA for VRS support
 *  - Reconnects automatically on failure
 *  - Injects RTK fix quality back into [GnssManager]
 */
@Singleton
class NtripClientManager @Inject constructor(
    private val gnssManager: GnssManager,
    private val settingsRepository: SettingsRepository
) {
    private val _ntripState = MutableStateFlow(NtripState())
    val ntripState: StateFlow<NtripState> = _ntripState.asStateFlow()

    private var managerScope: CoroutineScope? = null
    private var currentClient: NtripClient? = null
    private val rtcmParser = RtcmParser()

    // Track RTCM message receipt for fix quality injection
    private var lastRtcmMs = 0L
    private var bytesReceived = 0L

    init {
        setupRtcmParser()
    }

    private fun setupRtcmParser() {
        rtcmParser.onMessageParsed = { msg ->
            lastRtcmMs = System.currentTimeMillis()
            bytesReceived += msg.data.size

            when (msg.messageType) {
                // MSM7 (highest quality) messages indicate RTK-capable corrections
                in listOf(1077, 1087, 1097, 1117) -> {
                    // MSM7 = full pseudorange + carrier phase = RTK Fixed capable
                    gnssManager.injectRtkFixType(FixType.FIX, 0.0, "TUSAGA")
                }
                in listOf(1074, 1084, 1094, 1114) -> {
                    // MSM4 = lower quality, typically RTK Float
                    gnssManager.injectRtkFixType(FixType.FLOAT, 0.0, "TUSAGA")
                }
                in listOf(1001, 1002, 1009, 1010) -> {
                    // Legacy RTK observables
                    gnssManager.injectRtkFixType(FixType.FLOAT, 0.0, "TUSAGA")
                }
                in listOf(1005, 1006) -> {
                    // Station coordinates – parse for reference position
                    val ecef = rtcmParser.parseStationType1005(msg.data)
                    Timber.d("NTRIP: Reference station ECEF: $ecef")
                }
                in listOf(1033) -> {
                    Timber.d("NTRIP: Receiver descriptor received")
                }
                else -> Timber.v("NTRIP: RTCM type=${msg.messageType}")
            }

            _ntripState.update { it.copy(
                bytesReceived    = bytesReceived,
                lastRtcmMessageMs = lastRtcmMs
            )}
        }
    }

    fun start() {
        managerScope?.cancel()
        managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        managerScope!!.launch { connectionLoop() }
    }

    fun stop() {
        managerScope?.cancel()
        currentClient?.disconnect()
        currentClient = null
        _ntripState.value = NtripState(isConnected = false)
        Timber.i("NTRIP: Stopped")
    }

    fun updateConfig(config: NtripConfig) {
        // Disconnect and reconnect with new config
        stop()
        start()
    }

    // ──────────────────────────────────────────────────────────────────
    // Connection loop with exponential backoff
    // ──────────────────────────────────────────────────────────────────

    private suspend fun connectionLoop() {
        var backoffMs = 3_000L
        val maxBackoffMs = 60_000L

        while (currentCoroutineContext().isActive) {
            val config = settingsRepository.getNtripConfig()

            if (config.username.isBlank() || config.password.isBlank()) {
                Timber.w("NTRIP: Credentials not configured, waiting…")
                delay(10_000L)
                continue
            }

            Timber.i("NTRIP: Attempting connection to ${config.host}:${config.port}")
            _ntripState.update {
                it.copy(isConnected = false, host = config.host,
                        mountpoint = config.mountpoint, errorMessage = null)
            }

            val client = NtripClient(config).also { currentClient = it }

            val result = withContext(Dispatchers.IO) { client.connect() }

            when (result) {
                NtripClient.ConnectResult.SUCCESS -> {
                    backoffMs = 3_000L  // Reset backoff on success
                    _ntripState.update {
                        it.copy(isConnected = true, errorMessage = null)
                    }
                    Timber.i("NTRIP: Connected – streaming RTCM")

                    // Run receive + GGA-send loops concurrently
                    try {
                        coroutineScope {
                            launch { receiveRtcmLoop(client, config) }
                            launch { sendGgaLoop(client, config) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "NTRIP: Stream error")
                    }

                    client.disconnect()
                    _ntripState.update { it.copy(isConnected = false) }
                    gnssManager.injectRtkFixType(FixType.SINGLE, 0.0, "")
                }
                NtripClient.ConnectResult.AUTH_FAILED -> {
                    _ntripState.update {
                        it.copy(isConnected = false, errorMessage = "Auth failed – check credentials")
                    }
                    delay(30_000L)   // Wait longer before retrying auth failure
                    continue
                }
                NtripClient.ConnectResult.MOUNTPOINT_NOT_FOUND -> {
                    _ntripState.update {
                        it.copy(isConnected = false, errorMessage = "Mountpoint not found: ${config.mountpoint}")
                    }
                    delay(30_000L)
                    continue
                }
                NtripClient.ConnectResult.NETWORK_ERROR -> {
                    _ntripState.update {
                        it.copy(isConnected = false, errorMessage = "Network error – retrying in ${backoffMs/1000}s")
                    }
                }
            }

            Timber.d("NTRIP: Reconnect in ${backoffMs}ms")
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, maxBackoffMs)
        }
    }

    /**
     * Continuously reads RTCM binary data and feeds it to the parser.
     * Exits when the socket closes or an error occurs.
     */
    private suspend fun receiveRtcmLoop(client: NtripClient, config: NtripConfig) {
        val buffer = ByteArray(4096)
        while (currentCoroutineContext().isActive) {
            val n = withContext(Dispatchers.IO) { client.readRtcmChunk(buffer) }
            if (n < 0) {
                Timber.d("NTRIP: Stream closed")
                break
            }
            if (n > 0) {
                rtcmParser.feed(buffer, n)
            }
        }
    }

    /**
     * Periodically sends NMEA GGA to caster for VRS corrections.
     * TUSAGA-Aktif VRS requires GGA every 5 seconds to maintain corrections.
     */
    private suspend fun sendGgaLoop(client: NtripClient, config: NtripConfig) {
        while (currentCoroutineContext().isActive) {
            delay(config.sendNmeaIntervalMs)

            val pos = gnssManager.getCurrentPosition()
            if (pos != null) {
                val (lat, lon, alt) = pos
                val state = gnssManager.rtkStateFlow.value
                val gga = NmeaGenerator.buildGga(
                    lat      = lat,
                    lon      = lon,
                    altM     = alt,
                    fixType  = state.fixType,
                    numSats  = state.usedSatellites.coerceAtLeast(4),
                    hdop     = state.hdop.coerceAtMost(10.0),
                    ageOfDiff = state.ageOfDifferentialSeconds
                )
                withContext(Dispatchers.IO) { client.sendGga(gga) }
            }
        }
    }
}
