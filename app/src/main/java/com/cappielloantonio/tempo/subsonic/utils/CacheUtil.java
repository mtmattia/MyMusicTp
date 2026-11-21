package com.cappielloantonio.tempo.subsonic.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.cappielloantonio.tempo.App;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;

public class CacheUtil {
    private int maxAge; // 60 seconds
    private int maxStale; // 60 * 60 * 24 * 30 = 30 days (60 seconds * 60 minutes * 24 hours * 30 days)

    public CacheUtil(int maxAge, int maxStale) {
        this.maxAge = maxAge;
        this.maxStale = maxStale;
    }

    public Interceptor onlineInterceptor = chain -> {
        okhttp3.Response response = chain.proceed(chain.request());
        return response.newBuilder()
                .header("Cache-Control", "public, max-age=" + maxAge)
                .removeHeader("Pragma")
                .build();
    };

    public Interceptor offlineInterceptor = chain -> {
        Request request = chain.request();

        if (!hasNetwork()) {
            return chain.proceed(toCacheOnly(request));
        }

        try {
            return chain.proceed(request);
        } catch (IOException e) {
            // Network present but the server is unreachable (network just switched,
            // validation pending, dead socket...): serve the cached copy if there is one,
            // otherwise surface the original error so callers can react to it.
            if (!"GET".equals(request.method())) throw e;

            okhttp3.Response cached = chain.proceed(toCacheOnly(request));
            if (cached.code() == 504) {
                cached.close();
                throw e;
            }
            return cached;
        }
    };

    private Request toCacheOnly(Request request) {
        return request.newBuilder()
                .header("Cache-Control", "public, only-if-cached, max-stale=" + maxStale)
                .removeHeader("Pragma")
                .build();
    }

    // Only INTERNET is required: VALIDATED is missing for a few seconds after every network
    // change and permanently on LAN-only/VPN setups, which used to force cache-only requests
    // (empty screens) even though the server was reachable.
    private boolean hasNetwork() {
        ConnectivityManager connectivityManager = (ConnectivityManager) App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            Network network = connectivityManager.getActiveNetwork();

            if (network != null) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
        }

        return false;
    }
}
