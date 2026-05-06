package com.kcg.dr.vocom.location

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.kcg.dr.LiveLocationProvider
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class LocationViewModel : ViewModel() {
    val deviceLocation = MutableLiveData<LocationCoordinate3D?>()
    
    private var liveLocationProvider: LiveLocationProvider? = null

    fun initLocation(context: Context, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        if (liveLocationProvider == null) {
            liveLocationProvider = LiveLocationProvider(
                lifecycleOwner,
                200, 50,
                500,
                Priority.PRIORITY_HIGH_ACCURACY
            ).apply {
                init(context)
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        locationResult.lastLocation?.let { location ->
                            deviceLocation.postValue(LocationCoordinate3D().apply {
                                latitude = location.latitude
                                longitude = location.longitude
                                altitude = 3.0 // Default human height
                            })
                        }
                    }
                }
            }
        }
        liveLocationProvider?.startRequesting()
    }

    fun stopLocation() {
        liveLocationProvider?.stopRequesting()
    }

    override fun onCleared() {
        super.onCleared()
        stopLocation()
    }
}
