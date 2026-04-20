package com.mtedwin.ekeyboard

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File
import kotlin.math.log

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        copyAssetIfNotExists("chinese.csv")
        copyAssetIfNotExists("english.txt")

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val navHostView = findViewById<View>(R.id.nav_host_fragment)

        // Handle window insets for the main container
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, windowInsets ->
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())

            // Hide bottom nav when keyboard is open
            if (imeVisible) {
                bottomNavigationView.visibility = View.GONE
                // Push fragment content above the keyboard
                navHostView.setPadding(0, 0, 0, imeInsets.bottom)
            } else {
                bottomNavigationView.visibility = View.VISIBLE
                navHostView.setPadding(0, 0, 0, 0)
            }

            // Apply bottom margin to bottom navigation for system nav bar
            bottomNavigationView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBarInsets.bottom
            }

            WindowInsetsCompat.CONSUMED
        }

        bottomNavigationView.setupWithNavController(navController)
    }

    // copy library files from assets to internal storage if not already present
    private fun copyAssetIfNotExists(assetName: String) {
        val file = File(filesDir, assetName)
        if (!file.exists()) {
            try {
                assets.open(assetName).use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.d("MainActivity", "Error copying asset $assetName: ${e.message}")
            }
        }
    }

}