package com.joolz.familyjukeboxhelper;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class JukeboxLaunchActivity extends Activity {
    private static final String JUKEBOX_WEB_URL = "http://192.168.1.252:3010";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent serviceIntent = new Intent(this, PlayerService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(JUKEBOX_WEB_URL));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(browserIntent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No browser found for Family Jukebox", Toast.LENGTH_LONG).show();
        }

        finish();
    }
}
