package com.tusaga.rtkinfra.gnss

import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Generates NMEA 0183 GGA sentences required by NTRIP casters to:
 *  1. Authenticate position for VRS (Virtual Reference Station) generation
 *  2. Select the nearest mountpoint automatically
 *
 * TUSAGA-Aktif VRS requires valid GGA every 5-10 seconds.
 *
 * GGA format:
 *   $GPGGA,HHMMSS.ss,DDMM.MMMMM,N,DDDMM.MMMMM,E,Q,NN,H.H,AAA.A,M,GGG.G,M,D.D,SSSS*hh
 */
object NmeaGenerator {

    private val timeFormat = SimpleDateFormat("HHmmss.SS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Builds a GPGGA sentence from WGS84 position.
     *
     * @param lat         Latitude in decimal degrees (positive = North)
     * @param lon         Longitude in decimal degrees (positive = East)
     * @param altM        Ellipsoidal height in meters
     * @param fixType     Current RTK fix quality
     * @param numSats     Number of satellites used
     * @param hdop        Horizontal dilution of precision
     * @param geoidSepM   Geoid undulation in meters (default 0 if unknown)
     */
    fun buildGga(
        lat: Double,
        lon: Double,
        altM: Double,
        fixType: FixType = FixType.SINGLE,
        numSats: Int = 8,
        hdop: Double = 1.0,
        geoidSepM: Double = 0.0,
        ageOfDiff: Double = 0.0,
        stationId: String = ""
    ): String {
        val utcTime  = timeFormat.format(Date())
        val latStr   = decimalDegreesToDDMM(lat, isLat = true)
        val latHemi  = if (lat >= 0) "N" else "S"
        val lonStr   = decimalDegreesToDDMM(lon, isLat = false)
        val lonHemi  = if (lon >= 0) "E" else "W"
        val quality  = fixType.nmeaQuality
        val sats     = numSats.toString().padStart(2, '0')
        val hdopStr  = "%.1f".format(hdop)
        val altStr   = "%.3f".format(altM)
        val geoidStr = "%.3f".format(geoidSepM)
        val ageStr   = if (ageOfDiff > 0) "%.1f".format(ageOfDiff) else ""
        val stId     = stationId.take(4)

        val sentence = "\$GPGGA,$utcTime,$latStr,$latHemi,$lonStr,$lonHemi," +
                       "$quality,$sats,$hdopStr,$altStr,M,$geoidStr,M,$ageStr,$stId"

        val checksum = computeChecksum(sentence)
        return "$sentence*$checksum\r\n"
    }

    /**
     * Converts decimal degrees to NMEA DDmm.mmmmm (or DDDmm.mmmmm) format.
     */
    private fun decimalDegreesToDDMM(degrees: Double, isLat: Boolean): String {
        val absDeg = abs(degrees)
        val d      = absDeg.toInt()
        val m      = (absDeg - d) * 60.0
        return if (isLat) {
            "%02d%010.7f".format(d, m)   // DDmm.mmmmmmm
        } else {
            "%03d%010.7f".format(d, m)   // DDDmm.mmmmmmm
        }
    }

    /**
     * XOR checksum of all characters between $ and * (exclusive).
     */
    private fun computeChecksum(sentence: String): String {
        val start = sentence.indexOf('$') + 1
        val end   = sentence.indexOf('*').let { if (it < 0) sentence.length else it }
        var cs    = 0
        for (i in start until end) cs = cs xor sentence[i].code
        return "%02X".format(cs)
    }
}
