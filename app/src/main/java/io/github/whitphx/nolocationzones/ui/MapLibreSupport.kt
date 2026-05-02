package io.github.whitphx.nolocationzones.ui

import org.maplibre.android.geometry.LatLng
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal const val OPEN_FREE_MAP_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Approximates a real-world geodesic circle with `steps + 1` vertices (closed ring) using the
 * spherical forward-azimuth formula. MapLibre's CircleLayer is pixel-sized — wrong for a
 * meters-defined geofence — so we draw the circle as a polygon instead.
 */
internal fun circlePolygon(center: LatLng, radiusMeters: Double, steps: Int = 64): List<LatLng> {
    val latRad = Math.toRadians(center.latitude)
    val lngRad = Math.toRadians(center.longitude)
    val angDist = radiusMeters / EARTH_RADIUS_METERS
    val sinLat = sin(latRad)
    val cosLat = cos(latRad)
    val sinAng = sin(angDist)
    val cosAng = cos(angDist)
    return (0..steps).map { i ->
        val brg = 2.0 * PI * i / steps
        val newLat = asin(sinLat * cosAng + cosLat * sinAng * cos(brg))
        val newLng = lngRad + atan2(sin(brg) * sinAng * cosLat, cosAng - sinLat * sin(newLat))
        LatLng(Math.toDegrees(newLat), Math.toDegrees(newLng))
    }
}
