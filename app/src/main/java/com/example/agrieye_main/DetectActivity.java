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
import androidx.cardview.widget.CardView;

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

    private float detectedConfidence  = 0f;
    private long  leafMaskPixelCount  = 0L;

    // ── Models ────────────────────────────────────────────────────────────────
    private YoloClassifier    classifier;
    private DiseaseClassifier diseaseClassifier;

    // ── Views ─────────────────────────────────────────────────────────────────
    private ImageView    ivDetectImage;
    private Button       btnAction;
    private Button       btnRetake;
    private ProgressBar  progressBar;
    private View         viewOverlay;
    private TextView     tvInfoMessage;
    private LinearLayout confidenceLayout;
    private TextView     tvConfidenceValue;

    // New views for the failure card
    private CardView     cardNoDetection;
    private TextView     tvNoDetectionHint;
    private LinearLayout layoutDetectFailOverlay;

    private ActivityResultLauncher<String> galleryLauncher;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);

        // Bind views
        ivDetectImage          = findViewById(R.id.ivDetectImage);
        btnAction              = findViewById(R.id.btnAction);
        btnRetake              = findViewById(R.id.btnRetake);
        progressBar            = findViewById(R.id.progressBar);
        viewOverlay            = findViewById(R.id.viewOverlay);
        tvInfoMessage          = findViewById(R.id.tvInfoMessage);
        confidenceLayout       = findViewById(R.id.confidenceLayout);
        tvConfidenceValue      = findViewById(R.id.tvConfidenceValue);
        cardNoDetection        = findViewById(R.id.cardNoDetection);
        tvNoDetectionHint      = findViewById(R.id.tvNoDetectionHint);
        layoutDetectFailOverlay = findViewById(R.id.layoutDetectFailOverlay);

        // Load image
        imagePath    = getIntent().getStringExtra("image_path");
        isFromCamera = getIntent().getBooleanExtra("is_from_camera", false);
        loadedBitmap = loadBitmapFromPath(imagePath);
        btnRetake.setText(isFromCamera ? "Retake" : "Re-import");
        if (loadedBitmap != null) ivDetectImage.setImageBitmap(loadedBitmap);

        // ── Load Model 1 ──────────────────────────────────────────────────────
        classifier = new YoloClassifier(this);
        if (!classifier.initialize()) {
            Log.e(TAG, "Model 1 (YoloClassifier / best.tflite) failed to load.");
            showWarningCard("Leaf detection model failed to load. Please reinstall the app.",
                    "Model load error");
            btnAction.setEnabled(false);
        }

        // ── Load Model 2 ──────────────────────────────────────────────────────
        diseaseClassifier = new DiseaseClassifier(this);
        if (!diseaseClassifier.initialize()) {
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
                            resetToInitialState();
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
                if (isFromCamera) { deleteCurrentPhoto(); goToCamera(); }
                else              { galleryLauncher.launch("image/*"); }

            } else if (!isPalayDetected) {
                // ── STEP 1: Detect palay leaf ─────────────────────────────────
                showLoadingState("Detecting…");

                new Thread(() -> {
                    List<YoloClassifier.DetectionResult> results = null;
                    long leafPixels = 0L;

                    if (loadedBitmap != null) {
                        results    = classifier.detect(loadedBitmap);
                        leafPixels = classifier.getLeafMaskPixelCount();
                    }

                    final List<YoloClassifier.DetectionResult> finalResults = results;
                    final long finalLeafPixels = leafPixels;

                    runOnUiThread(() -> {
                        hideLoadingState();

                        if (finalResults != null && !finalResults.isEmpty()) {
                            // ── Palay detected ────────────────────────────────
                            isPalayDetected    = true;
                            leafMaskPixelCount = finalLeafPixels;

                            YoloClassifier.DetectionResult best = finalResults.get(0);
                            detectedConfidence = best.confidence;

                            Bitmap withBox = classifier.renderMask(loadedBitmap, finalResults);
                            ivDetectImage.setImageBitmap(withBox);
                            saveAnnotatedBitmap(withBox);

                            int confidencePct = Math.round(best.confidence * 100);

                            // Hide failure UI, show success UI
                            layoutDetectFailOverlay.setVisibility(View.GONE);
                            cardNoDetection.setVisibility(View.GONE);

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

                            String hint = isFromCamera
                                    ? "Make sure the image clearly shows a rice plant leaf. Try better lighting or move closer."
                                    : "Make sure the imported image clearly shows a rice plant leaf on a plain background.";

                            showFailureState(hint);
                        }
                    });
                }).start();

            } else {
                // ── STEP 2: Classify disease ──────────────────────────────────
                showLoadingState("Analyzing…");
                btnRetake.setEnabled(false);

                final long leafPixelsForAnalysis = leafMaskPixelCount;

                new Thread(() -> {
                    DiseaseClassifier.AnalysisSummary summary = null;

                    if (loadedBitmap != null) {
                        summary = diseaseClassifier.analyze(loadedBitmap, leafPixelsForAnalysis);
                    }

                    final DiseaseClassifier.AnalysisSummary finalSummary = summary;

                    runOnUiThread(() -> {
                        hideLoadingState();
                        btnRetake.setEnabled(true);

                        if (finalSummary == null) {
                            showWarningCard(
                                    "Disease analysis unavailable. Check that disease.tflite is in assets.",
                                    "Analysis error");
                            return;
                        }

                        if (finalSummary.annotatedBitmap != null) {
                            ivDetectImage.setImageBitmap(finalSummary.annotatedBitmap);
                            saveAnnotatedBitmap(finalSummary.annotatedBitmap);
                        }

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

    // ── UI state helpers ──────────────────────────────────────────────────────

    /**
     * Shows the image-level failure overlay AND the warning card below.
     * Called when Model 1 finds no palay leaf.
     */
    private void showFailureState(String hint) {
        // Dim overlay + icon on the image
        layoutDetectFailOverlay.setVisibility(View.VISIBLE);

        // Warning card
        tvNoDetectionHint.setText(hint);
        cardNoDetection.setVisibility(View.VISIBLE);

        // Hide success views
        tvInfoMessage.setVisibility(View.GONE);
        confidenceLayout.setVisibility(View.GONE);

        // Update buttons
        btnAction.setText(isFromCamera ? "Retake" : "Re-import");
        btnRetake.setVisibility(View.GONE);
    }

    /**
     * Shows the warning card with a custom title override for non-detection errors
     * (e.g. model load failure, analysis unavailable).
     */
    private void showWarningCard(String hint, String title) {
        cardNoDetection.setVisibility(View.VISIBLE);
        tvNoDetectionHint.setText(hint);
        // Optionally update the card title — find the title TextView by tag or id if needed.
    }

    /** Resets all UI back to the initial "ready to detect" state. */
    private void resetToInitialState() {
        isPalayDetected    = false;
        isDetectionFailed  = false;
        isFromCamera       = false;
        leafMaskPixelCount = 0L;

        btnAction.setText("Detect");
        btnRetake.setVisibility(View.GONE);
        btnRetake.setText("Re-import");

        tvInfoMessage.setVisibility(View.GONE);
        confidenceLayout.setVisibility(View.GONE);
        cardNoDetection.setVisibility(View.GONE);
        layoutDetectFailOverlay.setVisibility(View.GONE);
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

    // ── Other helpers ─────────────────────────────────────────────────────────

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