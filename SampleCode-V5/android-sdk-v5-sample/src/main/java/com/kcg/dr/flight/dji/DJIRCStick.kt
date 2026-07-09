package com.kcg.dr.flight.dji

import com.kcg.dr.flight.AircraftController.IRCState
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import kotlinx.coroutines.flow.MutableStateFlow

class DJIRCStick : IRCState {
    private val _stickValue = MutableStateFlow(VirtualStickVM.RCStickValue(0, 0, 0, 0))
    override val stickValue = _stickValue

    override suspend fun listen() {
        RemoteControllerKey.KeyStickLeftHorizontal.create().listen(this) {
            val lh = it ?: return@listen
            val sticks = stickValue.value
            _stickValue.value = sticks.copy(leftHorizontal = lh)
        }
        RemoteControllerKey.KeyStickLeftVertical.create().listen(this) {
            val lv = it ?: return@listen
            val sticks = stickValue.value
            _stickValue.value = sticks.copy(leftVertical = lv)
        }
        RemoteControllerKey.KeyStickRightHorizontal.create().listen(this) {
            val rh = it ?: return@listen
            val sticks = stickValue.value
            _stickValue.value = sticks.copy(rightHorizontal = rh)
        }
        RemoteControllerKey.KeyStickRightVertical.create().listen(this) {
            val rv = it ?: return@listen
            val sticks = stickValue.value
            _stickValue.value = sticks.copy(rightVertical = rv)
        }
    }

    override suspend fun stopListening() {
        RemoteControllerKey.KeyStickLeftHorizontal.create().cancelListen(this)
        RemoteControllerKey.KeyStickLeftVertical.create().cancelListen(this)
        RemoteControllerKey.KeyStickRightHorizontal.create().cancelListen(this)
        RemoteControllerKey.KeyStickRightVertical.create().cancelListen(this)
    }
}