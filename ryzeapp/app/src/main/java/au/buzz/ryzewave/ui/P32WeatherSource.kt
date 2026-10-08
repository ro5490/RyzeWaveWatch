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
internal object P32WeatherSource {
    suspend fun fetch(context: Context): List<Triple<Int, Int, Int>> = withContext(Dispatchers.IO) {
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
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m&daily=temperature_2m_max,temperature_2m_min&forecast_days=7&timezone=auto&temperature_unit=celsius")
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
            (0 until 7).map { i -> Triple(if (i == 0) current else 0, highs.getDouble(i).roundToInt(), lows.getDouble(i).roundToInt()) }
        } finally {
            conn.disconnect()
        }
    }
}
