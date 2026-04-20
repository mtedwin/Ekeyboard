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

class EditChiListPage : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_edit_chi, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val editText = view.findViewById<EditText>(R.id.editTextChi)
        val editTextCode = view.findViewById<EditText>(R.id.editTextChiCode)
        val addButton = view.findViewById<Button>(R.id.saveButtonChi)

        val positionSpinner = view.findViewById<Spinner>(R.id.spinnerChi)

        // Set up spinner options
        val positions = listOf("Bottom", "Top", "Middle")
        positionSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, positions)


        addButton.setOnClickListener {
            val word = editText.text.toString().trim()
            val code = editTextCode.text.toString().trim()
            val position = positionSpinner.selectedItem.toString()

            if (word.isEmpty() || code.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter both word and code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveWord(word, code, position, editText, editTextCode)

        }
    }

    private fun saveWord(word: String, code: String, position: String, editText: EditText? = null, editTextCode: EditText? = null) {
        val file = requireContext().getFileStreamPath("chinese.csv")
        val words = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        val entry = "$word,$code"
        val exists = words.any { it.split(",")[0].trim().equals(word, ignoreCase = true) }
        if (exists) {
            Toast.makeText(requireContext(), "Word already exists", Toast.LENGTH_SHORT).show()
            return
        }
        when (position) {
            "Top" -> words.add(0, entry)
            "Middle" -> words.add(words.size / 2, entry)
            else -> words.add(entry)
        }
        requireContext().openFileOutput("chinese.csv", Context.MODE_PRIVATE).use { fos ->
            fos.write(words.joinToString("\n").toByteArray())
        }
        editText?.text?.clear()
        editTextCode?.text?.clear()
        Toast.makeText(requireContext(), "Word added successfully", Toast.LENGTH_SHORT).show()
    }
}