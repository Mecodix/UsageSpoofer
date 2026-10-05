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
        cursor.addRow(arrayOf("target_package_to_spoof", prefs.getString("target_package_to_spoof", "")))
        cursor.addRow(arrayOf("custom_spoof_minutes", prefs.getLong("custom_spoof_minutes", 0L).toString()))
        cursor.addRow(arrayOf("spoof_mode", prefs.getInt("spoof_mode", 0).toString()))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
