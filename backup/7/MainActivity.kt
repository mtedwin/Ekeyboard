package com.mtedwin.ekeyboard

import android.os.Bundle
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Get SharedPreferences
        val sharedPreferences = getSharedPreferences("KeyboardSettings", MODE_PRIVATE)

        // Get UI components
        val seekBar = findViewById<SeekBar>(R.id.popupPositionSeekBar)
        val valueTextView = findViewById<TextView>(R.id.popupPositionValue)
        val t13AutoSpaceCheckBox = findViewById<CheckBox>(R.id.t13AutoSpaceCheckBox)

        // ...existing code for popup position...
        val savedPosition = sharedPreferences.getInt("popupPosition", -300)
        val seekBarProgress = -(savedPosition + 100)
        seekBar.progress = seekBarProgress
        valueTextView.text = savedPosition.toString()

        // Setup SeekBar listener
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val position = -100 - progress
                valueTextView.text = position.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 1200
                val position = -100 - progress
                sharedPreferences.edit().putInt("popupPosition", position).apply()
            }
        })

        // Load T13 auto-space setting (default is false - disabled)
        val t13AutoSpaceEnabled = sharedPreferences.getBoolean("t13AutoSpace", false)
        t13AutoSpaceCheckBox.isChecked = t13AutoSpaceEnabled

        // Setup CheckBox listener
        t13AutoSpaceCheckBox.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("t13AutoSpace", isChecked).apply()
        }
    }
}