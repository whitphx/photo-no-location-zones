package dev.whitphx.nolocationzones.domain

data class Zone(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
) {
    companion object {
        const val MIN_RADIUS_METERS = 100f
        const val MAX_RADIUS_METERS = 5_000f
        const val DEFAULT_RADIUS_METERS = 150f
    }
}
