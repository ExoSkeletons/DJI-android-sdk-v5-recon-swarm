package com.kcg.dr.api

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.kcg.dr.NotificationVM
import com.kcg.dr.flight.AircraftControlViewModel
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragApiServerBinding
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import kotlinx.coroutines.launch

class ApiServerFragment : Fragment() {

    private var _binding: FragApiServerBinding? = null
    private val binding get() = _binding!!

    // controller vms
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val controllerVM: AircraftControlViewModel by activityViewModels()

    private val notificationVM: NotificationVM by activityViewModels()
    private val viewModel: ApiServerVM by activityViewModels()

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

        lifecycleScope.launch {
            controllerVM.init(
                basicAircraftControlVM,
                virtualStickVM,
            )
            viewModel.initController(controllerVM.controller)
        }

        binding.switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.startService(notificationVM.controllerChannelId)
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
        viewModel.serverLogs.observe(viewLifecycleOwner) { logs ->
            binding.tvLogs.text =
                if (logs.isEmpty()) "Waiting for requests..."
                else logs.joinToString("\n")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
