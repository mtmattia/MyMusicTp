package com.cappielloantonio.tempo.service;

/**
 * Hands audio levels from the playback thread to the UI. Each chunk is stamped
 * with the time it should be heard: audio is processed ahead of the speaker by
 * roughly the sink buffer, so the UI reads the entry that is "playing now".
 */
public final class AudioLevelBus {
    private static final int SIZE = 128;
    /** Approximate delay between processing and playback (AudioTrack buffer). */
    private static final long OUTPUT_LATENCY_NS = 250_000_000L;

    private static final long[] times = new long[SIZE];
    private static final float[] levels = new float[SIZE];
    private static final float[] brightness = new float[SIZE];
    private static int head = 0;
    private static int count = 0;
    private static long nextPlayTimeNs = 0;

    private AudioLevelBus() {
    }

    static synchronized void publish(float rms, float zcr, long durationNs) {
        long now = System.nanoTime();
        // Chunks are queued back to back; never schedule in the past.
        long playAt = Math.max(now + OUTPUT_LATENCY_NS, nextPlayTimeNs);
        nextPlayTimeNs = playAt + durationNs;

        times[head] = playAt;
        levels[head] = rms;
        brightness[head] = zcr;
        head = (head + 1) % SIZE;
        if (count < SIZE) count++;
    }

    static synchronized void reset() {
        count = 0;
        nextPlayTimeNs = 0;
    }

    /** Fills out[0] = level (0..1), out[1] = brightness (0..1). Returns false if nothing is playing. */
    public static synchronized boolean read(float[] out) {
        long now = System.nanoTime();
        for (int i = 1; i <= count; i++) {
            int idx = (head - i + SIZE) % SIZE;
            if (times[idx] <= now) {
                if (now - times[idx] > 500_000_000L) return false; // stale: paused/stopped
                out[0] = Math.min(1f, levels[idx] * 2.2f);
                out[1] = Math.min(1f, brightness[idx] * 4f);
                return true;
            }
        }
        return false;
    }
}
