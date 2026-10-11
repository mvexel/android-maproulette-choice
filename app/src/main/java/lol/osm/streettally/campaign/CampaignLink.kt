package lol.osm.streettally.campaign

import java.net.URI

/** Links that open a campaign: `mrchoice://challenge/<id>`, and `https://<host>/challenge/<id>`
 * (the participant web page; App Links come later). Same rules as the iOS app. `?preview=1` asks
 * for preview mode (design/preview-mode.md §3), which only a draft honors. */
object CampaignLink {
    const val SCHEME = "mrchoice"

    fun url(challenge: Long): String = "$SCHEME://challenge/$challenge"

    /** The link to share and print: the landing page, which opens the app where installed. */
    fun webUrl(challenge: Long, host: String): String = "https://$host/challenge/$challenge"

    /** Whether a campaign link asks for preview mode (`preview=1`). */
    fun isPreview(link: String): Boolean {
        val query = runCatching { URI(link) }.getOrNull()?.rawQuery ?: return false
        return query.split('&').any { it == "preview=1" }
    }

    /** The campaign id a link opens, or null. */
    fun challenge(link: String): Long? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val parts = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.toMutableList()
        when (scheme) {
            SCHEME -> uri.host?.takeIf { it.isNotEmpty() }?.let { parts.add(0, it) }
            "https" -> Unit
            else -> return null
        }
        if (parts.size != 2 || !parts[0].equals("challenge", ignoreCase = true)) return null
        return parts[1].toLongOrNull()?.takeIf { it > 0 }
    }
}
