package com.mtedwin.ekeyboard

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment

class HomePage : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HomePageContent()
            }
        }
    }

    @Composable
    private fun HomePageContent() {
        val sharedPreferences = requireContext().getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)
        val defaultCycleOrder = listOf("qwerty", "t13", "chinese", "t13c")
        val layoutOptions = listOf("qwerty", "t13", "chinese", "t13c")
        val savedOrder = sharedPreferences.getString("keyboardCycleOrder", defaultCycleOrder.joinToString(","))
            ?: defaultCycleOrder.joinToString(",")
        val initialCycleText = savedOrder
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it in layoutOptions }
            .distinct()
            .ifEmpty { defaultCycleOrder }
            .joinToString(",")

        var popupPosition by remember { mutableIntStateOf(sharedPreferences.getInt("popupPosition", -300)) }
        var autoSpace by remember { mutableStateOf(sharedPreferences.getBoolean("t13AutoSpace", false)) }
        var cycleOrderText by remember { mutableStateOf(initialCycleText) }

        fun saveCycleOrder(order: String) {
            val sanitized = order
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it in layoutOptions }
                .distinct()
                .ifEmpty { defaultCycleOrder }
                .joinToString(",")
            cycleOrderText = sanitized
            sharedPreferences.edit().putString("keyboardCycleOrder", sanitized).apply()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Ekeyboard Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }

            item {
                SettingsCard(title = "Popup Position") {
                    Text(
                        text = popupPosition.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Slider(
                        value = popupPosition.toFloat(),
                        onValueChange = {
                            val value = it.toInt()
                            popupPosition = value
                            sharedPreferences.edit().putInt("popupPosition", value).apply()
                        },
                        valueRange = -1500f..-100f,
                        steps = 1399,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Range: -100 to -1500",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingsCard(title = "Keyboard Cycle") {
                    val enabledLayouts = cycleOrderText
                        .split(",")
                        .map { it.trim().lowercase() }
                        .filter { it in layoutOptions }
                        .distinct()
                        .toSet()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        layoutOptions.forEach { layout ->
                            val selected = layout in enabledLayouts
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        val current = cycleOrderText
                                            .split(",")
                                            .map { it.trim().lowercase() }
                                            .filter { it in layoutOptions }
                                            .toMutableList()

                                        if (checked) {
                                            if (!current.contains(layout)) current.add(layout)
                                        } else {
                                            current.remove(layout)
                                        }
                                        saveCycleOrder(current.joinToString(","))
                                    }
                                )
                                Text(text = layout.uppercase())
                            }
                        }
                    }

                    OutlinedTextField(
                        value = cycleOrderText,
                        onValueChange = { text ->
                            val sanitized = text
                                .split(",")
                                .map { it.trim().lowercase() }
                                .filter { it in layoutOptions }
                                .distinct()
                                .joinToString(",")
                            if (sanitized.isNotEmpty()) {
                                saveCycleOrder(sanitized)
                            }
                        },
                        label = { Text("Order: qwerty,t13,chinese,t13c") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                SettingsCard(title = "T13 Settings") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Add space after selecting word")
                        Checkbox(
                            checked = autoSpace,
                            onCheckedChange = { checked ->
                                autoSpace = checked
                                sharedPreferences.edit().putBoolean("t13AutoSpace", checked).apply()
                            }
                        )
                    }
                }
            }

            item {
                SettingsCard(title = "Tips") {
                    Text("• Lower popup values move the key label higher.")
                    Text("• Use the keyboard cycle order to customize the loop.")
                    Text("• Toggle layouts on/off to choose what appears in the cycle.")
                }
            }

            item {
                SettingsCard(title = "Version") {
                    Text("Version: ${com.mtedwin.ekeyboard.BuildConfig.VERSION_NAME}")
                }
            }

        }
    }
}



@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}
