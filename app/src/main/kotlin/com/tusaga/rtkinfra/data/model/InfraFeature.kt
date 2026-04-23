package com.tusaga.rtkinfra.data.model

/**
 * Domain model for a single infrastructure feature loaded from GeoJSON.
 */
data class InfraFeature(
    val id: String,
    val type: String?,               // "water", "sewer", "gas", "telecom", "electric"
    val geometryType: GeometryType,
    val latitude: Double,            // WGS84
    val longitude: Double,
    val depthM: Double?,             // Depth below surface (negative = underground)
    val label: String?,              // Display label
    val properties: Map<String, String> = emptyMap()
)

enum class GeometryType { POINT, LINE }

/**
 * Represents a GeoJSON FeatureCollection of infrastructure.
 */
data class InfraDataset(
    val name: String,
    val features: List<InfraFeature>,
    val loadedAtMs: Long = System.currentTimeMillis()
)
