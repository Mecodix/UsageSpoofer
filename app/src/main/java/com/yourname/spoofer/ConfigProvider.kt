package com.yourname.spoofer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class ConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val prefs = context?.getSharedPreferences("SpooferConfigPrivate", Context.MODE_PRIVATE) ?: return null

        // Build a temporary row structure to securely transmit settings variables across processes
        val cursor = MatrixCursor(arrayOf("key", "value"))

        val target = prefs.getString("target_package_to_spoof", "").orEmpty()
        val mode = prefs.getInt("spoof_mode", 0)
        val enabled = prefs.getBoolean("spoofer_enabled", true)

        // "time" is transmitted in milliseconds; the UI still stores minutes.
        val timeMs = prefs.getLong("custom_spoof_minutes", 0L) * 60_000L

        val requestedPackage = uri.pathSegments.firstOrNull().orEmpty()

        when {
            // Global row set: lets the hook discover the selected target package.
            requestedPackage.isEmpty() -> {
                cursor.addRow(arrayOf("target_package_to_spoof", target))
                cursor.addRow(arrayOf("time", timeMs.toString()))
                cursor.addRow(arrayOf("mode", mode.toString()))
                cursor.addRow(arrayOf("enabled", enabled.toString()))
            }

            // Package-scoped row set. No rows means "not configured for this package".
            requestedPackage == target && target.isNotEmpty() -> {
                cursor.addRow(arrayOf("time", timeMs.toString()))
                cursor.addRow(arrayOf("mode", mode.toString()))
                cursor.addRow(arrayOf("enabled", enabled.toString()))
            }

            else -> Unit // intentionally empty
        }

        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
