package lol.osm.streettally.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import lol.osm.streettally.campaign.LatLon

/** One position fix for sorting places. The location never leaves the phone. */
sealed interface LocateResult {
    /** [accuracyM]: the fix's horizontal accuracy in meters, when the provider reports one. */
    data class Found(val position: LatLon, val accuracyM: Float? = null) : LocateResult
    data object Denied : LocateResult
    data object Unavailable : LocateResult
    data object TimedOut : LocateResult
}

object Locator {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun permitted(context: Context): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** A current fix within [timeoutMs]; call after the permission prompt. Fused first where the
     * phone has it (it uses Wi-Fi indoors). Otherwise GPS, falling back to the network for the last
     * third of the time, because GPS alone rarely gets a fix indoors. */
    @SuppressLint("MissingPermission") // Checked by permitted().
    suspend fun locate(context: Context, timeoutMs: Long = 15_000): LocateResult {
        if (!permitted(context)) return LocateResult.Denied
        val manager = context.getSystemService(LocationManager::class.java) ?: return LocateResult.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return LocateResult.Unavailable
        val enabled = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val attempts = attempts(enabled, timeoutMs)
        if (attempts.isEmpty()) return LocateResult.Unavailable
        for ((provider, budget) in attempts) {
            val fix = current(manager, provider, budget) ?: continue
            return LocateResult.Found(LatLon(fix.latitude, fix.longitude), fix.accuracy.takeIf { fix.hasAccuracy() })
        }
        return LocateResult.TimedOut
    }

    /** Which providers to ask, in order, and how long each gets out of [timeoutMs]. */
    internal fun attempts(enabled: List<String>, timeoutMs: Long): List<Pair<String, Long>> = when {
        LocationManager.FUSED_PROVIDER in enabled -> listOf(LocationManager.FUSED_PROVIDER to timeoutMs)
        LocationManager.GPS_PROVIDER in enabled && LocationManager.NETWORK_PROVIDER in enabled -> listOf(
            LocationManager.GPS_PROVIDER to timeoutMs * 2 / 3,
            LocationManager.NETWORK_PROVIDER to timeoutMs - timeoutMs * 2 / 3,
        )
        else -> enabled.take(1).map { it to timeoutMs }
    }

    /** One provider's current fix, or null when it has none within [timeoutMs]. */
    @SuppressLint("MissingPermission")
    private suspend fun current(manager: LocationManager, provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
                    continuation.resumeWith(Result.success(location)) // Null when the provider has no fix.
                }
            }
        }

    private val executor = Executors.newSingleThreadExecutor()
}
