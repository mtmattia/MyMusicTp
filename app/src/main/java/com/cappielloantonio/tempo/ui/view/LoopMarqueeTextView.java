package com.cappielloantonio.tempo.ui.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * Titolo che scorre in modo regolare quando non ci sta, al posto del marquee di Android
 * (che con i titoli di poco più lunghi dello spazio "salta"):
 * 2 s fermo all'inizio → scorre fino a mostrare la fine → 2 s fermo → continua a sinistra
 * finché l'inizio rientra da destra e si ferma al bordo sinistro → di nuovo 2 s, e ripete.
 * Se il testo ci sta, resta fermo.
 */
public class LoopMarqueeTextView extends AppCompatTextView {
    private static final long PAUSE_MS = 2000;
    private static final float SPEED_DP_PER_S = 40f;
    private static final float GAP_DP = 48f;

    private float offset = 0f;
    private ValueAnimator animator;
    private final Runnable startRunnable = this::runCycle;

    public LoopMarqueeTextView(@NonNull Context context) {
        this(context, null);
    }

    public LoopMarqueeTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public LoopMarqueeTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setSingleLine(true);
        setEllipsize(null);
        setHorizontallyScrolling(true);
    }

    @Override
    public void setEllipsize(TextUtils.TruncateAt where) {
        // Il marquee di sistema non serve: lo scorrimento lo facciamo noi
        super.setEllipsize(null);
    }

    private float textWidth() {
        CharSequence t = getText();
        return t == null ? 0f : getPaint().measureText(t, 0, t.length());
    }

    private float available() {
        return getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
    }

    private float gap() {
        return GAP_DP * getResources().getDisplayMetrics().density;
    }

    private boolean overflows() {
        return getWidth() > 0 && textWidth() > available() + 1;
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        restart();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        restart();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restart();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void stop() {
        removeCallbacks(startRunnable);
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    private void restart() {
        stop();
        offset = 0f;
        invalidate();
        if (isAttachedToWindow()) postDelayed(startRunnable, PAUSE_MS);
    }

    private long durationFor(float distance) {
        float pxPerMs = SPEED_DP_PER_S * getResources().getDisplayMetrics().density / 1000f;
        return (long) Math.max(1, distance / pxPerMs);
    }

    /** Un giro: fino alla fine, pausa, poi l'inizio rientra e si ferma a sinistra, pausa. */
    private void runCycle() {
        if (!overflows()) {
            offset = 0f;
            invalidate();
            return;
        }
        float toEnd = textWidth() - available();
        float loop = textWidth() + gap();

        animateTo(toEnd, () -> postDelayed(() -> animateTo(loop, () -> {
            offset = 0f;
            invalidate();
            postDelayed(startRunnable, PAUSE_MS);
        }), PAUSE_MS));
    }

    private void animateTo(float target, Runnable end) {
        float from = offset;
        animator = ValueAnimator.ofFloat(from, target);
        animator.setDuration(durationFor(Math.abs(target - from)));
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled) end.run();
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        android.text.Layout layout = getLayout();
        if (layout == null || !overflows()) {
            super.onDraw(canvas);
            return;
        }
        // TextView taglia il testo alla propria larghezza: disegniamo noi il layout intero
        getPaint().setColor(getCurrentTextColor());
        getPaint().drawableState = getDrawableState();
        int left = getCompoundPaddingLeft();
        int right = getWidth() - getCompoundPaddingRight();
        canvas.save();
        canvas.clipRect(left, 0, right, getHeight());
        canvas.translate(left - offset, getTotalPaddingTop());
        layout.draw(canvas);
        // Copia che rientra da destra
        if (offset > textWidth() - available()) {
            canvas.translate(textWidth() + gap(), 0);
            layout.draw(canvas);
        }
        canvas.restore();
    }
}
