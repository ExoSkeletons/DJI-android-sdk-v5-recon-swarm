package com.kcg.dr.flight

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.databinding.FragVocomVirtualStickBinding
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystick
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystickListener
import dji.v5.manager.aircraft.virtualstick.Stick
import kotlin.math.abs

class VirtualStickControlsFragment : Fragment() {
    private var _binding: FragVocomVirtualStickBinding? = null
    private val binding get() = _binding!!

    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val aircraftVM: AircraftControlViewModel by activityViewModels()

    private val deviation: Double = 0.02

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragVocomVirtualStickBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.leftStickView.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                val px = if (abs(pX) >= deviation) pX else 0f
                val py = if (abs(pY) >= deviation) pY else 0f
                virtualStickVM.setLeftPosition(
                    (px * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (py * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })

        // todo: add ui for enable/disable virtual stick mode, we forgot that lol
        //  future idea- create custom vm that combines both RC input and virtual stick input (with modes? merge/override?)

        binding.rightStickView.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                val px = if (abs(pX) >= deviation) pX else 0f
                val py = if (abs(pY) >= deviation) pY else 0f
                virtualStickVM.setRightPosition(
                    (px * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (py * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })

        binding.btnTakeOff.setOnClickListener {
            aircraftVM.controller.fly { takeoff() }
        }

        binding.btnLanding.setOnClickListener {
            aircraftVM.controller.fly { land() }
        }

        binding.btnStop.setOnClickListener {
            aircraftVM.controller.stop()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
