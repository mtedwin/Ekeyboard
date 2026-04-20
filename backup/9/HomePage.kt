package com.mtedwin.ekeyboard

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment

class HomePage : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get SharedPreferences
        val sharedPreferences = requireActivity().getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)

        // Get UI components
        val seekBar = view.findViewById<SeekBar>(R.id.popupPositionSeekBar)
        val valueTextView = view.findViewById<TextView>(R.id.popupPositionValue)
        val t13AutoSpaceCheckBox = view.findViewById<CheckBox>(R.id.t13AutoSpaceCheckBox)

        // Ensure the layout adjusts when keyboard appears
        requireActivity().window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        // Load saved value (default -300)
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

        // Add this after the CheckBox listener setup
        val scrollView = view.findViewById<ScrollView>(R.id.scrollView) // Adjust ID as needed
        val editText = view.findViewById<EditText>(R.id.editText) // Adjust ID as needed

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollView.post {
                    scrollView.smoothScrollTo(0, editText.bottom)
                }
            }
        }

        // Apply window insets as top padding to the root ConstraintLayout
        val constraintLayout = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(android.R.id.content)
        val fallbackConstraintLayout = (view as? ViewGroup)?.getChildAt(0) as? androidx.constraintlayout.widget.ConstraintLayout
        val layoutToPad = constraintLayout ?: fallbackConstraintLayout
        layoutToPad?.let { cl ->
            ViewCompat.setOnApplyWindowInsetsListener(cl) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(top = systemBars.top + 16) // 16dp for original padding
                insets
            }
        }
    }
}