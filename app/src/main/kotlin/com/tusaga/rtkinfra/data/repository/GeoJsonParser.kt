package com.tusaga.rtkinfra.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tusaga.rtkinfra.data.model.*
import timber.log.Timber

/**
 * Parses GeoJSON FeatureCollection into [InfraDataset].
 * Supports Point and LineString geometries.
 *
 * Expected GeoJSON structure:
 * {
 *   "type": "FeatureCollection",
 *   "features": [
 *     {
 *       "type": "Feature",
 *       "geometry": {
 *         "type": "Point",
 *         "coordinates": [longitude, latitude, altitude?]
 *       },
 *       "properties": {
 *         "id": "WM_001",
 *         "type": "water",
 *         "depth": -1.2,
 *         "label": "Water Main Ø200",
 *         ...
 *       }
 *     }
 *   ]
 * }
 */
object GeoJsonParser {

    fun parse(geoJsonString: String, datasetName: String = "infrastructure"): InfraDataset {
        val features = mutableListOf<InfraFeature>()

        try {
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val adapter = moshi.adapter(GeoJsonFeatureCollection::class.java)
            val collection = adapter.fromJson(geoJsonString) ?: return InfraDataset(datasetName, emptyList())

            collection.features.forEachIndexed { index, feature ->
                val parsed = parseFeature(feature, index)
                if (parsed != null) features.add(parsed)
            }

            Timber.i("GeoJSON: Parsed ${features.size} features from '$datasetName'")
        } catch (e: Exception) {
            Timber.e(e, "GeoJSON parse error")
        }

        return InfraDataset(datasetName, features)
    }

    private fun parseFeature(feature: GeoJsonFeature, index: Int): InfraFeature? {
        val geom = feature.geometry ?: return null
        val props = feature.properties ?: emptyMap()

        val id    = props["id"] ?: "feature_$index"
        val type  = props["type"] ?: props["infrastructure_type"]
        val depth = props["depth"]?.toDoubleOrNull() ?: props["derinlik"]?.toDoubleOrNull()
        val label = props["label"] ?: props["ad"] ?: props["name"]

        return when (geom.type) {
            "Point" -> {
                val coords = geom.coordinates as? List<*> ?: return null
                val lon    = (coords.getOrNull(0) as? Number)?.toDouble() ?: return null
                val lat    = (coords.getOrNull(1) as? Number)?.toDouble() ?: return null
                InfraFeature(
                    id = id, type = type,
                    geometryType = GeometryType.POINT,
                    latitude = lat, longitude = lon,
                    depthM = depth, label = label,
                    properties = props
                )
            }
            "LineString" -> {
                // For lines: create one feature per vertex pair (midpoint approach)
                val coords = geom.coordinates as? List<*> ?: return null
                // Return centroid as representative point (renderer handles full line)
                if (coords.size < 2) return null
                val first  = coords.first() as? List<*> ?: return null
                val last   = coords.last()  as? List<*> ?: return null
                val midLon = (((first[0] as? Number)?.toDouble() ?: 0.0) +
                              ((last[0]  as? Number)?.toDouble() ?: 0.0)) / 2
                val midLat = (((first[1] as? Number)?.toDouble() ?: 0.0) +
                              ((last[1]  as? Number)?.toDouble() ?: 0.0)) / 2
                InfraFeature(
                    id = id, type = type,
                    geometryType = GeometryType.LINE,
                    latitude = midLat, longitude = midLon,
                    depthM = depth, label = label,
                    properties = props
                )
            }
            else -> {
                Timber.v("GeoJSON: Unsupported geometry type: ${geom.type}")
                null
            }
        }
    }

    // ── Moshi-compatible data classes ─────────────────────────────────

    data class GeoJsonFeatureCollection(
        val type: String,
        val features: List<GeoJsonFeature>
    )

    data class GeoJsonFeature(
        val type: String,
        val geometry: GeoJsonGeometry?,
        val properties: Map<String, String>?
    )

    data class GeoJsonGeometry(
        val type: String,
        val coordinates: Any?
    )
}
