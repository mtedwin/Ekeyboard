package com.mtedwin.ekeyboard

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import kotlin.div
import kotlin.text.clear
import kotlin.toString

class EditEngListPage : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_edit_eng, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val editText = view.findViewById<EditText>(R.id.editTextEng)
        val addButton = view.findViewById<Button>(R.id.saveButtonEng)

        val positionSpinner = view.findViewById<Spinner>(R.id.spinnerEng)

        // Set up spinner options
        val positions = listOf("Bottom", "Top", "Middle")
        positionSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, positions)


        addButton.setOnClickListener {
            val newText = editText.text.toString().trim()
            if (newText.isNotBlank()) {
                val file = requireContext().getFileStreamPath("english.txt")
                val words = if (file.exists()) file.readLines().toMutableList() else mutableListOf()

                val exists = words.any { it.trim().equals(newText, ignoreCase = true) }
                if (exists) {
                    Toast.makeText(requireContext(), "Word already exists", Toast.LENGTH_SHORT).show()
                } else {
                    val pos = positionSpinner.selectedItem.toString()
                    when (pos) {
                        "Top" -> words.add(0, newText)
                        "Middle" -> words.add(words.size / 2, newText)
                        else -> words.add(newText) // Default is Bottom
                    }
                    requireContext().openFileOutput("english.txt", Context.MODE_PRIVATE).use { fos ->
                        fos.write(words.joinToString("\n").toByteArray())
                    }
                    editText.text.clear()
                    Toast.makeText(requireContext(), "Word added successfully", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}