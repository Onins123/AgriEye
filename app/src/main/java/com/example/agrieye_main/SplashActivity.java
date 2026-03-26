package com.example.agrieye_main;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Show the logo for 2.5 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);

            // This adds a smooth fade transition between the logo and the disclaimer
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

            finish(); // Prevents user from going back to the splash screen
        }, 2500);
    }
}