package com.kcg.dr.api

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.kcg.dr.NotificationVM
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragApiServerBinding

class ApiServerFragment : Fragment() {

    private var _binding: FragApiServerBinding? = null
    private val binding get() = _binding!!

    private val notificationVM: NotificationVM by activityViewModels()
    private val viewModel: ApiServerVM by viewModels()

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

        binding.switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.startServer(notificationVM.controllerChannelId)
            else viewModel.stopServer()
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
