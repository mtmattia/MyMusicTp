package com.cappielloantonio.tempo.util;

import android.content.Context;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Flavors {
    public static void initializeCastContext(Context context) {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CastContext.getSharedInstance(context, executor)
                    .addOnCompleteListener(task -> executor.shutdown());
        }
    }
}
