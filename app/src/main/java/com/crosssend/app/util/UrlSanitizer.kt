package com.crosssend.app.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Strips well-known analytics, referral, and ad-click tracking query parameters
 * from HTTP(S) and safe relative URLs while preserving functional query parameters
 * and fragments.
 */
object UrlSanitizer {

    private const val RELATIVE_PLACEHOLDER_HOST = "relative.invalid"
    private const val RELATIVE_PLACEHOLDER_ORIGIN = "https://$RELATIVE_PLACEHOLDER_HOST"

    private val TRACKING_PREFIXES = listOf(
        "utm_",
        "mtm_",
        "pk_",
        "piwik_",
        "at_",
        "wt_",
        "fb_",
        "mc_",
        "oly_",
        "vero_",
        "_hs",
        "guce_"
    )

    private val TRACKING_EXACT_NAMES = setOf(
        "fbclid",
        "gclid",
        "gclsrc",
        "dclid",
        "wbraid",
        "gbraid",
        "msclkid",
        "yclid",
        "twclid",
        "ttclid",
        "li_fat_id",
        "igshid",
        "igsh",
        "si",
        "hsctatracking",
        "mkt_tok",
        "rb_clickid",
        "wickedid",
        "s_kwcid",
        "_ga",
        "_gl",
        "_ke",
        "guccounter",
        "ncid",
        "sr_share",
        "cmpid",
        "ito",
        "xtor",
        "curator",
        "ref_src",
        "ref_url",
        "share_id",
        "aff_id",
        "partner_id",
        "campaign_id",
        "ad_id",
        "adset_id",
        "trk",
        "trkcampaign",
        "sc_cid",
        "s_cid",
        "ef_id",
        "epik",
        "spm",
        "scm",
        "algo_expid",
        "algo_pvid"
    )

    /**
     * Returns `true` if [paramName] matches a known tracking query parameter
     * (case-insensitive using [Locale.ROOT]).
     */
    fun isTrackingParam(paramName: String): Boolean {
        if (paramName.isEmpty()) return false
        val normalized = paramName.lowercase(Locale.ROOT)
        return normalized in TRACKING_EXACT_NAMES ||
            TRACKING_PREFIXES.any { normalized.startsWith(it) }
    }

    /**
     * Removes tracking query parameters from [rawUrl] while preserving functional
     * parameters and fragments.
     *
     * Supports `http://` / `https://` URLs and safe relative paths starting with `/`
     * (excluding protocol-relative `//` URLs). Non-HTTP schemes (`mailto:`, `javascript:`,
     * `data:`, `content:`, `file:`) and unparseable inputs are returned trimmed and
     * otherwise untouched.
     */
    fun stripTrackingParams(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""

        val isSafeRelative = trimmed.startsWith("/") && !trimmed.startsWith("//")
        val parsed = if (isSafeRelative) {
            "$RELATIVE_PLACEHOLDER_ORIGIN$trimmed".toHttpUrlOrNull()
                ?.takeIf { it.host == RELATIVE_PLACEHOLDER_HOST }
        } else {
            trimmed.toHttpUrlOrNull()
        } ?: return trimmed

        val trackingParams = parsed.queryParameterNames.filter(::isTrackingParam)
        if (trackingParams.isEmpty()) {
            return trimmed
        }

        val cleaned = removeParams(parsed, trackingParams)
        return if (isSafeRelative) {
            formatRelativeUrl(cleaned)
        } else {
            cleaned.toString()
        }
    }

    private fun removeParams(url: HttpUrl, paramNames: List<String>): HttpUrl {
        val builder = url.newBuilder()
        for (name in paramNames) {
            builder.removeAllQueryParameters(name)
        }
        return builder.build()
    }

    private fun formatRelativeUrl(url: HttpUrl): String = buildString {
        append(url.encodedPath)
        url.encodedQuery?.let { query ->
            append('?')
            append(query)
        }
        url.encodedFragment?.let { fragment ->
            append('#')
            append(fragment)
        }
    }
}
