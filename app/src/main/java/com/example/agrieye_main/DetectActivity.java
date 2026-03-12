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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class DetectActivity extends AppCompatActivity {

    private static final String TAG = "DetectActivity";

    // ── State ─────────────────────────────────────────────────────────────────
    private String  imagePath;
    private Bitmap  loadedBitmap;
    private boolean isPalayDetected   = false;
    private boolean isDetectionFailed = false;

    // Stored from YOLO result to pass on to AnalysisResultActivity
    private float detectedConfidence = 0f;

    // ── YOLO ──────────────────────────────────────────────────────────────────
    private YoloClassifier classifier;

    // ── Views ─────────────────────────────────────────────────────────────────
    private ImageView    ivDetectImage;
    private Button       btnAction;
    private Button       btnRetake;
    private ProgressBar  progressBar;
    private View         viewOverlay;
    private TextView     tvInfoMessage;
    private LinearLayout confidenceLayout;
    private TextView     tvConfidenceValue;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);

        // Bind views
        ivDetectImage     = findViewById(R.id.ivDetectImage);
        btnAction         = findViewById(R.id.btnAction);
        btnRetake         = findViewById(R.id.btnRetake);
        progressBar       = findViewById(R.id.progressBar);
        viewOverlay       = findViewById(R.id.viewOverlay);
        tvInfoMessage     = findViewById(R.id.tvInfoMessage);
        confidenceLayout  = findViewById(R.id.confidenceLayout);
        tvConfidenceValue = findViewById(R.id.tvConfidenceValue);

        // Load image passed from CameraActivity or MainActivity (gallery)
        imagePath    = getIntent().getStringExtra("image_path");
        loadedBitmap = loadBitmapFromPath(imagePath);
        if (loadedBitmap != null) {
            ivDetectImage.setImageBitmap(loadedBitmap);
        }

        // Load YOLOv8 model — fast (just memory-maps the .tflite file)
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
            onDestroy();
            goToCamera();
        });

        // ── Main action button ────────────────────────────────────────────────
        btnAction.setOnClickListener(v -> {

            if (isDetectionFailed) {
                // After failed detection → "Retake" goes back to camera
                deleteCurrentPhoto();
                goToCamera();

            } else if (!isPalayDetected) {
                // ── Step 1: Run YOLO detection ────────────────────────────────
                showLoadingState("Detecting…");

                new Thread(() -> {
                    List<YoloClassifier.DetectionResult> results = null;
                    if (loadedBitmap != null) {
                        results = classifier.detect(loadedBitmap);
                    }
                    final List<YoloClassifier.DetectionResult> finalResults = results;

                    runOnUiThread(() -> {
                        hideLoadingState();

                        if (finalResults != null && !finalResults.isEmpty()) {
                            // ── Palay / Rice Leaf detected ────────────────────
                            isPalayDetected = true;

                            YoloClassifier.DetectionResult best = finalResults.get(0);
                            detectedConfidence = best.confidence; // save for later

                            int confidencePct = Math.round(best.confidence * 100);

                            // Draw bounding box overlay on the ImageView
                            Bitmap withMask = classifier.renderMask(loadedBitmap, finalResults);
                            ivDetectImage.setImageBitmap(withMask);

                            // Save the annotated bitmap so AnalysisResultActivity
                            // can show the image with the box drawn on it
                            saveAnnotatedBitmap(withMask);

                            tvInfoMessage.setText("Palay Detected!");
                            tvInfoMessage.setVisibility(View.VISIBLE);

                            confidenceLayout.setVisibility(View.VISIBLE);
                            tvConfidenceValue.setText(confidencePct + "%");

                            btnAction.setText("Analyze");
                            btnRetake.setVisibility(View.VISIBLE);

                        } else {
                            // ── No rice leaf detected ─────────────────────────
                            isDetectionFailed = true;

                            tvInfoMessage.setText("No Palay Detected!\nPlease retake the photo.");
                            tvInfoMessage.setVisibility(View.VISIBLE);

                            confidenceLayout.setVisibility(View.GONE);
                            btnAction.setText("Retake");
                        }
                    });
                }).start();

            } else {
                // ── Step 2: Detected — move to disease analysis ───────────────
                // NOTE: Your current model only detects RICE LEAF (is it palay or not).
                // Disease classification would require a second model.
                // For now we pass the confidence as "diseased_percentage" as a placeholder
                // so AnalysisResultActivity renders correctly.
                // Replace disease_name + diseased_percentage with your disease model later.

                showLoadingState("Analyzing…");
                btnRetake.setEnabled(false);

                Intent intent = new Intent(DetectActivity.this, AnalysisResultActivity.class);
                intent.putExtra("image_path",          imagePath);

                // ── TODO: Replace these two lines with real disease model output ──
                intent.putExtra("disease_name",        "Bacterial Leaf Blight");
                intent.putExtra("diseased_percentage", (double)(detectedConfidence * 100));
                // ─────────────────────────────────────────────────────────────────

                startActivity(intent);

                hideLoadingState();
                btnRetake.setEnabled(true);
            }
        });
    }

    // ── Saves annotated bitmap back over the original cached file ────────────
    private void saveAnnotatedBitmap(Bitmap bitmap) {
        if (imagePath == null) return;
        try {
            FileOutputStream out = new FileOutputStream(new File(imagePath));
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save annotated bitmap: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        startActivity(new Intent(DetectActivity.this, CameraActivity.class));
        finish();
    }

    private Bitmap loadBitmapFromPath(String path) {
        if (path == null) { Log.e(TAG, "image_path is null"); return null; }
        File f = new File(path);
        if (!f.exists()) { Log.e(TAG, "File not found: " + path); return null; }
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp == null) Log.e(TAG, "Failed to decode: " + path);
        return bmp;
    }

    private void deleteCurrentPhoto() {
        if (imagePath != null) {
            File f = new File(imagePath);
            if (f.exists()) f.delete();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (classifier != null) classifier.close();
    }
}