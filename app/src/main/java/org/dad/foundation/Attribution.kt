package org.dad.foundation

import android.content.Context
import androidx.core.content.edit

/**
 * Sanitized codes read from Play Install Referrer and passed to LinkAttribution.
 * Server-issued visitor tokens, rather than these raw codes, are persisted for giving.
 */
data class Attribution(
    /** An invite code from a sharing link (person-to-person referral, R-1). */
    val inviteCode: String? = null,
    /** An in-real-life code (printed/QR at an event), distinct from an online invite (R-2). */
    val irlCode: String? = null,
    /** An employer/company code (D-4) — joins the donor to a workforce team. */
    val companyCode: String? = null,
    /** The raw source/campaign tag, kept for the analytics `attribution_captured` event (§9). */
    val source: String? = null,
) {
    /** True when nothing worth persisting was captured. */
    val isEmpty: Boolean get() = inviteCode == null && irlCode == null && companyCode == null && source == null

    companion object {
        /**
         * Codes are short alphanumerics; reject anything that is not, so a crafted deep link
         * or spoofed install-referrer string cannot stuff arbitrary data through the attribution
         * channel into sign-up.
         */
        fun sanitized(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.length !in 1..64 || !CODE.matches(trimmed)) return null
            return trimmed
        }

        private val CODE = Regex("^[A-Za-z0-9_-]+$")

        /** Build a sanitized Attribution from raw inputs in one step. */
        fun of(inviteCode: String? = null, irlCode: String? = null, companyCode: String? = null, source: String? = null) =
            Attribution(sanitized(inviteCode), sanitized(irlCode), sanitized(companyCode), sanitized(source))
    }
}

/** Account deletion must erase only the attribution key saved by older app versions. */
object LegacyAttribution {
    fun clear(context: Context) {
        context.getSharedPreferences("org.dad.attribution", Context.MODE_PRIVATE)
            .edit { remove("org.dad.attribution") }
    }
}

/**
 * Play Install Referrer seam (D-3 deferred deep linking): [PlayInstallReferrer] is the real client, and tests replay a
 * referrer string without the Play Store. The referrer string is `utm`-style query params
 * (`invite=ABC&utm_source=...`), parsed into an Attribution.
 */
interface InstallReferrer {
    /** The raw referrer, or null when there is none or it could not be read. */
    suspend fun fetch(): String?
}

/** Parses a Play Install Referrer query string into a sanitized Attribution. */
object InstallReferrerParser {
    fun parse(referrer: String?): Attribution {
        val raw = referrer?.takeIf { it.isNotBlank() } ?: return Attribution()
        val params = raw.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                runCatching { java.net.URLDecoder.decode(kv[0], "UTF-8") to java.net.URLDecoder.decode(kv[1], "UTF-8") }.getOrNull()
            } else null
        }.toMap()
        return Attribution.of(
            inviteCode = params["invite"] ?: params["ref"],
            irlCode = params["irl"],
            companyCode = params["company"] ?: params["company_code"],
            source = params["utm_source"] ?: params["source"],
        )
    }
}
