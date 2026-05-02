package io.github.whitphx.nolocationzones.place

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin client for Photon (https://photon.komoot.io) — a keyless OpenStreetMap-backed geocoder
 * tuned for autocomplete. We use it to power the search box on the zone-editor map.
 *
 * Why Photon instead of Nominatim:
 *  - No API key, no signup. Same posture as the OpenFreeMap tile source.
 *  - Designed for type-as-you-go: returns ranked partial-match results in tens of ms.
 *  - Public instance has a fair-use guideline rather than Nominatim's hard 1-req/sec limit,
 *    which is friendlier for an interactive UI.
 *
 * No SDK / no JSON library on the classpath, so we go straight to `HttpURLConnection` and
 * `org.json` — the dependency-free path. Cancellation is best-effort: the coroutine that calls
 * [search] will see a `CancellationException` if the surrounding scope cancels, but the
 * underlying HTTP request will run to completion or its short timeout. Acceptable for a one-shot
 * fetch; this isn't worth pulling OkHttp in for.
 */
object PhotonGeocoder {

    /** A geocoded place result. [description] is a human-readable hierarchy (city, state, country). */
    data class GeoResult(
        val name: String,
        val description: String,
        val lat: Double,
        val lon: Double,
    )

    suspend fun search(query: String, limit: Int = 8): List<GeoResult> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val url = URL("$BASE/?q=$encoded&limit=$limit")

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "Photon HTTP ${conn.responseCode} for '$trimmed'")
                    return@withContext emptyList()
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            } catch (t: Throwable) {
                Log.w(TAG, "Photon search failed for '$trimmed'", t)
                emptyList()
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun parse(body: String): List<GeoResult> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<GeoResult>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lon.isNaN() || lat.isNaN()) continue
            val props = f.optJSONObject("properties") ?: continue
            val name = props.optString("name").ifBlank { props.optString("street") }.ifBlank { continue }
            // Build a hierarchy line from whichever properties Photon supplied. Order matches the
            // user's mental model of "narrow → wide" (suburb of city of state of country).
            val parts = listOf("housenumber", "city", "state", "country")
                .map { props.optString(it) }
                .filter { it.isNotBlank() && it != name }
            out += GeoResult(
                name = name,
                description = parts.joinToString(", "),
                lat = lat,
                lon = lon,
            )
        }
        return out
    }

    private const val BASE = "https://photon.komoot.io/api"
    private const val USER_AGENT =
        "PhotoNoLocationZones-Android/1.0 (+https://github.com/whitphx/photo-no-location-zones)"
    private const val MIN_QUERY_LENGTH = 2
    private const val TAG = "PhotonGeocoder"
}
