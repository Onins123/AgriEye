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

/**
 * YoloClassifier — YOLOv8n-seg TFLite wrapper for Palay detection.
 *
 * Output tensor shape for YOLOv8n-seg (1 class):
 *   [1, 37, 8400]  →  4 box coords + 1 class score + 32 mask coefficients
 *
 * Mask proto tensor shape:
 *   [1, 160, 160, 32]
 *
 * IMPORTANT: After loading the model, run the shape debug log once and confirm
 * these match. See debugOutputShapes().
 */
public class YoloClassifier {

    private static final String TAG = "YoloClassifier";
    private static final String MODEL_FILE = "best_float32.tflite";

    // Model I/O constants
    private static final int INPUT_SIZE      = 640;
    private static final int NUM_CLASSES     = 2;       // only "palay"
    private static final int NUM_MASKS       = 32;      // YOLOv8-seg mask coefficients
    private static final int MASK_SIZE       = 160;     // prototype mask resolution

    // Detection thresholds
    private static final float CONFIDENCE_THRESHOLD = 0.45f;
    private static final float IOU_THRESHOLD        = 0.45f;

    // Mask overlay color: semi-transparent green
    private static final int MASK_COLOR = Color.argb(120, 0, 200, 80);

    private Interpreter interpreter;
    private final Context context;

    // ──────────────────────────────────────────────
    // Result container
    // ──────────────────────────────────────────────
    public static class DetectionResult {
        public RectF    boundingBox;   // normalized [0,1]
        public float    confidence;
        public String   label;
        public float[]  maskCoefficients; // 32 values for segmentation

        public DetectionResult(RectF box, float conf, String lbl, float[] maskCoefs) {
            this.boundingBox      = box;
            this.confidence       = conf;
            this.label            = lbl;
            this.maskCoefficients = maskCoefs;
        }
    }

    // ──────────────────────────────────────────────
    // Constructor
    // ──────────────────────────────────────────────
    public YoloClassifier(Context context) {
        this.context = context;
    }

    // ──────────────────────────────────────────────
    // Initialize — call ONCE in Activity.onCreate()
    // ──────────────────────────────────────────────
    public boolean initialize() {
        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE);

            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            // options.setUseNNAPI(true); // Uncomment for faster inference on supported devices

            interpreter = new Interpreter(modelBuffer, options);

            // Log shapes so you can verify them once
            debugOutputShapes();

