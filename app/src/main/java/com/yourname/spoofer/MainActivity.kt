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
        
        val pkgInput = EditText(this).apply { 
            hint = "App Package (e.g., com.google.android.youtube)"
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

        layout.addView(title)
        layout.addView(toggleSwitch)
        layout.addView(pkgInput)
        layout.addView(timeInput)
        layout.addView(modeSpinner)
        layout.addView(saveBtn)
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
