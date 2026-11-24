package com.cappielloantonio.tempo.ui.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.glide.CustomGlideRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Sfondo animato dei riquadri (Flashback/Anni e Generi): ogni copertina zooma piano per
 * 5 secondi, poi scorre a sinistra e lascia il posto alla successiva, che riparte dalla
 * dimensione iniziale e zooma a sua volta.
 */
public class CoverSlideshowView extends FrameLayout {
    private static final long HOLD_MS = 5000;
    private static final long SLIDE_MS = 700;
    private static final float ZOOM_TO = 1.15f;

    /** Copertina visibile e quella pronta a entrare: si scambiano a ogni passaggio. */
    private ImageView front;
    private ImageView back;
    private final List<String> covers = new ArrayList<>();
    private int index = 0;
    private Animator zoom;
    private Animator slide;
    private final Runnable next = this::slideToNext;

    public CoverSlideshowView(@NonNull Context context) {
        this(context, null);
    }

    public CoverSlideshowView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        back = newImage(context);
        front = newImage(context);
        addView(back);
        addView(front);
    }

    private static ImageView newImage(Context context) {
        ImageView v = new ImageView(context);
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Imposta le copertine (id copertina Subsonic) e riparte dalla prima. */
    public void setCovers(List<String> coverIds) {
        stop();
        covers.clear();
        if (coverIds != null) covers.addAll(coverIds);
        index = 0;
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        if (covers.isEmpty()) return;
        load(front, covers.get(0));
        resetTransform(front);
        if (isAttachedToWindow()) start();
    }

    public void clear() {
        setCovers(null);
    }

    private void load(ImageView view, String coverId) {
        // Glide non accetta richieste per un'activity già chiusa (crash su alcuni telefoni)
        if (!isContextAlive()) return;
        CustomGlideRequest.Builder
                .from(getContext(), coverId, CustomGlideRequest.ResourceType.Album)
                .build()
                .into(view);
    }

    private boolean isContextAlive() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                Activity a = (Activity) c;
                return !a.isDestroyed() && !a.isFinishing();
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return true;
    }

    private static void resetTransform(ImageView v) {
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationX(0f);
    }

    private void start() {
        if (covers.isEmpty()) return;
        zoom = zoomOf(front);
        zoom.start();
        if (covers.size() > 1) {
            // Sfasato per riquadro, così non scorrono tutti nello stesso istante
            postDelayed(next, HOLD_MS + (Math.abs(hashCode()) % 1500));
        }
    }

    private Animator zoomOf(ImageView v) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(v, SCALE_X, v.getScaleX(), ZOOM_TO),
                ObjectAnimator.ofFloat(v, SCALE_Y, v.getScaleY(), ZOOM_TO));
        set.setDuration(HOLD_MS + SLIDE_MS);
        set.setInterpolator(new LinearInterpolator());
        return set;
    }

    private void slideToNext() {
        if (covers.size() < 2 || getWidth() == 0 || !isAttachedToWindow() || !isContextAlive()) return;
        index = (index + 1) % covers.size();

        // La successiva entra da destra alla dimensione iniziale
        load(back, covers.get(index));
        resetTransform(back);
        back.setTranslationX(getWidth());
        back.bringToFront();

        float width = getWidth();
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SLIDE_MS);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            front.setTranslationX(-width * f);
            back.setTranslationX(width * (1 - f));
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // Annullata da stop() (vista staccata o copertine cambiate): non ripartire,
                // altrimenti il prossimo passaggio girerebbe su un'activity ormai chiusa
                if (cancelled || slide != animation) return;
                slide = null;
                ImageView old = swap();
                old.setImageDrawable(null);
                resetTransform(old);
                if (zoom != null) zoom.cancel();
                zoom = zoomOf(front);
                zoom.start();
                postDelayed(next, HOLD_MS);
            }
        });
        slide = anim;
        anim.start();
    }

    /** Scambia i ruoli: quella appena entrata diventa la principale. Restituisce la vecchia. */
    private ImageView swap() {
        ImageView old = front;
        front = back;
        back = old;
        return old;
    }

    private void stop() {
        removeCallbacks(next);
        if (zoom != null) zoom.cancel();
        if (slide != null) slide.cancel();
        zoom = null;
        slide = null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (zoom == null) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
