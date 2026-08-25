package com.kcg.dr.api

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.kcg.dr.flight.AircraftControlVM
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserVM
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragApiServerBinding

class ApiServerFragment : Fragment() {

    private var _binding: FragApiServerBinding? = null
    private val binding get() = _binding!!

    private val controllerVM: AircraftControlVM by activityViewModels()
    private val userVM: UserVM by activityViewModels()

    private val viewModel: ApiServerVM by activityViewModels(
        {
            MutableCreationExtras(defaultViewModelCreationExtras).apply {
                set(ApiServerVM.CONTROLLER_KEY, controllerVM.controller)
                set(ApiServerVM.USER_KEY, userVM.metrics)
            }
        },
        { ApiServerVM.Factory }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragApiServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchServer.setOnClickListener {
            val isChecked = binding.switchServer.isChecked
            if (isChecked) viewModel.startService(AircraftController.TAG)
            else viewModel.stopService()
        }

        viewModel.isServerRunning.observe(viewLifecycleOwner) {
            binding.switchServer.isChecked = it
            binding.tvServerStatus.text = when (it) {
                true -> "API Server: Running"
                else -> "API Server: Stopped"
            }
            binding.ivServerIcon.setImageResource(
                if (it) R.drawable.ic_media_play
                else R.drawable.ic_media_stop
            )
            binding.ivServerIcon.setColorFilter(
                if (it) resources.getColor(android.R.color.holo_green_light, null)
                else resources.getColor(android.R.color.white, null)
            )
            binding.tvLogs.alpha = if (it) 1f else 0.5f
        }
        viewModel.tunnelingUrl.observe(viewLifecycleOwner) {
            binding.tvTunnelingUrl.text = it
        }
        viewModel.serverLogs.observe(viewLifecycleOwner) { latest ->
            binding.tvLogs.text =
                if (latest?.isEmpty() ?: true) "Waiting for requests..."
                else latest
        }
        viewModel.wsLogs.observe(viewLifecycleOwner) { latest ->
            binding.tvLogsWs.text =
                if (latest?.isEmpty() ?: true) "Waiting for WS frames..."
                else latest
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
