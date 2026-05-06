package com.kcg.dr.camera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.databinding.FragVocomCameraFeedBinding
import dji.sampleV5.aircraft.models.CameraActionVM
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

class CameraFeedFragment : Fragment() {
    private var _binding: FragVocomCameraFeedBinding? = null
    private val binding get() = _binding!!

    private val cameraVM: CameraActionVM by activityViewModels()
    
    private var cameraStreamSurface: Surface? = null
    private var cameraStreamWidth: Int = -1
    private var cameraStreamHeight: Int = -1
    private var cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    private val cameraStreamScaleType: ICameraStreamManager.ScaleType = ICameraStreamManager.ScaleType.CENTER_INSIDE
    private val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragVocomCameraFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraStreamSurface()
        initRecordingControls()
    }

    private fun initCameraStreamSurface() {
        binding.svCameraStream.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                cameraStreamSurface = holder.surface
                if (cameraStreamWidth != -1 && cameraStreamHeight != -1) {
                    putCameraStreamSurface()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                cameraStreamWidth = width
                cameraStreamHeight = height
                cameraStreamSurface = holder.surface
                putCameraStreamSurface()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (cameraStreamSurface != null) {
                    cameraStreamManager.removeCameraStreamSurface(holder.surface)
                }
                cameraStreamSurface = null
            }
        })
    }

    private fun putCameraStreamSurface() {
        if (cameraStreamSurface == null || cameraStreamWidth == -1 || cameraStreamHeight == -1) return
        cameraStreamManager.putCameraStreamSurface(
            cameraIndex,
            cameraStreamSurface!!,
            cameraStreamWidth,
            cameraStreamHeight,
            cameraStreamScaleType
        )
    }

    private fun initRecordingControls() {
        binding.btnStartRecordVideo.setOnClickListener {
            cameraVM.setCameraIndex(cameraIndex)
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
