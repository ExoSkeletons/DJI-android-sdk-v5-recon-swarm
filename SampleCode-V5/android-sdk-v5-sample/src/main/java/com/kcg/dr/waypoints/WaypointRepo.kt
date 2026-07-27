package com.kcg.dr.waypoints

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.locationDataStore by preferencesDataStore(name = "waypoint_locations")
private val KEY_LOCATIONS = stringPreferencesKey("waypoint_locations_json")

class WaypointRepo(context: Context) {
    private val dataStore = context.locationDataStore

    private val locations: MutableMap<String, LocationCoordinate3D?> = mutableMapOf()

    fun locations(): Map<String, LocationCoordinate3D?> = locations

    suspend fun load() {
        val prefs = dataStore.data.first()

        locations.clear()

        val json = prefs[KEY_LOCATIONS] ?: return
        val jsonMap = JSONObject(json)
        for (key in jsonMap.keys())
            locations[key] = LocationCoordinate3D.fromJson(jsonMap.getString(key))
    }

    suspend fun save() {
        if (locations.isEmpty()) return

        val jsonMap = JSONObject()
        for ((key, value) in locations)
            if (value != null)
                jsonMap.put(key, value.toJson())


        dataStore.edit { prefs ->
            prefs[KEY_LOCATIONS] = jsonMap.toString()
        }
    }

    suspend fun put(name: String, location: LocationCoordinate3D?) {
        locations[name] = location
        save()
    }

    suspend fun clear() {
        locations.clear()
        save()
    }
}