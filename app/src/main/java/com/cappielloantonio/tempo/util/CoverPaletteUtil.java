package com.cappielloantonio.tempo.util;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.palette.graphics.Palette;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.google.android.material.color.MaterialColors;

/**
 * Colors taken from album covers. The background tint is normalised in HSL
 * (fixed lightness, capped saturation) so it is clearly visible even for dark
 * covers, yet text on top stays readable in both themes.
 */
public final class CoverPaletteUtil {
    private static final int TAG_COLOR = R.id.cover_palette_color_tag;

    public interface TintCallback {
        void onTint(@ColorInt int background, @ColorInt int accent);
    }

    private CoverPaletteUtil() {
    }

    /** Loads the cover and returns a background tint and a vivid accent. Falls back to theme colors. */
    public static void extract(@NonNull Fragment fragment, @Nullable String coverArtId, @NonNull TintCallback callback) {
        Context context = fragment.requireContext();
        int surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.BLACK);
        int primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.WHITE);

        if (coverArtId == null || Preferences.isDataSavingMode()) {
            callback.onTint(surface, primary);
            return;
        }

        Glide.with(fragment)
                .asBitmap()
                .load(CustomGlideRequest.createUrl(coverArtId, 128))
                .override(96, 96)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                        Palette.from(bitmap).maximumColorCount(24).generate(palette -> {
                            if (!fragment.isAdded()) return;
                            // Dark/black covers: the dominant color is gray, so pick the most colorful
                            // detail instead (e.g. a red rose on a black cover); if the cover is truly
                            // colorless, fall back to the theme's primary hue so the tint still shows.
                            Palette.Swatch swatch = palette == null ? null : mostColorful(palette);
                            int base = swatch != null ? swatch.getRgb() : primary;
                            boolean dark = isDark(context);
                            callback.onTint(normalise(base, dark ? 0.27f : 0.82f, 0.85f),
                                    normalise(base, dark ? 0.72f : 0.38f, 0.95f));
                        });
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        callback.onTint(surface, primary);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    /** Paints {@code target} with a gradient from the cover tint to the surface. */
    public static void applyGradient(@NonNull Fragment fragment, @Nullable String coverArtId, @NonNull View target, @Nullable View solidTarget) {
        extract(fragment, coverArtId, (background, accent) -> animate(target, solidTarget, background, surfaceOf(target)));
    }

    /**
     * App-wide theme: the whole window behind every screen takes the colors of
     * the cover that is playing, plus the status bar.
     */
    /**
     * "Liquid glass" surface: translucent tinted fill, soft top sheen, thin light rim.
     * (Android can't blur what lies behind a view, so translucency stands in for it.)
     */
    public static Drawable glassDrawable(@NonNull Context context, @ColorInt int color, float radiusDp) {
        float density = context.getResources().getDisplayMetrics().density;
        float radius = radiusDp * density;

        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(radius);
        fill.setColor(ColorUtils.setAlphaComponent(color, 0xD9));
        fill.setStroke(Math.max(1, Math.round(density)), Color.argb(0x38, 255, 255, 255));

        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(0x22, 255, 255, 255), Color.argb(0x00, 255, 255, 255)});
        sheen.setCornerRadius(radius);

        return new LayerDrawable(new Drawable[]{fill, sheen});
    }

    public static void applyAppTheme(@NonNull Fragment fragment, @Nullable String coverArtId, View... extraGradientTargets) {
        Activity activity = fragment.getActivity();
        if (activity == null) return;
        View root = activity.findViewById(R.id.drawer_layout);

        extract(fragment, coverArtId, (background, accent) -> {
            int surface = surfaceOf(root);
            if (root != null) animate(root, null, background, surface);
            for (View v : extraGradientTargets) if (v != null) animate(v, null, background, surface);
            activity.getWindow().setStatusBarColor(background);
        });
    }

    @ColorInt
    public static int normalise(@ColorInt int color, float lightness, float maxSaturation) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[1] = Math.min(maxSaturation, Math.max(hsl[1], 0.40f));
        hsl[2] = lightness;
        return ColorUtils.HSLToColor(hsl);
    }

    private static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int surfaceOf(@Nullable View view) {
        return view == null ? Color.BLACK : MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface);
    }

    /** Swatch with the best mix of saturation and presence; null if the cover is grayscale. */
    @Nullable
    private static Palette.Swatch mostColorful(@NonNull Palette palette) {
        Palette.Swatch best = null;
        double bestScore = 0;
        int total = 0;
        for (Palette.Swatch s : palette.getSwatches()) total += s.getPopulation();
        if (total == 0) return null;
        for (Palette.Swatch s : palette.getSwatches()) {
            float[] hsl = s.getHsl();
            // Ignore near-gray and near-black/white swatches: they carry no hue
            if (hsl[1] < 0.22f || hsl[2] < 0.08f || hsl[2] > 0.95f) continue;
            double share = (double) s.getPopulation() / total;
            double score = hsl[1] * Math.sqrt(Math.max(share, 0.002));
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static void animate(View target, @Nullable View solidTarget, @ColorInt int top, @ColorInt int surface) {
        Object previous = target.getTag(TAG_COLOR);
        int from = previous instanceof Integer ? (Integer) previous : surface;
        target.setTag(TAG_COLOR, top);

        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{from, ColorUtils.blendARGB(from, surface, 0.25f), ColorUtils.blendARGB(from, surface, 0.6f)});
        target.setBackground(gradient);

        ValueAnimator animator = ValueAnimator.ofArgb(from, top);
        animator.setDuration(450);
        animator.addUpdateListener(a -> {
            int c = (int) a.getAnimatedValue();
            gradient.setColors(new int[]{c, ColorUtils.blendARGB(c, surface, 0.25f), ColorUtils.blendARGB(c, surface, 0.6f)});
            if (solidTarget != null) solidTarget.setBackgroundColor(c);
        });
        animator.start();
    }
}
