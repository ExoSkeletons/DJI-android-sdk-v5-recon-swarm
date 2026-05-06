package com.kcg.dr.vocom.waypoints

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.RecyclerView
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.ItemWaypointLocationBinding
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class LocationAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val onFlyTo: (LocationCoordinate3D) -> Unit,
    private val onLookAt: (LocationCoordinate3D) -> Unit,
    private val deviceLocation: LiveData<LocationCoordinate3D?>,
    private val aircraftLocation: LiveData<LocationCoordinate3D?>,
) : RecyclerView.Adapter<LocationAdapter.LocationVH>() {
    private val locations: MutableList<Pair<String, LocationCoordinate3D?>> = mutableListOf()
    var onLocationChanged: ((String, LocationCoordinate3D?) -> Unit)? = null

    class LocationVH(val binding: ItemWaypointLocationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocationVH = LocationVH(
        ItemWaypointLocationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent, false
        )
    )

    override fun onBindViewHolder(holder: LocationVH, position: Int) {
        val (key, loc) = locations.toList()[position]

        holder.binding.btnSetAircraft.setOnClickListener { set(key, aircraftLocation.value) }
        holder.binding.btnSetDevice.setOnClickListener { set(key, deviceLocation.value) }
        holder.binding.btnDelete.setOnClickListener { set(key, null) }
        holder.binding.btnGoto.setOnClickListener { loc?.let { onFlyTo(it) } }
        holder.binding.btnLookAt.setOnClickListener { loc?.let { onLookAt(it) } }

        holder.binding.btnSetAircraft.isEnabled = false
        holder.binding.btnSetDevice.isEnabled = false
        aircraftLocation.observe(lifecycleOwner) {
            holder.binding.btnSetAircraft.isEnabled = it != null
        }
        deviceLocation.observe(lifecycleOwner) {
            holder.binding.btnSetDevice.isEnabled = it != null
        }

        holder.binding.layoutActionsLocation.visibility =
            if (loc == null) ViewGroup.GONE else ViewGroup.VISIBLE
        holder.binding.layoutActionsNoLocation.visibility =
            if (loc == null) ViewGroup.VISIBLE else ViewGroup.GONE

        holder.binding.tvAlias.text = key
        holder.binding.tvLocation.text = loc?.let {
            holder.binding.root.context.getString(
                R.string.location_fmt_short,
                loc.latitude,
                loc.longitude,
                loc.altitude
            )
        } ?: "-"
    }

    override fun getItemCount() = locations.size

    fun set(key: String, loc: LocationCoordinate3D?) {
        locations.forEachIndexed { i, (k, _) ->
            if (k == key) {
                locations[i] = Pair(k, loc)
                notifyItemChanged(i)
                onLocationChanged?.let { it(k, loc) }
                return
            }
        }

        locations.add(Pair(key, loc))
        notifyItemInserted(locations.size - 1)
    }
}
