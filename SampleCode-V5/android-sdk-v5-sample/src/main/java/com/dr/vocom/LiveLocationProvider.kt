package com.dr.vocom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import dji.sampleV5.aircraft.util.ToastUtils

class LiveLocationProvider(
    fragment: Fragment,
    intervalMillis: Long = 10000,
    minUpdateIntervalMillis: Long = 5000,
    maxUpdateDelayMillis: Long = 15000
) {
    private lateinit var context: Context
    private lateinit var locationProviderClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private val locationRequest = LocationRequest.Builder(intervalMillis) // 10 seconds
        .setWaitForAccurateLocation(false)
        .setMinUpdateIntervalMillis(minUpdateIntervalMillis) // 5 seconds
        .setMaxUpdateDelayMillis(maxUpdateDelayMillis) // 15 seconds
        .build()
    private val enableLocationLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User has returned from the location settings screen.
        // We can optionally re-check if location is enabled and try to start updates.
        Log.d("LocationSettings", "Returned from location settings.")
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) enable()
        }
    }
    private val requestLocationPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            ToastUtils.showToast("Location permission granted")
            enable()
        } else {
            // Explain to the user that the feature is unavailable because the
            // features requires a permission that the user has denied.
            ToastUtils.showToast("Location permission denied, Follow Me feature unavailable.")
        }
    }

    var locationCallback: LocationCallback? = null
    private var requestingEnabled = false

    fun init(context: Context) {
        this.context = context
        locationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)
        locationManager =
            context.getSystemService(Context.LOCATION_SERVICE)
                    as LocationManager
    }


    fun enabled(): Boolean = requestingEnabled

    fun enable() {
        if (enabled()) return
        // Check permissions granted
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            ToastUtils.showToast("Location permission not granted. Cannot start updates.")
            return
        }
        // Check location enabled
        if (!(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
            ))
        ) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.putExtra(Intent.EXTRA_TITLE, "Location Settings")
            intent.putExtra(Intent.EXTRA_TEXT, "Please enable Location Services")
            enableLocationLauncher.launch(intent)
            return
        }

        locationCallback?.let {
            locationProviderClient.requestLocationUpdates(
                locationRequest,
                it,
                Looper.getMainLooper()
            )
        }
        requestingEnabled = true
        Log.d("DeviceLocation", "Started location updates")
    }

    fun disable() {
        if (requestingEnabled) {
            locationCallback?.let {
                locationProviderClient.removeLocationUpdates(it)
            }
            requestingEnabled = false
            Log.d("DeviceLocation", "Stopped location updates")
        }
    }
}