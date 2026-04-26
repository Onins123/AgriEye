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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class DetectActivity extends AppCompatActivity {

    private static final String TAG = "DetectActivity";

    // ── State ─────────────────────────────────────────────────────────────────
    private String  imagePath;
    private Bitmap  loadedBitmap;
    private boolean isPalayDetected   = false;
    private boolean isDetectionFailed = false;
    private boolean isFromCamera      = false;

    // Stored from Model 1 result
    private float detectedConfidence = 0f;

    // ── Models ────────────────────────────────────────────────────────────────
    private YoloClassifier    classifier;        // Model 1: palay/leaf presence
    private DiseaseClassifier diseaseClassifier; // Model 2: disease + severity

    // ── Views ─────────────────────────────────────────────────────────────────
    private ImageView    ivDetectImage;
    private Button       btnAction;
    private Button       btnRetake;
    private ProgressBar  progressBar;
    private View         viewOverlay;
    private TextView     tvInfoMessage;
    private LinearLayout confidenceLayout;
    private TextView     tvConfidenceValue;

    private ActivityResultLauncher<String> galleryLauncher;

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
        isFromCamera = getIntent().getBooleanExtra("is_from_camera", false);
        loadedBitmap = loadBitmapFromPath(imagePath);
        btnRetake.setText(isFromCamera ? "Retake" : "Re-import");
        if (loadedBitmap != null) ivDetectImage.setImageBitmap(loadedBitmap);

        // ── Load Model 1: Palay/Leaf detector ────────────────────────────────
        classifier = new YoloClassifier(this);
        if (!classifier.initialize()) {
            Log.e(TAG, "Model 1 (YoloClassifier) failed to load.");
            tvInfoMessage.setText("Leaf detection model failed to load. Please reinstall the app.");
            tvInfoMessage.setVisibility(View.VISIBLE);
            btnAction.setEnabled(false);
        }

        // ── Load Model 2: Disease classifier ─────────────────────────────────
        diseaseClassifier = new DiseaseClassifier(this);
        if (!diseaseClassifier.initialize()) {
            // Non-fatal — we log it, but still allow leaf detection (Step 1) to work.
            // Step 2 (Analyze) will show an error if Model 2 is unavailable.
            Log.e(TAG, "Model 2 (DiseaseClassifier) failed to load.");
        }

        // ── Gallery launcher ──────────────────────────────────────────────────
        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        File savedFile = copyUriToCache(uri);
                        if (savedFile != null) {
                            deleteCurrentPhoto();
                            imagePath    = savedFile.getAbsolutePath();
                            loadedBitmap = loadBitmapFromPath(imagePath);
                            ivDetectImage.setImageBitmap(loadedBitmap);

                            // Reset state for the new image
                            isPalayDetected   = false;
                            isDetectionFailed = false;
                            isFromCamera      = false;
                            btnAction.setText("Detect");
                            btnRetake.setVisibility(View.GONE);
                            btnRetake.setText("Re-import");
                            tvInfoMessage.setVisibility(View.GONE);
                            confidenceLayout.setVisibility(View.GONE);
                        }
                    }
                }
        );

        // ── Retake / Re-import button ─────────────────────────────────────────
        btnRetake.setOnClickListener(v -> {
            if (isFromCamera) { deleteCurrentPhoto(); goToCamera(); }
            else              { galleryLauncher.launch("image/*"); }
        });

        // ── Main action button ────────────────────────────────────────────────
        btnAction.setOnClickListener(v -> {

            if (isDetectionFailed) {
                // Failure state → allow retry
                if (isFromCamera) { deleteCurrentPhoto(); goToCamera(); }
                else              { galleryLauncher.launch("image/*"); }

            } else if (!isPalayDetected) {
                // ══════════════════════════════════════════════════════════════
                //  STEP 1 — Run Model 1: Is there a rice/palay leaf here?
                // ══════════════════════════════════════════════════════════════
                showLoadingState("Detecting…");

                new Thread(() -> {
                    List<YoloClassifier.DetectionResult> results = null;
                    if (loadedBitmap != null) results = classifier.detect(loadedBitmap);
                    final List<YoloClassifier.DetectionResult> finalResults = results;

                    runOnUiThread(() -> {
                        hideLoadingState();

                        if (finalResults != null && !finalResults.isEmpty()) {
                            // ── Palay leaf detected ───────────────────────────
                            isPalayDetected  = true;
                            YoloClassifier.DetectionResult best = finalResults.get(0);
                            detectedConfidence = best.confidence;

                            // Draw Model 1 bounding box
                            Bitmap withBox = classifier.renderMask(loadedBitmap, finalResults);
                            ivDetectImage.setImageBitmap(withBox);
                            saveAnnotatedBitmap(withBox);

                            int confidencePct = Math.round(best.confidence * 100);
                            tvInfoMessage.setText("Palay Detected!");
                            tvInfoMessage.setVisibility(View.VISIBLE);
                            confidenceLayout.setVisibility(View.VISIBLE);
                            tvConfidenceValue.setText(confidencePct + "%");

                            btnAction.setText("Analyze");
                            btnRetake.setVisibility(View.VISIBLE);

                        } else {
                            // ── No leaf detected ──────────────────────────────
                            isDetectionFailed = true;
                            String msg = isFromCamera
                                    ? "         No Palay Detected!\nPlease retake the photo."
                                    : "         No Palay Detected!\nPlease re-import the photo.";
                            tvInfoMessage.setText(msg);
                            tvInfoMessage.setVisibility(View.VISIBLE);
                            confidenceLayout.setVisibility(View.GONE);
                            btnAction.setText(isFromCamera ? "Retake" : "Re-import");
                        }
                    });
                }).start();

            } else {
                // ══════════════════════════════════════════════════════════════
                //  STEP 2 — Run Model 2: What disease is it? How severe?
                // ══════════════════════════════════════════════════════════════
                showLoadingState("Analyzing…");
                btnRetake.setEnabled(false);

                new Thread(() -> {
                    DiseaseClassifier.AnalysisSummary summary = null;

                    if (loadedBitmap != null) {
                        // Use the original (non-annotated) bitmap for disease inference
                        // so Model 1's bounding box doesn't interfere with Model 2's input.
                        summary = diseaseClassifier.analyze(loadedBitmap);
                    }

                    final DiseaseClassifier.AnalysisSummary finalSummary = summary;

                    runOnUiThread(() -> {
                        hideLoadingState();
                        btnRetake.setEnabled(true);

                        if (finalSummary == null) {
                            // Disease model unavailable
                            tvInfoMessage.setText("Disease analysis unavailable. Check that disease.tflite is in assets.");
                            tvInfoMessage.setVisibility(View.VISIBLE);
                            return;
                        }

                        // Show the disease-annotated bitmap in the preview
                        if (finalSummary.annotatedBitmap != null) {
                            ivDetectImage.setImageBitmap(finalSummary.annotatedBitmap);
                            saveAnnotatedBitmap(finalSummary.annotatedBitmap);
                        }

                        // Navigate to AnalysisResultActivity with real model output
                        Intent intent = new Intent(DetectActivity.this, AnalysisResultActivity.class);
                        intent.putExtra("image_path",          imagePath);
                        intent.putExtra("disease_name",        finalSummary.diseaseName);
                        intent.putExtra("diseased_percentage", finalSummary.diseasedPercentage);
                        startActivity(intent);
                    });
                }).start();
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveAnnotatedBitmap(Bitmap bitmap) {
        if (imagePath == null) return;
        try {
            FileOutputStream out = new FileOutputStream(new File(imagePath));
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush(); out.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save annotated bitmap: " + e.getMessage());
        }
    }

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

    private File copyUriToCache(Uri uri) {
        try {
            InputStream  in   = getContentResolver().openInputStream(uri);
            File         file = new File(getCacheDir(), "imported_leaf_" + System.currentTimeMillis() + ".jpg");
            OutputStream out  = new FileOutputStream(file);
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
            out.close(); in.close();
            return file;
        } catch (Exception e) { e.printStackTrace(); return null; }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (classifier       != null) classifier.close();
        if (diseaseClassifier != null) diseaseClassifier.close();
    }
}