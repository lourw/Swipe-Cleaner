@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import dev.swipe.db.SwipeDatabase
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * Opens the app's SQLite database (REQUIREMENTS §7, WP-1.3). The file lives in the
 * Application Support directory and is excluded from iCloud backup (§7 note), so
 * decisions survive relaunches (FR-20) without eating the user's backup quota.
 */
class DatabaseDriverFactory {
    /** The production driver: `swipe-cleaner.db` under Application Support. */
    fun create(): NativeSqliteDriver = createIn(applicationSupportDirectory(), DATABASE_NAME)

    /**
     * Opens [name] under [basePath] with the current schema, creating the file if it
     * is new. Excluded from backup once created (§7). Tests point this at a temp
     * directory to verify durability across driver instances (FR-20).
     */
    fun createIn(
        basePath: String,
        name: String,
    ): NativeSqliteDriver {
        val driver =
            NativeSqliteDriver(
                DatabaseConfiguration(
                    name = name,
                    version = schemaVersion(),
                    create = { connection -> wrapConnection(connection) { SwipeDatabase.Schema.create(it) } },
                    upgrade = { connection, oldVersion, newVersion ->
                        wrapConnection(connection) {
                            SwipeDatabase.Schema.migrate(it, oldVersion.toLong(), newVersion.toLong())
                        }
                    },
                    extendedConfig = DatabaseConfiguration.Extended(basePath = basePath),
                ),
            )
        // SQLDelight connects lazily; run one query so the file and schema materialise
        // from launch (§7, FR-20) and schema errors surface at startup, not mid-swipe.
        runBlocking { SwipeDatabase(driver).decisionQueries.decidedIds().executeAsList() }
        excludeFromBackup("$basePath/$name")
        return driver
    }

    private fun schemaVersion(): Int {
        val version = SwipeDatabase.Schema.version
        require(version <= Int.MAX_VALUE.toLong()) { "Schema version $version exceeds Int.MAX_VALUE" }
        return version.toInt()
    }

    /** Sets `NSURLIsExcludedFromBackupKey` on the database file (§7). */
    private fun excludeFromBackup(path: String) {
        NSURL
            .fileURLWithPath(path)
            .setResourceValue(value = true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    private fun applicationSupportDirectory(): String {
        val url =
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSApplicationSupportDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )
        return requireNotNull(url?.path) { "Application Support directory is unavailable" }
    }

    private companion object {
        const val DATABASE_NAME = "swipe-cleaner.db"
    }
}
