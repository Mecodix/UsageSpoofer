package com.yourname.spoofer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LogViewerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Spoofer Logs"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }

        val logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#00FF00"))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val clearBtn = Button(this).apply {
            text = "CLEAR LOGS"
            setBackgroundColor(Color.parseColor("#BB86FC"))
            setOnClickListener {
                LogWriter.clear(this@LogViewerActivity)
                logView.text = "Logs cleared."
            }
        }

        val refreshBtn = Button(this).apply {
            text = "REFRESH"
            setBackgroundColor(Color.parseColor("#03DAC5"))
            setOnClickListener {
                logView.text = LogWriter.getLogs(this@LogViewerActivity)
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refreshBtn)
            addView(clearBtn)
        }

        layout.addView(title)
        layout.addView(scrollView)
        layout.addView(buttonRow)
        setContentView(layout)

        logView.text = LogWriter.getLogs(this)
    }
}