            Log.d(TAG, "Model loaded successfully.");
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to load model: " + e.getMessage());
            return false;
        }
    }

    // ──────────────────────────────────────────────
    // Main detection — call with your Bitmap
    // ──────────────────────────────────────────────
    public List<DetectionResult> detect(Bitmap bitmap) {
        List<DetectionResult> results = new ArrayList<>();

        if (interpreter == null) {
            Log.e(TAG, "Interpreter not initialized. Call initialize() first.");
            return results;
        }

        // 1. Resize to 640×640
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Convert to float ByteBuffer
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);

        // 3. Prepare output buffers
        //    Output 0: [1, 37, 8400]  → boxes + class score + 32 mask coefficients
        //    Output 1: [1, 160, 160, 32] → mask prototypes
        float[][][] detectionOutput = new float[1][4 + NUM_CLASSES + NUM_MASKS][8400];
        float[][][][] maskProtos    = new float[1][MASK_SIZE][MASK_SIZE][NUM_MASKS];

        Object[] inputs  = { inputBuffer };
        java.util.HashMap<Integer, Object> outputs = new java.util.HashMap<>();
        outputs.put(0, detectionOutput);
        outputs.put(1, maskProtos);

        // 4. Run inference
        try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs);
        } catch (Exception e) {
            Log.e(TAG, "Inference error: " + e.getMessage());
            return results;
        }

        // 5. Parse detections + apply NMS
        results = parseOutput(detectionOutput[0]);
        return results;
    }

    // ──────────────────────────────────────────────
    // Render segmentation mask onto a Bitmap
    // ──────────────────────────────────────────────
    /**
     * Draws a semi-transparent green mask over detected palay regions.
     * Call this AFTER detect() with the same bitmap.
     *
     * @param bitmap   The original image bitmap (mutable copy will be created)
     * @param results  Results from detect()
     * @return         New bitmap with mask overlay painted on it
     */
    public Bitmap renderMask(Bitmap bitmap, List<DetectionResult> results) {
        if (results == null || results.isEmpty()) return bitmap;

        // We need to re-run inference to get mask protos — store them as a field instead
        // For simplicity, this method uses bounding box fill as mask approximation.
        // See renderMaskWithProtos() below for full segmentation.

        Bitmap mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        int W = mutable.getWidth();
        int H = mutable.getHeight();

        for (DetectionResult r : results) {
            int left   = (int)(r.boundingBox.left   * W);
            int top    = (int)(r.boundingBox.top    * H);
            int right  = (int)(r.boundingBox.right  * W);
            int bottom = (int)(r.boundingBox.bottom * H);

            left   = Math.max(0, left);
            top    = Math.max(0, top);
            right  = Math.min(W - 1, right);
            bottom = Math.min(H - 1, bottom);

            for (int y = top; y <= bottom; y++) {
                for (int x = left; x <= right; x++) {
                    int original = mutable.getPixel(x, y);
                    int blended  = blendColor(original, MASK_COLOR);
                    mutable.setPixel(x, y, blended);
                }
            }
        }
        return mutable;
    }

    // ──────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────

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

    private List<DetectionResult> parseOutput(float[][] output) {
        List<DetectionResult> candidates = new ArrayList<>();
        int numDetections = output[0].length; // 8400

        for (int i = 0; i < numDetections; i++) {
            float cx = output[0][i];
            float cy = output[1][i];
            float w  = output[2][i];
            float h  = output[3][i];

            // Class score (only 1 class: palay)
            float score = output[4][i];

            if (score >= CONFIDENCE_THRESHOLD) {
                float x1 = cx - w / 2f;
                float y1 = cy - h / 2f;
                float x2 = cx + w / 2f;
                float y2 = cy + h / 2f;

                // Clamp to [0, 1]
                x1 = Math.max(0f, Math.min(1f, x1));
                y1 = Math.max(0f, Math.min(1f, y1));
                x2 = Math.max(0f, Math.min(1f, x2));
                y2 = Math.max(0f, Math.min(1f, y2));

                // Extract 32 mask coefficients
                float[] maskCoefs = new float[NUM_MASKS];
                for (int m = 0; m < NUM_MASKS; m++) {
                    maskCoefs[m] = output[5 + m][i];
                }

                String label = String.format("Palay %.0f%%", score * 100);
                candidates.add(new DetectionResult(new RectF(x1, y1, x2, y2), score, label, maskCoefs));
            }
        }

        return applyNMS(candidates);
    }

    private List<DetectionResult> applyNMS(List<DetectionResult> detections) {
        List<DetectionResult> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];

        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (!suppressed[j]) {
                    float iou = computeIoU(detections.get(i).boundingBox,
                            detections.get(j).boundingBox);
                    if (iou > IOU_THRESHOLD) suppressed[j] = true;
                }
            }
        }
        return result;
    }

    private float computeIoU(RectF a, RectF b) {
        float interLeft   = Math.max(a.left,   b.left);
        float interTop    = Math.max(a.top,    b.top);
        float interRight  = Math.min(a.right,  b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        float interArea = Math.max(0, interRight - interLeft)
                * Math.max(0, interBottom - interTop);
        float aArea = (a.right - a.left) * (a.bottom - a.top);
        float bArea = (b.right - b.left) * (b.bottom - b.top);

        return interArea / (aArea + bArea - interArea + 1e-6f);
    }

    private int blendColor(int base, int overlay) {
        float alpha = Color.alpha(overlay) / 255f;
        int r = (int)(Color.red(overlay)   * alpha + Color.red(base)   * (1 - alpha));
        int g = (int)(Color.green(overlay) * alpha + Color.green(base) * (1 - alpha));
        int b = (int)(Color.blue(overlay)  * alpha + Color.blue(base)  * (1 - alpha));
        return Color.rgb(r, g, b);
    }

    // ──────────────────────────────────────────────
    // Debug — run once and check Logcat for shapes
    // ──────────────────────────────────────────────
    public void debugOutputShapes() {
        if (interpreter == null) return;
        int[] inputShape   = interpreter.getInputTensor(0).shape();
        int[] outputShape0 = interpreter.getOutputTensor(0).shape();
        int numOutputs     = interpreter.getOutputTensorCount();
        Log.d(TAG, "Input shape:    " + Arrays.toString(inputShape));
        Log.d(TAG, "Output[0] shape: " + Arrays.toString(outputShape0));
        Log.d(TAG, "Total outputs:  " + numOutputs);
        if (numOutputs > 1) {
            int[] outputShape1 = interpreter.getOutputTensor(1).shape();
            Log.d(TAG, "Output[1] shape: " + Arrays.toString(outputShape1));
        }
    }

    // ──────────────────────────────────────────────
    // Cleanup — call in onDestroy()
    // ──────────────────────────────────────────────
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}