package com.example.agrieye_main;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
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
    private static final String MODEL_FILE = "best.tflite";

    private static final int   INPUT_SIZE            = 640;
    private static final int   NUM_CLASSES           = 1;
    private static final float CONFIDENCE_THRESHOLD  = 0.60f;
    private static final float IOU_THRESHOLD         = 0.45f;
    private static final int   NUM_MASK_COEFFICIENTS = 32;

    private Interpreter interpreter;
    private final Context context;

    // Discovered at runtime from model tensor shapes
    private int     outputDim1   = -1;
    private int     outputDim2   = -1;
    private boolean isTransposed = false;

    // ── Output tensor index resolution ───────────────────────────────────────
    private int boxTensorIndex   = 0;
    private int protoTensorIndex = 1;

    // Proto mask shape
    private int     protoH             = 160;
    private int     protoW             = 160;
    private boolean protoChannelsFirst = true;

    // Stored after detect() so renderMask() and getLeafMaskPixelCount() can use them
    private float[][][][] protoMasksFirst = null; // [1][32][H][W]
    private float[][][][] protoMasksLast  = null; // [1][H][W][32]

    // Last detection results — stored so DetectActivity can request leaf pixel count
    private List<DetectionResult> lastResults = null;

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

    // ── Initialize ────────────────────────────────────────────────────────────
    public boolean initialize() {
        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(modelBuffer, options);

            Log.d(TAG, "══════════════════════════════════════════════");
            Log.d(TAG, "INPUT  tensors : " + interpreter.getInputTensorCount());
            Log.d(TAG, "OUTPUT tensors : " + interpreter.getOutputTensorCount());

            int[] inShape = interpreter.getInputTensor(0).shape();
            Log.d(TAG, "INPUT[0]  shape: " + Arrays.toString(inShape));

            for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
                int[] s = interpreter.getOutputTensor(i).shape();
                Log.d(TAG, "OUTPUT[" + i + "] shape: " + Arrays.toString(s));
            }

            // ── Auto-detect which output is boxes vs proto masks ──────────────
            if (interpreter.getOutputTensorCount() >= 2) {
                int[] shape0 = interpreter.getOutputTensor(0).shape();
                int[] shape1 = interpreter.getOutputTensor(1).shape();

                if (shape0.length == 4 && shape1.length == 3) {
                    protoTensorIndex = 0;
                    boxTensorIndex   = 1;
                    Log.d(TAG, "Detected SWAPPED layout: proto=output[0], boxes=output[1]");
                } else {
                    boxTensorIndex   = 0;
                    protoTensorIndex = 1;
                    Log.d(TAG, "Detected STANDARD layout: boxes=output[0], proto=output[1]");
                }

                int[] outShape = interpreter.getOutputTensor(boxTensorIndex).shape();
                if (outShape.length == 3) {
                    int d1 = outShape[1];
                    int d2 = outShape[2];
                    if (d1 < d2) { outputDim1 = d1; outputDim2 = d2; isTransposed = false; }
                    else          { outputDim1 = d1; outputDim2 = d2; isTransposed = true;  }
                } else if (outShape.length == 2) {
                    outputDim1   = outShape[0];
                    outputDim2   = outShape[1];
                    isTransposed = (outShape[0] > outShape[1]);
                }

                Log.d(TAG, "isTransposed=" + isTransposed
                        + "  outputDim1=" + outputDim1
                        + "  outputDim2=" + outputDim2);

                int[] protoShape = interpreter.getOutputTensor(protoTensorIndex).shape();
                Log.d(TAG, "Proto tensor[" + protoTensorIndex + "] shape: " + Arrays.toString(protoShape));

                if (protoShape.length == 4) {
                    if (protoShape[1] == NUM_MASK_COEFFICIENTS) {
                        protoChannelsFirst = true;
                        protoH = protoShape[2];
                        protoW = protoShape[3];
                    } else {
                        protoChannelsFirst = false;
                        protoH = protoShape[1];
                        protoW = protoShape[2];
                    }
                }
                Log.d(TAG, "protoChannelsFirst=" + protoChannelsFirst
                        + "  protoH=" + protoH + "  protoW=" + protoW);

            } else {
                int[] outShape = interpreter.getOutputTensor(0).shape();
                if (outShape.length == 3) {
                    int d1 = outShape[1];
                    int d2 = outShape[2];
                    if (d1 < d2) { outputDim1 = d1; outputDim2 = d2; isTransposed = false; }
                    else          { outputDim1 = d1; outputDim2 = d2; isTransposed = true;  }
                }
                Log.w(TAG, "Model has only 1 output tensor — segmentation masks NOT available.");
            }

            Log.d(TAG, "Model loaded successfully.");
            Log.d(TAG, "══════════════════════════════════════════════");
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
            Log.e(TAG, "Interpreter not ready — call initialize() first.");
            return results;
        }

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);

        int[] boxShape = interpreter.getOutputTensor(boxTensorIndex).shape();
        float[][][] outputBoxes;
        if (boxShape.length == 3) {
            outputBoxes = new float[1][boxShape[1]][boxShape[2]];
        } else {
            outputBoxes = new float[1][boxShape[0]][boxShape[1]];
        }

        protoMasksFirst = null;
        protoMasksLast  = null;

        boolean hasProtoOutput = interpreter.getOutputTensorCount() >= 2;

        if (hasProtoOutput) {
            if (protoChannelsFirst) {
                protoMasksFirst = new float[1][NUM_MASK_COEFFICIENTS][protoH][protoW];
            } else {
                protoMasksLast = new float[1][protoH][protoW][NUM_MASK_COEFFICIENTS];
            }

            try {
                Object[] inputs = {inputBuffer};
                java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
                outputs.put(boxTensorIndex,   outputBoxes);
                outputs.put(protoTensorIndex, protoChannelsFirst ? protoMasksFirst : protoMasksLast);
                interpreter.runForMultipleInputsOutputs(inputs, outputs);
                Log.d(TAG, "Dual-output inference succeeded.");
                Log.d(TAG, "Proto masks populated: protoMasksFirst="
                        + (protoMasksFirst != null) + "  protoMasksLast=" + (protoMasksLast != null));

            } catch (Exception e) {
                Log.e(TAG, "Dual-output inference failed:", e);

                Log.w(TAG, "Retrying with SWAPPED output indices…");
                protoMasksFirst = null;
                protoMasksLast  = null;
                int tmp = boxTensorIndex;
                boxTensorIndex   = protoTensorIndex;
                protoTensorIndex = tmp;

                int[] swappedBoxShape = interpreter.getOutputTensor(boxTensorIndex).shape();
                float[][][] swappedOutputBoxes;
                if (swappedBoxShape.length == 3) {
                    swappedOutputBoxes = new float[1][swappedBoxShape[1]][swappedBoxShape[2]];
                } else {
                    swappedOutputBoxes = new float[1][swappedBoxShape[0]][swappedBoxShape[1]];
                }

                if (protoChannelsFirst) {
                    protoMasksFirst = new float[1][NUM_MASK_COEFFICIENTS][protoH][protoW];
                } else {
                    protoMasksLast = new float[1][protoH][protoW][NUM_MASK_COEFFICIENTS];
                }

                try {
                    inputBuffer.rewind();
                    Object[] inputs2 = {inputBuffer};
                    java.util.Map<Integer, Object> outputs2 = new java.util.HashMap<>();
                    outputs2.put(boxTensorIndex,   swappedOutputBoxes);
                    outputs2.put(protoTensorIndex, protoChannelsFirst ? protoMasksFirst : protoMasksLast);
                    interpreter.runForMultipleInputsOutputs(inputs2, outputs2);
                    Log.d(TAG, "Fallback dual-output inference succeeded with swapped indices.");
                    outputBoxes = swappedOutputBoxes;
                } catch (Exception e2) {
                    Log.e(TAG, "Fallback inference also failed:", e2);
                    return results;
                }
            }
        } else {
            try {
                interpreter.run(inputBuffer, outputBoxes);
                Log.w(TAG, "Single-output mode — no segmentation masks.");
            } catch (Exception e) {
                Log.e(TAG, "Single-output inference failed:", e);
                return results;
            }
        }

        lastResults = applyNMS(parseOutput(outputBoxes[0]));
        return lastResults;
    }

    // ── Convert Bitmap → float32 ByteBuffer [0,1] ────────────────────────────
    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
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

    // ── Parse output tensor into DetectionResult list ─────────────────────────
    private List<DetectionResult> parseOutput(float[][] output) {
        List<DetectionResult> candidates = new ArrayList<>();

        int numDetections = isTransposed ? outputDim1 : outputDim2;
        int numFeatures   = isTransposed ? outputDim2 : outputDim1;
        int numMaskCoeffs = Math.max(0, numFeatures - 4 - NUM_CLASSES);

        float highest = -Float.MAX_VALUE;
        for (int i = 0; i < numDetections; i++) {
            float score = isTransposed ? output[i][4] : output[4][i];
            if (score > highest) highest = score;
        }
        Log.d(TAG, "──────────────────────────────────────────────");
        Log.d(TAG, "Highest Palay score : " + highest);
        Log.d(TAG, "Confidence threshold: " + CONFIDENCE_THRESHOLD);
        Log.d(TAG, "numDetections=" + numDetections + "  numFeatures=" + numFeatures
                + "  numMaskCoeffs=" + numMaskCoeffs);
        Log.d(TAG, "──────────────────────────────────────────────");

        for (int i = 0; i < numDetections; i++) {
            float cx = isTransposed ? output[i][0] : output[0][i];
            float cy = isTransposed ? output[i][1] : output[1][i];
            float w  = isTransposed ? output[i][2] : output[2][i];
            float h  = isTransposed ? output[i][3] : output[3][i];

            if (cx > 1.5f) { cx /= INPUT_SIZE; cy /= INPUT_SIZE;
                w  /= INPUT_SIZE; h  /= INPUT_SIZE; }

            float score = isTransposed ? output[i][4] : output[4][i];
            if (score < CONFIDENCE_THRESHOLD) continue;

            float x1 = Math.max(0f, cx - w / 2f);
            float y1 = Math.max(0f, cy - h / 2f);
            float x2 = Math.min(1f, cx + w / 2f);
            float y2 = Math.min(1f, cy + h / 2f);

            float[] maskCoeffs = new float[numMaskCoeffs];
            for (int m = 0; m < numMaskCoeffs; m++) {
                int fi = 4 + NUM_CLASSES + m;
                maskCoeffs[m] = isTransposed ? output[i][fi] : output[fi][i];
            }

            String label = "Palay " + String.format("%.0f%%", score * 100);
            candidates.add(new DetectionResult(new RectF(x1, y1, x2, y2), score, label, maskCoeffs));
        }

        Log.d(TAG, "Candidates after threshold: " + candidates.size());
        return candidates;
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
        float iL = Math.max(a.left, b.left),   iT = Math.max(a.top, b.top);
        float iR = Math.min(a.right, b.right),  iB = Math.min(a.bottom, b.bottom);
        float inter = Math.max(0, iR - iL) * Math.max(0, iB - iT);
        float aA = (a.right - a.left) * (a.bottom - a.top);
        float bA = (b.right - b.left) * (b.bottom - b.top);
        return inter / (aA + bA - inter + 1e-6f);
    }

    // ── Helper: get a proto value regardless of channel layout ───────────────
    private float getProto(int k, int py, int px) {
        if (protoChannelsFirst && protoMasksFirst != null) {
            return protoMasksFirst[0][k][py][px];
        } else if (!protoChannelsFirst && protoMasksLast != null) {
            return protoMasksLast[0][py][px][k];
        }
        return 0f;
    }

    // ── NEW: Count leaf mask pixels from best.tflite segmentation output ──────
    /**
     * Counts the number of proto-grid pixels that belong to the detected leaf
     * mask from best.tflite. This value is used by DiseaseClassifier as the
     * denominator (total leaf area) when computing disease severity percentage.
     *
     * Must be called AFTER detect() has been run on the same bitmap.
     *
     * Returns 0 if no proto masks are available (single-output model fallback).
     */
    public long getLeafMaskPixelCount() {
        if (lastResults == null || lastResults.isEmpty()) return 0;
        boolean hasProto = (protoMasksFirst != null || protoMasksLast != null);
        if (!hasProto) {
            // Fallback: estimate leaf area from bounding box of best detection
            DetectionResult best = lastResults.get(0);
            float area = (best.boundingBox.right - best.boundingBox.left)
                    * (best.boundingBox.bottom - best.boundingBox.top);
            // Scale to proto grid units so caller has a consistent unit
            return (long)(area * protoH * protoW);
        }

        long leafPixels = 0;
        for (int py = 0; py < protoH; py++) {
            for (int px = 0; px < protoW; px++) {
                float nx = (px + 0.5f) / protoW;
                float ny = (py + 0.5f) / protoH;

                for (DetectionResult r : lastResults) {
                    if (nx < r.boundingBox.left  || nx > r.boundingBox.right
                            || ny < r.boundingBox.top   || ny > r.boundingBox.bottom) continue;

                    float dot = 0f;
                    int len = Math.min(r.maskCoefficients.length, NUM_MASK_COEFFICIENTS);
                    for (int k = 0; k < len; k++) {
                        dot += r.maskCoefficients[k] * getProto(k, py, px);
                    }
                    if (sigmoid(dot) > 0.5f) {
                        leafPixels++;
                        break; // count this proto pixel once even if detections overlap
                    }
                }
            }
        }
        Log.d(TAG, "getLeafMaskPixelCount ► leafPixels=" + leafPixels
                + "  protoGrid=" + protoW + "x" + protoH);
        return leafPixels;
    }

    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
    }

    // ── Render segmentation mask ONLY (no bounding box, no label) ────────────
    public Bitmap renderMask(Bitmap original, List<DetectionResult> results) {
        Bitmap mutable = original.copy(Bitmap.Config.ARGB_8888, true);

        int W = mutable.getWidth();
        int H = mutable.getHeight();

        boolean hasProto = (protoMasksFirst != null || protoMasksLast != null);

        if (!hasProto) {
            Log.w(TAG, "renderMask: no proto masks available — check Logcat for dual-output inference errors.");
            return mutable;
        }

        int[] pixels = new int[W * H];
        mutable.getPixels(pixels, 0, W, 0, 0, W, H);

        final int alpha = 120;

        for (DetectionResult r : results) {
            if (r.maskCoefficients == null || r.maskCoefficients.length == 0) continue;

            int numCoeffs = Math.min(r.maskCoefficients.length, NUM_MASK_COEFFICIENTS);

            float[] rawMask = new float[protoH * protoW];
            for (int k = 0; k < numCoeffs; k++) {
                float coeff = r.maskCoefficients[k];
                for (int py = 0; py < protoH; py++) {
                    for (int px = 0; px < protoW; px++) {
                        rawMask[py * protoW + px] += coeff * getProto(k, py, px);
                    }
                }
            }

            float[] sigMask = new float[protoH * protoW];
            for (int i = 0; i < rawMask.length; i++) {
                sigMask[i] = 1f / (1f + (float) Math.exp(-rawMask[i]));
            }

            int bx1 = (int)(r.boundingBox.left   * W);
            int by1 = (int)(r.boundingBox.top    * H);
            int bx2 = (int)(r.boundingBox.right  * W);
            int by2 = (int)(r.boundingBox.bottom * H);

            bx1 = Math.max(0, bx1); by1 = Math.max(0, by1);
            bx2 = Math.min(W, bx2); by2 = Math.min(H, by2);

            for (int iy = by1; iy < by2; iy++) {
                for (int ix = bx1; ix < bx2; ix++) {

                    float protoFx = ((ix + 0.5f) / W) * protoW - 0.5f;
                    float protoFy = ((iy + 0.5f) / H) * protoH - 0.5f;

                    int x0 = (int) Math.floor(protoFx);
                    int y0 = (int) Math.floor(protoFy);
                    int x1 = x0 + 1;
                    int y1 = y0 + 1;

                    float wx = protoFx - x0;
                    float wy = protoFy - y0;

                    x0 = Math.max(0, Math.min(protoW - 1, x0));
                    x1 = Math.max(0, Math.min(protoW - 1, x1));
                    y0 = Math.max(0, Math.min(protoH - 1, y0));
                    y1 = Math.max(0, Math.min(protoH - 1, y1));

                    float v00 = sigMask[y0 * protoW + x0];
                    float v10 = sigMask[y0 * protoW + x1];
                    float v01 = sigMask[y1 * protoW + x0];
                    float v11 = sigMask[y1 * protoW + x1];

                    float val = (v00 * (1 - wx) + v10 * wx) * (1 - wy)
                            + (v01 * (1 - wx) + v11 * wx) * wy;

                    if (val < 0.5f) continue;

                    int base = pixels[iy * W + ix];
                    int inv  = 255 - alpha;
                    int r2 = (Color.red(base)   * inv + 0     * alpha) / 255;
                    int g2 = (Color.green(base) * inv + 220   * alpha) / 255;
                    int b2 = (Color.blue(base)  * inv + 220   * alpha) / 255;
                    pixels[iy * W + ix] = Color.argb(255, r2, g2, b2);
                }
            }
        }

        mutable.setPixels(pixels, 0, W, 0, 0, W, H);
        return mutable;
    }

    // ── Free memory ───────────────────────────────────────────────────────────
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        protoMasksFirst = null;
        protoMasksLast  = null;
        lastResults     = null;
    }
}