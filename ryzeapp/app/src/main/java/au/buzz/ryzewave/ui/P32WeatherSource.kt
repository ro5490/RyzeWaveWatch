package au.buzz.ryzewave.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Manual, foreground-only forecast fetch. No GPS collection or background tracking. */
internal data class P32Forecast(val temperatures: List<Triple<Int, Int, Int>>, val conditions: List<Int>)

internal object P32WeatherSource {
    /** Verified P32 icons: 1 sunny, 2 cloudy, 3 overcast, 4 shower,
     * 5 thunderstorm, 6 sleet, 7 light rain, 8 heavy rain, 9 snow,
     * 10 sandstorm, 11 haze, 12 windy.
     * WMO has no dedicated wind, haze, or sandstorm code: use additional
     * Open-Meteo measurements where available rather than mislabel snow.
     */
    fun conditionForWmo(wmo: Int, windKmh: Double = 0.0, visibilityM: Double? = null): Int {
        val base = when (wmo) {
            0 -> 1
            1, 2 -> 2
            3 -> 3
            45, 48 -> 11               // fog/mist: haze is closest available icon
            51, 53, 55, 80, 81 -> 4   // drizzle and showers
            56, 57, 66, 67 -> 6       // freezing drizzle/rain: sleet icon
            61, 63 -> 7               // light/moderate continuous rain
            65, 82 -> 8               // heavy rain/showers
            71, 73, 75, 77, 85, 86 -> 9 // all snow, including heavy snow
            95, 96, 99 -> 5           // thunderstorms
            else -> 2                 // unrecognised condition: neutral cloudy
        }
        // Never replace precipitation or thunderstorms with a wind/haze icon.
        if (base in setOf(1, 2, 3)) {
            if (visibilityM != null && visibilityM < 1_000.0) return 11
            if (windKmh >= 40.0) return 12
        }
        return base
    }

    suspend fun fetch(context: Context): P32Forecast = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "Location permission is required for weather. Grant location permission in Android Settings."
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time in 0..(2 * 60 * 60 * 1000L) }
            .maxByOrNull { it.time }
            ?: error("No recent phone location (within 2 hours). Open a maps app to obtain a fix, then retry.")
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,weather_code,wind_speed_10m,visibility&daily=temperature_2m_max,temperature_2m_min,weather_code,wind_speed_10m_max&forecast_days=7&timezone=auto&temperature_unit=celsius")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            check(conn.responseCode == 200) { "Weather provider HTTP ${conn.responseCode}" }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val current = json.getJSONObject("current").getDouble("temperature_2m").roundToInt()
            val daily = json.getJSONObject("daily")
            val highs = daily.getJSONArray("temperature_2m_max")
            val lows = daily.getJSONArray("temperature_2m_min")
            check(highs.length() >= 7 && lows.length() >= 7) { "Incomplete seven-day forecast" }
            val dailyCodes = daily.getJSONArray("weather_code")
            check(dailyCodes.length() >= 7) { "Incomplete weather conditions" }
            val currentWeather = json.getJSONObject("current")
            val currentCode = currentWeather.getInt("weather_code")
            val currentWind = currentWeather.optDouble("wind_speed_10m", 0.0)
            val currentVisibility = if (currentWeather.isNull("visibility")) null else currentWeather.optDouble("visibility").takeUnless { it.isNaN() }
            val dailyWinds = daily.optJSONArray("wind_speed_10m_max")
            P32Forecast(
                temperatures = (0 until 7).map { i -> Triple(if (i == 0) current else 0, highs.getDouble(i).roundToInt(), lows.getDouble(i).roundToInt()) },
                conditions = (0 until 7).map { i ->
                    conditionForWmo(
                        if (i == 0) currentCode else dailyCodes.getInt(i),
                        if (i == 0) currentWind else dailyWinds?.optDouble(i, 0.0) ?: 0.0,
                        if (i == 0) currentVisibility else null,
                    )
                },
            )
        } finally {
            conn.disconnect()
        }
    }
}
