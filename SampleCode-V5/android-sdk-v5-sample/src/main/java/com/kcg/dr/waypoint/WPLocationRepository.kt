package com.kcg.dr.waypoint

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.flow.first
import org.json.JSONArray

private val Context.locationDataStore by preferencesDataStore(name = "waypoint_locations")
private val KEY_LOCATIONS = stringPreferencesKey("waypoint_locations_json")

class WPLocationRepository(context: Context) {
    private val dataStore = context.locationDataStore

    val locations: MutableList<LocationCoordinate3D> = mutableListOf()

    suspend fun load() {
        val prefs = dataStore.data.first()

        locations.clear()

        prefs[KEY_LOCATIONS]?.let { jsonString ->
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val l = LocationCoordinate3D.fromJson(jsonArray.getString(i))
                locations.add(l)
            }
        }
    }

    suspend fun save() {
        if (locations.isEmpty()) return

        val jsonArray = JSONArray()
        for (l in locations) jsonArray.put(l.toJson())

        dataStore.edit { prefs ->
            prefs[KEY_LOCATIONS] = jsonArray.toString()
        }
    }

    suspend fun add(l: LocationCoordinate3D) {
        locations.add(l)
        save()
    }


    suspend fun removeIndex(index: Int) {
        locations.removeAt(index)
        save()
    }

    suspend fun remove(l: LocationCoordinate3D) {
        locations.remove(l)
        save()
    }

    suspend fun clear() {
        locations.clear()
        save()
    }

    suspend fun addAll(ls: List<LocationCoordinate3D>) {
        locations.addAll(ls)
        save()
    }
}