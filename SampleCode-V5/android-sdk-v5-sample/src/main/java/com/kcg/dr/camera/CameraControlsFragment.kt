package com.kcg.dr.camera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.databinding.FragVocomCameraControlsBinding
import dji.sampleV5.aircraft.models.CameraControlsVM
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

class CameraControlsFragment : Fragment() {
    private var _binding: FragVocomCameraControlsBinding? = null
    private val binding get() = _binding!!

    private val cameraVM: CameraControlsVM by activityViewModels()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomCameraControlsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initRecordingControls()
    }

    private fun initRecordingControls() {
        // todo: add ui for camera index & scale type

        binding.btnStartRecordVideo.setOnClickListener {
            cameraVM.cameraIndex.postValue(ComponentIndexType.LEFT_OR_MAIN)
            cameraVM.startRecord(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(p0: EmptyMsg?) {}
                override fun onFailure(error: IDJIError) {}
            })
        }

        binding.btnStopRecordVideo.setOnClickListener {
            if (cameraVM.isRecording.value == true)
                cameraVM.stopRecord(null)
        }

        cameraVM.isRecording.observe(viewLifecycleOwner) { recording ->
            binding.tvVideoRecordingStatus.text = "Recording: ${recording ?: false}"
            binding.btnStartRecordVideo.isEnabled = !(recording ?: false)
            binding.btnStopRecordVideo.isEnabled = (recording ?: false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraVM.stopRecord()
        _binding = null
    }
}
