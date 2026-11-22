package com.cappielloantonio.tempo.util;

import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Copertina per i riquadri di decenni e generi: la prende da un album del decennio
 * o da un brano del genere e la ricorda, così lo scorrimento non rifà le richieste.
 */
public final class TileCoverLoader {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final String NONE = "";

    private TileCoverLoader() {
    }

    public static void loadDecade(ImageView view, int decade) {
        String key = "decade:" + decade;
        load(view, key, () -> App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getAlbumList2("byYear", 1, 0, decade, decade + 9), response -> {
            List<AlbumID3> albums = response.getSubsonicResponse().getAlbumList2() != null
                    ? response.getSubsonicResponse().getAlbumList2().getAlbums() : null;
            return albums != null && !albums.isEmpty() ? albums.get(0).getCoverArtId() : null;
        });
    }

    public static void loadGenre(ImageView view, String genre) {
        String key = "genre:" + genre;
        load(view, key, () -> App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getSongsByGenre(genre, 1, 0), response -> {
            List<Child> songs = response.getSubsonicResponse().getSongsByGenre() != null
                    ? response.getSubsonicResponse().getSongsByGenre().getSongs() : null;
            return songs != null && !songs.isEmpty() ? songs.get(0).getCoverArtId() : null;
        });
    }

    private interface CallFactory {
        Call<ApiResponse> create();
    }

    private interface Extractor {
        String coverId(ApiResponse response);
    }

    private static void load(ImageView view, String key, CallFactory call, Extractor extractor) {
        view.setTag(com.cappielloantonio.tempo.R.id.tile_cover_key, key);
        view.setImageDrawable(null);

        String cached = CACHE.get(key);
        if (cached != null) {
            show(view, key, cached);
            return;
        }

        call.create().enqueue(new Callback<ApiResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse> c, @NonNull Response<ApiResponse> response) {
                String id = null;
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        id = extractor.coverId(response.body());
                    } catch (Exception ignored) {
                    }
                }
                CACHE.put(key, id != null ? id : NONE);
                show(view, key, id != null ? id : NONE);
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse> c, @NonNull Throwable t) {
            }
        });
    }

    private static void show(ImageView view, String key, String coverId) {
        // La vista nel frattempo può essere stata riciclata per un altro elemento
        if (!key.equals(view.getTag(com.cappielloantonio.tempo.R.id.tile_cover_key)) || coverId.isEmpty()) return;
        CustomGlideRequest.Builder
                .from(view.getContext(), coverId, CustomGlideRequest.ResourceType.Album)
                .build()
                .into(view);
    }
}
