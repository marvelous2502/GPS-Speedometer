package com.example.gpsspeedometer

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class SpeedUiState(
    val currentSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val distanceKm: Double = 0.0,
    val elapsedSeconds: Long = 0L,
    val isTracking: Boolean = false,
    val hasGpsFix: Boolean = false
)

class SpeedViewModel(application: Application) : AndroidViewModel(application) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)

    private val _uiState = MutableStateFlow(SpeedUiState())
    val uiState: StateFlow<SpeedUiState> = _uiState

    private var lastLocation: Location? = null
    private var totalDistanceMeters: Double = 0.0
    private var trackingStartTime: Long = 0L
    private var accumulatedElapsedMs: Long = 0L
    private var timerJob: kotlinx.coroutines.Job? = null

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 1000L
    ).setMinUpdateIntervalMillis(500L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onNewLocation(location)
        }
    }

    private fun onNewLocation(location: Location) {
        // Prefer the GPS-reported speed (m/s); fall back to computing it from distance/time.
        val speedMs = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val speedKmh = speedMs * 3.6

        val prev = lastLocation
        if (prev != null && _uiState.value.isTracking) {
            // Only accumulate distance for reasonably accurate fixes to avoid GPS jitter.
            if (location.accuracy <= 25f) {
                totalDistanceMeters += prev.distanceTo(location)
            }
        }
        lastLocation = location

        val elapsedS = (accumulatedElapsedMs + if (_uiState.value.isTracking) {
            System.currentTimeMillis() - trackingStartTime
        } else 0L) / 1000.0

        val avg = if (elapsedS > 0) (totalDistanceMeters / elapsedS) * 3.6 else 0.0

        _uiState.value = _uiState.value.copy(
            currentSpeedKmh = speedKmh,
            maxSpeedKmh = maxOf(_uiState.value.maxSpeedKmh, speedKmh),
            avgSpeedKmh = avg,
            distanceKm = totalDistanceMeters / 1000.0,
            hasGpsFix = true
        )
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, getApplication<Application>().mainLooper
        )
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    fun startTrip() {
        if (_uiState.value.isTracking) return
        trackingStartTime = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(isTracking = true)
        startTimer()
    }

    fun pauseTrip() {
        if (!_uiState.value.isTracking) return
        accumulatedElapsedMs += System.currentTimeMillis() - trackingStartTime
        _uiState.value = _uiState.value.copy(isTracking = false)
        timerJob?.cancel()
    }

    fun resetTrip() {
        timerJob?.cancel()
        totalDistanceMeters = 0.0
        accumulatedElapsedMs = 0L
        trackingStartTime = System.currentTimeMillis()
        lastLocation = null
        _uiState.value = _uiState.value.copy(
            maxSpeedKmh = 0.0,
            avgSpeedKmh = 0.0,
            distanceKm = 0.0,
            elapsedSeconds = 0L
        )
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_uiState.value.isTracking) {
                val elapsedS = (accumulatedElapsedMs + (System.currentTimeMillis() - trackingStartTime)) / 1000
                _uiState.value = _uiState.value.copy(elapsedSeconds = elapsedS)
                delay(1000L)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates()
        timerJob?.cancel()
    }
}
