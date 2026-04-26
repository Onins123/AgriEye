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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DiseaseClassifier — Second YOLOv8-seg model.
 *
 * Responsibilities:
 *   1. Detect and classify rice leaf disease (BLB, BLS, HEALTHY, LEAF, LEAF BLAST, NBS).
 *   2. Render per-class segmentation masks onto the original bitmap.
 *   3. Compute diseased leaf percentage using the segmentation masks:
 *        - "LEAF" pixels  → total leaf area
 *        - Disease pixels → diseased area
 *        - severity %     → (diseased pixels / leaf pixels) × 100
 *
 * Place your exported TFLite model in:  app/src/main/assets/disease.tflite
 */
public class DiseaseClassifier {

    private static final String TAG        = "DiseaseClassifier";
    private static final String MODEL_FILE = "disease.tflite"; // ← put your model here

    // ── Model constants — adjust if your export differs ──────────────────────
    private static final int   INPUT_SIZE            = 768;
    private static final float CONFIDENCE_THRESHOLD  = 0.05f;
    private static final float IOU_THRESHOLD         = 0.45f;
    private static final int   NUM_MASK_COEFFICIENTS = 32;

    /**
     * Class order MUST match the order in your training data.yaml (nc / names list).
     * Current order from AgriEye_Verified-Disease v8:
     *   0=BLB, 1=BLS, 2=HEALTHY, 3=LEAF, 4=LEAF BLAST, 5=NBS
     */
    private static final String[] CLASS_NAMES = {
            "Bacterial Leaf Blight",   // 0 — BLB
            "Bacterial Leaf Streak",   // 1 — BLS
            "Healthy",                 // 2 — HEALTHY
            "Leaf",                    // 3 — LEAF  (whole-leaf segmentation)
            "Leaf Blast",              // 4 — LEAF BLAST
            "Narrow Brown Spot"        // 5 — NBS
    };
    private static final int NUM_CLASSES = CLASS_NAMES.length; // 6

    // Class index for the whole-leaf segment (used as denominator for severity)
    private static final int LEAF_CLASS_INDEX = 3;

    // Per-class overlay colours (ARGB)  — matched to CLASS_NAMES order
    private static final int[] CLASS_COLORS = {
            Color.argb(120, 231,  76,  60),  // BLB         — red
            Color.argb(120,  52, 152, 219),  // BLS         — blue
            Color.argb(120,  46, 204, 113),  // HEALTHY     — green
            Color.argb( 60, 255, 255, 255),  // LEAF        — white (faint)
            Color.argb(120, 155,  89, 182),  // LEAF BLAST  — purple
            Color.argb(120,  26, 188, 156),  // NBS         — teal
    };

    // ── Runtime state ────────────────────────────────────────────────────────
    private Interpreter interpreter;
    private final Context context;

    private int     outputDim1    = -1;
    private int     outputDim2    = -1;
    private boolean isTransposed  = false;

    private int     protoH             = 160;
    private int     protoW             = 160;
    private boolean protoChannelsFirst = true;

    private float[][][][] protoMasksFirst = null; // [1][32][H][W]
    private float[][][][] protoMasksLast  = null; // [1][H][W][32]

    // ── Result container ─────────────────────────────────────────────────────
    public static class DiseaseResult {
        public RectF   boundingBox;       // normalized [0,1]
        public float   confidence;
        public int     classIndex;
        public String  className;
        public float[] maskCoefficients;  // length 32

        public DiseaseResult(RectF box, float conf, int cls, String name, float[] masks) {
            this.boundingBox      = box;
            this.confidence       = conf;
            this.classIndex       = cls;
            this.className        = name;
            this.maskCoefficients = masks;
        }
    }

    /** Summary returned to DetectActivity after a full disease analysis run. */
    public static class AnalysisSummary {
        /** Display name of the primary detected disease (or "Healthy" / "Unknown"). */
        public String  diseaseName;
        /**
         * Percentage of the LEAF area that is covered by disease pixels.
         * 0–100. Used directly as the severity input in AnalysisResultActivity.
         */
        public double  diseasedPercentage;
        /** Bitmap with coloured segmentation masks + bounding boxes drawn on it. */
        public Bitmap  annotatedBitmap;
    }

