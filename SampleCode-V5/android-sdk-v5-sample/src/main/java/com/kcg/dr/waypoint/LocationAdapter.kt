import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.ItemWaypointLocationBinding
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class LocationAdapter(
    private val locations: MutableList<LocationCoordinate3D>,
    private val aliases: List<String>,
    private val onFlyTo: (LocationCoordinate3D) -> Unit,
    private val onLookAt: (LocationCoordinate3D)->Unit,
    private val onDelete: (LocationCoordinate3D) -> Unit,
) : RecyclerView.Adapter<LocationAdapter.LocationVH>() {
    class LocationVH(val binding: ItemWaypointLocationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocationVH {
        val binding =
            ItemWaypointLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LocationVH(binding)
    }

    override fun onBindViewHolder(holder: LocationVH, position: Int) {
        val loc = locations[position]

        holder.binding.tvIndex.text = (position + 1).toString()
        holder.binding.tvAlias.text = aliases.getOrNull(position) ?: ""
        holder.binding.tvLocation.text = holder.binding.root.context.getString(
            R.string.location_fmt_short,
            loc.latitude,
            loc.longitude,
            loc.altitude
        )

        holder.binding.btnGoto.setOnClickListener { onFlyTo(loc) }
        holder.binding.btnLookAt.setOnClickListener { onLookAt(loc) }
        holder.binding.btnDelete.setOnClickListener { onDelete(loc) }
    }

    override fun getItemCount() = locations.size

    fun add(loc: LocationCoordinate3D) {
        locations.add(loc)
        notifyItemInserted(locations.size - 1)
    }

    fun remove(loc: LocationCoordinate3D) {
        val index = locations.indexOfFirst {
            it.latitude == loc.latitude && it.longitude == loc.longitude && it.altitude == loc.altitude
        }
        if (index >= 0) {
            locations.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun set(newList: List<LocationCoordinate3D>) {
        locations.clear()
        locations.addAll(newList)
        notifyDataSetChanged()
    }
}
