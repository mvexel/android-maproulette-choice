package lol.osm.streettally

import java.net.URI
import lol.osm.streettally.demo.DemoBackend
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
    /** Participants may answer before signing in (deferred sign-up), where this build takes writes. */
    val guests: Boolean = false,
    /** Show other people's not-here and moved reports (parity row 4), once the backend can list them. */
    val serverReports: Boolean = false,
    /** The demo build: [baseUrl] is [DemoBackend.ORIGIN] and the in-process [DemoBackend] answers. */
    val demo: Boolean = false,
) {
    init {
        require(origin(baseUrl) == baseUrl) { "baseUrl must be an https origin without a path: $baseUrl" }
        require(!demo || baseUrl == DemoBackend.ORIGIN) { "the demo build has no real backend" }
    }

    /** Whether this build sends answers to [baseUrl]. The demo's answers never leave the phone. */
    val writesAllowed: Boolean
        get() = demo || baseUrl in WRITE_ORIGINS || (allowLiveWrites && baseUrl in LIVE_WRITE_ORIGINS)

    /** The OAuth callback registered with the backend (`<scheme>:/oauth2redirect`). */
    val redirectUri: String get() = "$callbackScheme:/oauth2redirect"

    /** The SDK environment; writes are opted in only when [writesAllowed]. */
    fun environment(): MapRouletteEnvironment = MapRouletteEnvironment("$baseUrl/api/v2/", writesAllowed)

    companion object {
        val WRITE_ORIGINS = setOf("https://mr-api.osm.lol", "https://mr-dev.osm.lol")
        const val PROD_ORIGIN = "https://mr-prod.osm.lol"
        val LIVE_WRITE_ORIGINS = setOf("https://mr-stage.osm.lol", PROD_ORIGIN)

        fun fromBuild(): AppConfig = AppConfig(
            baseUrl = BuildConfig.MAPROULETTE_BASE_URL,
            oauthClientId = BuildConfig.MAPROULETTE_OAUTH_CLIENT_ID,
            callbackScheme = BuildConfig.MAPROULETTE_OAUTH_CALLBACK_SCHEME,
            allowLiveWrites = BuildConfig.MAPROULETTE_ALLOW_LIVE_WRITES,
            mapStyleUrl = BuildConfig.MAP_STYLE_URL,
            campaignLinkHost = BuildConfig.CAMPAIGN_LINK_HOST,
            guests = BuildConfig.MAPROULETTE_GUESTS,
            serverReports = BuildConfig.SERVER_REPORTS,
            demo = BuildConfig.DEMO,
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
