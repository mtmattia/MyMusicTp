package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.view.View;

import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.framework.CastButtonFactory;

/** Pulsante Chromecast da mettere fuori dalla barra in alto. */
public final class CastUi {
    private CastUi() {
    }

    public static View createButton(Context context) {
        try {
            MediaRouteButton button = new MediaRouteButton(context);
            CastButtonFactory.setUpMediaRouteButton(context, button);
            return button;
        } catch (Exception e) {
            return null;
        }
    }
}
