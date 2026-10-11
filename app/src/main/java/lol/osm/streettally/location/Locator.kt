package lol.osm.streettally.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
    data class Found(val position: LatLon) : LocateResult
    data object Denied : LocateResult
    data object Unavailable : LocateResult
    data object TimedOut : LocateResult
}

object Locator {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun permitted(context: Context): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** A current fix from GPS or the network, within [timeoutMs]; call after the permission prompt. */
    @SuppressLint("MissingPermission") // Checked by permitted().
    suspend fun locate(context: Context, timeoutMs: Long = 15_000): LocateResult {
        if (!permitted(context)) return LocateResult.Denied
        val manager = context.getSystemService(LocationManager::class.java) ?: return LocateResult.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return LocateResult.Unavailable
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return LocateResult.Unavailable
        val fix = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
                    continuation.resumeWith(Result.success(location))
                }
            }
        } ?: return LocateResult.TimedOut
        return LocateResult.Found(LatLon(fix.latitude, fix.longitude))
    }

    private val executor = Executors.newSingleThreadExecutor()
}
