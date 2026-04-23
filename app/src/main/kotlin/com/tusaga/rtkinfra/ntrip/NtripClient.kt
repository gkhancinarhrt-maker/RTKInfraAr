package com.tusaga.rtkinfra.ntrip

import timber.log.Timber
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * NTRIP Rev1 Client – socket-based implementation compatible with TUSAGA-Aktif.
 *
 * NTRIP Rev1 Protocol flow:
 *   1. Open TCP socket to caster host:port
 *   2. Send HTTP/1.0 GET request with Authorization header
 *   3. Read HTTP response: "ICY 200 OK" = success
 *   4. Stream RTCM 3.x binary data from response body
 *   5. Periodically send NMEA GGA to support VRS corrections
 *
 * This class handles a SINGLE connection attempt.
 * [NtripClientManager] wraps it with retry/reconnect logic.
 */
class NtripClient(private val config: NtripConfig) {

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    enum class ConnectResult { SUCCESS, AUTH_FAILED, MOUNTPOINT_NOT_FOUND, NETWORK_ERROR }

    /**
     * Establishes a connection to the NTRIP caster.
     * Blocking – call from a background coroutine.
     *
     * @return [ConnectResult.SUCCESS] if RTCM stream is ready
     */
    fun connect(): ConnectResult {
        Timber.i("NTRIP: Connecting to ${config.host}:${config.port}/${config.mountpoint}")
        return try {
            val sock = Socket().also { socket = it }
            sock.connect(
                InetSocketAddress(config.host, config.port),
                config.connectTimeoutMs
            )
            sock.soTimeout = config.socketTimeoutMs
            sock.keepAlive = true
            sock.setPerformancePreferences(0, 1, 0) // latency > bandwidth > conn-time

            inputStream  = sock.getInputStream()
            outputStream = sock.getOutputStream()

            sendHttpRequest()
            readResponse()
        } catch (e: Exception) {
            Timber.e(e, "NTRIP: Connect failed")
            disconnect()
            ConnectResult.NETWORK_ERROR
        }
    }

    /**
     * Sends NTRIP Rev1 HTTP request.
     * Header format closely follows IGS NTRIP Rev1 specification (BKG).
     */
    private fun sendHttpRequest() {
        val request = buildString {
            append("GET /${config.mountpoint} HTTP/1.0\r\n")
            append("Host: ${config.host}\r\n")
            append("Ntrip-Version: Ntrip/1.0\r\n")
            append("User-Agent: NTRIP RTKInfraAR/1.0\r\n")
            append("Authorization: Basic ${config.basicAuthBase64}\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        outputStream?.write(request.toByteArray(Charsets.US_ASCII))
        outputStream?.flush()
        Timber.d("NTRIP: Request sent for /${config.mountpoint}")
    }

    /**
     * Reads HTTP response line(s) to determine if stream is ready.
     * NTRIP Rev1 uses non-standard "ICY 200 OK" success status.
     * NTRIP Rev2 uses standard "HTTP/1.1 200 OK".
     */
    private fun readResponse(): ConnectResult {
        val reader = inputStream?.bufferedReader(Charsets.US_ASCII) ?: return ConnectResult.NETWORK_ERROR
        val firstLine = reader.readLine() ?: return ConnectResult.NETWORK_ERROR

        Timber.d("NTRIP: Response: $firstLine")

        return when {
            firstLine.contains("ICY 200 OK", ignoreCase = true) ||
            firstLine.contains("HTTP/1.1 200", ignoreCase = true) ||
            firstLine.contains("HTTP/1.0 200", ignoreCase = true) -> {
                // Drain remaining headers until blank line
                var line: String?
                do { line = reader.readLine() } while (!line.isNullOrBlank())
                Timber.i("NTRIP: Stream connected successfully")
                ConnectResult.SUCCESS
            }
            firstLine.contains("401", ignoreCase = true) -> {
                Timber.e("NTRIP: Authentication failed (401)")
                ConnectResult.AUTH_FAILED
            }
            firstLine.contains("404", ignoreCase = true) ||
            firstLine.contains("SOURCETABLE", ignoreCase = true) -> {
                Timber.e("NTRIP: Mountpoint not found")
                ConnectResult.MOUNTPOINT_NOT_FOUND
            }
            else -> {
                Timber.e("NTRIP: Unexpected response: $firstLine")
                ConnectResult.NETWORK_ERROR
            }
        }
    }

    /**
     * Reads one chunk of RTCM binary data from the stream.
     * Returns null when connection is closed or errored.
     *
     * The caller (NtripClientManager) passes received data to the RTCM parser.
     * Uses a modest 4096-byte buffer; RTCM messages are typically 20-500 bytes.
     */
    fun readRtcmChunk(buffer: ByteArray): Int {
        return try {
            inputStream?.read(buffer) ?: -1
        } catch (e: SocketException) {
            Timber.d("NTRIP: Socket closed during read")
            -1
        } catch (e: IOException) {
            Timber.e(e, "NTRIP: Read error")
            -1
        }
    }

    /**
     * Sends an NMEA GGA sentence to the caster.
     * Required by VRS casters (like TUSAGA VRS) to generate station-specific
     * corrections relative to the rover position.
     */
    fun sendGga(ggaSentence: String): Boolean {
        return try {
            outputStream?.write(ggaSentence.toByteArray(Charsets.US_ASCII))
            outputStream?.flush()
            Timber.v("NTRIP: GGA sent: ${ggaSentence.trim()}")
            true
        } catch (e: Exception) {
            Timber.e(e, "NTRIP: Failed to send GGA")
            false
        }
    }

    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Timber.v("NTRIP: Disconnect error (expected): ${e.message}")
        } finally {
            inputStream  = null
            outputStream = null
            socket       = null
        }
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false
}
