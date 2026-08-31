package com.kcg.dr.api.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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

        viewModel.isServerRunning.observe(viewLifecycleOwner) { isRunning ->
            binding.switchServer.isChecked = isRunning
            binding.tvServerStatus.setText(
                if (isRunning) R.string.actionbar_item_status_on
                else R.string.actionbar_item_status_off
            )
            binding.layoutHeader.setBackgroundResource(
                if (isRunning) R.drawable.uxsdk_gradient_good
                else R.drawable.uxsdk_gradient_offline
            )
            binding.tvLogsRest.alpha = if (isRunning) 1f else 0.5f
            binding.tvLogsWs.alpha = if (isRunning) 1f else 0.5f
        }
        viewModel.tunnelingUrl.observe(viewLifecycleOwner) {
            binding.tvTunnelingUrl.text = it
        }
        binding.tvTunnelingUrl.setOnClickListener {
            val urlText = binding.tvTunnelingUrl.text?.toString()
            if (urlText.isNullOrBlank()) return@setOnClickListener
            val clipboard = requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("url", urlText))
            Toast.makeText(requireContext(), "URL copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        viewModel.serverLogs.observe(viewLifecycleOwner) { latest ->
            binding.tvLogsRest.text =
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
