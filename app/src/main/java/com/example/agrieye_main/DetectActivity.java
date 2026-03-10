package com.example.agrieye_main;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;

public class DetectActivity extends AppCompatActivity {

    private static final String TAG = "DetectActivity";

    // ── State ────────────────────────────────────────────────────────────────
    private String  imagePath;
    private Bitmap  loadedBitmap;
    private boolean isPalayDetected   = false;
    private boolean isDetectionFailed = false;

    // ── YOLO ─────────────────────────────────────────────────────────────────
    private YoloClassifier classifier;

    // ── Views ────────────────────────────────────────────────────────────────
    private ImageView   ivDetectImage;
    private Button      btnAction;
    private Button      btnRetake;
    private ProgressBar progressBar;
    private View        viewOverlay;
    private TextView    tvInfoMessage;
    private LinearLayout confidenceLayout;
    private TextView    tvConfidenceValue;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);

        // Bind views
        ivDetectImage    = findViewById(R.id.ivDetectImage);
        btnAction        = findViewById(R.id.btnAction);
        btnRetake        = findViewById(R.id.btnRetake);
        progressBar      = findViewById(R.id.progressBar);
        viewOverlay      = findViewById(R.id.viewOverlay);
        tvInfoMessage    = findViewById(R.id.tvInfoMessage);
        confidenceLayout = findViewById(R.id.confidenceLayout);
        tvConfidenceValue = findViewById(R.id.tvConfidenceValue);

        // Load image from path passed by CameraActivity
        imagePath = getIntent().getStringExtra("image_path");
        loadedBitmap = loadBitmapFromPath(imagePath);
        if (loadedBitmap != null) {
            ivDetectImage.setImageBitmap(loadedBitmap);
        }

        // Load YOLOv8 model (fast — just memory-maps the file)
        classifier = new YoloClassifier(this);
        boolean modelLoaded = classifier.initialize();
        if (!modelLoaded) {
            Log.e(TAG, "YOLOv8 model failed to load.");
            tvInfoMessage.setText("Model failed to load. Please reinstall the app.");
            tvInfoMessage.setVisibility(View.VISIBLE);
            btnAction.setEnabled(false);
        }

        // ── Retake button ─────────────────────────────────────────────────────
        btnRetake.setOnClickListener(v -> {
            deleteCurrentPhoto();
            goToCamera();
        });

        // ── Action button (Detect → Analyze / Retake) ─────────────────────────
        btnAction.setOnClickListener(v -> {

            if (isDetectionFailed) {
                // "Retake" after failed detection
                deleteCurrentPhoto();
                goToCamera();

            } else if (!isPalayDetected) {
                // ── Run YOLO detection ────────────────────────────────────────
                showLoadingState("Detecting");

                new Thread(() -> {
                    List<YoloClassifier.DetectionResult> results = null;

                    if (loadedBitmap != null) {
                        results = classifier.detect(loadedBitmap);
                    }

                    final List<YoloClassifier.DetectionResult> finalResults = results;

                    runOnUiThread(() -> {
                        hideLoadingState();

                        if (finalResults != null && !finalResults.isEmpty()) {
                            // ── Palay detected ────────────────────────────────
                            isPalayDetected = true;

                            YoloClassifier.DetectionResult best = finalResults.get(0);
                            int confidencePct = Math.round(best.confidence * 100);

                            // Draw segmentation mask overlay on the image
                            Bitmap withMask = classifier.renderMask(loadedBitmap, finalResults);
                            ivDetectImage.setImageBitmap(withMask);

                            tvInfoMessage.setText("Palay Detected!");
                            tvInfoMessage.setVisibility(View.VISIBLE);

                            confidenceLayout.setVisibility(View.VISIBLE);
                            tvConfidenceValue.setText(confidencePct + "%");

                            btnAction.setText("Analyze");
                            btnRetake.setVisibility(View.VISIBLE);

                        } else {
                            // ── No palay detected ─────────────────────────────
                            isDetectionFailed = true;

                            tvInfoMessage.setText("No Palay Detected!");
                            tvInfoMessage.setVisibility(View.VISIBLE);

                            btnAction.setText("Retake");
                            // btnRetake stays hidden; only the single "Retake" btnAction shows
                        }
                    });
                }).start();

            } else {
                // ── Already detected — go to analysis ────────────────────────
                showLoadingState("Analyzing");
                btnRetake.setEnabled(false);

                // Navigate to AnalysisResultActivity
                // (keeping your existing intent extras — replace disease data
                //  with real disease model results when ready)
                Intent intent = new Intent(DetectActivity.this, AnalysisResultActivity.class);
                intent.putExtra("image_path", imagePath);
                intent.putExtra("disease_name", "Bacterial Leaf Blight");   // TODO: replace with real model output
                intent.putExtra("diseased_percentage", 15.0);               // TODO: replace with real model output
                startActivity(intent);

                // Reset UI state for when user navigates back
                hideLoadingState();
                btnRetake.setEnabled(true);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void showLoadingState(String buttonLabel) {
        progressBar.setVisibility(View.VISIBLE);
        viewOverlay.setVisibility(View.VISIBLE);
        btnAction.setText(buttonLabel);
        btnAction.setEnabled(false);
    }

    private void hideLoadingState() {
        progressBar.setVisibility(View.GONE);
        viewOverlay.setVisibility(View.GONE);
        btnAction.setEnabled(true);
    }

    private void goToCamera() {
        Intent intent = new Intent(DetectActivity.this, CameraActivity.class);
        startActivity(intent);
        finish();
    }

    private Bitmap loadBitmapFromPath(String path) {
        if (path == null) {
            Log.e(TAG, "image_path extra is null");
            return null;
        }
        File imgFile = new File(path);
        if (!imgFile.exists()) {
            Log.e(TAG, "Image file not found: " + path);
            return null;
        }
        Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
        if (bmp == null) Log.e(TAG, "Failed to decode bitmap: " + path);
        return bmp;
    }

    private void deleteCurrentPhoto() {
        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) imgFile.delete();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (classifier != null) classifier.close();
    }
}