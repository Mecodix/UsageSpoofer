package com.yourname.spoofer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.*
import android.graphics.Color

class MainActivity : Activity() {
    @SuppressLint("WorldReadableFiles")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Build a simple UI using code (No XML needed!)
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
        // Modes: SET exact time, ADD to real time, HIDE completely
        val modes = arrayOf("SET (Overwrite exact time)", "ADD (Increase real time)", "HIDE (Ghost Mode)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        modeSpinner.adapter = adapter
        
        val saveBtn = Button(this).apply { 
            text = "SAVE CONFIGURATION" 
            setBackgroundColor(Color.parseColor("#BB86FC"))
        }
        
        layout.addView(title)
        layout.addView(pkgInput)
        layout.addView(timeInput)
        layout.addView(modeSpinner)
        layout.addView(saveBtn)
        setContentView(layout)
        
        // This is where the magic bridge happens. 
        // MODE_WORLD_READABLE allows LSPosed to read this config from another app.
        val prefs = getSharedPreferences("SpooferConfig", Context.MODE_WORLD_READABLE)
        
        saveBtn.setOnClickListener {
            val pkg = pkgInput.text.toString().trim()
            val minutes = timeInput.text.toString().toLongOrNull() ?: 0L
            val mode = modeSpinner.selectedItemPosition
            
            if (pkg.isNotEmpty()) {
                prefs.edit().apply {
                    putLong("${pkg}_time", minutes * 60000L) // Convert mins to milliseconds
                    putInt("${pkg}_mode", mode)
                    putBoolean("${pkg}_active", true)
                    apply()
                }
                Toast.makeText(this, "Saved! Open Digital Wellbeing to see changes.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
