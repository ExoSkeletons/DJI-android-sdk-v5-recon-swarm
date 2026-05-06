package com.kcg.dr.location

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.kcg.dr.LiveLocationProvider
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class LocationViewModel(application: Application) : AndroidViewModel(application) {
    val deviceLocation = MutableLiveData<LocationCoordinate3D?>()
    
    private var liveLocationProvider: LiveLocationProvider? = null

    fun initProvider(parent: Fragment) {
        if (liveLocationProvider == null) {
            liveLocationProvider = LiveLocationProvider(
                parent,
                200, 50,
                500,
                Priority.PRIORITY_HIGH_ACCURACY
            ).apply {
                init(getApplication())
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
    }

    fun startLocation() {
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
