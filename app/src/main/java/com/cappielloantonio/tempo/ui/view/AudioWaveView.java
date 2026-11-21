package com.cappielloantonio.tempo.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.C;
import androidx.media3.common.Player;

import com.cappielloantonio.tempo.service.AudioLevelBus;

/**
 * Soft overlapping waves drawn around the seek bar. Their swell is centered on
 * the playback position and their height follows the loudness of the music.
 */
public class AudioWaveView extends View {
    private static final int WAVES = 3;
    private static final float[] SPEED = {1.6f, -1.1f, 2.3f};
    private static final float[] CYCLES = {2.2f, 3.1f, 1.6f};
    private static final float[] ALPHA = {0.55f, 0.35f, 0.25f};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] reading = new float[2];

    @Nullable
    private Player player;
    private int tint = Color.WHITE;
    private float level = 0f;
    private float brightness = 0.3f;
    private float phase = 0f;
    private long lastFrameNs = 0;

    public AudioWaveView(Context context) {
        this(context, null);
    }

    public AudioWaveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(getResources().getDisplayMetrics().density);
    }

    public void setPlayer(@Nullable Player player) {
        this.player = player;
        postInvalidateOnAnimation();
    }

    public void setTint(@ColorInt int color) {
        tint = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0f : Math.min(0.05f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;

        boolean playing = player != null && player.isPlaying();
        float targetLevel = 0f;
        if (playing && AudioLevelBus.read(reading)) {
            targetLevel = reading[0];
            brightness += (reading[1] - brightness) * Math.min(1f, dt * 4f);
        }
        // Fast attack, slow release, so peaks feel punchy but not jittery.
        float k = targetLevel > level ? 18f : 5f;
        level += (targetLevel - level) * Math.min(1f, dt * k);
        phase += dt * (1.2f + level * 3f);

        float w = getWidth();
        float h = getHeight();
        float mid = h / 2f;
        float center = progressFraction();
        float maxAmp = h * 0.46f;

        for (int i = 0; i < WAVES; i++) {
            float amp = maxAmp * (0.06f + level * (1f - i * 0.18f));
            float cycles = CYCLES[i] + brightness * 2.5f;
            buildWave(w, mid, amp, cycles, phase * SPEED[i] + i * 1.3f, center);
            fill.setColor(ColorUtils.setAlphaComponent(tint, (int) (255 * ALPHA[i] * 0.55f)));
            canvas.drawPath(path, fill);
        }

        line.setColor(ColorUtils.setAlphaComponent(tint, 140));
        canvas.drawLine(0, mid, w, mid, line);

        if (playing || level > 0.005f) postInvalidateOnAnimation();
        else lastFrameNs = 0;
    }

    private float progressFraction() {
        if (player == null) return 0.5f;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) return 0.5f;
        return Math.max(0f, Math.min(1f, (float) player.getCurrentPosition() / duration));
    }

    /** A closed lens-shaped wave: upper and lower edge mirrored, swelling around {@code center}. */
    private void buildWave(float w, float mid, float amp, float cycles, float phase, float center) {
        path.reset();
        int steps = 64;
        float spread = 0.35f; // width of the swell, as a fraction of the view

        path.moveTo(0, mid);
        for (int s = 0; s <= steps; s++) {
            float t = (float) s / steps;
            path.lineTo(t * w, mid - envelope(t, center, spread) * amp * (0.6f + 0.4f * (float) Math.sin(t * cycles * Math.PI * 2 + phase)));
        }
        for (int s = steps; s >= 0; s--) {
            float t = (float) s / steps;
            path.lineTo(t * w, mid + envelope(t, center, spread) * amp * (0.6f + 0.4f * (float) Math.cos(t * cycles * Math.PI * 2 - phase)));
        }
        path.close();
    }

    private static float envelope(float t, float center, float spread) {
        float d = (t - center) / spread;
        float bell = (float) Math.exp(-d * d);
        float edges = (float) Math.sin(Math.PI * t); // taper to a point at both ends
        return 0.25f * edges + 0.75f * bell * edges;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        lastFrameNs = 0;
    }
}
