package com.example.agrieye_main;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class YoloClassifier {

    private static final String TAG        = "YoloClassifier";
    private static final String MODEL_FILE = "best_float32.tflite";

    private static final int   INPUT_SIZE           = 640;
    private static final int   NUM_CLASSES          = 2;    // 2 classes: 4+2+32=38 ✓
    private static final float CONFIDENCE_THRESHOLD = 0.75f; // lowered for diagnosis
    private static final float IOU_THRESHOLD        = 0.45f;

    private Interpreter interpreter;
    private final Context context;

    // Discovered at runtime from the model itself
    private int  outputDim1    = -1;   // rows  (e.g. 37)
    private int  outputDim2    = -1;   // cols  (e.g. 8400)
    private boolean isTransposed = false; // true if model exports [8400, 37] instead of [37, 8400]

    // ── Result container ──────────────────────────────────────────────────────
    public static class DetectionResult {
        public RectF   boundingBox;
        public float   confidence;
        public String  label;
        public float[] maskCoefficients;

        public DetectionResult(RectF box, float conf, String lbl, float[] masks) {
            this.boundingBox      = box;
            this.confidence       = conf;
            this.label            = lbl;
            this.maskCoefficients = masks;
        }
    }

    public YoloClassifier(Context context) {
        this.context = context;
    }

    // ── Initialize — call once in Activity.onCreate ───────────────────────────
    public boolean initialize() {
        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE);

            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(modelBuffer, options);

            // ── Log ALL tensor shapes — copy these from Logcat and share them ──
            Log.d(TAG, "══════════════════════════════════════════════");
            Log.d(TAG, "Number of INPUT  tensors: " + interpreter.getInputTensorCount());
            Log.d(TAG, "Number of OUTPUT tensors: " + interpreter.getOutputTensorCount());

            int[] inShape = interpreter.getInputTensor(0).shape();
            Log.d(TAG, "INPUT[0]  shape: " + Arrays.toString(inShape));

            for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
                int[] s = interpreter.getOutputTensor(i).shape();
                Log.d(TAG, "OUTPUT[" + i + "] shape: " + Arrays.toString(s));
            }

            // Grab the primary output shape
            int[] outShape = interpreter.getOutputTensor(0).shape();

            if (outShape.length == 3) {
                int d1 = outShape[1];
                int d2 = outShape[2];
                // YOLOv8-seg normal:     [1, 37,   8400] → d1=37,   d2=8400
                // YOLOv8-seg transposed: [1, 8400, 37  ] → d1=8400, d2=37
                if (d1 < d2) {
                    // Normal: rows=features (37), cols=detections (8400)
                    outputDim1   = d1;
                    outputDim2   = d2;
                    isTransposed = false;
                } else {
                    // Transposed: rows=detections (8400), cols=features (37)
                    outputDim1   = d1;
                    outputDim2   = d2;
                    isTransposed = true;
                }
            } else if (outShape.length == 2) {
                // Edge case: [8400, 37] without batch dim
                outputDim1   = outShape[0];
                outputDim2   = outShape[1];
                isTransposed = (outShape[0] > outShape[1]);
            }

            Log.d(TAG, "isTransposed=" + isTransposed
                    + "  outputDim1=" + outputDim1
                    + "  outputDim2=" + outputDim2);
            Log.d(TAG, "══════════════════════════════════════════════");

            Log.d(TAG, "Model loaded successfully.");
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to load model: " + e.getMessage());
            return false;
        }
    }

    // ── Main detection ────────────────────────────────────────────────────────
    public List<DetectionResult> detect(Bitmap bitmap) {
        List<DetectionResult> results = new ArrayList<>();

        if (interpreter == null || outputDim1 < 0) {
            Log.e(TAG, "Interpreter not ready.");
            return results;
        }

        // 1. Resize
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Bitmap → ByteBuffer
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);

        // 3. Allocate output using ACTUAL runtime dimensions
        float[][][] output = new float[1][outputDim1][outputDim2];

        // 4. Run inference
        try {
            interpreter.run(inputBuffer, output);
        } catch (Exception e) {
            Log.e(TAG, "Inference failed: " + e.getMessage());
            Log.e(TAG, "Expected output shape: [1][" + outputDim1 + "][" + outputDim2 + "]");
            return results;
        }

        // 5. Parse
        return applyNMS(parseOutput(output[0]));
    }

    // ── Convert Bitmap to float32 ByteBuffer normalized [0,1] ────────────────
    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        // 4 bytes (float32) × 3 channels (RGB) × 640 × 640
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
            buffer.putFloat(((pixel >> 8)  & 0xFF) / 255.0f); // G
            buffer.putFloat(( pixel        & 0xFF) / 255.0f); // B
        }
        return buffer;
    }

    // ── Parse output ──────────────────────────────────────────────────────────
    private List<DetectionResult> parseOutput(float[][] output) {
        List<DetectionResult> candidates = new ArrayList<>();

        // If transposed: output[detectionIdx][featureIdx]
        // If normal:     output[featureIdx][detectionIdx]
        int numDetections = isTransposed ? outputDim1 : outputDim2;
        int numFeatures   = isTransposed ? outputDim2 : outputDim1;
        int numMaskCoeffs = Math.max(0, numFeatures - 4 - NUM_CLASSES);

        // CLASS INDEX REFERENCE (from data.yaml):
        // class 0 = "Not Palay"  → feature row index 4
        // class 1 = "Palay"      → feature row index 5
        final int NOT_PALAY_IDX = 0;
        final int PALAY_IDX     = 1;

        // ── Log top scores per class for diagnosis ────────────────────────────
        float highestNotPalay = -Float.MAX_VALUE;
        float highestPalay    = -Float.MAX_VALUE;
        for (int i = 0; i < numDetections; i++) {
            float scoreNotPalay = isTransposed ? output[i][4] : output[4][i];
            float scorePalay    = isTransposed ? output[i][5] : output[5][i];
            if (scoreNotPalay > highestNotPalay) highestNotPalay = scoreNotPalay;
            if (scorePalay    > highestPalay)    highestPalay    = scorePalay;
        }
        Log.d(TAG, "──────────────────────────────────────────────");
        Log.d(TAG, "Highest 'Not Palay' score : " + highestNotPalay);
        Log.d(TAG, "Highest 'Palay'     score : " + highestPalay);
        Log.d(TAG, "Confidence threshold      : " + CONFIDENCE_THRESHOLD);
        Log.d(TAG, "──────────────────────────────────────────────");

        for (int i = 0; i < numDetections; i++) {
            float cx = isTransposed ? output[i][0] : output[0][i];
            float cy = isTransposed ? output[i][1] : output[1][i];
            float w  = isTransposed ? output[i][2] : output[2][i];
            float h  = isTransposed ? output[i][3] : output[3][i];

            // Read BOTH class scores explicitly
            float scoreNotPalay = isTransposed ? output[i][4] : output[4][i]; // class 0
            float scorePalay    = isTransposed ? output[i][5] : output[5][i]; // class 1

            // The winning class is whichever score is higher
            float maxScore;
            int   bestClass;
            if (scorePalay >= scoreNotPalay) {
                maxScore  = scorePalay;
                bestClass = PALAY_IDX;
            } else {
                maxScore  = scoreNotPalay;
                bestClass = NOT_PALAY_IDX;
            }

            // Skip boxes below threshold
            if (maxScore < CONFIDENCE_THRESHOLD) continue;

            // ── IMPORTANT: skip "Not Palay" boxes entirely ────────────────────
            // We only want to surface Palay detections to the UI.
            // "Not Palay" boxes are logged but not added as results.
            if (bestClass == NOT_PALAY_IDX) {
                Log.d(TAG, "Skipping 'Not Palay' box  conf=" + maxScore);
                continue;
            }

            float x1 = Math.max(0f, cx - w / 2f);
            float y1 = Math.max(0f, cy - h / 2f);
            float x2 = Math.min(1f, cx + w / 2f);
            float y2 = Math.min(1f, cy + h / 2f);

            float[] maskCoeffs = new float[numMaskCoeffs];
            for (int m = 0; m < numMaskCoeffs; m++) {
                int fi = 4 + NUM_CLASSES + m;
                maskCoeffs[m] = isTransposed ? output[i][fi] : output[fi][i];
            }

            String label = getLabel(bestClass) + String.format(" %.0f%%", maxScore * 100);
            candidates.add(new DetectionResult(new RectF(x1, y1, x2, y2), maxScore, label, maskCoeffs));
        }

        Log.d(TAG, "Candidates after threshold: " + candidates.size());
        return candidates;
    }

    private String getLabel(int classIndex) {
        // Matches data.yaml: class 0 = Not Palay, class 1 = Palay
        String[] labels = {"Not Palay", "Palay"};
        return (classIndex >= 0 && classIndex < labels.length) ? labels[classIndex] : "Unknown";
    }

    // ── NMS ───────────────────────────────────────────────────────────────────
    private List<DetectionResult> applyNMS(List<DetectionResult> detections) {
        List<DetectionResult> result     = new ArrayList<>();
        boolean[]             suppressed = new boolean[detections.size()];
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (!suppressed[j] && computeIoU(detections.get(i).boundingBox,
                        detections.get(j).boundingBox) > IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        Log.d(TAG, "Final detections after NMS: " + result.size());
        return result;
    }

    private float computeIoU(RectF a, RectF b) {
        float iL = Math.max(a.left, b.left),  iT = Math.max(a.top, b.top);
        float iR = Math.min(a.right, b.right), iB = Math.min(a.bottom, b.bottom);
        float inter = Math.max(0, iR - iL) * Math.max(0, iB - iT);
        float aA = (a.right - a.left) * (a.bottom - a.top);
        float bA = (b.right - b.left) * (b.bottom - b.top);
        return inter / (aA + bA - inter + 1e-6f);
    }

    // ── Draw bounding boxes on the bitmap ─────────────────────────────────────
    public Bitmap renderMask(Bitmap original, List<DetectionResult> results) {
        // Always work on a mutable copy of the ORIGINAL bitmap (not the 640x640 resized one)
        // The bounding box coordinates are normalized [0,1] so they scale to any size correctly
        Bitmap mutable = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas  = new Canvas(mutable);

        int W = mutable.getWidth();
        int H = mutable.getHeight();

        // Scale stroke width relative to image size so it's visible on any resolution
        float strokeWidth = Math.max(W, H) * 0.005f; // 0.5% of the larger dimension
        float textSize    = Math.max(W, H) * 0.04f;  // 4% of the larger dimension

        // Semi-transparent green fill inside the box
        Paint fillPaint = new Paint();
        fillPaint.setColor(Color.argb(60, 0, 220, 80));
        fillPaint.setStyle(Paint.Style.FILL);

        // Solid green border
        Paint boxPaint = new Paint();
        boxPaint.setColor(Color.argb(230, 0, 220, 80));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(strokeWidth);
        boxPaint.setAntiAlias(true);

        // Dark green label background
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.argb(200, 0, 100, 40));
        bgPaint.setStyle(Paint.Style.FILL);

        // White label text
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setAntiAlias(true);
        textPaint.setFakeBoldText(true);

        for (DetectionResult r : results) {
            // Convert normalized [0,1] coords → actual pixel coords on the ORIGINAL image
            float left   = r.boundingBox.left   * W;
            float top    = r.boundingBox.top    * H;
            float right  = r.boundingBox.right  * W;
            float bottom = r.boundingBox.bottom * H;

            // Draw semi-transparent fill
            canvas.drawRect(left, top, right, bottom, fillPaint);

            // Draw border
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // Draw label background + text
            float labelHeight = textSize + 16f;
            float labelTop    = (top - labelHeight > 0) ? top - labelHeight : bottom;
            float labelWidth  = textPaint.measureText(r.label) + 24f;

            canvas.drawRoundRect(
                    left, labelTop,
                    left + labelWidth, labelTop + labelHeight,
                    8f, 8f, bgPaint
            );
            canvas.drawText(r.label, left + 12f, labelTop + labelHeight - 8f, textPaint);
        }

        return mutable;
    }

    // ── Free memory ───────────────────────────────────────────────────────────
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}