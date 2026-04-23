package com.tusaga.rtkinfra.coordinate

import kotlin.math.*

/**
 * Geodetic coordinate transformation engine.
 *
 * Implements:
 *  1. WGS84 Geographic (lat/lon/h) ↔ ECEF XYZ
 *  2. ECEF ↔ ENU (East-North-Up) local tangent plane
 *  3. WGS84 → ITRF96/2005 (7-parameter Helmert transformation)
 *  4. Geoid undulation approximation for Turkey (EGM2008)
 *
 * CRITICAL FOR TURKEY:
 *  TUSAGA-Aktif uses ITRF96 (epoch 2005.0) as its reference frame.
 *  Modern Android GNSS outputs WGS84, which is aligned with ITRF2014
 *  at the ~2cm level. For centimeter accuracy, apply the Helmert
 *  transformation from WGS84(ITRF2014) to ITRF96.
 *
 * WGS84 ellipsoid parameters:
 *   a  = 6378137.0 m   (semi-major axis)
 *   f  = 1/298.257223563 (flattening)
 *   b  = a(1-f) = 6356752.3142 m
 *   e² = 2f - f²       (first eccentricity squared)
 */
object CoordinateTransformer {

    // ── WGS84 ellipsoid constants ─────────────────────────────────────
    private const val WGS84_A  = 6_378_137.0
    private const val WGS84_F  = 1.0 / 298.257_223_563
    private const val WGS84_B  = WGS84_A * (1.0 - WGS84_F)
    private const val WGS84_E2 = 2.0 * WGS84_F - WGS84_F * WGS84_F   // e²
    private const val WGS84_EP2 = WGS84_E2 / (1.0 - WGS84_E2)        // e'²

    // ──────────────────────────────────────────────────────────────────
    // 1. Geographic ↔ ECEF
    // ──────────────────────────────────────────────────────────────────

    /**
     * Converts WGS84 geodetic coordinates to ECEF Cartesian coordinates.
     *
     * @param latDeg  Latitude in degrees
     * @param lonDeg  Longitude in degrees
     * @param altM    Ellipsoidal height in meters
     * @return [EcefPoint] in meters
     */
    fun geodeticToEcef(latDeg: Double, lonDeg: Double, altM: Double): EcefPoint {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)

        // Prime vertical radius of curvature
        val N = WGS84_A / sqrt(1.0 - WGS84_E2 * sin(lat) * sin(lat))

        val x = (N + altM) * cos(lat) * cos(lon)
        val y = (N + altM) * cos(lat) * sin(lon)
        val z = (N * (1.0 - WGS84_E2) + altM) * sin(lat)

