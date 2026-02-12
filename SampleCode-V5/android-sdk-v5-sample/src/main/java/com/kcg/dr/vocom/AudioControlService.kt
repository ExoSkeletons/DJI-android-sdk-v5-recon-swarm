package com.kcg.dr.vocom

import android.app.Notification
import android.app.Notification.EXTRA_CHANNEL_ID
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.media.session.MediaButtonReceiver
import com.kcg.dr.ServiceUtils.startAsForeground
import dji.sampleV5.aircraft.R


class AudioControlService() : Service() {
    val NOTIFICATION_ID = 87506

    private var channelId: String = ""

    private var mediaSession: MediaSessionCompat?=null
    private var mediaPlayer: MediaPlayer?=null


    override fun onDestroy() {
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaSession?.apply {
            isActive = false
            release()
        }
        super.onDestroy()
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID) ?: channelId

        startAsForeground(NOTIFICATION_ID, createNotification(channelId))

        setupMediaSession(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val event = mediaButtonEvent?.let {
                    IntentCompat.getParcelableExtra(
                        it,
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java
                    )
                }

                if (event?.action == KeyEvent.ACTION_DOWN) {
                    // ToastUtils.showToast("clicked ${event.keyCode}!")
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_RECORD,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            sendBroadcast(Intent().apply {
                                action = ACTION_START_VOICE_RECOGNITION
                            })
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
        // requestAudioFocus()
        // startSilentAudio()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null


    private fun setupMediaSession(callback: MediaSessionCompat.Callback? = null) {
        // Explicitly create the PendingIntent with the required FLAG_IMMUTABLE
        val mbrIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = ComponentName(this@AudioControlService, MediaButtonReceiver::class.java)
        }
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        val pIntent = PendingIntent.getBroadcast(this, 0, mbrIntent, flags)

        mediaSession = MediaSessionCompat(this, "AudioControlService", null, pIntent).apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .build()
            )
            callback?.let { setCallback(it) }
            isActive = true
        }
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest =
                android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun startSilentAudio() {
        mediaPlayer = MediaPlayer.create(this, R.raw.sfx_still_alive).apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            setVolume(0f, 0f)
            start()
        }
    }


    private fun createNotification(channelId: String): Notification {
        // Create notification
        val notification: Notification =
            NotificationCompat.Builder(this, channelId).apply {
                setContentTitle("Media Controller")
                setContentText("Press middle button to start Voice Recognition")
                setSmallIcon(R.drawable.ic_mic_white_36dp)
                setOngoing(true)
            }.build()
        return notification
    }

    companion object {
        const val ACTION_START_VOICE_RECOGNITION = "ACTION_START_VOICE_RECOGNITION"
    }
}