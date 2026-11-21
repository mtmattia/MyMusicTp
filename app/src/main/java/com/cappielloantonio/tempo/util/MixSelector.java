package com.cappielloantonio.tempo.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Pure selection logic for smart mixes (no Android deps, testable on the JVM).
 * Candidates from several sources accumulate weight; recently played tracks are
 * excluded or penalised; the final pick is a weighted random sample without
 * replacement (Efraimidis-Spirakis) with a per-artist cap for variety.
 */
public final class MixSelector<T> {
    public static final double W_SIMILAR = 3.0;
    public static final double W_GENRE = 1.5;
    public static final double W_ARTIST_TOP = 1.0;
    public static final double W_RANDOM = 0.3;

    /** Played within the last N tracks: never proposed (unless nothing else is left). */
    public static final int HARD_RECENT = 40;
    /** Older history entries are still allowed, but with reduced weight. */
    public static final double OLD_HISTORY_FACTOR = 0.2;

    private static final class Cand<T> {
        final T item;
        final String artist;
        double weight;

        Cand(T item, String artist) {
            this.item = item;
            this.artist = artist;
        }
    }

    private final Map<String, Cand<T>> cands = new LinkedHashMap<>();
    private final Random random;

    public MixSelector(Random random) {
        this.random = random;
    }

    /** Adds a ranked list from one source; earlier items weigh a bit more. */
    public void addSource(List<T> items, Key<T> key, double baseWeight) {
        if (items == null || items.isEmpty()) return;
        int n = items.size();
        for (int i = 0; i < n; i++) {
            T it = items.get(i);
            String id = key.id(it);
            if (id == null) continue;
            Cand<T> c = cands.get(id);
            if (c == null) {
                c = new Cand<>(it, normArtist(key.artist(it)));
                cands.put(id, c);
            }
            c.weight += baseWeight * (1.0 - 0.5 * i / n);
        }
    }

    public int size() {
        return cands.size();
    }

    /**
     * @param history   most recent first
     * @param exclude   ids never allowed (seed, current queue)
     * @param seedArtist artist of the seed track, capped harder to avoid an artist-only mix
     */
    public List<T> pick(int count, List<String> history, Set<String> exclude, String seedArtist) {
        Map<String, Integer> histPos = new HashMap<>();
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                if (!histPos.containsKey(history.get(i))) histPos.put(history.get(i), i);
            }
        }

        List<Map.Entry<Double, Cand<T>>> fresh = new ArrayList<>();
        List<Map.Entry<Double, Cand<T>>> recent = new ArrayList<>();

        for (Map.Entry<String, Cand<T>> e : cands.entrySet()) {
            if (exclude != null && exclude.contains(e.getKey())) continue;
            Cand<T> c = e.getValue();
            double w = c.weight;
            Integer pos = histPos.get(e.getKey());
            boolean isRecent = pos != null && pos < HARD_RECENT;
            if (pos != null && !isRecent) w *= OLD_HISTORY_FACTOR;
            if (w <= 0) continue;
            double k = Math.pow(random.nextDouble(), 1.0 / w);
            (isRecent ? recent : fresh).add(new java.util.AbstractMap.SimpleEntry<>(k, c));
        }

        Collections.sort(fresh, (a, b) -> Double.compare(b.getKey(), a.getKey()));
        Collections.sort(recent, (a, b) -> Double.compare(b.getKey(), a.getKey()));

        int artistCap = Math.max(2, (int) Math.ceil(count / 4.0));
        int seedCap = Math.max(1, Math.min(artistCap, (int) Math.ceil(count / 5.0)));
        String seed = normArtist(seedArtist);

        List<T> out = new ArrayList<>();
        Map<String, Integer> perArtist = new HashMap<>();
        List<Cand<T>> overflow = new ArrayList<>();

        for (Map.Entry<Double, Cand<T>> e : fresh) {
            if (out.size() >= count) break;
            Cand<T> c = e.getValue();
            int used = perArtist.getOrDefault(c.artist, 0);
            int cap = c.artist != null && c.artist.equals(seed) ? seedCap : artistCap;
            if (c.artist != null && used >= cap) {
                overflow.add(c);
                continue;
            }
            perArtist.put(c.artist, used + 1);
            out.add(c.item);
        }
        // Small libraries: relax the artist cap, then allow recent tracks as last resort.
        for (Cand<T> c : overflow) {
            if (out.size() >= count) break;
            out.add(c.item);
        }
        for (Map.Entry<Double, Cand<T>> e : recent) {
            if (out.size() >= count) break;
            out.add(e.getValue().item);
        }

        // Interleave so the same artist does not play back to back when avoidable.
        return spreadArtists(out);
    }

    private List<T> spreadArtists(List<T> list) {
        if (list.size() < 3) return list;
        Map<T, String> artistOf = new HashMap<>();
        for (Cand<T> c : cands.values()) artistOf.put(c.item, c.artist);
        List<T> pool = new ArrayList<>(list);
        List<T> res = new ArrayList<>();
        String last = null;
        while (!pool.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < pool.size(); i++) {
                String a = artistOf.get(pool.get(i));
                if (a == null || !a.equals(last)) {
                    idx = i;
                    break;
                }
            }
            T t = pool.remove(idx);
            last = artistOf.get(t);
            res.add(t);
        }
        return res;
    }

    static String normArtist(String a) {
        if (a == null) return null;
        String s = a.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    public interface Key<T> {
        String id(T item);

        String artist(T item);
    }
}