    // ── Constructor ──────────────────────────────────────────────────────────
    public DiseaseClassifier(Context context) {
        this.context = context;
    }

    // ── Initialize ───────────────────────────────────────────────────────────
    public boolean initialize() {
        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(modelBuffer, options);

            Log.d(TAG, "══════════════════════════════════════════════");
            Log.d(TAG, "DiseaseClassifier — model loaded");
            Log.d(TAG, "INPUT  tensors : " + interpreter.getInputTensorCount());
            Log.d(TAG, "OUTPUT tensors : " + interpreter.getOutputTensorCount());

            int[] inShape = interpreter.getInputTensor(0).shape();
            Log.d(TAG, "INPUT[0]  shape: " + Arrays.toString(inShape));
            for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
                int[] s = interpreter.getOutputTensor(i).shape();
                Log.d(TAG, "OUTPUT[" + i + "] shape: " + Arrays.toString(s));
            }

            // ── Parse Output[0]: detection tensor ────────────────────────────
            int[] outShape = interpreter.getOutputTensor(0).shape();
            if (outShape.length == 3) {
                int d1 = outShape[1], d2 = outShape[2];
                if (d1 < d2) { outputDim1 = d1; outputDim2 = d2; isTransposed = false; }
                else          { outputDim1 = d1; outputDim2 = d2; isTransposed = true;  }
            } else if (outShape.length == 2) {
                outputDim1 = outShape[0]; outputDim2 = outShape[1];
                isTransposed = (outShape[0] > outShape[1]);
            }
            Log.d(TAG, "isTransposed=" + isTransposed
                    + "  outputDim1=" + outputDim1 + "  outputDim2=" + outputDim2);

            // ── Parse Output[1]: proto mask tensor ───────────────────────────
            if (interpreter.getOutputTensorCount() >= 2) {
                int[] protoShape = interpreter.getOutputTensor(1).shape();
                Log.d(TAG, "Proto mask shape: " + Arrays.toString(protoShape));
                if (protoShape.length == 4) {
                    if (protoShape[1] == NUM_MASK_COEFFICIENTS) {
                        protoChannelsFirst = true;
                        protoH = protoShape[2]; protoW = protoShape[3];
                    } else {
                        protoChannelsFirst = false;
                        protoH = protoShape[1]; protoW = protoShape[2];
                    }
                }
                Log.d(TAG, "protoChannelsFirst=" + protoChannelsFirst
                        + "  protoH=" + protoH + "  protoW=" + protoW);
            } else {
                Log.w(TAG, "Model has only 1 output — segmentation masks NOT available.");
            }

