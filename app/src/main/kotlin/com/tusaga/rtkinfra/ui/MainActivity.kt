package com.tusaga.rtkinfra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.tusaga.rtkinfra.R
import com.tusaga.rtkinfra.databinding.ActivityMainBinding
import com.tusaga.rtkinfra.ui.ar.ArFragment
import com.tusaga.rtkinfra.ui.map.MapFragment
import com.tusaga.rtkinfra.ui.settings.SettingsFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Timber.i("All permissions granted")
            initApp()
        } else {
            Toast.makeText(this,
                "Location + Camera permissions required for RTK AR",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()

        if (hasPermissions()) {
            initApp()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun initApp() {
        // Load sample GeoJSON dataset on first run
        viewModel.loadGeoJsonFromAssets("sample_infrastructure.geojson")

        // Show map fragment by default
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            showMapFragment()
        }
    }

    private fun setupUI() {
        // Bottom navigation
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map      -> { showMapFragment();      true }
                R.id.nav_ar       -> { showArFragment();       true }
                R.id.nav_settings -> { showSettingsFragment(); true }
                else -> false
            }
        }

        // FAB: toggle Map/AR mode
        binding.fabToggle.setOnClickListener {
            viewModel.toggleViewMode()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.rtkState.collect { state ->
                // Update RTK status indicator
                binding.rtkStatusChip.apply {
                    text = state.fixType.label
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                        when (state.fixType) {
                            com.tusaga.rtkinfra.gnss.FixType.FIX    -> 0xFF4CAF50.toInt()  // Green
                            com.tusaga.rtkinfra.gnss.FixType.FLOAT  -> 0xFFFF9800.toInt()  // Orange
                            com.tusaga.rtkinfra.gnss.FixType.DGPS   -> 0xFF2196F3.toInt()  // Blue
                            com.tusaga.rtkinfra.gnss.FixType.SINGLE -> 0xFFF44336.toInt()  // Red
                            com.tusaga.rtkinfra.gnss.FixType.NONE   -> 0xFF9E9E9E.toInt()  // Gray
                        }
                    )
                }
                binding.accuracyText.text = state.accuracyDisplay
                binding.satCountText.text = "${state.usedSatellites}/${state.visibleSatellites} sats"
                if (state.dualFrequencyAvailable) {
                    binding.dualFreqBadge.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.ntripState.collect { state ->
                binding.ntripStatusDot.setBackgroundResource(
                    if (state.isConnected) R.drawable.dot_green else R.drawable.dot_red
                )
                binding.ntripMountText.text = if (state.isConnected) state.mountpoint else "Disconnected"
            }
        }

        lifecycleScope.launch {
            viewModel.uiEvent.collect { event ->
                when (event) {
                    is UiEvent.ShowSnackbar ->
                        Snackbar.make(binding.root, event.message, Snackbar.LENGTH_SHORT).show()
                    is UiEvent.ShowError ->
                        Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
                    else -> {}
                }
            }
        }

        lifecycleScope.launch {
            viewModel.viewMode.collect { mode ->
                binding.fabToggle.setImageResource(
                    if (mode == ViewMode.AR) R.drawable.ic_map else R.drawable.ic_ar
                )
            }
        }
    }

    private fun showMapFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, MapFragment())
            .commit()
    }

    private fun showArFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ArFragment())
            .commit()
    }

    private fun showSettingsFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SettingsFragment())
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopGnssService()
    }
}
