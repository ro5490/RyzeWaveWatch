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
    /** Open-Meteo WMO codes mapped to GloryFit's 1..12 XiaoYang watch categories.
     *  The numeric P32 categories and CB offsets come from GloryFit's decompiled SDK.
     *  WMO-to-category interpretation is approximate because providers differ.
     */
    fun conditionForWmo(wmo: Int): Int = when (wmo) {
        0 -> 1                    // clear
        1, 2 -> 2                 // mainly clear / partly cloudy
        3, 45, 48 -> 3            // overcast / fog (no dedicated fog category)
        51, 53, 55, 56, 57 -> 4   // drizzle
        61, 80 -> 7               // light rain / showers
        63, 81 -> 5               // moderate rain / showers
        65, 82 -> 8               // heavy rain / showers
        66, 67 -> 6               // freezing rain / mixed precipitation
        71, 73, 77, 85 -> 9       // snow / snow showers
        75, 86 -> 10              // heavy snow
        95, 96, 99 -> 12          // thunderstorm
        else -> 1
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
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,weather_code&forecast_days=7&timezone=auto&temperature_unit=celsius")
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
            val currentCode = json.getJSONObject("current").getInt("weather_code")
            P32Forecast(
                temperatures = (0 until 7).map { i -> Triple(if (i == 0) current else 0, highs.getDouble(i).roundToInt(), lows.getDouble(i).roundToInt()) },
                conditions = (0 until 7).map { i -> conditionForWmo(if (i == 0) currentCode else dailyCodes.getInt(i)) },
            )
        } finally {
            conn.disconnect()
        }
    }
}
