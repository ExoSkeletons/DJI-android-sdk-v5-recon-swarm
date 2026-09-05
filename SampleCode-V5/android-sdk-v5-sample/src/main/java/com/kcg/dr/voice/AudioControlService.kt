package com.kcg.dr.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.media.session.MediaButtonReceiver
import com.kcg.dr.flight.AircraftController
import com.aviadl40.utils.android.startAsForeground
import dji.sampleV5.aircraft.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AudioControlService : Service() {
    companion object {
        const val NOTIFICATION_ID = 87506

        private val _mediaButtonPresses = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val mediaButtonPresses = _mediaButtonPresses.asSharedFlow()
    }

    private var mediaSession: MediaSessionCompat? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        initSilentPlayer()
    }

    private fun initSilentPlayer() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.sfx_still_alive).apply {
                setVolume(0f, 0f)
                isLooping = true
                start()
            }
            Log.d("AudioControlService", "Silent player started")
        } catch (e: Exception) {
            Log.e("AudioControlService", "Failed to start silent player", e)
        }
    }

    override fun onDestroy() {
        abandonAudioFocus()
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
        val action = intent?.action
        Log.d("AudioControlService", "onStartCommand: action=$action")
        startAsForeground(NOTIFICATION_ID, createNotification())

        if (mediaSession == null) {
            setupMediaSession(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val event = mediaButtonEvent?.let {
                        IntentCompat.getParcelableExtra(
                            it,
                            Intent.EXTRA_KEY_EVENT,
                            KeyEvent::class.java
                        )
                    } ?: return super.onMediaButtonEvent(mediaButtonEvent)

                    Log.i("AudioControlService", "onMediaButtonEvent: $event")
                    return handleKeyEvent(event) || super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
        }

        requestAudioFocus()

        if (Intent.ACTION_MEDIA_BUTTON == action) {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            event?.let {
                Log.i("AudioControlService", "Handling media button intent: $it")
                handleKeyEvent(it)
            }
        }
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        return START_NOT_STICKY
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_RECORD,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    Log.i("AudioControlService", "Media button press detected: ${event.keyCode}")
                    _mediaButtonPresses.tryEmit(Unit)
                    return true
                }
            }
        }
        return false
    }

    private fun requestAudioFocus() {
        Log.d("AudioControlService", "requesting audio focus...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null)
                audioFocusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setOnAudioFocusChangeListener { }
                        .build()
            audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
        } else
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                {},
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        Log.i("AudioControlService", "audio focus requested")
    }

    private fun abandonAudioFocus() {
        Log.i("AudioControlService", "abandoning audio focus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        else
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus {}
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun setupMediaSession(callback: MediaSessionCompat.Callback? = null) {
        Log.d("AudioControlService", "setting up media session")
        val mbrIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = ComponentName(this@AudioControlService, MediaButtonReceiver::class.java)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AircraftController.TAG).apply {
            setContentTitle("Media Controller")
            setContentText("Press middle button to start Voice Recognition")
            setSmallIcon(R.drawable.ic_mic_white_36dp)
            setOngoing(true)
        }.build()
    }
}
