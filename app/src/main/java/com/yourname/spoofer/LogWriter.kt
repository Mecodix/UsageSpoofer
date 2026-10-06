package com.yourname.spoofer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogWriter {
    private const val LOG_FILE = "spoofer_logs.txt"
    private const val MAX_LOG_SIZE = 500 * 1024 // 500KB max

    fun log(context: Context, tag: String, message: String) {
        try {
            val file = File(context.filesDir, LOG_FILE)
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
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists()) file.readText() else "No logs yet."
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, LOG_FILE).delete()
        } catch (e: Exception) {
            // Silent fail
        }
    }
}
