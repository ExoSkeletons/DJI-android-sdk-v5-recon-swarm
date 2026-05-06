package com.kcg.dr.waypoints

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kcg.dr.LocaleUtils.getLocalizedResources
import com.kcg.dr.LocationUtils.distanceTo
import com.kcg.dr.as2D
import com.kcg.dr.flight.AircraftControlViewModel
import com.kcg.dr.location.LocationViewModel
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomWaypointsBinding
import kotlinx.coroutines.launch
import java.util.Locale

class WaypointsFragment : Fragment() {
    private var _binding: FragVocomWaypointsBinding? = null
    private val binding get() = _binding!!

    private val waypointsVM: WaypointsViewModel by activityViewModels()
    private val aircraftVM: AircraftControlViewModel by activityViewModels()
    private val locationVM: LocationViewModel by activityViewModels()

    private lateinit var waypointAdapter: LocationAdapter
    private val locale = Locale("iw", "IL")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomWaypointsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        waypointsVM.loadWaypoints()
        locationVM.initProvider(this)
        locationVM.startLocation()

        setupAdapter()

        waypointsVM.locations.observe(viewLifecycleOwner) { }
    }

    private fun setupAdapter() {
        val savedWaypointNames = requireContext().getLocalizedResources(locale)
            .getStringArray(R.array.commands_mission_targets)

        waypointAdapter = LocationAdapter(
            viewLifecycleOwner,
            onFlyTo = { loc ->
                aircraftVM.controller?.fly {
                    flyToSticks(loc, maxVelocity = 8.0)
                }
            },
            onLookAt = { loc ->
                aircraftVM.controller?.fly {
                    if ((location.value?.distanceTo(loc) ?: 0.0) <= 1.0) return@fly
                    lookAtWithSpin(loc.as2D, 2.0)
                }
            },
            locationVM.deviceLocation, aircraftVM.aircraftLocation
        )

        binding.rvWaypointLocations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWaypointLocations.adapter = waypointAdapter

        lifecycleScope.launch {
            for (name in savedWaypointNames)
                waypointAdapter.set(name, null)
            val locations = waypointsVM.locations.value ?: emptyMap()
            for ((name, location) in locations)
                waypointAdapter.set(name, location)

            waypointAdapter.onLocationChanged = waypointsVM::updateWaypoint
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
