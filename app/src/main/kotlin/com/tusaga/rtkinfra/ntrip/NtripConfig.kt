package com.tusaga.rtkinfra.ntrip

/**
 * NTRIP connection configuration for TUSAGA-Aktif CORS network.
 *
 * TUSAGA-Aktif (Turkey CORS) details:
 *   Host:  cors.tusaga-aktif.gov.tr  (or ntrip.tusaga-aktif.gov.tr)
 *   Port:  2101
 *   Auth:  Username/Password from KGM (General Directorate of Highways)
 *
 * Common mountpoints:
 *   TUSK00TUR0  – VRS (Virtual Reference Station) – RECOMMENDED
 *   IST000TUR0  – Istanbul physical station
 *   ANK000TUR0  – Ankara physical station
 *   IZM000TUR0  – Izmir physical station
 *   (Full list via sourcetable request to caster)
 */
data class NtripConfig(
    val host: String       = "cors.tusaga-aktif.gov.tr",
    val port: Int          = 2101,
    val mountpoint: String = "TUSK00TUR0",   // VRS mountpoint (preferred)
    val username: String   = "",              // Your TUSAGA-Aktif username
    val password: String   = "",              // Your TUSAGA-Aktif password
    val sendNmeaIntervalMs: Long = 5_000L,    // GGA send interval for VRS
    val reconnectDelayMs: Long   = 3_000L,    // Reconnect delay on failure
    val socketTimeoutMs: Int     = 30_000,    // Read timeout
    val connectTimeoutMs: Int    = 15_000     // Connect timeout
) {
    /** Base64-encoded "username:password" for HTTP Basic Auth */
    val basicAuthBase64: String
        get() {
            val credentials = "$username:$password"
            return android.util.Base64.encodeToString(
                credentials.toByteArray(Charsets.ISO_8859_1),
                android.util.Base64.NO_WRAP
            )
        }
}
