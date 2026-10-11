package lol.osm.streettally

import java.net.URI
import org.maproulette.sdk.MapRouletteEnvironment

/** The backend this build talks to and whether it may send answers there.
 *
 * Mirrors the iOS app's write rule: the dev backends always take writes; stage and prod edit the
 * real OpenStreetMap and take writes only in builds made with `maprouletteAllowLiveWrites=true`.
 * Anything else, including maproulette.org, is read-only. */
data class AppConfig(
    val baseUrl: String,
    val oauthClientId: String,
    val callbackScheme: String,
    val allowLiveWrites: Boolean,
    val mapStyleUrl: String,
    val campaignLinkHost: String,
    val guestEnabled: Boolean = false,
) {
    init {
        require(origin(baseUrl) == baseUrl) { "baseUrl must be an https origin without a path: $baseUrl" }
    }

    /** Whether this build sends answers to [baseUrl]. */
    val writesAllowed: Boolean
        get() = baseUrl in WRITE_ORIGINS || (allowLiveWrites && baseUrl in LIVE_WRITE_ORIGINS)

    val guestWritesAllowed: Boolean get() = guestEnabled && writesAllowed

    /** The OAuth callback registered with the backend (`<scheme>:/oauth2redirect`). */
    val redirectUri: String get() = "$callbackScheme:/oauth2redirect"

    /** The SDK environment; writes are opted in only when [writesAllowed]. */
    fun environment(): MapRouletteEnvironment = MapRouletteEnvironment("$baseUrl/api/v2/", writesAllowed)

    companion object {
        val WRITE_ORIGINS = setOf("https://mr-api.osm.lol", "https://mr-dev.osm.lol")
        val LIVE_WRITE_ORIGINS = setOf("https://mr-stage.osm.lol", "https://mr-prod.osm.lol")

        fun fromBuild(): AppConfig = AppConfig(
            baseUrl = BuildConfig.MAPROULETTE_BASE_URL,
            oauthClientId = BuildConfig.MAPROULETTE_OAUTH_CLIENT_ID,
            callbackScheme = BuildConfig.MAPROULETTE_OAUTH_CALLBACK_SCHEME,
            allowLiveWrites = BuildConfig.MAPROULETTE_ALLOW_LIVE_WRITES,
            mapStyleUrl = BuildConfig.MAP_STYLE_URL,
            campaignLinkHost = BuildConfig.CAMPAIGN_LINK_HOST,
            guestEnabled = BuildConfig.MAPROULETTE_GUEST_ENABLED,
        )

        /** `https://host[:port]` of [url], or null when it is not a plain https origin. */
        internal fun origin(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
            if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
            return "https://${uri.host.lowercase()}" + if (uri.port == -1) "" else ":${uri.port}"
        }
    }
}
