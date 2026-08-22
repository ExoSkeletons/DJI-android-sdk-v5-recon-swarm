package com.kcg.dr.waypoints

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kcg.dr.utils.getLocalizedResources
import dji.sampleV5.aircraft.R
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.launch
import java.util.Locale

class WaypointsVM(application: Application) : AndroidViewModel(application) {
    private var waypointRepo: WaypointRepo = WaypointRepo(getApplication())

    private val _locations = MutableLiveData<Map<String, LocationCoordinate3D?>>(emptyMap())
    val locations: LiveData<Map<String, LocationCoordinate3D?>> = _locations

    fun loadWaypoints() {
        viewModelScope.launch {
            waypointRepo.load()
            _locations.postValue(waypointRepo.locations())
        }
    }

    fun updateWaypoint(name: String, location: LocationCoordinate3D?) {
        viewModelScope.launch {
            waypointRepo.put(name, location)
            _locations.postValue(waypointRepo.locations())
        }
    }

    fun matchWaypointLocationFromRegexCapture(
        regexMatch: MatchResult,
        selfLocation: LocationCoordinate3D?,
        locale: Locale = Locale.getDefault(),
    ): Pair<String?, LocationCoordinate3D?> {
        // extract the args target from the regex match capture group
        val args = regexMatch.groups[1]?.value ?: ""

        val waypoints = waypointRepo.locations()

        val waypointAliases = getApplication<Application>().getLocalizedResources(locale)
            .getStringArray(R.array.commands_mission_targets).toMutableList()
        val deviceAliases = getApplication<Application>().getLocalizedResources(locale)
            .getString(R.string.commands_mission_target_device)

        // try match args to a waypoint target
        var nameKey: String? = null
        val target = when {
            args.isBlank() -> null // no target specified in command args, perform generic scan
            else -> when {
                // args match self alias
                deviceAliases.toRegex().containsMatchIn(args) ->
                    // choose device location as scan target
                    selfLocation ?: throw RuntimeException("device location unavailable")

                else -> {
                    // try match args to the list of waypoint targets aliases
                    waypointAliases.forEachIndexed { i, aliases ->
                        Log.i(
                            "LocationResolver",
                            "matching aliases $i) $aliases to args $args:"
                        )
                        if (aliases.toRegex().containsMatchIn(args)) {
                            nameKey = aliases
                            Log.i("LocationResolver", "matched. index=$i")
                            return@forEachIndexed
                        }
                    }
                    if (nameKey == null) {
                        Log.d("LocationResolver", "no key match for arg: $args")
                        throw RuntimeException("no such location: $args")
                    }

                    // return the location of the matched waypoint target
                    waypoints[nameKey] ?: throw RuntimeException("no location set for $args")
                }
            }
        }
        return Pair(nameKey, target)
    }
}
