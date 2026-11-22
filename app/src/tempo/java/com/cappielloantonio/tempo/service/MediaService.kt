package com.cappielloantonio.tempo.service

import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.Intent
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession.ControllerInfo
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DownloadUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.ReplayGainUtil
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@UnstableApi
class MediaService : MediaLibraryService(), SessionAvailabilityListener {
    private lateinit var automotiveRepository: AutomotiveRepository
    private lateinit var player: ExoPlayer
    private var playbackRecovery: PlaybackRecovery? = null
    private lateinit var castPlayer: CastPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var librarySessionCallback: MediaLibrarySessionCallback
    private val castExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isReleased = false

    override fun onCreate() {
        super.onCreate()

        initializeRepository()
        initializePlayer()
        playbackRecovery = PlaybackRecovery.attach(this, player)
        initializeCastPlayer()
        initializeMediaLibrarySession()
        initializePlayerListener()

        setPlayer(null, player)
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession.player

        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun initializeRepository() {
        automotiveRepository = AutomotiveRepository()
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
                .setRenderersFactory(getRenderersFactory())
                .setMediaSourceFactory(getMediaSourceFactory())
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(initializeLoadControl())
                .build()
    }

    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                        .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            // CastContext può lanciare eccezioni (es. Play Services in aggiornamento): un crash qui
            // in onCreate fa sparire l'app da Android Auto. Il cast è opzionale, quindi lo saltiamo.
            // Inizializzazione asincrona (getSharedInstance(Context) è deprecata): non blocca onCreate.
            try {
                CastContext.getSharedInstance(this, castExecutor)
                        .addOnSuccessListener { castContext -> onCastContextReady(castContext) }
                        .addOnFailureListener { e -> Log.w("MediaService", "Cast non disponibile", e) }
            } catch (e: Exception) {
                Log.w("MediaService", "Cast non disponibile", e)
            }
        }
    }

    private fun onCastContextReady(castContext: CastContext) {
        if (isReleased) return
        try {
            castPlayer = CastPlayer(castContext)
            castPlayer.setSessionAvailabilityListener(this)
            castPlayer.addListener(layoutListener)
            if (castPlayer.isCastSessionAvailable) onCastSessionAvailable()
        } catch (e: Exception) {
            Log.w("MediaService", "Cast non disponibile", e)
        }
    }

    private fun initializeMediaLibrarySession() {
        val sessionActivityPendingIntent =
                TaskStackBuilder.create(this).run {
                    addNextIntent(Intent(this@MediaService, MainActivity::class.java))
                    getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
                }

        mediaLibrarySession =
                MediaLibrarySession.Builder(this, player, createLibrarySessionCallback())
                        .setSessionActivity(sessionActivityPendingIntent)
                        .build()
    }

    private fun createLibrarySessionCallback(): MediaLibrarySession.Callback {
        librarySessionCallback = MediaLibrarySessionCallback(this, automotiveRepository)
        return librarySessionCallback
    }

    // Tiene sincronizzati i pulsanti shuffle/repeat in auto anche quando cambiano dal telefono
    private val layoutListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (this@MediaService::mediaLibrarySession.isInitialized) librarySessionCallback.updateCustomLayout(mediaLibrarySession)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (this@MediaService::mediaLibrarySession.isInitialized) librarySessionCallback.updateCustomLayout(mediaLibrarySession)
        }
    }

    private fun initializePlayerListener() {
        player.addListener(layoutListener)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    MediaManager.setLastPlayedTimestamp(mediaItem)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                ReplayGainUtil.setReplayGain(player, tracks)
                MediaManager.scrobble(player.currentMediaItem, false)

                if (player.currentMediaItemIndex + 1 == player.mediaItemCount)
                    MediaManager.continuousPlay(player.currentMediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    MediaManager.setPlayingPausedTimestamp(
                            player.currentMediaItem,
                            player.currentPosition
                    )
                } else {
                    MediaManager.scrobble(player.currentMediaItem, false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                if (!player.hasNextMediaItem() &&
                        playbackState == Player.STATE_ENDED &&
                        player.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC
                ) {
                    MediaManager.scrobble(player.currentMediaItem, true)
                    MediaManager.saveChronology(player.currentMediaItem)
                }
            }

            override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)

                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    if (oldPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.scrobble(oldPosition.mediaItem, true)
                        MediaManager.saveChronology(oldPosition.mediaItem)
                    }

                    if (newPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.setLastPlayedTimestamp(newPosition.mediaItem)
                    }
                } else if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                        oldPosition.mediaItemIndex != newPosition.mediaItemIndex &&
                        oldPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC &&
                        MediaManager.isPlayedEnough(oldPosition.mediaItem, oldPosition.positionMs)
                ) {
                    // Manually skipped but listened long enough: count it as a play
                    MediaManager.scrobble(oldPosition.mediaItem, true)
                    MediaManager.saveChronology(oldPosition.mediaItem)
                }
            }
        })
    }

    private fun initializeLoadControl(): DefaultLoadControl {
        val strategy = Preferences.getBufferingStrategy()
        val minBufferMs = (DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * strategy).toInt()
        val maxBufferMs = maxOf((DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * strategy).toInt(), minBufferMs)
        // Con "Minimo" il buffer era 5 s e per ripartire dopo un rebuffer servivano proprio 5 s:
        // il player poteva restare fermo in buffering finche' non si spostava il cursore.
        // Le soglie di avvio restano sotto la meta' del buffer minimo.
        return DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        minBufferMs,
                        maxBufferMs,
                        minOf(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, minBufferMs / 2),
                        minOf(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, minBufferMs / 2)
                )
                .build()
    }

    private fun setPlayer(oldPlayer: Player?, newPlayer: Player) {
        if (oldPlayer === newPlayer) return
        oldPlayer?.stop()
        mediaLibrarySession.player = newPlayer
    }

    private fun releasePlayer() {
        isReleased = true
        castExecutor.shutdown()
        if (this::castPlayer.isInitialized) castPlayer.setSessionAvailabilityListener(null)
        if (this::castPlayer.isInitialized) castPlayer.release()
        playbackRecovery?.release()
        player.release()
        mediaLibrarySession.release()
        automotiveRepository.deleteMetadata()
        clearListener()
    }

    private fun getRenderersFactory() = DownloadUtil.buildPlaybackRenderersFactory(this)

    private fun getMediaSourceFactory() =
            DefaultMediaSourceFactory(this).setDataSourceFactory(DownloadUtil.getDataSourceFactory(this))

    override fun onCastSessionAvailable() {
        setPlayer(player, castPlayer)
    }

    override fun onCastSessionUnavailable() {
        setPlayer(castPlayer, player)
    }
}