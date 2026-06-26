package com.joolz.familyjukeboxhelper;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH_CONNECT = 1001;
    private static final String JUKEBOX_STATUS_URL = "http://192.168.1.252:3010/api/phone-status";
    private TextView reportView;

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
            new ApprovedSpeaker("Conservatory", "Echo Dot-HSS", "4C:17:44:8F:15:C9")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 28, 28, 28);

        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh phone status");
        layout.addView(refreshButton);

        Button copyButton = new Button(this);
        copyButton.setText("Copy report");
        layout.addView(copyButton);

        Button sendButton = new Button(this);
        sendButton.setText("Send status to jukebox");
        layout.addView(sendButton);

        reportView = new TextView(this);
        reportView.setTextSize(15);
        reportView.setTextIsSelectable(true);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(reportView);
        layout.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        setContentView(layout);

        refreshButton.setOnClickListener(v -> refreshReport());
        copyButton.setOnClickListener(v -> copyReport());
        sendButton.setOnClickListener(v -> sendStatusToJukebox());

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

    private void sendStatusToJukebox() {
        reportView.setText(reportView.getText() + "\n\nSending status to jukebox server...");
        writeSendResult("STARTED sending to " + JUKEBOX_STATUS_URL);

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

                writeSendResult("HTTP response code: " + responseCode);

                runOnUiThread(() -> {
                    if (responseCode >= 200 && responseCode < 300) {
                        reportView.setText(buildReport() + "\n\nSent to jukebox server: OK");
                    } else {
                        reportView.setText(buildReport() + "\n\nSent to jukebox server: failed HTTP " + responseCode);
                    }
                });
            } catch (Exception error) {
                writeSendResult("ERROR: " + error.getClass().getName() + ": " + error.getMessage());

                runOnUiThread(() ->
                        reportView.setText(buildReport() + "\n\nSent to jukebox server: failed - " + error.getMessage())
                );
            }
        }).start();
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
