package com.cappielloantonio.tempo.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
 * Extracts a tint from an album cover and paints it as a soft top-to-bottom
 * gradient that fades into the theme surface, so text stays readable.
 */
public final class CoverPaletteUtil {
    private static final int TAG_COLOR = R.id.cover_palette_color_tag;

    private CoverPaletteUtil() {
    }

    public static void applyGradient(@NonNull Fragment fragment, @Nullable String coverArtId, @NonNull View target, @Nullable View solidTarget) {
        Context context = fragment.requireContext();
        int surface = MaterialColors.getColor(target, com.google.android.material.R.attr.colorSurface);

        if (coverArtId == null || Preferences.isDataSavingMode()) {
            animateTo(target, solidTarget, surface, surface);
            return;
        }

        Glide.with(fragment)
                .asBitmap()
                .load(CustomGlideRequest.createUrl(coverArtId, 128))
                .override(96, 96)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                        Palette.from(bitmap).maximumColorCount(16).generate(palette -> {
                            if (!fragment.isAdded()) return;
                            animateTo(target, solidTarget, pickTint(context, palette, surface), surface);
                        });
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        animateTo(target, solidTarget, surface, surface);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    @ColorInt
    private static int pickTint(Context context, @Nullable Palette palette, @ColorInt int surface) {
        if (palette == null) return surface;

        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        Palette.Swatch swatch = dark
                ? firstNonNull(palette.getDarkVibrantSwatch(), palette.getVibrantSwatch(), palette.getDarkMutedSwatch(), palette.getDominantSwatch())
                : firstNonNull(palette.getLightVibrantSwatch(), palette.getVibrantSwatch(), palette.getLightMutedSwatch(), palette.getDominantSwatch());

        if (swatch == null) return surface;

        // Blend with the surface: strong enough to feel like the cover, soft enough for text.
        return ColorUtils.blendARGB(surface, swatch.getRgb(), dark ? 0.55f : 0.40f);
    }

    @Nullable
    private static Palette.Swatch firstNonNull(Palette.Swatch... swatches) {
        for (Palette.Swatch s : swatches) if (s != null) return s;
        return null;
    }

    private static void animateTo(View target, @Nullable View solidTarget, @ColorInt int top, @ColorInt int surface) {
        Object previous = target.getTag(TAG_COLOR);
        int from = previous instanceof Integer ? (Integer) previous : surface;
        target.setTag(TAG_COLOR, top);

        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{from, surface});
        target.setBackground(gradient);

        ValueAnimator animator = ValueAnimator.ofArgb(from, top);
        animator.setDuration(450);
        animator.addUpdateListener(a -> {
            int c = (int) a.getAnimatedValue();
            gradient.setColors(new int[]{c, surface});
            if (solidTarget != null) solidTarget.setBackgroundColor(c);
        });
        animator.start();
    }
}
