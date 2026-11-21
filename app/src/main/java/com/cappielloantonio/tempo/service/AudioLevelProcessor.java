package com.cappielloantonio.tempo.service;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pass-through processor in the ExoPlayer audio sink: it does not change the
 * sound, it only measures loudness (RMS) and "brightness" (zero-crossing rate)
 * of each chunk and publishes them to {@link AudioLevelBus} for the visualizer.
 * No RECORD_AUDIO permission is needed, unlike android.media.audiofx.Visualizer.
 */
@UnstableApi
public final class AudioLevelProcessor extends BaseAudioProcessor {
    private int encoding = C.ENCODING_INVALID;
    private int channels = 1;
    private int sampleRate = 44100;

    @NonNull
    @Override
    protected AudioFormat onConfigure(@NonNull AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        int e = inputAudioFormat.encoding;
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT && e != C.ENCODING_PCM_24BIT && e != C.ENCODING_PCM_32BIT) {
            // Leave other formats alone (inactive processor = untouched audio).
            return AudioFormat.NOT_SET;
        }
        encoding = inputAudioFormat.encoding;
        channels = Math.max(1, inputAudioFormat.channelCount);
        sampleRate = inputAudioFormat.sampleRate > 0 ? inputAudioFormat.sampleRate : 44100;
        return inputAudioFormat;
    }

    @Override
    public void queueInput(@NonNull ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;

        measure(inputBuffer.duplicate().order(ByteOrder.nativeOrder()));

        ByteBuffer out = replaceOutputBuffer(remaining);
        out.put(inputBuffer);
        out.flip();
    }

    private void measure(ByteBuffer buf) {
        double sumSquares = 0;
        int frames = 0;
        int crossings = 0;
        float previous = 0;

        if (encoding == C.ENCODING_PCM_16BIT) {
            int frameBytes = 2 * channels;
            while (buf.remaining() >= frameBytes) {
                float sample = buf.getShort() / 32768f;
                for (int c = 1; c < channels; c++) buf.getShort();
                sumSquares += sample * sample;
                if ((sample >= 0) != (previous >= 0)) crossings++;
                previous = sample;
                frames++;
            }
        } else if (encoding == C.ENCODING_PCM_24BIT) {
            // Hi-res FLAC often decodes to packed 24-bit little-endian
            int frameBytes = 3 * channels;
            while (buf.remaining() >= frameBytes) {
                int b0 = buf.get() & 0xFF, b1 = buf.get() & 0xFF, b2 = buf.get();
                float sample = ((b2 << 16) | (b1 << 8) | b0) / 8388608f;
                for (int c = 1; c < channels; c++) { buf.get(); buf.get(); buf.get(); }
                sumSquares += sample * sample;
                if ((sample >= 0) != (previous >= 0)) crossings++;
                previous = sample;
                frames++;
            }
        } else if (encoding == C.ENCODING_PCM_32BIT) {
            int frameBytes = 4 * channels;
            while (buf.remaining() >= frameBytes) {
                float sample = buf.getInt() / 2147483648f;
                for (int c = 1; c < channels; c++) buf.getInt();
                sumSquares += sample * sample;
                if ((sample >= 0) != (previous >= 0)) crossings++;
                previous = sample;
                frames++;
            }
        } else {
            int frameBytes = 4 * channels;
            while (buf.remaining() >= frameBytes) {
                float sample = buf.getFloat();
                for (int c = 1; c < channels; c++) buf.getFloat();
                sumSquares += sample * sample;
                if ((sample >= 0) != (previous >= 0)) crossings++;
                previous = sample;
                frames++;
            }
        }

        if (frames == 0) return;

        float rms = (float) Math.sqrt(sumSquares / frames);
        float zcr = (float) crossings / frames; // 0..~0.5
        long durationNs = frames * 1_000_000_000L / sampleRate;
        AudioLevelBus.publish(rms, zcr, durationNs);
    }

    @Override
    protected void onFlush() {
        AudioLevelBus.reset();
    }

    @Override
    protected void onReset() {
        encoding = C.ENCODING_INVALID;
        AudioLevelBus.reset();
    }
}
