package com.kcg.dr.location

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
    private val TAG = "LiveLocationProvider"

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
        // We re-check if location is enabled and try to start updates.
        Log.d(TAG, "Returned from location settings.")
        startRequesting() // FIXME: if user does not enable location in settings, this causes a settings loop
    }
    private val requestLocationPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        var allGranted = true
        results.forEach {
            if (!it.value) {
                allGranted = false
                return@forEach
            }
        }
        if (!allGranted) {
            // User denied location permissions request.
            Log.d(TAG, "Location permissions request denied.")
            Log.i(TAG, results.toString())
            // Explain to the user that the feature is unavailable because the
            // features requires a permission that the user has denied.
            ToastUtils.showToast("Location permissions denied.\nSome features may be unavailable.")
            return@registerForActivityResult
        }
        // Location permissions granted.
        Log.d(TAG, "Location permissions request granted.")
        // Try to start location updates.
        startRequesting()
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


    fun isRequesting(): Boolean = requestingEnabled

    fun startRequesting() {
        if (isRequesting()) return

        // Check permissions granted
        val locationPerms = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        var allGranted = true
        for (perm in locationPerms) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    perm
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                allGranted = false
                break
            }
        }
        if (!allGranted) {
            // Permissions missing
            Log.d(TAG, "Location permissions missing, requesting permission.")
            requestLocationPermissionLauncher.launch(locationPerms)
            return
        }
        // Check location provider enabled
        if (!(
                    mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                            mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    )
        ) {
            // Ask user to enable location
            Log.d(TAG, "Location not enabled, launching settings intent.")
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.putExtra(Intent.EXTRA_TITLE, "Location Settings")
            intent.putExtra(Intent.EXTRA_TEXT, "Please enable Location Services")
            mEnableLocationLauncher.launch(intent)
            return
        }
        // Start location updates
        Log.i(TAG, "Starting location updates.")
        mLocationProviderClient.requestLocationUpdates(
            locationRequest,
            mLocationCallback,
            Looper.getMainLooper()
        )
        requestingEnabled = true
        Log.d(TAG, "Started location updates")
    }

    fun stopRequesting() {
        if (requestingEnabled) {
            mLocationProviderClient.removeLocationUpdates(mLocationCallback)
            requestingEnabled = false
            Log.d(TAG, "Stopped location updates")
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