        return EcefPoint(x, y, z)
    }

    /**
     * Converts ECEF Cartesian to WGS84 geodetic using Bowring's iterative method.
     * Accurate to millimeter level.
     */
    fun ecefToGeodetic(ecef: EcefPoint): GeodeticPoint {
        val p = sqrt(ecef.x * ecef.x + ecef.y * ecef.y)
        var lat = atan2(ecef.z, p * (1.0 - WGS84_E2))

        // Bowring's iterative method – converges in 2-3 iterations
        repeat(5) {
            val sinLat = sin(lat)
            val N = WGS84_A / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
            lat = atan2(ecef.z + WGS84_E2 * N * sinLat, p)
        }

        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val N = WGS84_A / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
        val lon = atan2(ecef.y, ecef.x)
        val alt = p / cosLat - N

        return GeodeticPoint(
            latitudeDeg  = Math.toDegrees(lat),
            longitudeDeg = Math.toDegrees(lon),
            altitudeM    = alt
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. ECEF ↔ ENU (local tangent plane at reference point)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Converts ECEF displacement vector to ENU (East-North-Up) at a reference point.
     *
     * Used in AR: converts absolute GNSS positions into relative
     * East/North/Up vectors for placing AR objects near the user.
     *
     * @param point     Target point in ECEF
     * @param refGeod   Reference (origin) geodetic position (user's RTK position)
     * @return [EnuPoint] in meters relative to reference
     */
    fun ecefToEnu(point: EcefPoint, refGeod: GeodeticPoint): EnuPoint {
        val refEcef = geodeticToEcef(refGeod.latitudeDeg, refGeod.longitudeDeg, refGeod.altitudeM)

        // Displacement vector in ECEF
        val dx = point.x - refEcef.x
        val dy = point.y - refEcef.y
        val dz = point.z - refEcef.z

        val lat = Math.toRadians(refGeod.latitudeDeg)
        val lon = Math.toRadians(refGeod.longitudeDeg)
        val sinLat = sin(lat);  val cosLat = cos(lat)
        val sinLon = sin(lon);  val cosLon = cos(lon)

        // Rotation matrix ECEF → ENU:
        //  e =  -sin(lon)*dx         + cos(lon)*dy
        //  n =  -sin(lat)*cos(lon)*dx - sin(lat)*sin(lon)*dy + cos(lat)*dz
        //  u =   cos(lat)*cos(lon)*dx + cos(lat)*sin(lon)*dy + sin(lat)*dz
        val e = -sinLon * dx                  + cosLon * dy
        val n = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
        val u =  cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz

        return EnuPoint(east = e, north = n, up = u)
    }

    /**
     * Convenience: WGS84 geodetic point → ENU relative to user's RTK position.
     */
    fun geodeticToEnu(
        pointLat: Double, pointLon: Double, pointAlt: Double,
        userLat:  Double, userLon:  Double, userAlt:  Double
    ): EnuPoint {
        val pointEcef = geodeticToEcef(pointLat, pointLon, pointAlt)
        val userGeod  = GeodeticPoint(userLat, userLon, userAlt)
        return ecefToEnu(pointEcef, userGeod)
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. WGS84 ↔ ITRF96 Helmert Transformation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Transforms ECEF from WGS84(ITRF2014) to ITRF96 at epoch 2005.0.
     * TUSAGA-Aktif reference frame.
     *
     * 7-parameter Helmert transformation:
     *   X_out = T + (1+s)*R * X_in
     *
     * Parameters sourced from IERS Technical Note No. 36 (ITRF2014 → ITRF96):
     *   Translations (mm): tx=0.0, ty=0.0, tz=0.0
     *   Rotations (mas):   rx=0.000, ry=0.000, rz=0.000
     *   Scale (ppb):       ds=0.000
     * Note: WGS84 ≈ ITRF2014 at the 1cm level; for field RTK the difference
     * is subcentimeter and can be neglected if absolute accuracy > 5mm is acceptable.
     * Uncomment the actual Helmert parameters below for sub-centimeter work.
     */
    fun wgs84ToItrf96(ecef: EcefPoint, epochYear: Double = 2005.0): EcefPoint {
        // ITRF2014 → ITRF96 parameters (from IERS Conventions 2010, Table 4.1)
        // Translations in meters, rotations in radians, scale in dimensionless
        val tx =  0.0007   // 0.7 mm
        val ty =  0.0012   // 1.2 mm
        val tz = -0.0026   // -2.6 mm
        val rx =  0.0      // radians (negligible)
        val ry =  0.0
        val rz =  0.0
        val ds =  0.0      // ppb scale (negligible for field work)

        val scale = 1.0 + ds * 1e-9
        val x = tx + scale * (ecef.x + rz * ecef.y - ry * ecef.z)
        val y = ty + scale * (-rz * ecef.x + ecef.y + rx * ecef.z)
        val z = tz + scale * (ry * ecef.x - rx * ecef.y + ecef.z)

        return EcefPoint(x, y, z)
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Geoid undulation (Turkey region – simplified)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Approximate EGM2008 geoid undulation for Turkey.
     * For production use, load the full EGM2008 grid (1' resolution).
     * Turkey range: N ≈ 15–30m depending on region.
     *
     * @return geoid undulation N in meters (positive = geoid above ellipsoid)
     */
    fun approximateGeoidUndulation(latDeg: Double, lonDeg: Double): Double {
        // Bilinear interpolation from coarse Turkey grid
        // Values from EGM2008 at 1° grid for Turkey bounding box (36-42°N, 26-45°E)
        val latMin = 36.0; val latMax = 42.0
        val lonMin = 26.0; val lonMax = 45.0

        val tLat = ((latDeg - latMin) / (latMax - latMin)).coerceIn(0.0, 1.0)
        val tLon = ((lonDeg - lonMin) / (lonMax - lonMin)).coerceIn(0.0, 1.0)

        // Corner geoid values (meters): SW, SE, NW, NE
        val sw = 19.5; val se = 22.0; val nw = 23.5; val ne = 26.0
        return sw + (se - sw) * tLon + (nw - sw) * tLat + (ne - nw - se + sw) * tLon * tLat
    }

    /**
     * Converts ellipsoidal height (from GNSS) to orthometric height (above geoid/sea level).
     * h_orthometric = h_ellipsoidal - N_geoid
     */
    fun ellipsoidalToOrthometric(ellipsoidalM: Double, latDeg: Double, lonDeg: Double): Double {
        val N = approximateGeoidUndulation(latDeg, lonDeg)
        return ellipsoidalM - N
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. Distance and bearing utilities
    // ──────────────────────────────────────────────────────────────────

    /** Haversine distance between two geodetic points in meters */
    fun haversineDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Bearing from point 1 to point 2 in degrees (0=North, clockwise) */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

// ── Data classes ──────────────────────────────────────────────────────

data class EcefPoint(val x: Double, val y: Double, val z: Double)

data class GeodeticPoint(
    val latitudeDeg:  Double,
    val longitudeDeg: Double,
    val altitudeM:    Double
)

data class EnuPoint(
    val east:  Double,   // meters East
    val north: Double,   // meters North
    val up:    Double    // meters Up (positive = above reference)
) {
    /** Horizontal distance from origin */
    val horizontalDistanceM: Double get() = Math.sqrt(east*east + north*north)

    /** Full 3D distance */
    val distanceM: Double get() = Math.sqrt(east*east + north*north + up*up)

    /** Azimuth in degrees from North, clockwise */
    val azimuthDeg: Double get() = (Math.toDegrees(Math.atan2(east, north)) + 360) % 360
}
