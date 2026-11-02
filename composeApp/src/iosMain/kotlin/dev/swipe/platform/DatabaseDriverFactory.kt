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
 *
 * A file that cannot be opened (corrupt) is moved aside as `<name>.corrupt` and a
 * fresh database is opened instead: the decisions it held are unrecoverable, and an
 * app that opens beats one that crash-loops (FR-20). Only the newest corrupt copy
 * is kept, and recovery is logged with `println` because the app has no logger.
 */
class DatabaseDriverFactory {
    /** The production driver: `swipe-cleaner.db` under Application Support. */
    fun create(): NativeSqliteDriver = createIn(applicationSupportDirectory(), DATABASE_NAME)

    /**
     * Opens [name] under [basePath] with the current schema, creating the file if it
     * is new. The backup exclusion runs *before* the warm-up query (§7), so the file
     * is never in a backup-eligible state while the schema materialises. If the file
     * cannot be opened, it (and its WAL sidecars) is moved aside and a fresh database
     * is opened. Tests point this at a temp directory to verify durability across
     * driver instances (FR-20).
     */
    fun createIn(
        basePath: String,
        name: String,
    ): NativeSqliteDriver {
        val fileUrl =
            NSURL
                .fileURLWithPath(basePath)
                .URLByAppendingPathComponent(name)
                ?: error("Cannot build a file URL for $name under $basePath")
        return runCatching { openMaterialized(basePath, name, fileUrl) }
            .recoverCatching { failure ->
                // Broad on purpose: SQLite surfaces corruption through opaque native
                // errors and the recovery is the same whatever failed. If the retry
                // fails too, the directory itself is unusable and this propagates.
                println(
                    "SwipeCleaner: database $name failed to open (${failure.message ?: failure}); " +
                        "moving it aside as $name$CORRUPT_SUFFIX and starting fresh",
                )
                moveAside(fileUrl)
                openMaterialized(basePath, name, fileUrl)
            }.getOrThrow()
    }

    private fun openMaterialized(
        basePath: String,
        name: String,
        fileUrl: NSURL,
    ): NativeSqliteDriver {
        // SQLite treats a zero-length file as a new empty database, so creating the
        // file up front is safe and lets the backup exclusion run before any query.
        val path = fileUrl.path ?: error("File URL without a path: $fileUrl")
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path) &&
            !manager.createFileAtPath(path, null, null)
        ) {
            error("Cannot create the database file at $path")
        }
        excludeFromBackup(fileUrl)
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
        // Close the half-open driver before propagating so no connection leaks.
        runCatching { runBlocking { SwipeDatabase(driver).decisionQueries.decidedIds().executeAsList() } }
            .onFailure { driver.close() }
            .getOrThrow()
        return driver
    }

    private fun schemaVersion(): Int {
        val version = SwipeDatabase.Schema.version
        require(version <= Int.MAX_VALUE.toLong()) { "Schema version $version exceeds Int.MAX_VALUE" }
        return version.toInt()
    }

    /**
     * Moves the database and its WAL sidecars to `<file>$CORRUPT_SUFFIX`, overwriting
     * the copy from an earlier recovery (only the latest corrupt database is kept).
     */
    private fun moveAside(fileUrl: NSURL) {
        val path = fileUrl.path ?: return
        val manager = NSFileManager.defaultManager
        // The sidecars are named after the database file, so they append to the path
        // here; appendingPathComponent would insert a separator.
        for (suffix in listOf("", "-wal", "-shm")) {
            val source = path + suffix
            val corrupt = source + CORRUPT_SUFFIX
            if (manager.fileExistsAtPath(corrupt)) {
                manager.removeItemAtPath(corrupt, error = null)
            }
            if (manager.fileExistsAtPath(source)) {
                manager.moveItemAtPath(source, toPath = corrupt, error = null)
            }
        }
    }

    /** Sets `NSURLIsExcludedFromBackupKey` on [fileUrl] (§7); logs a failed exclusion. */
    private fun excludeFromBackup(fileUrl: NSURL) {
        val excluded =
            fileUrl.setResourceValue(
                value = true,
                forKey = NSURLIsExcludedFromBackupKey,
                error = null,
            )
        if (!excluded) {
            println("SwipeCleaner: could not exclude the database from backup: ${fileUrl.path}")
        }
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
        const val CORRUPT_SUFFIX = ".corrupt"
    }
}
