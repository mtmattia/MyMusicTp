package com.cappielloantonio.tempo.util;

import androidx.annotation.NonNull;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.view.CoverSlideshowView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Copertine per i riquadri animati di decenni e generi: alcuni album del decennio o
 * brani del genere, in ordine casuale. Le ricorda, così lo scorrimento non rifà le richieste.
 */
public final class TileCoverLoader {
    private static final int MAX_COVERS = 6;
    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    private TileCoverLoader() {
    }

    public static void loadDecade(CoverSlideshowView view, int decade) {
        load(view, "decade:" + decade, () -> App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getAlbumList2("byYear", 30, 0, decade, decade + 9), response -> {
            List<String> ids = new ArrayList<>();
            List<AlbumID3> albums = response.getSubsonicResponse().getAlbumList2() != null
                    ? response.getSubsonicResponse().getAlbumList2().getAlbums() : null;
            if (albums != null) for (AlbumID3 a : albums) ids.add(a.getCoverArtId());
            return ids;
        });
    }

    public static void loadGenre(CoverSlideshowView view, String genre) {
        load(view, "genre:" + genre, () -> App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getSongsByGenre(genre, 60, 0), response -> {
            List<String> ids = new ArrayList<>();
            List<Child> songs = response.getSubsonicResponse().getSongsByGenre() != null
                    ? response.getSubsonicResponse().getSongsByGenre().getSongs() : null;
            if (songs != null) for (Child c : songs) ids.add(c.getCoverArtId());
            return ids;
        });
    }

    private interface CallFactory {
        Call<ApiResponse> create();
    }

    private interface Extractor {
        List<String> coverIds(ApiResponse response);
    }

    private static void load(CoverSlideshowView view, String key, CallFactory call, Extractor extractor) {
        view.setTag(R.id.tile_cover_key, key);

        List<String> cached = CACHE.get(key);
        if (cached != null) {
            view.setCovers(cached);
            return;
        }
        view.clear();

        call.create().enqueue(new Callback<ApiResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse> c, @NonNull Response<ApiResponse> response) {
                List<String> ids = new ArrayList<>();
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        Set<String> unique = new LinkedHashSet<>();
                        for (String id : extractor.coverIds(response.body())) {
                            if (id != null && !id.isEmpty()) unique.add(id);
                        }
                        ids.addAll(unique);
                        Collections.shuffle(ids);
                        if (ids.size() > MAX_COVERS) ids = new ArrayList<>(ids.subList(0, MAX_COVERS));
                    } catch (Exception ignored) {
                    }
                }
                CACHE.put(key, ids);
                // La vista nel frattempo può essere stata riciclata per un altro elemento
                if (key.equals(view.getTag(R.id.tile_cover_key))) view.setCovers(ids);
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse> c, @NonNull Throwable t) {
            }
        });
    }
}
