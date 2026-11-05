package dev.swipe.data

import kotlin.time.Instant

/**
 * Parsing for the app bundle's `embedded.mobileprovision` (FR-31), pure so it is
 * testable without a bundle. The file is a CMS/PKCS#7 signed blob whose payload is
 * an Apple property list; the plist text is readable ASCII inside the binary, and
 * `ExpirationDate` is what free-provisioning expiry Settings shows (§5.8, FR-31).
 *
 * [parseProvisioningProfileExpiration] extracts the substring between the first
 * `<?xml` and the following `</plist>` and reads the `ExpirationDate` key's
 * `<date>` value. Every failure — no plist, no key, malformed timestamp — returns
 * `null`, and the caller hides the row (FR-31: "if it can be determined").
 */
fun parseProvisioningProfileExpiration(profileText: String): Instant? {
    val raw = expirationDateString(profileText) ?: return null
    return runCatching { Instant.parse(raw) }.getOrNull()
}

/** The text between `<date>` and `</date>` for the `ExpirationDate` key inside the plist slice. */
private fun expirationDateString(profileText: String): String? =
    profileText
        .indexOf(XML_OPEN)
        .takeIf { it >= 0 }
        ?.let { xmlStart ->
            profileText
                .indexOf(PLIST_CLOSE, startIndex = xmlStart)
                .takeIf { it > xmlStart }
                ?.let { plistEnd -> profileText.substring(xmlStart, plistEnd) }
        }?.let { plist ->
            expirationDatePattern
                .find(plist)
                ?.groupValues
                ?.get(1)
                ?.trim()
        }

private const val XML_OPEN = "<?xml"

private const val PLIST_CLOSE = "</plist>"

/** Apple plists write the key and its `<date>` adjacent; whitespace between is layout only. */
private val expirationDatePattern = Regex("<key>ExpirationDate</key>\\s*<date>([^<]+)</date>")
