package com.cappielloantonio.tempo.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.cappielloantonio.tempo.service.AudioLevelBus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Voice-message style bars that grow from the start of the track up to the
 * playback point. Each bar keeps the loudness heard at that moment, so every
 * song draws its own shape; the newest bars ripple like a wave with the music.
 * Nothing is drawn after the playback point.
 */
public class AudioWaveView extends View {
    private static final int TAIL = 7; // bars near the playhead that ripple live
    private static final int CACHE = 12;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] reading = new float[2];
    private final float barWidth;
    private final float gap;

    /** Per-track history of bar heights (0 = not heard yet). */
    private final Map<String, float[]> histories = new LinkedHashMap<String, float[]>(CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > CACHE;
        }
    };

    @Nullable
    private Player player;
    private int tint = Color.WHITE;
    private float live = 0f;
    private float phase = 0f;
    private long lastFrameNs = 0;

    public AudioWaveView(Context context) {
        this(context, null);
    }

    public AudioWaveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        barWidth = 3f * d;
        gap = 2f * d;
        barPaint.setStyle(Paint.Style.FILL);
    }

    // Restart the animation whenever playback state changes (otherwise a frame
    // drawn while paused would never schedule the next one).
    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            lastFrameNs = 0;
            postInvalidateOnAnimation();
        }
    };

    public void setPlayer(@Nullable Player player) {
        if (this.player != null) this.player.removeListener(listener);
        this.player = player;
        if (player != null) player.addListener(listener);
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

        Player p = player;
        if (p == null) return;

        int count = Math.max(1, (int) ((getWidth() + gap) / (barWidth + gap)));
        String key = mediaKey(p);
        float[] history = histories.get(key);
        if (history == null || history.length != count) {
            history = new float[count];
            histories.put(key, history);
        }

        boolean playing = p.isPlaying();
        float target;
        if (playing && AudioLevelBus.read(reading)) {
            target = reading[0];
        } else if (playing) {
            // No PCM levels (e.g. format not measurable): keep moving with a soft synthetic pulse
            float t = now / 1e9f;
            target = 0.35f + 0.2f * (float) Math.sin(t * 2.7f) + 0.12f * (float) Math.sin(t * 6.3f + key.hashCode());
        } else {
            target = 0f;
        }
        float k = target > live ? 16f : 5f;
        live += (target - live) * Math.min(1f, dt * k);
        phase += dt * (3f + live * 6f);

        float fraction = progressFraction(p);
        int head = Math.min(count - 1, (int) (fraction * count));

        // Record what is heard now at the playhead; fill gaps left by seeking with a per-song pattern
        if (playing) history[head] = history[head] == 0f ? live : history[head] * 0.7f + live * 0.3f;
        fillGaps(history, head, key);

        float h = getHeight();
        float mid = h / 2f;
        float minH = barWidth;
        float maxH = h * 0.95f;

        for (int i = 0; i <= head; i++) {
            float v = Math.max(0.08f, Math.min(1f, history[i]));
            int fromHead = head - i;
            if (fromHead < TAIL) {
                // Wave tail: the latest bars breathe with the live level
                float wave = (float) Math.sin(phase - fromHead * 0.9f);
                v = Math.min(1f, v * (1f + 0.45f * wave * live) + live * 0.25f * (1f - fromHead / (float) TAIL));
            }
            float bh = minH + (maxH - minH) * v;
            float x = i * (barWidth + gap);
            rect.set(x, mid - bh / 2f, x + barWidth, mid + bh / 2f);

            // Older bars slightly softer, newest fully opaque
            float age = Math.min(1f, fromHead / 40f);
            barPaint.setColor(ColorUtils.setAlphaComponent(tint, (int) (255 * (1f - 0.35f * age))));
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, barPaint);
        }

        if (playing || live > 0.005f) postInvalidateOnAnimation();
        else lastFrameNs = 0;
    }

    private static void fillGaps(float[] history, int head, String key) {
        Random random = null;
        for (int i = 0; i < head; i++) {
            if (history[i] == 0f) {
                if (random == null) random = new Random(key.hashCode() * 31L + i);
                float prev = i > 0 ? history[i - 1] : 0.4f;
                history[i] = Math.max(0.1f, Math.min(0.9f, prev * 0.6f + (0.15f + random.nextFloat() * 0.6f) * 0.4f));
            }
        }
    }

    private static String mediaKey(Player p) {
        MediaItem item = p.getCurrentMediaItem();
        return item != null ? item.mediaId : "none";
    }

    private static float progressFraction(Player p) {
        long duration = p.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (float) p.getCurrentPosition() / duration));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        lastFrameNs = 0;
    }
}
