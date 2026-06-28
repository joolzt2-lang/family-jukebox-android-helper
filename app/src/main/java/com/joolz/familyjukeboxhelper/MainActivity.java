package com.joolz.familyjukeboxhelper;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothProfile;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH_CONNECT = 1001;
    private static final int REQUEST_POST_NOTIFICATIONS = 1002;
    private static final String JUKEBOX_STATUS_URL = "http://192.168.1.252:3010/api/phone-status";
    private static final String JUKEBOX_PLAYER_JOB_URL = "http://192.168.1.252:3010/api/android-player/job";
    private static final String JUKEBOX_PLAYER_COMPLETE_URL = "http://192.168.1.252:3010/api/android-player/job/complete";

    private static final long AUTO_SEND_FIRST_DELAY_MS = 2000;
    private static final long AUTO_SEND_INTERVAL_MS = 10000;
    private static final long PLAYER_JOB_FIRST_DELAY_MS = 3000;
    private static final long PLAYER_JOB_INTERVAL_MS = 5000;

    private final Handler autoSendHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSendRunnable = new Runnable() {
        @Override
        public void run() {
            sendStatusToJukebox(false);
            autoSendHandler.postDelayed(this, AUTO_SEND_INTERVAL_MS);
        }
    };

    private final Handler playerJobHandler = new Handler(Looper.getMainLooper());
    private final Runnable playerJobRunnable = new Runnable() {
        @Override
        public void run() {
            pollAndroidPlayerJob();
            playerJobHandler.postDelayed(this, PLAYER_JOB_INTERVAL_MS);
        }
    };

    private TextView reportView;
    private final Object playbackLock = new Object();
    private MediaPlayer currentMediaPlayer = null;
    private String currentPlaybackJobId = "";
    private boolean currentPlaybackPaused = false;


    private static class ApprovedSpeaker {
        final String roomName;
        final String bluetoothName;
        final String address;

        ApprovedSpeaker(String roomName, String bluetoothName, String address) {
            this.roomName = roomName;
            this.bluetoothName = bluetoothName;
            this.address = address;
        }
    }

    private static final ApprovedSpeaker[] APPROVED_SPEAKERS = {
            new ApprovedSpeaker("Sitting Room", "Echo Dot-QNV", "2C:71:FF:65:15:B6"),
            new ApprovedSpeaker("Bedroom", "Echo Dot-QCK", "2C:71:FF:74:F0:A3"),
            new ApprovedSpeaker("Office", "Echo Dot-W4Q", "FC:A1:83:AC:E8:DD"),
            new ApprovedSpeaker("Kitchen", "Echo Dot-JAC", "08:A6:BC:88:CA:64"),
            new ApprovedSpeaker("Conservatory", "Echo Dot-HSS", "4C:17:44:8F:15:C9"),
            new ApprovedSpeaker("Bose Headphones", "LE-Bose QC35 II", "2C:41:A1:C3:42:FA")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 28, 28, 28);

        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh status");
        layout.addView(refreshButton);

        Button copyButton = new Button(this);
        copyButton.setText("Copy diagnostics");
        layout.addView(copyButton);

        Button sendButton = new Button(this);
        sendButton.setText("Send status");
        layout.addView(sendButton);

        Button testToneButton = new Button(this);
        testToneButton.setText("Test sound");
        layout.addView(testToneButton);

        Button startServiceButton = new Button(this);
        startServiceButton.setText("Start helper");
        layout.addView(startServiceButton);

        Button stopServiceButton = new Button(this);
        stopServiceButton.setText("Stop helper");
        layout.addView(stopServiceButton);

        reportView = new TextView(this);
        reportView.setTextSize(15);
        reportView.setTextIsSelectable(true);

        Button diagnosticsButton = new Button(this);
        diagnosticsButton.setText("Show diagnostics");
        layout.addView(diagnosticsButton);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(reportView);
        scrollView.setVisibility(android.view.View.GONE);
        layout.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        diagnosticsButton.setOnClickListener(v -> {
            if (scrollView.getVisibility() == android.view.View.VISIBLE) {
                scrollView.setVisibility(android.view.View.GONE);
                diagnosticsButton.setText("Show diagnostics");
            } else {
                scrollView.setVisibility(android.view.View.VISIBLE);
                diagnosticsButton.setText("Hide diagnostics");
            }
        });

        setContentView(layout);

        requestNotificationPermissionIfNeeded();

        refreshButton.setOnClickListener(v -> refreshReport());
        copyButton.setOnClickListener(v -> copyReport());
        sendButton.setOnClickListener(v -> sendStatusToJukebox(true));
        testToneButton.setOnClickListener(v -> playLocalTestToneFromButton());
        startServiceButton.setOnClickListener(v -> startPlayerService());
        stopServiceButton.setOnClickListener(v -> stopPlayerService());

        ensureBluetoothPermission();
        refreshReport();
    }

    private void ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH_CONNECT
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startAutoSend();
    }

    @Override
    protected void onPause() {
        stopAutoSend();
        super.onPause();
    }

    private void startPlayerService() {
        Intent intent = new Intent(this, PlayerService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(this, "Background player service started", Toast.LENGTH_SHORT).show();
    }

    private void stopPlayerService() {
        stopService(new Intent(this, PlayerService.class));
        Toast.makeText(this, "Background player service stopped", Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS
            );
        }
    }

    private void startAutoSend() {
        autoSendHandler.removeCallbacks(autoSendRunnable);
        autoSendHandler.postDelayed(autoSendRunnable, AUTO_SEND_FIRST_DELAY_MS);
        startPlayerJobPolling();
    }

    private void stopAutoSend() {
        autoSendHandler.removeCallbacks(autoSendRunnable);
        stopPlayerJobPolling();
    }

    private void startPlayerJobPolling() {
        playerJobHandler.removeCallbacks(playerJobRunnable);
        playerJobHandler.postDelayed(playerJobRunnable, PLAYER_JOB_FIRST_DELAY_MS);
    }

    private void stopPlayerJobPolling() {
        playerJobHandler.removeCallbacks(playerJobRunnable);
    }

    private void refreshReport() {
        String report = buildReport();
        reportView.setText(report);
        saveReport(report);
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Family Jukebox Helper Report", reportView.getText()));
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
    }

    private void pollAndroidPlayerJob() {
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

                if (responseCode < 200 || responseCode >= 300) {
                    writePlayerJobResult("POLL HTTP " + responseCode + " at " + currentTimeText());
                    return;
                }

                JSONObject data = new JSONObject(responseText);
                JSONObject job = data.optJSONObject("job");

                if (job == null) {
                    return;
                }

                String jobId = job.optString("id", "");
                String jobType = job.optString("type", "");

                if ("test-sound".equals(jobType) && !jobId.isEmpty()) {
                    playTestSoundThenComplete(jobId);
                } else if ("test-audio-url".equals(jobType) && !jobId.isEmpty()) {
                    String audioUrl = extractJsonString(responseText, "audioUrl");
                    playAudioUrlThenComplete(jobId, audioUrl);
                } else if ("stop-audio".equals(jobType) && !jobId.isEmpty()) {
                    stopCurrentPlayback("server stop job " + jobId);
                    completeAndroidPlayerJob(jobId);
                } else if ("pause-audio".equals(jobType) && !jobId.isEmpty()) {
                    pauseCurrentPlayback("server pause job " + jobId);
                    completeAndroidPlayerJob(jobId);
                } else if ("resume-audio".equals(jobType) && !jobId.isEmpty()) {
                    resumeCurrentPlayback("server resume job " + jobId);
                    completeAndroidPlayerJob(jobId);
                } else if ("connect-bluetooth-speaker".equals(jobType) && !jobId.isEmpty()) {
                    connectBluetoothSpeakerThenComplete(jobId, job);
                } else {
                    writePlayerJobResult("UNKNOWN JOB " + jobType + " at " + currentTimeText());
                }
            } catch (Exception error) {
                writePlayerJobResult("POLL ERROR at " + currentTimeText() + ": " + error.getClass().getName() + ": " + error.getMessage());
            }
        }).start();
    }

    private void playLocalTestToneFromButton() {
        reportView.setText(reportView.getText() + "\n\nPlaying local test tone...");

        new Thread(() -> {
            try {
                playGeneratedTestTone();

                runOnUiThread(() ->
                        reportView.setText(buildReport() + "\n\nLocal test tone: played")
                );
            } catch (Exception error) {
                runOnUiThread(() ->
                        reportView.setText(buildReport() + "\n\nLocal test tone failed: "
                                + error.getClass().getName() + ": " + error.getMessage())
                );
            }
        }).start();
    }

    private void playGeneratedTestTone() throws Exception {
        final int sampleRate = 44100;
        final int durationMs = 2500;
        final double frequencyHz = 880.0;
        final double volume = 0.75;

        int sampleCount = sampleRate * durationMs / 1000;
        short[] samples = new short[sampleCount];

        for (int i = 0; i < sampleCount; i++) {
            double angle = 2.0 * Math.PI * i * frequencyHz / sampleRate;
            samples[i] = (short) (Math.sin(angle) * Short.MAX_VALUE * volume);
        }

        AudioTrack audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(samples.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();

        try {
            audioTrack.setVolume(1.0f);
            audioTrack.write(samples, 0, samples.length);
            audioTrack.play();

            try {
                Thread.sleep(durationMs + 300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }

            audioTrack.stop();
        } finally {
            audioTrack.release();
        }
    }

    private String extractJsonString(String json, String key) {
        try {
            JSONObject root = new JSONObject(json);

            if (root.has(key)) {
                return root.optString(key, "");
            }

            JSONObject job = root.optJSONObject("job");

            if (job != null && job.has(key)) {
                return job.optString(key, "");
            }
        } catch (Exception ignored) {
        }

        return "";
    }


    private void connectBluetoothSpeakerThenComplete(String jobId, JSONObject job) {
        String address = job.optString("address", "");
        String bluetoothName = job.optString("bluetoothName", "");
        String speakerName = job.optString("speakerName", bluetoothName);

        writePlayerJobResult("CONNECT BLUETOOTH job " + jobId + " at " + currentTimeText()
                + " target=" + speakerName + " address=" + address);

        String result = connectBluetoothSpeaker(address, bluetoothName, speakerName);
        writePlayerJobResult(result);
        completeAndroidPlayerJob(jobId);
    }

    private String connectBluetoothSpeaker(String address, String bluetoothName, String speakerName) {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "CONNECT BLUETOOTH failed: BLUETOOTH_CONNECT permission is not granted.";
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();

        if (adapter == null) {
            return "CONNECT BLUETOOTH failed: no Bluetooth adapter found.";
        }

        if (!adapter.isEnabled()) {
            return "CONNECT BLUETOOTH failed: Bluetooth is off.";
        }

        BluetoothDevice target = findBondedBluetoothDevice(adapter, address, bluetoothName);

        if (target == null) {
            return "CONNECT BLUETOOTH failed: target is not paired on this phone: "
                    + speakerName + " / " + bluetoothName + " / " + address;
        }

        final BluetoothA2dp[] a2dpHolder = new BluetoothA2dp[1];
        CountDownLatch latch = new CountDownLatch(1);

        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpHolder[0] = (BluetoothA2dp) proxy;
                }
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        };

        boolean requestedProxy = adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP);

        if (!requestedProxy) {
            return "CONNECT BLUETOOTH failed: Android would not provide the A2DP profile proxy.";
        }

        BluetoothA2dp a2dp = null;

        try {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                return "CONNECT BLUETOOTH failed: timed out waiting for A2DP profile proxy.";
            }

            a2dp = a2dpHolder[0];

            if (a2dp == null) {
                return "CONNECT BLUETOOTH failed: A2DP profile proxy was empty.";
            }

            int beforeState = a2dp.getConnectionState(target);
            String targetLabel = safeDeviceName(target) + " / " + address;

            if (beforeState == BluetoothProfile.STATE_CONNECTED) {
                trySetActiveA2dpDevice(a2dp, target);
                return "CONNECT BLUETOOTH already connected: " + targetLabel
                        + ". Asked Android to make it active.";
            }

            Object connectResult = invokeBluetoothA2dpMethod(a2dp, "connect", target);

            for (int attempt = 0; attempt < 16; attempt++) {
                int state = a2dp.getConnectionState(target);

                if (state == BluetoothProfile.STATE_CONNECTED) {
                    trySetActiveA2dpDevice(a2dp, target);
                    return "CONNECT BLUETOOTH connected: " + targetLabel
                            + " connectResult=" + String.valueOf(connectResult);
                }

                try {
                    Thread.sleep(750);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            return "CONNECT BLUETOOTH did not become connected: " + targetLabel
                    + " initialState=" + beforeState
                    + " connectResult=" + String.valueOf(connectResult);
        } catch (Exception error) {
            return "CONNECT BLUETOOTH failed: " + error.getClass().getName() + ": " + error.getMessage();
        } finally {
            if (a2dp != null) {
                try {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private BluetoothDevice findBondedBluetoothDevice(BluetoothAdapter adapter, String address, String bluetoothName) {
        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();

            if (bondedDevices == null) {
                return null;
            }

            for (BluetoothDevice device : bondedDevices) {
                String deviceAddress = "";

                try {
                    deviceAddress = device.getAddress();
                } catch (SecurityException ignored) {
                }

                String deviceName = safeDeviceName(device);

                if ((!address.isEmpty() && address.equalsIgnoreCase(deviceAddress)) ||
                        (!bluetoothName.isEmpty() && bluetoothName.equalsIgnoreCase(deviceName))) {
                    return device;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private Object invokeBluetoothA2dpMethod(BluetoothA2dp a2dp, String methodName, BluetoothDevice target)
            throws Exception {
        Method method;

        try {
            method = a2dp.getClass().getMethod(methodName, BluetoothDevice.class);
        } catch (NoSuchMethodException missingPublicMethod) {
            method = a2dp.getClass().getDeclaredMethod(methodName, BluetoothDevice.class);
            method.setAccessible(true);
        }

        return method.invoke(a2dp, target);
    }

    private void trySetActiveA2dpDevice(BluetoothA2dp a2dp, BluetoothDevice target) {
        try {
            invokeBluetoothA2dpMethod(a2dp, "setActiveDevice", target);
        } catch (Exception ignored) {
        }
    }


    private void pauseCurrentPlayback(String reason) {
        MediaPlayer playerToPause = null;

        synchronized (playbackLock) {
            if (currentMediaPlayer != null && !currentPlaybackPaused) {
                playerToPause = currentMediaPlayer;
                currentPlaybackPaused = true;
            }
        }

        if (playerToPause == null) {
            writePlayerJobResult("PAUSE requested at " + currentTimeText() + " but nothing was playing. Reason: " + reason);
            return;
        }

        try {
            if (playerToPause.isPlaying()) {
                playerToPause.pause();
            }
            writePlayerJobResult("PAUSED playback at " + currentTimeText() + ". Reason: " + reason);
        } catch (Exception error) {
            synchronized (playbackLock) {
                currentPlaybackPaused = false;
            }
            writePlayerJobResult("PAUSE failed at " + currentTimeText() + ": " + error.getClass().getName() + ": " + error.getMessage());
        }
    }

    private void resumeCurrentPlayback(String reason) {
        MediaPlayer playerToResume = null;

        synchronized (playbackLock) {
            if (currentMediaPlayer != null && currentPlaybackPaused) {
                playerToResume = currentMediaPlayer;
                currentPlaybackPaused = false;
            }
        }

        if (playerToResume == null) {
            writePlayerJobResult("RESUME requested at " + currentTimeText() + " but nothing was paused. Reason: " + reason);
            return;
        }

        try {
            playerToResume.start();
            writePlayerJobResult("RESUMED playback at " + currentTimeText() + ". Reason: " + reason);
        } catch (Exception error) {
            synchronized (playbackLock) {
                currentPlaybackPaused = true;
            }
            writePlayerJobResult("RESUME failed at " + currentTimeText() + ": " + error.getClass().getName() + ": " + error.getMessage());
        }
    }

    private void stopCurrentPlayback(String reason) {
        MediaPlayer playerToStop = null;

        synchronized (playbackLock) {
            if (currentMediaPlayer != null) {
                playerToStop = currentMediaPlayer;
                currentMediaPlayer = null;
                currentPlaybackJobId = "";
                currentPlaybackPaused = false;
            }
        }

        if (playerToStop == null) {
            writePlayerJobResult("STOP requested at " + currentTimeText() + " but nothing was playing. Reason: " + reason);
            return;
        }

        try {
            playerToStop.stop();
        } catch (Exception ignored) {
        }

        try {
            playerToStop.release();
        } catch (Exception ignored) {
        }

        writePlayerJobResult("STOPPED playback at " + currentTimeText() + ". Reason: " + reason);
    }

    private void playAudioUrlThenComplete(String jobId, String audioUrl) {
        MediaPlayer mediaPlayer = null;

        try {
            if (audioUrl == null || audioUrl.isEmpty()) {
                throw new IllegalArgumentException("audioUrl was empty");
            }

            synchronized (playbackLock) {
                if (jobId.equals(currentPlaybackJobId)) {
                    return;
                }

                if (currentMediaPlayer != null) {
                    stopCurrentPlayback("new audio job " + jobId);
                }

                currentPlaybackJobId = jobId;
                currentPlaybackPaused = false;
            }

            writePlayerJobResult("ACCEPTED audio URL job " + jobId + " at " + currentTimeText()
                    + "\nURL: " + audioUrl);

            completeAndroidPlayerJob(jobId);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(audioUrl);
            mediaPlayer.prepare();

            synchronized (playbackLock) {
                currentMediaPlayer = mediaPlayer;
                currentPlaybackJobId = jobId;
                currentPlaybackPaused = false;
            }

            mediaPlayer.start();

            while (true) {
                synchronized (playbackLock) {
                    if (currentMediaPlayer != mediaPlayer) {
                        break;
                    }
                }

                boolean paused;
                synchronized (playbackLock) {
                    paused = currentPlaybackPaused;
                }

                if (!paused) {
                    try {
                        if (!mediaPlayer.isPlaying()) {
                            break;
                        }
                    } catch (Exception ignored) {
                        break;
                    }
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            writePlayerJobResult("PLAYBACK ENDED for audio URL job " + jobId + " at " + currentTimeText());
        } catch (Exception error) {
            writePlayerJobResult("PLAY URL ERROR for job " + jobId + " at " + currentTimeText()
                    + ": " + error.getClass().getName() + ": " + error.getMessage());

            completeAndroidPlayerJob(jobId);
        } finally {
            boolean releaseHere = false;

            synchronized (playbackLock) {
                if (mediaPlayer != null && currentMediaPlayer == mediaPlayer) {
                    currentMediaPlayer = null;
                    currentPlaybackJobId = "";
                    currentPlaybackPaused = false;
                    releaseHere = true;
                }
            }

            if (releaseHere) {
                try {
                    mediaPlayer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }


    private void playTestSoundThenComplete(String jobId) {
        try {
            writePlayerJobResult("PLAYING server test-sound job " + jobId + " at " + currentTimeText());
            playGeneratedTestTone();
            writePlayerJobResult("PLAYED server test-sound job " + jobId + " at " + currentTimeText());
        } catch (Exception error) {
            writePlayerJobResult("PLAY ERROR for server test-sound job " + jobId + " at " + currentTimeText()
                    + ": " + error.getClass().getName() + ": " + error.getMessage());
        } finally {
            completeAndroidPlayerJob(jobId);
        }
    }


    private void completeAndroidPlayerJob(String jobId) {
        try {
            String json = "{\"jobId\":\"" + jsonEscape(jobId) + "\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);

            URL url = new URL(JUKEBOX_PLAYER_COMPLETE_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            writePlayerJobResult("COMPLETED job " + jobId + " HTTP " + responseCode + " at " + currentTimeText());
        } catch (Exception error) {
            writePlayerJobResult("COMPLETE ERROR at " + currentTimeText() + ": " + error.getClass().getName() + ": " + error.getMessage());
        }
    }

    private String readHttpResponseText(HttpURLConnection connection) throws Exception {
        int responseCode = connection.getResponseCode();
        InputStream stream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();

        if (stream == null) {
            return "";
        }

        StringBuilder text = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        }

        return text.toString();
    }

    private void writePlayerJobResult(String text) {
        try (FileOutputStream output = openFileOutput("last-player-job-result.txt", MODE_PRIVATE)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void sendStatusToJukebox(boolean updateScreen) {
        String sendMode = updateScreen ? "MANUAL" : "AUTO";

        if (updateScreen) {
            reportView.setText(reportView.getText() + "\n\nSending status to jukebox server...");
        }

        writeSendResult(sendMode + " STARTED at " + currentTimeText() + " sending to " + JUKEBOX_STATUS_URL);

        new Thread(() -> {
            try {
                String json = buildPhoneStatusJson();
                byte[] body = json.getBytes(StandardCharsets.UTF_8);

                URL url = new URL(JUKEBOX_STATUS_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }

                int responseCode = connection.getResponseCode();
                connection.disconnect();

                writeSendResult(sendMode + " HTTP response code: " + responseCode + " at " + currentTimeText());

                if (updateScreen) {
                    runOnUiThread(() -> {
                        if (responseCode >= 200 && responseCode < 300) {
                            reportView.setText(buildReport() + "\n\nSent to jukebox server: OK");
                        } else {
                            reportView.setText(buildReport() + "\n\nSent to jukebox server: failed HTTP " + responseCode);
                        }
                    });
                }
            } catch (Exception error) {
                writeSendResult(sendMode + " ERROR at " + currentTimeText() + ": " + error.getClass().getName() + ": " + error.getMessage());

                if (updateScreen) {
                    runOnUiThread(() ->
                            reportView.setText(buildReport() + "\n\nSent to jukebox server: failed - " + error.getMessage())
                    );
                }
            }
        }).start();
    }

    private String currentTimeText() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.UK)
                .format(new java.util.Date());
    }

    private void writeSendResult(String text) {
        try (FileOutputStream output = openFileOutput("last-send-result.txt", MODE_PRIVATE)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private String buildPhoneStatusJson() {
        StringBuilder json = new StringBuilder();
        String activeSpeaker = "";

        json.append("{");
        json.append("\"deviceName\":\"Julian phone\",");
        json.append("\"serverRole\":\"approved-king-device\",");
        json.append("\"androidModel\":\"").append(jsonEscape(Build.MODEL)).append("\",");
        json.append("\"androidRelease\":\"").append(jsonEscape(Build.VERSION.RELEASE)).append("\",");
        json.append("\"approvedSpeakers\":[");

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        Set<BluetoothDevice> pairedDevices = adapter != null ? adapter.getBondedDevices() : null;

        for (int index = 0; index < APPROVED_SPEAKERS.length; index++) {
            ApprovedSpeaker speaker = APPROVED_SPEAKERS[index];
            BluetoothDevice pairedDevice = findPairedDevice(pairedDevices, speaker);
            boolean paired = pairedDevice != null;
            boolean active = isAudioProductActive(speaker.bluetoothName);

            if (active) {
                activeSpeaker = speaker.roomName;
            }

            if (index > 0) {
                json.append(",");
            }

            json.append("{");
            json.append("\"room\":\"").append(jsonEscape(speaker.roomName)).append("\",");
            json.append("\"bluetoothName\":\"").append(jsonEscape(speaker.bluetoothName)).append("\",");
            json.append("\"address\":\"").append(jsonEscape(speaker.address)).append("\",");
            json.append("\"paired\":").append(paired ? "true" : "false").append(",");
            json.append("\"active\":").append(active ? "true" : "false");
            json.append("}");
        }

        json.append("],");
        json.append("\"activeSpeaker\":\"").append(jsonEscape(activeSpeaker)).append("\"");
        json.append("}");

        return json.toString();
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("Family Jukebox Helper v0.1\n");
        sb.append("Generated: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(new Date()))
                .append("\n\n");

        sb.append("PHONE\n");
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Device: ").append(Build.DEVICE).append("\n");
        sb.append("Product: ").append(Build.PRODUCT).append("\n");
        sb.append("Android SDK: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Android release: ").append(Build.VERSION.RELEASE).append("\n\n");

        appendApprovedSpeakerMatches(sb);
        appendBluetoothReport(sb);
        appendAudioOutputReport(sb);

        return sb.toString();
    }

    private void appendApprovedSpeakerMatches(StringBuilder sb) {
        sb.append("APPROVED HOUSEHOLD SPEAKER MATCHES\n");

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            sb.append("Bluetooth permission not granted yet.\n\n");
            return;
        }

        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            Set<BluetoothDevice> pairedDevices = adapter != null ? adapter.getBondedDevices() : null;

            for (ApprovedSpeaker speaker : APPROVED_SPEAKERS) {
                BluetoothDevice pairedDevice = findPairedDevice(pairedDevices, speaker);
                boolean paired = pairedDevice != null;
                boolean active = isAudioProductActive(speaker.bluetoothName);

                sb.append("- ").append(speaker.roomName).append("\n");
                sb.append("  Expected device: ").append(speaker.bluetoothName).append("\n");
                sb.append("  Address: ").append(speaker.address).append("\n");
                sb.append("  Paired on this phone: ").append(paired ? "yes" : "no").append("\n");
                sb.append("  Active audio output: ").append(active ? "yes" : "no").append("\n");

                if (pairedDevice != null) {
                    sb.append("  Phone sees it as: ").append(safeDeviceName(pairedDevice)).append("\n");
                }
            }

            sb.append("\n");
        } catch (Exception error) {
            sb.append("Approved speaker match error: ").append(error.getMessage()).append("\n\n");
        }
    }

    private BluetoothDevice findPairedDevice(Set<BluetoothDevice> pairedDevices, ApprovedSpeaker speaker) {
        if (pairedDevices == null) {
            return null;
        }

        for (BluetoothDevice device : pairedDevices) {
            String address = "";

            try {
                address = device.getAddress();
            } catch (SecurityException ignored) {
            }

            String name = safeDeviceName(device);

            if (speaker.address.equalsIgnoreCase(address) ||
                    speaker.bluetoothName.equalsIgnoreCase(name)) {
                return device;
            }
        }

        return null;
    }

    private boolean isAudioProductActive(String expectedProductName) {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

            for (AudioDeviceInfo device : devices) {
                String productName = String.valueOf(device.getProductName());

                if (audioDeviceTypeName(device.getType()).equals("Bluetooth audio") &&
                        productName.equalsIgnoreCase(expectedProductName)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private void appendBluetoothReport(StringBuilder sb) {
        sb.append("PAIRED BLUETOOTH DEVICES\n");

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            sb.append("Bluetooth permission not granted yet.\n\n");
            return;
        }

        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();

            if (adapter == null) {
                sb.append("No Bluetooth adapter found.\n\n");
                return;
            }

            sb.append("Bluetooth enabled: ").append(adapter.isEnabled()).append("\n");

            Set<BluetoothDevice> devices = adapter.getBondedDevices();

            if (devices == null || devices.isEmpty()) {
                sb.append("No paired Bluetooth devices found.\n\n");
                return;
            }

            for (BluetoothDevice device : devices) {
                sb.append("- Name: ").append(safeDeviceName(device)).append("\n");
                sb.append("  Address: ").append(device.getAddress()).append("\n");
                sb.append("  Type: ").append(device.getType()).append("\n");
                sb.append("  Bond state: ").append(device.getBondState()).append("\n");
            }

            sb.append("\n");
        } catch (Exception error) {
            sb.append("Bluetooth error: ").append(error.getMessage()).append("\n\n");
        }
    }

    private void appendAudioOutputReport(StringBuilder sb) {
        sb.append("CURRENT AND AVAILABLE AUDIO OUTPUTS\n");

        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

            if (devices.length == 0) {
                sb.append("No output devices reported.\n\n");
                return;
            }

            for (AudioDeviceInfo device : devices) {
                sb.append("- Type: ").append(audioDeviceTypeName(device.getType())).append("\n");
                sb.append("  Product: ").append(device.getProductName()).append("\n");
                sb.append("  ID: ").append(device.getId()).append("\n");
                sb.append("  Sink: ").append(device.isSink()).append("\n");
            }

            sb.append("\n");
        } catch (Exception error) {
            sb.append("Audio output error: ").append(error.getMessage()).append("\n\n");
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "(unnamed)" : name;
        } catch (SecurityException error) {
            return "(permission blocked)";
        }
    }

    private String audioDeviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Phone speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "Phone earpiece";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth audio";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth call audio";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB audio device";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB headset";
            case AudioDeviceInfo.TYPE_HDMI:
                return "HDMI";
            default:
                return "Other audio output (" + type + ")";
        }
    }

    private void saveReport(String report) {
        try (FileOutputStream output = openFileOutput("phone-status.txt", MODE_PRIVATE)) {
            output.write(report.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
