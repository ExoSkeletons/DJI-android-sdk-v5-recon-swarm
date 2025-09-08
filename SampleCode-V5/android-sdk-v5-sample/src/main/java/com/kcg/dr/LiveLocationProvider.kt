package com.kcg.dr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dji.sampleV5.aircraft.util.ToastUtils

class LiveLocationProvider(
    fragment: Fragment,
    intervalMillis: Long,
    minUpdateIntervalMillis: Long = intervalMillis,
    maxUpdateDelayMillis: Long = intervalMillis,
    priority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
) {
    private lateinit var context: Context
    private lateinit var mLocationProviderClient: FusedLocationProviderClient
    private lateinit var mLocationManager: LocationManager

    /** Enable smoothing of location updates. */
    val enableSmoothing: Boolean = true
    // Buffer for location smoothing
    private val locationBuffer: ArrayDeque<Location> = ArrayDeque()
    val smoothingWindowSize: Int = 10

    private val locationRequest = LocationRequest.Builder(intervalMillis)
        .setWaitForAccurateLocation(false)
        .setMinUpdateIntervalMillis(minUpdateIntervalMillis)
        .setMaxUpdateDelayMillis(maxUpdateDelayMillis)
        .setPriority(priority)
        .build()
    private val mEnableLocationLauncher = fragment.registerForActivityResult(
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
            if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
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


    /** Internal location callback wrapper. */
    private val mLocationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = mutableListOf<Location>()

            for (raw in result.locations) {
                val location =
                    if (enableSmoothing) {
                        // add location to smoothing buffer
                        locationBuffer.addLast(raw)
                        if (locationBuffer.size > smoothingWindowSize) locationBuffer.removeFirst()
                        // compute smoothed location
                        getSmoothedLocation(locationBuffer)
                    } else raw

                // collect locations
                if (location != null) locations.add(location)
            }

            // Forward collected locations to user callback
            if (locations.isNotEmpty())
                locationCallback?.let {
                    // Post on main thread
                    Handler(Looper.getMainLooper()).post {
                        it.onLocationResult(LocationResult.create(locations))
                    }
                }
        }
    }

    /** Callback for receiving location updates. */
    var locationCallback: LocationCallback? = null
    private var requestingEnabled = false


    fun init(context: Context) {
        this.context = context
        mLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)
        mLocationManager =
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
        if (!(mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || mLocationManager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
            ))
        ) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.putExtra(Intent.EXTRA_TITLE, "Location Settings")
            intent.putExtra(Intent.EXTRA_TEXT, "Please enable Location Services")
            mEnableLocationLauncher.launch(intent)
            return
        }
        // Start location updates
        mLocationProviderClient.requestLocationUpdates(
            locationRequest,
            mLocationCallback,
            Looper.getMainLooper()
        )
        requestingEnabled = true
        Log.d("DeviceLocation", "Started location updates")
    }

    fun disable() {
        if (requestingEnabled) {
            mLocationProviderClient.removeLocationUpdates(mLocationCallback)
            requestingEnabled = false
            Log.d("DeviceLocation", "Stopped location updates")
        }
    }

    /**
     * Computes the average of buffered locations.
     */
    private fun getSmoothedLocation(locationBuffer: Collection<Location>): Location? {
        if (locationBuffer.isEmpty()) return null

        val avgLat = locationBuffer.map { it.latitude }.average()
        val avgLon = locationBuffer.map { it.longitude }.average()
        val avgAlt = locationBuffer.map { it.altitude }.average()

        val last = locationBuffer.last()
        return Location(last).apply {
            latitude = avgLat
            longitude = avgLon
            altitude = avgAlt
            accuracy = locationBuffer.map { it.accuracy.toDouble() }.average().toFloat()
        }
    }
}