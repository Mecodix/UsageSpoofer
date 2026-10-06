package com.yourname.spoofer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.*
import android.graphics.Color

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Build the layout UI
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
            setBackgroundColor(Color.parseColor("#121212")) // Dark Mode
        }
        
        val title = TextView(this).apply { 
            text = "God Mode Spoofer"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 40)
        }

        // The package below is the app whose usage time is rewritten, which is not
        // necessarily the app the hook runs in. That one is fixed by LSPosed scope.
        val subtitle = TextView(this).apply {
            text = "Enter the app whose usage time you want to change. " +
                "The hook runs inside the monitoring app listed in LSPosed scope."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 24)
        }
        
        val pkgInput = EditText(this).apply { 
            hint = "App to spoof (e.g. com.google.android.youtube)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        
        val timeInput = EditText(this).apply { 
            hint = "Time in Minutes (e.g., 45)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val modeSpinner = Spinner(this)
        val modes = arrayOf("SET (Overwrite exact time)", "ADD (Increase real time)", "HIDE (Ghost Mode)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        modeSpinner.adapter = adapter
        
        val prefs = getSharedPreferences("SpooferConfigPrivate", Context.MODE_PRIVATE)

        // Show the previously saved values so the config is not silently blanked.
        pkgInput.setText(prefs.getString("target_package_to_spoof", ""))
        timeInput.setText(prefs.getLong("custom_spoof_minutes", 0L).toString())
        modeSpinner.setSelection(prefs.getInt("spoof_mode", 0))

        // Enable/Disable toggle
        val toggleSwitch = Switch(this).apply {
            text = "Spoofer Enabled"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("spoofer_enabled", true)
        }

        val saveBtn = Button(this).apply {
            text = "SAVE CONFIGURATION"
            setBackgroundColor(Color.parseColor("#BB86FC"))
        }

        val logBtn = Button(this).apply {
            text = "VIEW LOGS"
            setBackgroundColor(Color.parseColor("#03DAC5"))
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, LogViewerActivity::class.java))
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(toggleSwitch)
        layout.addView(pkgInput)
        layout.addView(timeInput)
        layout.addView(modeSpinner)
        layout.addView(saveBtn)
        layout.addView(logBtn)
        setContentView(layout)

        saveBtn.setOnClickListener {
            val pkg = pkgInput.text.toString().trim()
            val minutes = timeInput.text.toString().toLongOrNull() ?: 0L
            val mode = modeSpinner.selectedItemPosition
            val enabled = toggleSwitch.isChecked

            prefs.edit().apply {
                putString("target_package_to_spoof", pkg)
                putLong("custom_spoof_minutes", minutes)
                putInt("spoof_mode", mode)
                putBoolean("spoofer_enabled", enabled)
                apply()
            }
            Toast.makeText(this, "Saved successfully!", Toast.LENGTH_LONG).show()
        }
    }
}
