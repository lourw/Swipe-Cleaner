package dev.swipe.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * [parseProvisioningProfileExpiration] (FR-31, WP-6.1): reads `ExpirationDate`
 * out of the plist embedded in the CMS-signed `embedded.mobileprovision` blob,
 * and returns null — row hidden — for every shape the app can meet in the
 * wild: no profile text, no plist, no key, malformed timestamp.
 */
class ProvisioningProfileTest {
    @Test
    fun `FR-31 provisioning profile expiry is parsed`() {
        assertEquals(
            Instant.parse("2026-11-08T09:00:00Z"),
            parseProvisioningProfileExpiration(profile(expiration = "<date>2026-11-08T09:00:00Z</date>")),
        )
    }

    @Test
    fun `FR-31 profile text outside the plist is ignored`() {
        // A CMS blob: binary junk before `<?xml` and after `</plist>`.
        val blob = "\u0001\u00870\u000d\u0006\t\u0086H\u0086..." + profile(expiration = "") + "\u0000\u00ff junk"
        assertNull(parseProvisioningProfileExpiration(blob))
        val dated = "\u0001\u0087junk" + profile(expiration = "<date>2026-11-08T09:00:00Z</date>") + "junk\u00ff"
        assertEquals(
            Instant.parse("2026-11-08T09:00:00Z"),
            parseProvisioningProfileExpiration(dated),
        )
    }

    @Test
    fun `FR-31 unreadable profile yields null so the row is hidden`() {
        assertNull(parseProvisioningProfileExpiration(""))
        assertNull(parseProvisioningProfileExpiration("\u0000\u0001binary without any plist"))
        // A plist without the key (a profile that never expires, or the wrong file).
        assertNull(parseProvisioningProfileExpiration(profile(expiration = "")))
        // Missing or malformed `<date>` value.
        assertNull(parseProvisioningProfileExpiration(profile(expiration = "<date>not-a-date</date>")))
        assertNull(parseProvisioningProfileExpiration(profile(expiration = "<date></date>")))
        // The key is present but its date element sits outside the plist slice.
        assertNull(
            parseProvisioningProfileExpiration(
                "<?xml version=\"1.0\"?><plist><key>ExpirationDate</key></plist><date>2026-11-08T09:00:00Z</date>",
            ),
        )
    }

    /** A minimal profile plist with the [expiration] element inserted under the key. */
    private fun profile(expiration: String): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <plist version="1.0">
        <dict>
            <key>AppIDName</key>
            <string>SwipeCleaner</string>
            <key>CreationDate</key>
            <date>2026-11-01T09:00:00Z</date>
            <key>ExpirationDate</key>
            $expiration
            <key>Name</key>
            <string>SwipeCleaner Development</string>
            <key>TeamIdentifier</key>
            <array><string>ABCDE12345</string></array>
        </dict>
        </plist>
        """.trimIndent()
}
