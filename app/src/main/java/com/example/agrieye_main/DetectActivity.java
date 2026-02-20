package com.example.agrieye_main;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

public class DetectActivity extends AppCompatActivity {

    private String imagePath;
    private boolean isPalayDetected = false;
    private boolean isDetectionFailed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);

        ImageView ivDetectImage = findViewById(R.id.ivDetectImage);
        Button btnAction = findViewById(R.id.btnAction);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        View viewOverlay = findViewById(R.id.viewOverlay);
        TextView tvInfoMessage = findViewById(R.id.tvInfoMessage);
        LinearLayout confidenceLayout = findViewById(R.id.confidenceLayout);
        TextView tvConfidenceValue = findViewById(R.id.tvConfidenceValue);

        imagePath = getIntent().getStringExtra("image_path");

        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                ivDetectImage.setImageBitmap(myBitmap);
            }
        }

        btnAction.setOnClickListener(v -> {
            if (isDetectionFailed) {
                // --- Retake Logic ---
                deleteCurrentPhoto();
                Intent intent = new Intent(DetectActivity.this, CameraActivity.class);
                startActivity(intent);
                finish();

            } else if (!isPalayDetected) {
                // --- Detection Logic ---
                progressBar.setVisibility(View.VISIBLE);
                viewOverlay.setVisibility(View.VISIBLE);
                btnAction.setText("Detecting");
                btnAction.setEnabled(false);

                new Handler().postDelayed(() -> {
                    // --- Simulate Detection ---
                    boolean detectionSuccess = false; // Replace with your actual detection logic

                    if (detectionSuccess) {
                        isPalayDetected = true;
                        tvInfoMessage.setText("Palay Detected!");
                        tvInfoMessage.setVisibility(View.VISIBLE);
                        confidenceLayout.setVisibility(View.VISIBLE);
                        tvConfidenceValue.setText("98%"); // Replace with actual confidence
                        btnAction.setText("Analyze");
                    } else {
                        isDetectionFailed = true;
                        tvInfoMessage.setText("No Palay Detected!");
                        tvInfoMessage.setVisibility(View.VISIBLE);
                        btnAction.setText("Retake");
                    }

                    progressBar.setVisibility(View.GONE);
                    viewOverlay.setVisibility(View.GONE);
                    btnAction.setEnabled(true);
                }, 3000); // 3-second delay

            } else {
                // --- Analysis Logic ---
                progressBar.setVisibility(View.VISIBLE);
                viewOverlay.setVisibility(View.VISIBLE);
                btnAction.setText("Analyzing");
                btnAction.setEnabled(false);

                new Handler().postDelayed(() -> {
                    // Start AnalysisResultActivity
                    Intent intent = new Intent(DetectActivity.this, AnalysisResultActivity.class);
                    intent.putExtra("image_path", imagePath);
                    startActivity(intent);

                    // Reset the UI on this activity for when the user returns
                    progressBar.setVisibility(View.GONE);
                    viewOverlay.setVisibility(View.GONE);
                    btnAction.setText("Analyze");
                    btnAction.setEnabled(true);
                }, 3000); // 3-second delay
            }
        });
    }

    private void deleteCurrentPhoto() {
        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                imgFile.delete();
            }
        }
    }
}