            Log.d(TAG, "DiseaseClassifier ready.");
            Log.d(TAG, "══════════════════════════════════════════════");
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to load disease model: " + e.getMessage());
            return false;
        }
    }

    // ── Full analysis pipeline ───────────────────────────────────────────────
    /**
     * Run inference on the given bitmap and return a complete AnalysisSummary
     * containing disease name, severity %, and the annotated bitmap.
     *
     * Call this from a background thread (it is NOT main-thread safe).
     */
    public AnalysisSummary analyze(Bitmap bitmap) {
        AnalysisSummary summary = new AnalysisSummary();
        summary.diseaseName        = "Unknown";
        summary.diseasedPercentage = 0.0;
        summary.annotatedBitmap    = bitmap;

        // ── NEW: Check interpreter state before doing anything ──
        if (interpreter == null) {
            Log.e(TAG, "DIAGNOSE ► analyze() called but interpreter is NULL — model never loaded.");
            return summary;
        }
        Log.d(TAG, "DIAGNOSE ► analyze() started. Bitmap size: "
                + bitmap.getWidth() + "x" + bitmap.getHeight());

        List<DiseaseResult> results = detect(bitmap);

        Log.d(TAG, "DIAGNOSE ► detect() returned " + (results == null ? "NULL" : results.size()) + " result(s).");

        if (results == null || results.isEmpty()) {
            Log.w(TAG, "DIAGNOSE ► No detections survived NMS. Returning Unknown/0%.");
            summary.diseaseName        = "Unknown";
            summary.diseasedPercentage = 0.0;
            summary.annotatedBitmap    = bitmap;
            return summary;
        }

        // ── Log every detection that came back ──
        for (int i = 0; i < results.size(); i++) {
            DiseaseResult r = results.get(i);
            Log.d(TAG, "DIAGNOSE ► Result[" + i + "] class=" + r.classIndex
                    + " (" + r.className + ")  conf=" + String.format("%.3f", r.confidence)
                    + "  box=[" + String.format("%.3f,%.3f,%.3f,%.3f",
                    r.boundingBox.left, r.boundingBox.top,
                    r.boundingBox.right, r.boundingBox.bottom) + "]");
        }

        // ── Primary disease selection ──
        DiseaseResult primaryDisease = null;
        for (DiseaseResult r : results) {
            if (r.classIndex != LEAF_CLASS_INDEX && !r.className.equals("Healthy")) {
                if (primaryDisease == null || r.confidence > primaryDisease.confidence) {
                    primaryDisease = r;
                }
            }
        }

        if (primaryDisease != null) {
            Log.d(TAG, "DIAGNOSE ► Primary disease selected: " + primaryDisease.className
                    + " (conf=" + String.format("%.3f", primaryDisease.confidence) + ")");
        } else {
            Log.w(TAG, "DIAGNOSE ► No disease class found among results — all were LEAF or Healthy.");
        }

        summary.diseaseName = (primaryDisease != null) ? primaryDisease.className : "Healthy";

        summary.diseasedPercentage = computeSeverity(results);
        Log.d(TAG, "DIAGNOSE ► diseasedPercentage=" + String.format("%.2f", summary.diseasedPercentage) + "%");

        summary.annotatedBitmap = renderMasks(bitmap, results);
        Log.d(TAG, "DIAGNOSE ► analyze() complete. diseaseName=" + summary.diseaseName);

        return summary;
    }

    // ── Run YOLO inference ────────────────────────────────────────────────────
    private List<DiseaseResult> detect(Bitmap bitmap) {
        List<DiseaseResult> results = new ArrayList<>();
        if (interpreter == null || outputDim1 < 0) {
            Log.e(TAG, "Interpreter not ready."); return results;
        }

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer inputBuffer = bitmapToByteBuffer(resized);

        int[] outShape = interpreter.getOutputTensor(0).shape();
        float[][][] outputBoxes;
        if (outShape.length == 3) outputBoxes = new float[1][outShape[1]][outShape[2]];
        else                      outputBoxes = new float[1][outShape[0]][outShape[1]];

        protoMasksFirst = null;
        protoMasksLast  = null;

        boolean hasProto = interpreter.getOutputTensorCount() >= 2;

        if (hasProto) {
            if (protoChannelsFirst) protoMasksFirst = new float[1][NUM_MASK_COEFFICIENTS][protoH][protoW];
            else                    protoMasksLast  = new float[1][protoH][protoW][NUM_MASK_COEFFICIENTS];

            try {
                Object[] inputs = {inputBuffer};
                Map<Integer, Object> outputs = new HashMap<>();
                outputs.put(0, outputBoxes);
                outputs.put(1, protoChannelsFirst ? protoMasksFirst : protoMasksLast);
                interpreter.runForMultipleInputsOutputs(inputs, outputs);
            } catch (Exception e) {
                Log.e(TAG, "Inference failed: ", e); return results;
            }
        } else {
            try {
                interpreter.run(inputBuffer, outputBoxes);
            } catch (Exception e) {
                Log.e(TAG, "Single-output inference failed: ", e); return results;
            }
        }

        return applyNMS(parseOutput(outputBoxes[0]));
    }

    // ── Parse output tensor ───────────────────────────────────────────────────
    private List<DiseaseResult> parseOutput(float[][] output) {
        List<DiseaseResult> candidates = new ArrayList<>();

        int numDetections = isTransposed ? outputDim1 : outputDim2;
        int numFeatures   = isTransposed ? outputDim2 : outputDim1;
        int numMaskCoeffs = Math.max(0, numFeatures - 4 - NUM_CLASSES);

        // Log highest confidence per class for debugging
        float[] highestPerClass = new float[NUM_CLASSES];
        for (int i = 0; i < numDetections; i++) {
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = isTransposed ? output[i][4 + c] : output[4 + c][i];
                if (score > highestPerClass[c]) highestPerClass[c] = score;
            }
        }
        for (int c = 0; c < NUM_CLASSES; c++) {
            Log.d(TAG, "Class [" + CLASS_NAMES[c] + "] highest score: " + highestPerClass[c]);
        }

        for (int i = 0; i < numDetections; i++) {
            float cx = isTransposed ? output[i][0] : output[0][i];
            float cy = isTransposed ? output[i][1] : output[1][i];
            float w  = isTransposed ? output[i][2] : output[2][i];
            float h  = isTransposed ? output[i][3] : output[3][i];

            if (cx > 1.5f) { cx /= INPUT_SIZE; cy /= INPUT_SIZE;
                             w  /= INPUT_SIZE; h  /= INPUT_SIZE; }

            // Find the best class score for this detection
            int   bestClass = -1;
            float bestScore = CONFIDENCE_THRESHOLD;
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = isTransposed ? output[i][4 + c] : output[4 + c][i];
                if (score > bestScore) { bestScore = score; bestClass = c; }
            }
            if (bestClass < 0) continue;

            float x1 = Math.max(0f, cx - w / 2f);
            float y1 = Math.max(0f, cy - h / 2f);
            float x2 = Math.min(1f, cx + w / 2f);
            float y2 = Math.min(1f, cy + h / 2f);

            float[] maskCoeffs = new float[numMaskCoeffs];
            for (int m = 0; m < numMaskCoeffs; m++) {
                int fi = 4 + NUM_CLASSES + m;
                maskCoeffs[m] = isTransposed ? output[i][fi] : output[fi][i];
            }

            candidates.add(new DiseaseResult(
                    new RectF(x1, y1, x2, y2),
                    bestScore, bestClass,
                    CLASS_NAMES[bestClass],
                    maskCoeffs
            ));
        }

        // ── NEW: Always log top-5 raw detections regardless of threshold ──
        Log.d(TAG, "DIAGNOSE ► Scanning all " + numDetections + " anchors for top raw scores...");
        float[] topScores = new float[Math.min(5, numDetections)];
        int[]   topClass  = new int[Math.min(5, numDetections)];
        for (int i = 0; i < numDetections; i++) {
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = isTransposed ? output[i][4 + c] : output[4 + c][i];
                // Insert into top-5 if it qualifies
                for (int t = 0; t < topScores.length; t++) {
                    if (score > topScores[t]) {
                        for (int s = topScores.length - 1; s > t; s--) {
                            topScores[s] = topScores[s-1];
                            topClass[s]  = topClass[s-1];
                        }
                        topScores[t] = score;
                        topClass[t]  = c;
                        break;
                    }
                }
            }
        }
        for (int t = 0; t < topScores.length; t++) {
            Log.d(TAG, "DIAGNOSE ► Top raw score #" + (t+1) + ": "
                    + String.format("%.4f", topScores[t])
                    + " → class " + topClass[t] + " (" + CLASS_NAMES[Math.max(0, topClass[t])] + ")"
                    + (topScores[t] < CONFIDENCE_THRESHOLD ? "  ← BELOW THRESHOLD ("+CONFIDENCE_THRESHOLD+")" : "  ← PASSES"));
        }

        Log.d(TAG, "DIAGNOSE ► candidates after threshold: " + candidates.size());
        return candidates;
    }

    // ── NMS ───────────────────────────────────────────────────────────────────
    private List<DiseaseResult> applyNMS(List<DiseaseResult> detections) {
        List<DiseaseResult> result     = new ArrayList<>();
        boolean[]           suppressed = new boolean[detections.size()];
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (!suppressed[j] && iou(detections.get(i).boundingBox,
                        detections.get(j).boundingBox) > IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        Log.d(TAG, "Final disease detections after NMS: " + result.size());
        return result;
    }

    // ── Severity calculation from segmentation masks ──────────────────────────
    /**
     * Computes (diseased pixels / leaf pixels) × 100.
     *
     * Algorithm:
     *   For every proto pixel, reconstruct the binary mask for each detection.
     *   Count pixels that belong to the LEAF class → leafPixels.
     *   Count pixels that belong to any disease class → diseasedPixels.
     *   severity = diseasedPixels / max(leafPixels, 1) * 100.
     *
     * Falls back to bounding-box area ratio if no proto masks are available.
     */
    private double computeSeverity(List<DiseaseResult> results) {
        boolean hasProto = (protoMasksFirst != null || protoMasksLast != null);

        if (!hasProto) {
            // Fallback: use bounding box area of disease boxes vs LEAF box
            float leafArea     = 0f;
            float diseasedArea = 0f;
            for (DiseaseResult r : results) {
                float area = (r.boundingBox.right - r.boundingBox.left)
                           * (r.boundingBox.bottom - r.boundingBox.top);
                if (r.classIndex == LEAF_CLASS_INDEX) leafArea     += area;
                else if (!r.className.equals("Healthy")) diseasedArea += area;
            }
            if (leafArea <= 0) leafArea = 1f;
            return Math.min(100.0, (diseasedArea / leafArea) * 100.0);
        }

        // ── Mask-based severity ───────────────────────────────────────────────
        // For each proto pixel, find which detection "owns" it (highest mask value
        // after sigmoid, subject to the bounding box constraint).
        // Then bucket into LEAF vs disease.

        long leafPixels     = 0;
        long diseasedPixels = 0;

        for (int py = 0; py < protoH; py++) {
            for (int px = 0; px < protoW; px++) {
                // Normalized coords of this proto pixel
                float nx = (px + 0.5f) / protoW;
                float ny = (py + 0.5f) / protoH;

                int   bestClass    = -1;
                float bestMaskVal  = 0.5f; // sigmoid threshold

                for (DiseaseResult r : results) {
                    // Only consider proto pixels inside the detection bounding box
                    if (nx < r.boundingBox.left  || nx > r.boundingBox.right
                     || ny < r.boundingBox.top   || ny > r.boundingBox.bottom) continue;

                    // Dot product: maskCoeffs · proto[:, py, px]
                    float dot = 0f;
                    int   len = Math.min(r.maskCoefficients.length, NUM_MASK_COEFFICIENTS);
                    for (int k = 0; k < len; k++) dot += r.maskCoefficients[k] * getProto(k, py, px);

                    float maskVal = sigmoid(dot);
                    if (maskVal > bestMaskVal) {
                        bestMaskVal = maskVal;
                        bestClass   = r.classIndex;
                    }
                }

                if (bestClass < 0) continue;
                if (bestClass == LEAF_CLASS_INDEX)     leafPixels++;
                else if (!CLASS_NAMES[bestClass].equals("Healthy")) diseasedPixels++;
            }
        }

        Log.d(TAG, "Severity — leafPixels=" + leafPixels + "  diseasedPixels=" + diseasedPixels);
        if (leafPixels == 0) return 0.0;
        return Math.min(100.0, ((double) diseasedPixels / (leafPixels + diseasedPixels)) * 100.0);
    }

    // ── Render masks + bounding boxes ────────────────────────────────────────
    public Bitmap renderMasks(Bitmap original, List<DiseaseResult> results) {
        Bitmap mutable = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas  = new Canvas(mutable);

        int W = mutable.getWidth();
        int H = mutable.getHeight();

        float textSize = Math.max(W, H) * 0.04f;

        boolean hasProto = (protoMasksFirst != null || protoMasksLast != null);

        // ── Diagnostic logs ───────────────────────────────────────────────────────
        Log.d(TAG, "renderMasks ► hasProto=" + hasProto
                + "  results=" + results.size()
                + "  imageSize=" + W + "x" + H
                + "  protoSize=" + protoW + "x" + protoH);
        for (int i = 0; i < results.size(); i++) {
            DiseaseResult r = results.get(i);
            Log.d(TAG, "renderMasks ► result[" + i + "] class=" + r.className
                    + " maskCoeffs.length=" + r.maskCoefficients.length
                    + " box=[" + String.format("%.3f,%.3f,%.3f,%.3f",
                    r.boundingBox.left, r.boundingBox.top,
                    r.boundingBox.right, r.boundingBox.bottom) + "]");
        }

        // ── Draw segmentation masks ───────────────────────────────────────────────
        if (hasProto) {
            int[] pixels = new int[W * H];
            mutable.getPixels(pixels, 0, W, 0, 0, W, H);

            // Sample the proto grid directly at proto resolution,
            // then scale each proto cell → image pixels to avoid the
            // aspect-ratio mismatch from normalizing through image coords.
            for (int py = 0; py < protoH; py++) {
                for (int px = 0; px < protoW; px++) {

                    // Normalized position of this proto cell [0,1]
                    float nx = (px + 0.5f) / protoW;
                    float ny = (py + 0.5f) / protoH;

                    // Accumulate blended color across ALL detections for this proto cell
                    // (accumulate into a temp color, apply once to all image pixels it covers)
                    int overlayColor = 0; // 0 = nothing to draw
                    boolean anyHit   = false;

                    for (DiseaseResult r : results) {
                        // Bounding box clip
                        if (nx < r.boundingBox.left  || nx > r.boundingBox.right
                                || ny < r.boundingBox.top   || ny > r.boundingBox.bottom) continue;

                        float dot = 0f;
                        int   len = Math.min(r.maskCoefficients.length, NUM_MASK_COEFFICIENTS);
                        for (int k = 0; k < len; k++) {
                            dot += r.maskCoefficients[k] * getProto(k, py, px);
                        }

                        if (sigmoid(dot) > 0.5f && r.classIndex < CLASS_COLORS.length) {
                            if (!anyHit) {
                                overlayColor = CLASS_COLORS[r.classIndex];
                                anyHit = true;
                            } else {
                                // Blend multiple overlapping class colors together
                                overlayColor = blendColor(overlayColor, CLASS_COLORS[r.classIndex]);
                            }
                        }
                    }

                    if (!anyHit) continue;

                    // Map this proto cell → rectangle of image pixels it covers
                    // Proto cell px covers image x range: [px/protoW .. (px+1)/protoW] * W
                    int imgX0 = (int)((float) px       / protoW * W);
                    int imgX1 = (int)((float)(px + 1)  / protoW * W);
                    int imgY0 = (int)((float) py       / protoH * H);
                    int imgY1 = (int)((float)(py + 1)  / protoH * H);

                    imgX0 = Math.max(0, Math.min(imgX0, W - 1));
                    imgX1 = Math.max(0, Math.min(imgX1, W - 1));
                    imgY0 = Math.max(0, Math.min(imgY0, H - 1));
                    imgY1 = Math.max(0, Math.min(imgY1, H - 1));

                    for (int iy = imgY0; iy <= imgY1; iy++) {
                        for (int ix = imgX0; ix <= imgX1; ix++) {
                            pixels[iy * W + ix] = blendColor(pixels[iy * W + ix], overlayColor);
                        }
                    }
                }
            }

            mutable.setPixels(pixels, 0, W, 0, 0, W, H);
            Log.d(TAG, "renderMasks ► pixel pass complete.");

        } else {
            // Fallback: no proto masks — draw filled rects so something is visible
            Log.w(TAG, "renderMasks ► No proto masks available! Drawing filled rect fallback.");
            Paint fillPaint = new Paint();
            fillPaint.setStyle(Paint.Style.FILL);
            for (DiseaseResult r : results) {
                if (r.classIndex == LEAF_CLASS_INDEX) continue;
                int c = r.classIndex < CLASS_COLORS.length ? CLASS_COLORS[r.classIndex] : Color.YELLOW;
                fillPaint.setColor(c);
                canvas.drawRect(
                        r.boundingBox.left   * W, r.boundingBox.top    * H,
                        r.boundingBox.right  * W, r.boundingBox.bottom * H,
                        fillPaint
                );
            }
        }

        // ── Labels only — no bounding box border ──────────────────────────────────
        Paint bgPaint = new Paint();
        bgPaint.setStyle(Paint.Style.FILL);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setAntiAlias(true);
        textPaint.setFakeBoldText(true);

        for (DiseaseResult r : results) {
            if (r.classIndex == LEAF_CLASS_INDEX) continue;

            int classColor = (r.classIndex < CLASS_COLORS.length)
                    ? CLASS_COLORS[r.classIndex] : Color.WHITE;

            float left   = r.boundingBox.left   * W;
            float top    = r.boundingBox.top    * H;
            float bottom = r.boundingBox.bottom * H;

            String labelText = r.className + " " + Math.round(r.confidence * 100) + "%";
            float  labelH    = textSize + 16f;
            float  labelTop  = (top - labelH > 0) ? top - labelH : bottom;
            float  labelW    = textPaint.measureText(labelText) + 24f;

            bgPaint.setColor(Color.argb(200,
                    Color.red(classColor) / 2,
                    Color.green(classColor) / 2,
                    Color.blue(classColor) / 2));

            canvas.drawRoundRect(left, labelTop, left + labelW,
                    labelTop + labelH, 8f, 8f, bgPaint);
            canvas.drawText(labelText, left + 12f, labelTop + labelH - 8f, textPaint);
        }

        return mutable;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private ByteBuffer bitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        buffer.order(ByteOrder.nativeOrder());
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
            buffer.putFloat(((pixel >>  8) & 0xFF) / 255.0f);
            buffer.putFloat(( pixel        & 0xFF) / 255.0f);
        }
        return buffer;
    }

    private float getProto(int k, int py, int px) {
        if (protoChannelsFirst && protoMasksFirst != null) return protoMasksFirst[0][k][py][px];
        if (!protoChannelsFirst && protoMasksLast != null)  return protoMasksLast[0][py][px][k];
        return 0f;
    }

    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
    }

    /** Alpha-composite overlay colour on top of src colour. */
    private static int blendColor(int src, int overlay) {
        float a  = Color.alpha(overlay) / 255f;
        int   r  = (int)(Color.red(overlay)   * a + Color.red(src)   * (1 - a));
        int   g  = (int)(Color.green(overlay) * a + Color.green(src) * (1 - a));
        int   b  = (int)(Color.blue(overlay)  * a + Color.blue(src)  * (1 - a));
        return Color.argb(255, r, g, b);
    }

    private float iou(RectF a, RectF b) {
        float iL = Math.max(a.left, b.left),  iT = Math.max(a.top, b.top);
        float iR = Math.min(a.right, b.right), iB = Math.min(a.bottom, b.bottom);
        float inter = Math.max(0, iR - iL) * Math.max(0, iB - iT);
        float aA = (a.right - a.left) * (a.bottom - a.top);
        float bA = (b.right - b.left) * (b.bottom - b.top);
        return inter / (aA + bA - inter + 1e-6f);
    }

    public void close() {
        if (interpreter != null) { interpreter.close(); interpreter = null; }
        protoMasksFirst = null;
        protoMasksLast  = null;
    }
}
