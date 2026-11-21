package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Builds "similar but varied" mixes from a seed track, combining server
 * similarity (getSimilarSongs2), same genre, same artist top songs and a bit
 * of random discovery, avoiding tracks heard recently.
 */
public final class SmartMix {
    private static final String PREFS = "smart_mix";
    private static final String KEY_HISTORY = "history";
    private static final int HISTORY_MAX = 300;
    private static final Random RANDOM = new Random();

    private static final MixSelector.Key<Child> KEY = new MixSelector.Key<Child>() {
        @Override
        public String id(Child item) {
            return item.getId();
        }

        @Override
        public String artist(Child item) {
            return item.getArtist();
        }
    };

    public interface Callback0 {
        void onResult(List<Child> songs);
    }

    private SmartMix() {
    }

    /** Seed known only by id: resolve artist/genre first. */
    public static void fromSongId(String id, int count, Callback0 cb) {
        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getSong(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        Child song = response.isSuccessful() && response.body() != null
                                ? response.body().getSubsonicResponse().getSong() : null;
                        build(id, song != null ? song.getArtist() : null, song != null ? song.getGenre() : null, count, Collections.emptySet(), cb);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        build(id, null, null, count, Collections.emptySet(), cb);
                    }
                });
    }

    public static void fromMediaItem(MediaItem item, int count, Collection<String> exclude, Callback0 cb) {
        Bundle extras = item.mediaMetadata.extras;
        String id = extras != null && extras.getString("id") != null ? extras.getString("id") : item.mediaId;
        String artist = extras != null ? extras.getString("artist") : null;
        String genre = extras != null ? extras.getString("genre") : null;
        build(id, artist, genre, count, exclude, cb);
    }

    public static void build(String seedId, @Nullable String artist, @Nullable String genre, int count,
                             Collection<String> exclude, Callback0 cb) {
        MixSelector<Child> selector = new MixSelector<>(RANDOM);
        String firstGenre = firstGenre(genre);
        int pool = Math.max(30, count * 4);

        List<Call<ApiResponse>> calls = new ArrayList<>();
        List<Function<SubsonicResponse, List<Child>>> extract = new ArrayList<>();
        List<Double> weights = new ArrayList<>();

        calls.add(App.getSubsonicClientInstance(false).getBrowsingClient().getSimilarSongs2(seedId, pool));
        extract.add(r -> r.getSimilarSongs2() != null ? r.getSimilarSongs2().getSongs() : null);
        weights.add(MixSelector.W_SIMILAR);

        if (firstGenre != null) {
            calls.add(App.getSubsonicClientInstance(false).getAlbumSongListClient().getSongsByGenre(firstGenre, 150, 0));
            // shuffle + cap: a different slice of the genre every time
            extract.add(r -> r.getSongsByGenre() != null ? shuffled(r.getSongsByGenre().getSongs(), 40) : null);
            weights.add(MixSelector.W_GENRE);
        }

        if (!TextUtils.isEmpty(artist)) {
            calls.add(App.getSubsonicClientInstance(false).getBrowsingClient().getTopSongs(artist, 20));
            extract.add(r -> r.getTopSongs() != null ? r.getTopSongs().getSongs() : null);
            weights.add(MixSelector.W_ARTIST_TOP);
        }

        calls.add(App.getSubsonicClientInstance(false).getAlbumSongListClient().getRandomSongs(20, null, null));
        extract.add(r -> r.getRandomSongs() != null ? r.getRandomSongs().getSongs() : null);
        weights.add(MixSelector.W_RANDOM);

        final int[] pending = {calls.size()};
        Runnable done = () -> {
            if (--pending[0] > 0) return;
            Set<String> ex = new HashSet<>(exclude);
            ex.add(seedId);
            List<Child> result = selector.pick(count, getHistory(), ex, artist);
            markPlayed(result);
            cb.onResult(result);
        };

        for (int i = 0; i < calls.size(); i++) {
            final Function<SubsonicResponse, List<Child>> ext = extract.get(i);
            final double w = weights.get(i);
            calls.get(i).enqueue(new Callback<ApiResponse>() {
                @Override
                public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                    try {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse() != null) {
                            List<Child> songs = ext.apply(response.body().getSubsonicResponse());
                            selector.addSource(songs, KEY, w);
                        }
                    } finally {
                        done.run();
                    }
                }

                @Override
                public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                    done.run();
                }
            });
        }
    }

    // ---- listening history (local, per device) ----

    private static SharedPreferences prefs() {
        return App.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Most recent first. */
    public static synchronized List<String> getHistory() {
        String raw = prefs().getString(KEY_HISTORY, "");
        if (TextUtils.isEmpty(raw)) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    public static synchronized void markPlayed(String id) {
        if (TextUtils.isEmpty(id)) return;
        markPlayedIds(Collections.singletonList(id));
    }

    private static void markPlayed(List<Child> songs) {
        List<String> ids = new ArrayList<>();
        for (Child c : songs) ids.add(c.getId());
        // queued tracks count as "recent" so the next mix doesn't repeat them
        Collections.reverse(ids);
        markPlayedIds(ids);
    }

    private static synchronized void markPlayedIds(List<String> ids) {
        List<String> h = getHistory();
        for (String id : ids) {
            if (TextUtils.isEmpty(id) || id.contains(",")) continue;
            h.remove(id);
            h.add(0, id);
        }
        while (h.size() > HISTORY_MAX) h.remove(h.size() - 1);
        prefs().edit().putString(KEY_HISTORY, TextUtils.join(",", h)).apply();
    }

    @Nullable
    private static String firstGenre(@Nullable String genre) {
        if (TextUtils.isEmpty(genre)) return null;
        String g = genre.split("[;/,]")[0].trim();
        return g.isEmpty() ? null : g;
    }

    private static List<Child> shuffled(@Nullable List<Child> list, int max) {
        if (list == null) return null;
        List<Child> l = new ArrayList<>(list);
        Collections.shuffle(l, RANDOM);
        return l.size() > max ? new ArrayList<>(l.subList(0, max)) : l;
    }
}
