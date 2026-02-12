package com.example.agrieye_main;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

public class ExitActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exit);

        setFinishOnTouchOutside(false); // User must click a button

        MaterialButton btnExitYes = findViewById(R.id.btnExitYes);
        MaterialButton btnExitNo = findViewById(R.id.btnExitNo);

        btnExitYes.setOnClickListener(v -> {
            finishAffinity(); // Closes the whole app
        });

        btnExitNo.setOnClickListener(v -> {
            finish(); // Just closes the modal
        });
    }
}