package com.kcg.dr.waypoints

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.kcg.dr.flight.AircraftControlVM
import com.kcg.dr.location.LiveLocationProvider
import com.kcg.dr.utils.ResourcesManager
import com.kcg.dr.location.UserVM
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import com.kcg.dr.utils.LocationUtils.distanceTo
import com.kcg.dr.utils.as2D
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVocomWaypointsBinding
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.launch
import java.util.Locale

class WaypointsFragment : Fragment() {
    private var _binding: FragVocomWaypointsBinding? = null
    private val binding get() = _binding!!

    private val waypointsVM: WaypointsVM by activityViewModels()
    private val aircraftVM: AircraftControlVM by activityViewModels()
    private val userVM: UserVM by activityViewModels()

    private lateinit var waypointAdapter: LocationAdapter
    private lateinit var liveLocationProvider: LiveLocationProvider
    private val locale: Locale get() = ResourcesManager.locale

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

        ResourcesManager.setLocale(requireContext(), Locale("he", "IL"))

        waypointsVM.loadWaypoints()

        setupLocationProvider()
        setupAdapter()

        userVM.location.observe(viewLifecycleOwner) { }
        waypointsVM.locations.observe(viewLifecycleOwner) { }
    }

    private fun setupLocationProvider() {
        liveLocationProvider = LiveLocationProvider(
            this,
            200, 50,
            500,
            Priority.PRIORITY_HIGH_ACCURACY
        ).apply {
            init(requireContext())
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) =
                    userVM.location.postValue(locationResult.lastLocation?.let {
                        LocationCoordinate3D().apply {
                            latitude = it.latitude
                            longitude = it.longitude
                            altitude = it.altitude
                        }
                    })
            }
            startRequesting()
        }
    }

    private fun setupAdapter() {
        val savedWaypointNames = requireContext().getLocalizedResources(locale)
            .getStringArray(R.array.commands_mission_targets)

        waypointAdapter = LocationAdapter(
            viewLifecycleOwner,
            onFlyTo = { loc ->
                aircraftVM.controller.fly {
                    flyToSticks(loc, maxVelocity = 8.0)
                }
            },
            onLookAt = { loc ->
                aircraftVM.controller.fly {
                    if ((ac.location.value?.distanceTo(loc) ?: 0.0) <= 1.0) return@fly
                    lookAtWithSpin(loc.as2D, 2.0)
                }
            },
            userVM.standingLocation, aircraftVM.aircraftLocation
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
        liveLocationProvider.stopRequesting()
    }
}
