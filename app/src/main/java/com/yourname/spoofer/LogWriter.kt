package com.yourname.spoofer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogWriter {
    private const val LOG_FILE = "spoofer_logs.txt"
    private const val MAX_LOG_SIZE = 500 * 1024 // 500KB max

    /**
     * Shared log location.
     *
     * Writes arrive from the hooked process via ConfigProvider, so the context
     * here is always the module's own. Reading externalFilesDir is unreliable
     * early in startup and its path differs per device/user, so fall back to
     * internal storage, which is always present and always readable by the
     * module's own activities.
     */
    private fun getLogFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, LOG_FILE)
    }

    fun log(context: Context, tag: String, message: String) {
        try {
            val file = getLogFile(context)
            // Trim if too large
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(500).joinToString("\n"))
            }
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            file.appendText("[$timestamp] [$tag] $message\n")
        } catch (e: Exception) {
            // Silent fail
        }
    }

    fun getLogs(context: Context): String {
        return try {
            val file = getLogFile(context)
            if (file.exists()) file.readText() else "No logs yet."
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    fun clear(context: Context) {
        try {
            getLogFile(context).delete()
        } catch (e: Exception) {
            // Silent fail
        }
    }
}
