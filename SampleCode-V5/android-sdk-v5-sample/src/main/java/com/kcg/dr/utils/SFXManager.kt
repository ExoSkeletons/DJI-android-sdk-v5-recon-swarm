package com.kcg.dr.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dji.sampleV5.aircraft.R

object SFXManager {
    enum class SFX(val resId: Int, var soundId: Int? = null) {
        NOTIFY_STILL_ALIVE(R.raw.sfx_still_alive),
        NOTIFY_INFO(R.raw.sfx_notif_general),
        NOTIFY_TECHNICAL(R.raw.sfx_notif_technical),
        ACTION_CONFIRM(R.raw.sfx_action_confirm)
    }

    private lateinit var soundPool: SoundPool

    fun init(context: Context) {
        val context = context.applicationContext
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setAudioAttributes(audioAttributes)
            .setMaxStreams(1)
            .build()

        SFX.values().forEach {
            it.soundId = soundPool.load(context, it.resId, 1)
        }
    }

    fun release() {
        soundPool.release()
    }

    fun playSfx(sound: SFX) {
        sound.soundId?.let {
            soundPool.play(it, 1f, 1f, 1, 0, 1f)
        }
    }
}