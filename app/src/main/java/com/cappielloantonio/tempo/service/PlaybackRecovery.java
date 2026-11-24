package com.cappielloantonio.tempo.service;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.List;

/**
 * Fa ripartire la musica dopo le interruzioni del navigatore.
 *
 * 1. Alcuni navigatori/assistenti chiedono l'audio focus in modo permanente
 *    (AUDIOFOCUS_LOSS) invece che temporaneo: ExoPlayer allora mette in pausa e non
 *    riprende mai da solo. Qui, se a prendere il focus è stata una voce di navigazione
 *    o dell'assistente, riprendiamo quando ha finito di parlare.
 * 2. Se durante la pausa lo stream cade (errore di rete), il player resta fermo in
 *    errore: riproviamo a preparare dalla stessa posizione.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackRecovery implements Player.Listener {
    private static final String TAG = "PlaybackRecovery";

    /** Oltre questo tempo dalla perdita del focus non riprendiamo più da soli. */
    private static final long RESUME_WINDOW_MS = 3 * 60 * 1000;
    private static final int MAX_NETWORK_RETRIES = 3;

    private final ExoPlayer player;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long focusLostAt = 0;
    private boolean sawGuidance = false;
    private boolean sawOtherMedia = false;
    private int networkRetries = 0;

    private Object playbackCallback;
    private final Runnable stopWatchingRunnable = this::stopWatching;

    private PlaybackRecovery(Context context, ExoPlayer player) {
        this.player = player;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public static PlaybackRecovery attach(Context context, ExoPlayer player) {
        PlaybackRecovery recovery = new PlaybackRecovery(context.getApplicationContext(), player);
        player.addListener(recovery);
        return recovery;
    }

    public void release() {
        player.removeListener(this);
        stopWatching();
        handler.removeCallbacksAndMessages(null);
    }

    // ---- 1. Audio focus perso in modo permanente ----

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
            Log.i(TAG, "Audio focus perso: controllo se è il navigatore");
            focusLostAt = SystemClock.elapsedRealtime();
            sawGuidance = false;
            sawOtherMedia = false;
            startWatching();
        } else if (playWhenReady) {
            stopWatching();
        }
    }

    private void startWatching() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || audioManager == null) return;
        stopWatching();
        Api26.register(this);
        handler.postDelayed(stopWatchingRunnable, RESUME_WINDOW_MS);
    }

    private void stopWatching() {
        handler.removeCallbacks(stopWatchingRunnable);
        if (playbackCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Api26.unregister(this);
        }
        playbackCallback = null;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        boolean guidanceActive = false;
        boolean otherMediaActive = false;

        for (AudioPlaybackConfiguration config : configs) {
            int usage = config.getAudioAttributes().getUsage();
            if (isGuidance(usage)) {
                guidanceActive = true;
            } else if (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME) {
                otherMediaActive = true;
            }
        }

        sawGuidance |= guidanceActive;
        sawOtherMedia |= otherMediaActive;

        // Ha parlato il navigatore, ora tace e nessun'altra app sta suonando musica.
        if (sawGuidance && !sawOtherMedia && !guidanceActive && !otherMediaActive
                && SystemClock.elapsedRealtime() - focusLostAt < RESUME_WINDOW_MS
                && !player.getPlayWhenReady() && player.getMediaItemCount() > 0) {
            Log.i(TAG, "Il navigatore ha finito: riprendo la riproduzione");
            stopWatching();
            player.play();
        }
    }

    private static boolean isGuidance(int usage) {
        return usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                || usage == AudioAttributes.USAGE_ASSISTANT
                || usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                || usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static final class Api26 {
        static void register(PlaybackRecovery r) {
            AudioManager.AudioPlaybackCallback cb = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    r.onPlaybackConfigChanged(configs);
                }
            };
            r.playbackCallback = cb;
            r.audioManager.registerAudioPlaybackCallback(cb, r.handler);
        }

        static void unregister(PlaybackRecovery r) {
            r.audioManager.unregisterAudioPlaybackCallback((AudioManager.AudioPlaybackCallback) r.playbackCallback);
        }
    }

    // ---- 2. Stream caduto durante la pausa ----

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        boolean network = error.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                && error.errorCode < PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
        if (!network || networkRetries >= MAX_NETWORK_RETRIES) return;

        networkRetries++;
        long delay = 2000L * networkRetries;
        Log.w(TAG, "Errore di rete (" + error.getErrorCodeName() + "), nuovo tentativo tra " + delay + " ms");
        handler.postDelayed(() -> {
            if (player.getPlayerError() != null) {
                player.prepare();
            }
        }, delay);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying) networkRetries = 0;
    }
}
