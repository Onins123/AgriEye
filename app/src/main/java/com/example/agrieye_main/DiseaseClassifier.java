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
 * DiseaseClassifier — Second YOLOv8-seg model (disease.tflite).
 *
 * Refactored for 4-class model:
 *   0 = BLB  (Bacterial Leaf Blight)
 *   1 = BLS  (Bacterial Leaf Streak)
 *   2 = Leaf Blast
 *   3 = NBS  (Narrow Brown Spot)
 *
 * The LEAF and HEALTHY classes have been removed from this model.
 * Leaf area (denominator for severity %) is now supplied externally via
 * analyze(bitmap, leafPixelCount) — the count comes from YoloClassifier
 * (best.tflite) after Step 1 detection.
 *
 * Place your exported TFLite model in: app/src/main/assets/disease.tflite
 */
public class DiseaseClassifier {

    private static final String TAG        = "DiseaseClassifier";
    private static final String MODEL_FILE = "disease.tflite";

    // ── Model constants ───────────────────────────────────────────────────────
    private static final int   INPUT_SIZE            = 640;
    private static final float CONFIDENCE_THRESHOLD  = 0.05f;
    private static final float IOU_THRESHOLD         = 0.45f;
    private static final int   NUM_MASK_COEFFICIENTS = 32;

    /**
     * Class order MUST match the order in your training data.yaml (nc / names list).
     * Refactored 4-class order (LEAF and HEALTHY removed):
     *   0 = BLB, 1 = BLS, 2 = Leaf Blast, 3 = NBS
     */
    private static final String[] CLASS_NAMES = {
            "Bacterial Leaf Blight",   // 0 — BLB
            "Bacterial Leaf Streak",   // 1 — BLS
            "Leaf Blast",              // 2 — LEAF BLAST
            "Narrow Brown Spot"        // 3 — NBS
    };
    private static final int NUM_CLASSES = CLASS_NAMES.length; // 4

    // Per-class overlay colours (ARGB) — matched to CLASS_NAMES order
    private static final int[] CLASS_COLORS = {
            Color.argb(120, 231,  76,  60),  // BLB        — red
            Color.argb(120,  52, 152, 219),  // BLS        — blue
            Color.argb(120, 155,  89, 182),  // LEAF BLAST — purple
            Color.argb(120,  26, 188, 156),  // NBS        — teal
    };

    // ── Runtime state ─────────────────────────────────────────────────────────
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

    // ── Result container ──────────────────────────────────────────────────────
    public static class DiseaseResult {
        public RectF   boundingBox;
        public float   confidence;
        public int     classIndex;
        public String  className;
        public float[] maskCoefficients;

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
        /** Display name of the primary detected disease (or "Unknown"). */
        public String  diseaseName;
        /**
         * Percentage of the leaf area covered by disease pixels.
         * Denominator = leaf pixel count from YoloClassifier (best.tflite).
         */
        public double  diseasedPercentage;
        /**
         * Average confidence across consistent-class detections only (0–1).
         * Displayed in AnalysisResultActivity instead of per-detection labels.
         */
        public float   averageConfidence;
        /** Bitmap with coloured segmentation masks drawn on it (no labels). */
        public Bitmap  annotatedBitmap;
    }

    // ── Constructor ───────────────────────────────────────────────────────────
    public DiseaseClassifier(Context context) {
        this.context = context;
    }

    // ── Initialize ────────────────────────────────────────────────────────────
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

            // ── Parse Output[1]: proto mask tensor ────────────────────────────
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

