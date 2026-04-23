package com.tusaga.rtkinfra.ar

import com.google.ar.core.*
import com.tusaga.rtkinfra.coordinate.CoordinateTransformer
import com.tusaga.rtkinfra.coordinate.EnuPoint
import com.tusaga.rtkinfra.data.model.InfraFeature
import com.tusaga.rtkinfra.gnss.RtkState
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Manages AR anchor placement for underground infrastructure objects.
 *
 * GNSS–AR Fusion Strategy:
 *  1. User's RTK position (from GNSS) = world origin in ENU frame
 *  2. Infrastructure point (from GeoJSON) → ENU displacement vector
 *  3. ENU → ARCore pose (right=East, up=Up, forward=-North)
 *  4. Apply depth offset for underground visualization
 *  5. Create ARCore Anchor at computed pose
 *
 * Drift correction:
 *  - Re-anchor periodically when RTK Fix is available
 *  - Use IMU integration between GNSS updates for smooth AR
 */
@Singleton
class InfrastructureAnchorManager @Inject constructor() {

    // Maps feature ID → ARCore Anchor
    private val anchorMap = mutableMapOf<String, Anchor>()

    // Current world origin (user's RTK position)
    private var originRtkState: RtkState? = null
    private var arSession: Session? = null

    // ── Underground depth simulation ─────────────────────────────────
    // Typical underground infrastructure depths in Turkey (meters below surface)
    companion object {
        const val DEPTH_WATER_MAIN     = -1.0    // Water mains: ~1m
        const val DEPTH_SEWER          = -2.5    // Sewer: ~2.5m
        const val DEPTH_GAS_PIPE       = -0.6    // Gas: ~0.6m
        const val DEPTH_TELECOM        = -0.8    // Telecom: ~0.8m
        const val DEPTH_ELECTRIC_MV    = -0.9    // Medium-voltage power: ~0.9m
        const val DEPTH_DEFAULT        = -1.0

        // Max distance to render AR objects (performance + relevance)
        const val MAX_RENDER_DISTANCE_M = 100.0
    }

    fun setArSession(session: Session) {
        arSession = session
    }

    /**
     * Updates the GNSS origin. Called when RTK Fix is acquired.
     * Existing anchors are rebuilt relative to new origin.
     */
    fun updateGnssOrigin(rtkState: RtkState) {
        if (rtkState.fixType.nmeaQuality >= 4) {   // RTK Fix or Float
            originRtkState = rtkState
            Timber.d("AR: Origin updated – Fix=${rtkState.fixType.label} " +
                     "Acc=${rtkState.accuracyDisplay}")
        }
    }

    /**
     * Computes the ARCore pose for an infrastructure feature.
     *
     * @param feature   GeoJSON infrastructure point/line vertex
     * @param frame     Current ARCore frame for camera-space reference
     * @return Pose in ARCore world space, or null if outside render range
     */
    fun computePoseForFeature(feature: InfraFeature, frame: Frame): Pose? {
        val origin = originRtkState ?: return null

        // 1. Compute ENU displacement: infrastructure point relative to user
        val enu: EnuPoint = CoordinateTransformer.geodeticToEnu(
            pointLat = feature.latitude,
            pointLon = feature.longitude,
            pointAlt = origin.altitudeM + (feature.depthM ?: DEPTH_DEFAULT),
            userLat  = origin.latitude,
            userLon  = origin.longitude,
            userAlt  = origin.altitudeM
        )

        // Cull objects outside render range
        if (enu.horizontalDistanceM > MAX_RENDER_DISTANCE_M) return null

        // 2. ENU → ARCore coordinate system
        //    ARCore: +X=right(East), +Y=up, +Z=backward(-North)
        //    ENU:    East=+, North=+, Up=+
        val arX =  enu.east.toFloat()
        val arY =  enu.up.toFloat()
        val arZ = -enu.north.toFloat()  // ARCore Z is negative North

        // 3. Apply camera pose offset to place in world space
        //    The ARCore session origin is set at first pose, not at GPS
        //    We use the latest camera pose as our reference
        val cameraPose = frame.camera.displayOrientedPose
        val worldPose = Pose.makeTranslation(arX, arY, arZ)

        return worldPose
    }

    /**
     * Creates or updates an ARCore anchor for a feature.
     * Anchors are persistent across frames within the session.
     */
    fun anchorFeature(featureId: String, pose: Pose): Anchor? {
        val session = arSession ?: return null

        // Remove stale anchor if re-anchoring
        anchorMap[featureId]?.detach()

        return try {
            val anchor = session.createAnchor(pose)
            anchorMap[featureId] = anchor
            anchor
        } catch (e: Exception) {
            Timber.e(e, "AR: Failed to create anchor for $featureId")
            null
        }
    }

    /**
     * Detaches and removes all anchors (e.g., when loading new dataset).
     */
    fun clearAllAnchors() {
        anchorMap.values.forEach { it.detach() }
        anchorMap.clear()
    }

    /**
     * Gets depth offset for a feature based on its infrastructure type.
     */
    fun getDepthOffset(featureType: String?): Double {
        return when (featureType?.lowercase()) {
            "water", "water_main", "su"     -> DEPTH_WATER_MAIN
            "sewer", "kanal", "atiksu"      -> DEPTH_SEWER
            "gas", "dogalgaz"               -> DEPTH_GAS_PIPE
            "telecom", "fiber", "telekom"   -> DEPTH_TELECOM
            "electric", "elektrik", "mv"    -> DEPTH_ELECTRIC_MV
            else                            -> DEPTH_DEFAULT
        }
    }

    /** Releases ARCore resources */
    fun release() {
        clearAllAnchors()
        arSession = null
    }
}
