package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

/**
 * Notifica i cambi della rete di default. Sostituisce il broadcast deprecato
 * {@code ConnectivityManager.CONNECTIVITY_ACTION}. Il listener viene chiamato sul main thread.
 */
public class ConnectivityStatusMonitor {
    public interface Listener {
        void onConnectivityChanged(boolean connected);
    }

    private final ConnectivityManager connectivityManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private boolean registered = false;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            mainHandler.post(() -> {
                if (registered) listener.onConnectivityChanged(true);
            });
        }

        @Override
        public void onLost(@NonNull Network network) {
            mainHandler.post(() -> {
                // La rete di default persa può essere già stata sostituita da un'altra.
                if (registered && connectivityManager.getActiveNetwork() == null) {
                    listener.onConnectivityChanged(false);
                }
            });
        }
    };

    public ConnectivityStatusMonitor(Context context, Listener listener) {
        this.connectivityManager = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public void register() {
        if (registered || connectivityManager == null) return;
        registered = true;
        connectivityManager.registerDefaultNetworkCallback(networkCallback);

        // Il vecchio broadcast "sticky" segnalava subito l'assenza di rete: replichiamo quel comportamento.
        if (connectivityManager.getActiveNetwork() == null) {
            mainHandler.post(() -> {
                if (registered) listener.onConnectivityChanged(false);
            });
        }
    }

    public void unregister() {
        if (!registered || connectivityManager == null) return;
        registered = false;
        mainHandler.removeCallbacksAndMessages(null);
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (IllegalArgumentException ignored) {
            // callback già rimosso
        }
    }
}
