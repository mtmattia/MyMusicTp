package com.cappielloantonio.tempo.github.utils;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.BuildConfig;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.github.models.Assets;
import com.cappielloantonio.tempo.github.models.LatestRelease;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Call;

/**
 * Scarica l'APK dell'ultima Release GitHub, verifica che sia firmato con la
 * stessa chiave dell'app installata e lo passa a Android per l'installazione
 * (che chiede sempre conferma all'utente).
 */
public final class AppUpdater {
    private static final String TAG = "AppUpdater";
    private static final String PREFERRED_ASSET = "app-tempo-release.apk";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running = false;

    /** APK già verificato, in attesa del permesso "installa app sconosciute". */
    private static volatile File pendingApk = null;

    private AppUpdater() {
    }

    public static boolean isSupported() {
        return BuildConfig.FLAVOR.equals("tempo");
    }

    /** Controlla la release più recente; se è più nuova la scarica e la installa. */
    public static void checkAndInstall(Activity activity, boolean manual) {
        if (!isSupported() || running) return;
        running = true;
        Context ctx = activity.getApplicationContext();

        EXECUTOR.execute(() -> {
            try {
                Call<LatestRelease> call = App.getGithubClientInstance().getReleaseClient().getLatestRelease();
                retrofit2.Response<LatestRelease> response = call.execute();
                LatestRelease release = response.body();

                if (response.code() == 404) {
                    // Nessuna Release pubblicata ancora
                    if (manual) toast(ctx, R.string.app_update_none);
                    return;
                }
                if (!response.isSuccessful() || release == null) {
                    if (manual) toast(ctx, R.string.app_update_check_failed);
                    return;
                }
                if (!UpdateUtil.showUpdateDialog(release)) {
                    if (manual) toast(ctx, R.string.app_update_none);
                    return;
                }
                downloadVerifyInstall(activity, ctx, release);
            } catch (Exception e) {
                Log.e(TAG, "Controllo aggiornamento fallito", e);
                if (manual) toast(ctx, R.string.app_update_check_failed);
            } finally {
                running = false;
            }
        });
    }

    /** Scarica e installa una release già nota (dal dialog "Aggiornamento disponibile"). */
    public static void install(Activity activity, LatestRelease release) {
        if (!isSupported() || running) return;
        running = true;
        Context ctx = activity.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                downloadVerifyInstall(activity, ctx, release);
            } finally {
                running = false;
            }
        });
    }

    /** Da chiamare in onResume: riprende l'installazione dopo che l'utente ha dato il permesso. */
    public static void resumePendingInstall(Context context) {
        File apk = pendingApk;
        if (apk == null || !canInstall(context)) return;
        pendingApk = null;
        EXECUTOR.execute(() -> commitInstall(context.getApplicationContext(), apk));
    }

    private static void downloadVerifyInstall(Activity activity, Context ctx, LatestRelease release) {
        Assets asset = pickAsset(release);
        if (asset == null || asset.getBrowserDownloadUrl() == null) {
            toast(ctx, R.string.app_update_check_failed);
            return;
        }

        toast(ctx, ctx.getString(R.string.app_update_downloading, release.getTagName()));

        File apk;
        try {
            apk = download(ctx, asset.getBrowserDownloadUrl());
        } catch (Exception e) {
            Log.e(TAG, "Download fallito", e);
            toast(ctx, R.string.app_update_download_failed);
            return;
        }

        if (!verify(ctx, apk)) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            toast(ctx, R.string.app_update_bad_signature);
            return;
        }

        if (!canInstall(ctx)) {
            pendingApk = apk;
            MAIN.post(() -> {
                Toast.makeText(ctx, R.string.app_update_allow_install, Toast.LENGTH_LONG).show();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.getPackageName()));
                    activity.startActivity(intent);
                }
            });
            return;
        }

        commitInstall(ctx, apk);
    }

    @Nullable
    private static Assets pickAsset(LatestRelease release) {
        Assets fallback = null;
        for (Assets a : release.getAssets()) {
            String name = a.getName();
            if (name == null || !name.endsWith(".apk")) continue;
            if (name.equals(PREFERRED_ASSET)) return a;
            if (fallback == null) fallback = a;
        }
        return fallback;
    }

    private static File download(Context ctx, String url) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, "update.apk");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.MINUTES)
                .build();

        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            try (InputStream in = response.body().byteStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            }
        }
        return out;
    }

    /** Stesso pacchetto, versione più nuova e stessa chiave di firma dell'app installata. */
    static boolean verify(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;

            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            PackageInfo installed = pm.getPackageInfo(ctx.getPackageName(), flags);
            if (archive == null || installed == null) return false;

            if (!ctx.getPackageName().equals(archive.packageName)) {
                Log.w(TAG, "Pacchetto diverso: " + archive.packageName);
                return false;
            }
            if (versionCode(archive) <= versionCode(installed)) {
                Log.w(TAG, "Versione non più recente: " + versionCode(archive));
                return false;
            }

            Set<String> a = digests(signers(archive));
            Set<String> b = digests(signers(installed));
            boolean same = !a.isEmpty() && a.equals(b);
            if (!same) Log.w(TAG, "Firma diversa: " + a + " vs " + b);
            return same;
        } catch (Exception e) {
            Log.e(TAG, "Verifica firma fallita", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) return new Signature[0];
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures != null ? info.signatures : new Signature[0];
    }

    private static Set<String> digests(Signature[] signatures) throws Exception {
        Set<String> out = new HashSet<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature s : signatures) {
            byte[] d = md.digest(s.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            out.add(sb.toString());
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static boolean canInstall(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ctx.getPackageManager().canRequestPackageInstalls();
    }

    private static void commitInstall(Context ctx, File apk) {
        try {
            PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ctx.getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }

            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("tempo.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    session.fsync(out);
                }

                Intent intent = new Intent(ctx, InstallResultReceiver.class);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(ctx, sessionId, intent, piFlags);
                session.commit(pi.getIntentSender());
            }
        } catch (Exception e) {
            Log.e(TAG, "Installazione fallita", e);
            toast(ctx, R.string.app_update_install_failed);
        }
    }

    private static void toast(Context ctx, int res) {
        MAIN.post(() -> Toast.makeText(ctx, res, Toast.LENGTH_LONG).show());
    }

    private static void toast(Context ctx, String text) {
        MAIN.post(() -> Toast.makeText(ctx, text, Toast.LENGTH_LONG).show());
    }
}
