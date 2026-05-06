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
    private float detectedConfidence  = 0f;

    /**
     * Leaf mask pixel count from best.tflite (Model 1).
     * Populated after a successful Step 1 detect() call.
     * Passed to DiseaseClassifier.analyze() as the severity denominator.
     */
    private long leafMaskPixelCount = 0L;

    // ── Models ────────────────────────────────────────────────────────────────
    private YoloClassifier    classifier;        // Model 1: best.tflite  — palay/leaf presence + leaf mask
    private DiseaseClassifier diseaseClassifier; // Model 2: disease.tflite — disease (4 classes)

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

        // ── Load Model 1: Palay/Leaf detector (best.tflite) ──────────────────
        classifier = new YoloClassifier(this);
        if (!classifier.initialize()) {
            Log.e(TAG, "Model 1 (YoloClassifier / best.tflite) failed to load.");
            tvInfoMessage.setText("Leaf detection model failed to load. Please reinstall the app.");
            tvInfoMessage.setVisibility(View.VISIBLE);
            btnAction.setEnabled(false);
        }

        // ── Load Model 2: Disease classifier (disease.tflite) ────────────────
        diseaseClassifier = new DiseaseClassifier(this);
        if (!diseaseClassifier.initialize()) {
            // Non-fatal — log it, but still allow Step 1 to work.
            Log.e(TAG, "Model 2 (DiseaseClassifier / disease.tflite) failed to load.");
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
                            leafMaskPixelCount = 0L;
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
                //  STEP 1 — Run Model 1 (best.tflite):
                //           Detect palay leaf presence AND extract leaf mask.
                // ══════════════════════════════════════════════════════════════
                showLoadingState("Detecting…");

                new Thread(() -> {
                    List<YoloClassifier.DetectionResult> results = null;
                    long leafPixels = 0L;

                    if (loadedBitmap != null) {
                        results    = classifier.detect(loadedBitmap);
                        // Grab the leaf pixel count immediately after detect()
                        // while proto masks are still in memory.
                        leafPixels = classifier.getLeafMaskPixelCount();
                    }

                    final List<YoloClassifier.DetectionResult> finalResults = results;
                    final long finalLeafPixels = leafPixels;

                    runOnUiThread(() -> {
                        hideLoadingState();

                        if (finalResults != null && !finalResults.isEmpty()) {
                            // ── Palay leaf detected ───────────────────────────
                            isPalayDetected    = true;
                            leafMaskPixelCount = finalLeafPixels; // store for Step 2

                            YoloClassifier.DetectionResult best = finalResults.get(0);
                            detectedConfidence = best.confidence;

                            // Draw Model 1 bounding-box / mask overlay
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

                            Log.d(TAG, "Step 1 complete — leafMaskPixelCount=" + leafMaskPixelCount);

                        } else {
                            // ── No leaf detected ──────────────────────────────
                            isDetectionFailed = true;
                            leafMaskPixelCount = 0L;
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
                //  STEP 2 — Run Model 2 (disease.tflite):
                //           Classify disease and compute severity using the
                //           leaf mask pixel count from Step 1.
                // ══════════════════════════════════════════════════════════════
                showLoadingState("Analyzing…");
                btnRetake.setEnabled(false);

                // Capture for lambda (leafMaskPixelCount is a field, but we
                // snapshot it here for thread safety).
                final long leafPixelsForAnalysis = leafMaskPixelCount;

                new Thread(() -> {
                    DiseaseClassifier.AnalysisSummary summary = null;

                    if (loadedBitmap != null) {
                        // Use the original (non-annotated) bitmap so Model 1's
                        // overlay doesn't interfere with Model 2's input.
                        summary = diseaseClassifier.analyze(loadedBitmap, leafPixelsForAnalysis);
                    }

                    final DiseaseClassifier.AnalysisSummary finalSummary = summary;

                    runOnUiThread(() -> {
                        hideLoadingState();
                        btnRetake.setEnabled(true);

                        if (finalSummary == null) {
                            tvInfoMessage.setText(
                                    "Disease analysis unavailable. Check that disease.tflite is in assets.");
                            tvInfoMessage.setVisibility(View.VISIBLE);
                            return;
                        }

                        // Show the disease-annotated bitmap in the preview
                        if (finalSummary.annotatedBitmap != null) {
                            ivDetectImage.setImageBitmap(finalSummary.annotatedBitmap);
                            saveAnnotatedBitmap(finalSummary.annotatedBitmap);
                        }

                        // Navigate to AnalysisResultActivity
                        Intent intent = new Intent(DetectActivity.this, AnalysisResultActivity.class);
                        intent.putExtra("image_path",           imagePath);
                        intent.putExtra("disease_name",         finalSummary.diseaseName);
                        intent.putExtra("diseased_percentage",  finalSummary.diseasedPercentage);
                        intent.putExtra("average_confidence",   finalSummary.averageConfidence);
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
        if (classifier        != null) classifier.close();
        if (diseaseClassifier != null) diseaseClassifier.close();
    }
}