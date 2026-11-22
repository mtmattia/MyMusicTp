package com.cappielloantonio.tempo.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Saves the stack trace of an uncaught crash and, on the next launch,
 * offers to share it so the bug can be reported.
 */
public final class CrashReporter {
    private static final String FILE_NAME = "last_crash.txt";

    private CrashReporter() {
    }

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                pw.println("Thread: " + thread.getName());
                throwable.printStackTrace(pw);
                pw.flush();
                try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE_NAME))) {
                    out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    public static void showIfPending(Activity activity) {
        File file = new File(activity.getFilesDir(), FILE_NAME);
        if (!file.exists()) return;

        String report;
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            report = new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            report = "Crash report unreadable: " + e;
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();

        final String text = report;
        new MaterialAlertDialogBuilder(activity)
                .setTitle("L'app si è chiusa per un errore")
                .setMessage("Vuoi condividere il report per farlo correggere?")
                .setPositiveButton("Condividi", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "Crash report");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    activity.startActivity(Intent.createChooser(send, "Condividi report"));
                })
                .setNegativeButton("Ignora", null)
                .show();
    }
}
