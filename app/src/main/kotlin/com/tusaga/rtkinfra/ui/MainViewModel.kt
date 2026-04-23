package com.tusaga.rtkinfra.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tusaga.rtkinfra.ar.InfrastructureAnchorManager
import com.tusaga.rtkinfra.data.model.InfraDataset
import com.tusaga.rtkinfra.data.repository.GeoJsonParser
import com.tusaga.rtkinfra.data.repository.SettingsRepository
import com.tusaga.rtkinfra.gnss.GnssManager
import com.tusaga.rtkinfra.gnss.NtripState
import com.tusaga.rtkinfra.gnss.RtkState
import com.tusaga.rtkinfra.gnss.GnssRtkService
import com.tusaga.rtkinfra.ntrip.NtripClientManager
import com.tusaga.rtkinfra.ntrip.NtripConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gnssManager: GnssManager,
    private val ntripClientManager: NtripClientManager,
    private val anchorManager: InfrastructureAnchorManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // ── Exposed State Flows ───────────────────────────────────────────

    val rtkState: StateFlow<RtkState> = gnssManager.rtkStateFlow
    val ntripState: StateFlow<NtripState> = ntripClientManager.ntripState

    private val _infraDataset = MutableStateFlow<InfraDataset?>(null)
    val infraDataset: StateFlow<InfraDataset?> = _infraDataset.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val _viewMode = MutableStateFlow(ViewMode.MAP)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    // ──────────────────────────────────────────────────────────────────
    init {
        // Forward RTK state to AR anchor manager for drift correction
        viewModelScope.launch {
            rtkState.collect { state ->
                anchorManager.updateGnssOrigin(state)
            }
        }

        // Auto-start the GNSS service
        startGnssService()
    }

    // ──────────────────────────────────────────────────────────────────
    // Service management
    // ──────────────────────────────────────────────────────────────────

    fun startGnssService() {
        val intent = Intent(context, GnssRtkService::class.java)
        context.startForegroundService(intent)
    }

    fun stopGnssService() {
        val intent = Intent(context, GnssRtkService::class.java).apply {
            action = GnssRtkService.ACTION_STOP
        }
        context.startService(intent)
    }

    // ──────────────────────────────────────────────────────────────────
    // NTRIP / TUSAGA configuration
    // ──────────────────────────────────────────────────────────────────

    fun saveNtripConfig(config: NtripConfig) {
        settingsRepository.saveNtripConfig(config)
        ntripClientManager.updateConfig(config)
        Timber.i("NTRIP config updated: ${config.host}/${config.mountpoint}")
    }

    fun getNtripConfig(): NtripConfig = settingsRepository.getNtripConfig()

    // ──────────────────────────────────────────────────────────────────
    // GeoJSON loading
    // ──────────────────────────────────────────────────────────────────

    fun loadGeoJsonFromAssets(assetPath: String) {
        viewModelScope.launch {
            try {
                val json = context.assets.open(assetPath)
                    .bufferedReader().use { it.readText() }
                val dataset = GeoJsonParser.parse(json, assetPath)
                _infraDataset.value = dataset
                _uiEvent.emit(UiEvent.ShowSnackbar("Loaded ${dataset.features.size} features"))
                Timber.i("GeoJSON loaded: ${dataset.features.size} features")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load GeoJSON: $assetPath")
                _uiEvent.emit(UiEvent.ShowSnackbar("Failed to load: ${e.message}"))
            }
        }
    }

    fun loadGeoJsonFromString(json: String, name: String = "imported") {
        viewModelScope.launch {
            val dataset = GeoJsonParser.parse(json, name)
            _infraDataset.value = dataset
            _uiEvent.emit(UiEvent.ShowSnackbar("Loaded ${dataset.features.size} features"))
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // View mode switching
    // ──────────────────────────────────────────────────────────────────

    fun switchToAR() { _viewMode.value = ViewMode.AR }
    fun switchToMap() { _viewMode.value = ViewMode.MAP }
    fun toggleViewMode() {
        _viewMode.value = if (_viewMode.value == ViewMode.MAP) ViewMode.AR else ViewMode.MAP
    }

    // ──────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        anchorManager.release()
    }
}

enum class ViewMode { MAP, AR }

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    data class ShowError(val message: String)    : UiEvent()
    object RequestPermissions                    : UiEvent()
}
