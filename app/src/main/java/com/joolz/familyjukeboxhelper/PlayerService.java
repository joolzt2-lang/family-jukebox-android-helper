package com.joolz.familyjukeboxhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class PlayerService extends Service {
    private static final String TAG = "FamilyJukeboxPlayerService";
    private static final String CHANNEL_ID = "family_jukebox_player";
    private static final int NOTIFICATION_ID = 1002;
    private static final String JUKEBOX_PLAYER_JOB_URL = "http://192.168.1.252:3010/api/android-player/job";

    private static final long PLAYER_JOB_FIRST_DELAY_MS = 3000;
    private static final long PLAYER_JOB_INTERVAL_MS = 5000;

    private final Handler playerJobHandler = new Handler(Looper.getMainLooper());
    private final Runnable playerJobRunnable = new Runnable() {
        @Override
        public void run() {
            pollAndroidPlayerJobForLogOnly();
            playerJobHandler.postDelayed(this, PLAYER_JOB_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.i(TAG, "PlayerService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNow("Family Jukebox helper is running");
        startPlayerJobPolling();
        Log.i(TAG, "PlayerService started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopPlayerJobPolling();
        Log.i(TAG, "PlayerService destroyed");
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startPlayerJobPolling() {
        playerJobHandler.removeCallbacks(playerJobRunnable);
        playerJobHandler.postDelayed(playerJobRunnable, PLAYER_JOB_FIRST_DELAY_MS);
    }

    private void stopPlayerJobPolling() {
        playerJobHandler.removeCallbacks(playerJobRunnable);
    }

    private void pollAndroidPlayerJobForLogOnly() {
        new Thread(() -> {
            try {
                URL url = new URL(JUKEBOX_PLAYER_JOB_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Accept", "application/json");

                int responseCode = connection.getResponseCode();
                String responseText = readHttpResponseText(connection);
                connection.disconnect();

                Log.i(TAG, "Poll HTTP " + responseCode + " body=" + responseText);
            } catch (Exception error) {
                Log.e(TAG, "Poll error: " + error.getClass().getName() + ": " + error.getMessage());
            }
        }).start();
    }

    private String readHttpResponseText(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();

        if (stream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }

            return result.toString();
        }
    }

    private void startForegroundNow(String text) {
        Notification notification = buildNotification(text);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        Intent activityIntent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Family Jukebox")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Family Jukebox Player",
                NotificationManager.IMPORTANCE_LOW
        );

        NotificationManager manager = getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