            Log.d(TAG, "DiseaseClassifier ready. Classes: " + Arrays.toString(CLASS_NAMES));
            Log.d(TAG, "══════════════════════════════════════════════");
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to load disease model: " + e.getMessage());
            return false;
        }
    }

    // ── Full analysis pipeline ────────────────────────────────────────────────
    /**
     * Run inference on the given bitmap and return a complete AnalysisSummary.
     *
     * @param bitmap         The original (non-annotated) leaf bitmap.
     * @param leafPixelCount Leaf area in proto-grid pixels, obtained from
     *                       YoloClassifier.getLeafMaskPixelCount() after Step 1.
     *                       Pass 0 to fall back to bounding-box area estimation.
     *
     * Call this from a background thread (NOT main-thread safe).
     */
    public AnalysisSummary analyze(Bitmap bitmap, long leafPixelCount) {
        AnalysisSummary summary = new AnalysisSummary();
        summary.diseaseName        = "Unknown";
        summary.diseasedPercentage = 0.0;
        summary.annotatedBitmap    = bitmap;

        if (interpreter == null) {
            Log.e(TAG, "DIAGNOSE ► analyze() called but interpreter is NULL — model never loaded.");
            return summary;
        }
        Log.d(TAG, "DIAGNOSE ► analyze() started. Bitmap: "
                + bitmap.getWidth() + "x" + bitmap.getHeight()
                + "  leafPixelCount=" + leafPixelCount);

        List<DiseaseResult> results = detect(bitmap);

        Log.d(TAG, "DIAGNOSE ► detect() returned "
                + (results == null ? "NULL" : results.size()) + " result(s).");

        if (results == null || results.isEmpty()) {
            Log.w(TAG, "DIAGNOSE ► No detections survived NMS. Returning Unknown/0%.");
            return summary;
        }

        for (int i = 0; i < results.size(); i++) {
            DiseaseResult r = results.get(i);
            Log.d(TAG, "DIAGNOSE ► Result[" + i + "] class=" + r.classIndex
                    + " (" + r.className + ")  conf=" + String.format("%.3f", r.confidence)
                    + "  box=[" + String.format("%.3f,%.3f,%.3f,%.3f",
                    r.boundingBox.left, r.boundingBox.top,
                    r.boundingBox.right, r.boundingBox.bottom) + "]");
        }

        // ── Step 1: Find the dominant class by total confidence sum ───────────
        // Using confidence sum (not count) so one strong true positive outweighs
        // several weak noise detections from a different class.
        Map<Integer, Float> classConfidenceSum = new HashMap<>();
        for (DiseaseResult r : results) {
            Float current = classConfidenceSum.get(r.classIndex);
            classConfidenceSum.put(r.classIndex,
                    (current == null ? 0f : current) + r.confidence);
        }

        int dominantClassIndex = -1;
        float highestSum = -1f;
        for (Map.Entry<Integer, Float> entry : classConfidenceSum.entrySet()) {
            if (entry.getValue() > highestSum) {
                highestSum = entry.getValue();
                dominantClassIndex = entry.getKey();
            }
        }

        Log.d(TAG, "DIAGNOSE ► Dominant class index=" + dominantClassIndex
                + " (" + (dominantClassIndex >= 0 ? CLASS_NAMES[dominantClassIndex] : "none") + ")"
                + "  totalConfSum=" + String.format("%.3f", highestSum));

        // ── Step 2: Filter results to dominant class only ─────────────────────
        List<DiseaseResult> consistentResults = new ArrayList<>();
        for (DiseaseResult r : results) {
            if (r.classIndex == dominantClassIndex) {
                consistentResults.add(r);
            }
        }

        int filteredOut = results.size() - consistentResults.size();
        if (filteredOut > 0) {
            Log.d(TAG, "DIAGNOSE ► Class consistency filter removed "
                    + filteredOut + " cross-class detection(s).");
        }

        // ── Step 3: Pick primary disease from consistent set ──────────────────
        DiseaseResult primaryDisease = null;
        for (DiseaseResult r : consistentResults) {
            if (primaryDisease == null || r.confidence > primaryDisease.confidence) {
                primaryDisease = r;
            }
        }

        if (primaryDisease != null) {
            Log.d(TAG, "DIAGNOSE ► Primary disease: " + primaryDisease.className
                    + " (conf=" + String.format("%.3f", primaryDisease.confidence) + ")");
        }

        summary.diseaseName        = (primaryDisease != null) ? primaryDisease.className : "Unknown";
        // Severity and masks use the filtered consistent set only
        summary.diseasedPercentage = computeSeverity(consistentResults, leafPixelCount);
        summary.annotatedBitmap    = renderMasks(bitmap, consistentResults);

        // ── Step 4: Average confidence over consistent class only ─────────────
        float confSum = 0f;
        for (DiseaseResult r : consistentResults) confSum += r.confidence;
        summary.averageConfidence = consistentResults.isEmpty() ? 0f
                : confSum / consistentResults.size();

        Log.d(TAG, "DIAGNOSE ► analyze() complete."
                + "  disease=" + summary.diseaseName
                + "  totalDetections=" + results.size()
                + "  consistentDetections=" + consistentResults.size()
                + "  severity=" + String.format("%.2f", summary.diseasedPercentage) + "%"
                + "  avgConf=" + String.format("%.3f", summary.averageConfidence));

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

        // Log top-5 raw detections for debugging
        Log.d(TAG, "DIAGNOSE ► Scanning all " + numDetections + " anchors for top raw scores...");
        float[] topScores = new float[Math.min(5, numDetections)];
        int[]   topClass  = new int[Math.min(5, numDetections)];
        for (int i = 0; i < numDetections; i++) {
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = isTransposed ? output[i][4 + c] : output[4 + c][i];
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
                    + " → class " + topClass[t]
                    + " (" + (topClass[t] >= 0 && topClass[t] < NUM_CLASSES
                    ? CLASS_NAMES[topClass[t]] : "?") + ")"
                    + (topScores[t] < CONFIDENCE_THRESHOLD
                    ? "  ← BELOW THRESHOLD (" + CONFIDENCE_THRESHOLD + ")" : "  ← PASSES"));
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

    // ── Severity calculation ──────────────────────────────────────────────────
    /**
     * Computes (diseased pixels / leaf pixels) × 100.
     *
     * Leaf pixel count comes from YoloClassifier (best.tflite) — passed in via
     * analyze(). This replaces the old approach of using a dedicated LEAF class
     * in the disease model.
     *
     * Falls back to bounding-box area estimation when:
     *   - leafPixelCount == 0 (caller didn't provide it or Model 1 had no masks), OR
     *   - disease model has no proto masks.
     */
    private double computeSeverity(List<DiseaseResult> results, long leafPixelCount) {
        boolean hasProto = (protoMasksFirst != null || protoMasksLast != null);

        if (!hasProto || leafPixelCount <= 0) {
            // Fallback: use bounding box areas
            float diseasedArea = 0f;
            float leafAreaEstimate = 0f;

            if (leafPixelCount > 0) {
                // We have a leaf count but no proto grid — convert to normalised area
                leafAreaEstimate = (float) leafPixelCount / (protoH * protoW);
            }

            for (DiseaseResult r : results) {
                float area = (r.boundingBox.right - r.boundingBox.left)
                        * (r.boundingBox.bottom - r.boundingBox.top);
                diseasedArea += area;
                if (leafPixelCount <= 0) leafAreaEstimate += area; // pure fallback
            }

            if (leafAreaEstimate <= 0) leafAreaEstimate = 1f;
            double pct = Math.min(100.0, (diseasedArea / leafAreaEstimate) * 100.0);
            Log.d(TAG, "computeSeverity (bbox fallback) ► "
                    + String.format("%.2f", pct) + "%");
            return pct;
        }

        // ── Mask-based severity ───────────────────────────────────────────────
        // diseased pixels = proto pixels where any disease detection mask fires
        // leaf pixels     = supplied by YoloClassifier from best.tflite proto masks
        long diseasedPixels = 0;

        for (int py = 0; py < protoH; py++) {
            for (int px = 0; px < protoW; px++) {
                float nx = (px + 0.5f) / protoW;
                float ny = (py + 0.5f) / protoH;

                boolean isDiseased = false;
                for (DiseaseResult r : results) {
                    if (nx < r.boundingBox.left  || nx > r.boundingBox.right
                            || ny < r.boundingBox.top   || ny > r.boundingBox.bottom) continue;

                    float dot = 0f;
                    int   len = Math.min(r.maskCoefficients.length, NUM_MASK_COEFFICIENTS);
                    for (int k = 0; k < len; k++) dot += r.maskCoefficients[k] * getProto(k, py, px);

                    if (sigmoid(dot) > 0.5f) { isDiseased = true; break; }
                }
                if (isDiseased) diseasedPixels++;
            }
        }

        Log.d(TAG, "computeSeverity ► leafPixels(from Model1)=" + leafPixelCount
                + "  diseasedPixels=" + diseasedPixels);

        // Severity = diseased / total leaf area.
        // Cap at 100 % in case the diseased area slightly exceeds the leaf area estimate.
        return Math.min(100.0, ((double) diseasedPixels / leafPixelCount) * 100.0);
    }

    // ── Render masks + labels ─────────────────────────────────────────────────
    public Bitmap renderMasks(Bitmap original, List<DiseaseResult> results) {
        Bitmap mutable = original.copy(Bitmap.Config.ARGB_8888, true);
        // Canvas is only needed for the fallback filled-rect path (no proto masks).
        Canvas canvas  = new Canvas(mutable);

        int W = mutable.getWidth();
        int H = mutable.getHeight();

        boolean hasProto = (protoMasksFirst != null || protoMasksLast != null);

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

        // ── Draw segmentation masks ───────────────────────────────────────────
        if (hasProto) {
            int[] pixels = new int[W * H];
            mutable.getPixels(pixels, 0, W, 0, 0, W, H);

            for (int py = 0; py < protoH; py++) {
                for (int px = 0; px < protoW; px++) {

                    float nx = (px + 0.5f) / protoW;
                    float ny = (py + 0.5f) / protoH;

                    int     overlayColor = 0;
                    boolean anyHit       = false;

                    for (DiseaseResult r : results) {
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
                                overlayColor = blendColor(overlayColor, CLASS_COLORS[r.classIndex]);
                            }
                        }
                    }

                    if (!anyHit) continue;

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
            // Fallback: filled rectangles
            Log.w(TAG, "renderMasks ► No proto masks available — drawing filled rect fallback.");
            Paint fillPaint = new Paint();
            fillPaint.setStyle(Paint.Style.FILL);
            for (DiseaseResult r : results) {
                int c = r.classIndex < CLASS_COLORS.length ? CLASS_COLORS[r.classIndex] : Color.YELLOW;
                fillPaint.setColor(c);
                canvas.drawRect(
                        r.boundingBox.left   * W, r.boundingBox.top    * H,
                        r.boundingBox.right  * W, r.boundingBox.bottom * H,
                        fillPaint
                );
            }
        }

        // Labels are intentionally omitted — disease name and averaged confidence
        // are shown in AnalysisResultActivity instead of on the bitmap overlay.

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

    private static int blendColor(int src, int overlay) {
        float a = Color.alpha(overlay) / 255f;
        int   r = (int)(Color.red(overlay)   * a + Color.red(src)   * (1 - a));
        int   g = (int)(Color.green(overlay) * a + Color.green(src) * (1 - a));
        int   b = (int)(Color.blue(overlay)  * a + Color.blue(src)  * (1 - a));
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