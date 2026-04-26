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
    private static final String MODEL_FILE = "best.tflite";

    private static final int   INPUT_SIZE           = 640;
    private static final int   NUM_CLASSES          = 1;
    private static final float CONFIDENCE_THRESHOLD = 0.75f; // lowered to catch real detections
    private static final float IOU_THRESHOLD        = 0.45f;
    private static final int   NUM_MASK_COEFFICIENTS = 32;

    private Interpreter interpreter;
    private final Context context;

    // Discovered at runtime from model tensor shapes
    private int     outputDim1    = -1;   // e.g. 37  (features)
    private int     outputDim2    = -1;   // e.g. 8400 (detections)
    private boolean isTransposed  = false;

    // Proto mask shape — read dynamically from the model
    // Channels-first: [1, 32, 160, 160]  → protoChannelsFirst = true
    // Channels-last:  [1, 160, 160, 32]  → protoChannelsFirst = false
    private int     protoH             = 160;
    private int     protoW             = 160;
    private boolean protoChannelsFirst = true; // default; corrected in initialize()

    // Stored after detect() so renderMask() can use them
    private float[][][][] protoMasksFirst = null; // [1][32][H][W]
    private float[][][][] protoMasksLast  = null; // [1][H][W][32]

    // ── Result container ──────────────────────────────────────────────────────
    public static class DetectionResult {
        public RectF   boundingBox;      // normalized [0,1]
        public float   confidence;
        public String  label;
        public float[] maskCoefficients; // length 32

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

            // ── Parse Output[0]: detection tensor ────────────────────────────
            int[] outShape = interpreter.getOutputTensor(0).shape();
            if (outShape.length == 3) {
                int d1 = outShape[1];
                int d2 = outShape[2];
                // [1, 37, 8400] → features=37, detections=8400 → normal
                // [1, 8400, 37] → detections=8400, features=37 → transposed
                if (d1 < d2) {
                    outputDim1   = d1;   // 37
                    outputDim2   = d2;   // 8400
                    isTransposed = false;
                } else {
                    outputDim1   = d1;   // 8400
                    outputDim2   = d2;   // 37
                    isTransposed = true;
                }
            } else if (outShape.length == 2) {
                outputDim1   = outShape[0];
                outputDim2   = outShape[1];
                isTransposed = (outShape[0] > outShape[1]);
            }

            Log.d(TAG, "isTransposed=" + isTransposed
                    + "  outputDim1=" + outputDim1
                    + "  outputDim2=" + outputDim2);

            // ── Parse Output[1]: proto mask tensor ───────────────────────────
            if (interpreter.getOutputTensorCount() >= 2) {
                int[] protoShape = interpreter.getOutputTensor(1).shape();
                Log.d(TAG, "Proto mask shape: " + Arrays.toString(protoShape));
                // Channels-first: [1, 32, 160, 160]
                // Channels-last:  [1, 160, 160, 32]
                if (protoShape.length == 4) {
                    if (protoShape[1] == NUM_MASK_COEFFICIENTS) {
                        // [1, 32, H, W]
                        protoChannelsFirst = true;
                        protoH = protoShape[2];
                        protoW = protoShape[3];
                    } else {
                        // [1, H, W, 32]
                        protoChannelsFirst = false;
                        protoH = protoShape[1];
                        protoW = protoShape[2];
                    }
                }
                Log.d(TAG, "protoChannelsFirst=" + protoChannelsFirst
                        + "  protoH=" + protoH + "  protoW=" + protoW);
            } else {
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

        // 1. Resize to 640×640
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Bitmap → float32 ByteBuffer
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);

        // 3. Allocate output tensors using EXACT shapes from the model
        //    Output[0]: [1, outputDim1, outputDim2] — use the raw shape as reported
        int[] outShape = interpreter.getOutputTensor(0).shape();
        float[][][] outputBoxes;
        if (outShape.length == 3) {
            outputBoxes = new float[1][outShape[1]][outShape[2]];
        } else {
            // 2D fallback
            outputBoxes = new float[1][outShape[0]][outShape[1]];
        }

        // Reset proto mask holders
        protoMasksFirst = null;
        protoMasksLast  = null;

        boolean hasProtoOutput = interpreter.getOutputTensorCount() >= 2;

        // 4. Run inference
        if (hasProtoOutput) {
            // Allocate proto mask buffer in the correct layout
            if (protoChannelsFirst) {
                protoMasksFirst = new float[1][NUM_MASK_COEFFICIENTS][protoH][protoW];
            } else {
                protoMasksLast = new float[1][protoH][protoW][NUM_MASK_COEFFICIENTS];
            }

            try {
                Object[] inputs = {inputBuffer};
                java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
                outputs.put(0, outputBoxes);
                outputs.put(1, protoChannelsFirst ? protoMasksFirst : protoMasksLast);
                interpreter.runForMultipleInputsOutputs(inputs, outputs);
                Log.d(TAG, "Dual-output inference succeeded.");
            } catch (Exception e) {
                // Log the FULL stack trace so you can see exactly what went wrong
                Log.e(TAG, "Dual-output inference failed — see stack trace below:", e);
                // Do NOT silently fall back. Return empty so you know something is wrong.
                return results;
            }
        } else {
            // Single-output model (no segmentation)
            try {
                interpreter.run(inputBuffer, outputBoxes);
                Log.w(TAG, "Single-output mode — no segmentation masks.");
            } catch (Exception e) {
                Log.e(TAG, "Single-output inference failed:", e);
                return results;
            }
        }

        // 5. Parse detections and return after NMS
        return applyNMS(parseOutput(outputBoxes[0]));
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

        // Log the highest class score to diagnose threshold issues
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
            // cx, cy, w, h are normalized to [0,1] relative to INPUT_SIZE
            float cx = isTransposed ? output[i][0] : output[0][i];
            float cy = isTransposed ? output[i][1] : output[1][i];
            float w  = isTransposed ? output[i][2] : output[2][i];
            float h  = isTransposed ? output[i][3] : output[3][i];

            // Normalize if values appear to be in pixel space (> 1.5 is a good heuristic)
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

    // ── Render segmentation mask + bounding box ───────────────────────────────
    //
    // HOW IT WORKS:
    //   1. For each detected object, take its 32 mask coefficients.
    //   2. Multiply each by the corresponding proto map (protoH × protoW).
    //   3. Sum all 32 → raw mask map of size protoH × protoW.
    //   4. Apply sigmoid → values 0..1.
    //   5. Threshold at 0.5 → binary mask.
    //   6. Scale each proto pixel → image pixel coordinates and paint green.
    //
    public Bitmap renderMask(Bitmap original, List<DetectionResult> results) {
        Bitmap mutable = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas  = new Canvas(mutable);

        int W = mutable.getWidth();
        int H = mutable.getHeight();

        float textSize = Math.max(W, H) * 0.04f;

        boolean hasProto = (protoMasksFirst != null || protoMasksLast != null);

        // ── Draw segmentation masks (pixel-level) ─────────────────────────────────
        if (hasProto) {
            int[] pixels = new int[W * H];
            mutable.getPixels(pixels, 0, W, 0, 0, W, H);

            // Semi-transparent green for detected palay leaf
            int maskColor = Color.argb(120, 0, 220, 80);

            for (int iy = 0; iy < H; iy++) {
                for (int ix = 0; ix < W; ix++) {
                    float nx = (ix + 0.5f) / W;
                    float ny = (iy + 0.5f) / H;

                    int blended = pixels[iy * W + ix];

                    for (DetectionResult r : results) {
                        if (nx < r.boundingBox.left  || nx > r.boundingBox.right
                                || ny < r.boundingBox.top   || ny > r.boundingBox.bottom) continue;

                        int px = Math.min((int)(nx * protoW), protoW - 1);
                        int py = Math.min((int)(ny * protoH), protoH - 1);

                        float dot = 0f;
                        int   len = Math.min(r.maskCoefficients.length, NUM_MASK_COEFFICIENTS);
                        for (int k = 0; k < len; k++) {
                            dot += r.maskCoefficients[k] * getProto(k, py, px);
                        }

                        if (sigmoid(dot) > 0.5f) {
                            blended = blendColor(blended, maskColor);
                        }
                    }

                    pixels[iy * W + ix] = blended;
                }
            }
            mutable.setPixels(pixels, 0, W, 0, 0, W, H);

        } else {
            // Fallback: no proto masks available — draw filled rect
            Log.w(TAG, "renderMask: no proto masks available, falling back to filled rect.");
            Paint fillPaint = new Paint();
            fillPaint.setColor(Color.argb(80, 0, 220, 80));
            fillPaint.setStyle(Paint.Style.FILL);
            for (DetectionResult r : results) {
                canvas.drawRect(
                        r.boundingBox.left * W, r.boundingBox.top * H,
                        r.boundingBox.right * W, r.boundingBox.bottom * H,
                        fillPaint
                );
            }
        }

        // ── Draw labels only (no bounding box border) ─────────────────────────────
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.argb(200, 0, 100, 40));
        bgPaint.setStyle(Paint.Style.FILL);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setAntiAlias(true);
        textPaint.setFakeBoldText(true);

        for (DetectionResult r : results) {
            float left   = r.boundingBox.left  * W;
            float top    = r.boundingBox.top   * H;
            float bottom = r.boundingBox.bottom * H;

            float labelHeight = textSize + 16f;
            float labelTop    = (top - labelHeight > 0) ? top - labelHeight : bottom;
            float labelWidth  = textPaint.measureText(r.label) + 24f;

            canvas.drawRoundRect(left, labelTop, left + labelWidth,
                    labelTop + labelHeight, 8f, 8f, bgPaint);
            canvas.drawText(r.label, left + 12f, labelTop + labelHeight - 8f, textPaint);
        }

        return mutable;
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

    // ── Free memory ───────────────────────────────────────────────────────────
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        protoMasksFirst = null;
        protoMasksLast  = null;
    }
}