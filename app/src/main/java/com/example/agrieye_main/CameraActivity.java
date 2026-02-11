package com.example.agrieye_main;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private PreviewView viewFinder;
    private ImageButton captureButton;
    private View overlaySquare;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        viewFinder = findViewById(R.id.viewFinder);
        captureButton = findViewById(R.id.image_capture_button);
        overlaySquare = findViewById(R.id.overlaySquare);

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // Set up capture button listener
        captureButton.setOnClickListener(v -> takePhoto());

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // Used to bind the lifecycle of cameras to the lifecycle owner
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview Use Case
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // ImageCapture Use Case (for taking the photo)
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                // Select back camera as a default
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                try {
                    // Unbind use cases before rebinding
                    cameraProvider.unbindAll();

                    // Bind use cases to camera
                    cameraProvider.bindToLifecycle(CameraActivity.this, cameraSelector, preview, imageCapture);

                } catch (Exception exc) {
                    Log.e(TAG, "Use case binding failed", exc);
                }

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider initialization failed.", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        // --- NEW: Capture BOTH Width and Height ---
        float screenWidth = viewFinder.getWidth();
        float screenHeight = viewFinder.getHeight();

        float boxWidth = overlaySquare.getWidth();
        float boxHeight = overlaySquare.getHeight();

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap fullBitmap = imageProxyToBitmap(image);
                image.close();

                if (fullBitmap != null) {
                    // --- NEW: Pass all 4 dimensions to the new function ---
                    Bitmap croppedBitmap = cropToCenterRectangle(fullBitmap, screenWidth, screenHeight, boxWidth, boxHeight);

                    java.io.File savedFile = saveBitmapToFile(croppedBitmap);

                    if (savedFile != null) {
                        android.content.Intent intent = new android.content.Intent(CameraActivity.this, ResultActivity.class);
                        intent.putExtra("image_path", savedFile.getAbsolutePath());
                        startActivity(intent);
                        finish();
                    } else {
                        Toast.makeText(CameraActivity.this, "Error saving image", Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed: " + exception.getMessage());
            }
        });
    }

    // --- Helper functions for Image Processing ---

    /**
     * Converts ImageProxy (from CameraX) to a manageable Bitmap object, handling rotation.
     */
    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        // Rotate the bitmap if necessary (cameras often capture rotated images)
        Matrix matrix = new Matrix();
        matrix.postRotate(image.getImageInfo().getRotationDegrees());
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * Crops a Bitmap to a square in the dead center of the image.
     */
    private Bitmap cropToCenterRectangle(Bitmap fullImage, float viewFinderWidth, float viewFinderHeight, float overlayWidth, float overlayHeight) {
        // 1. Get dimensions of the full captured image
        float imageWidth = fullImage.getWidth();
        float imageHeight = fullImage.getHeight();

        // 2. Calculate the scale factors
        // This tells us how much the image was scaled up/down to fit the screen width/height
        float scaleX = imageWidth / viewFinderWidth;
        float scaleY = imageHeight / viewFinderHeight;

        // 3. Determine the "Zoom" factor
        // Since the preview uses "FILL_CENTER", it uses the smaller scale to zoom in until the screen is filled.
        // We pick the smaller number here to reverse-engineer that zoom.
        // (Note: If your preview cuts off the sides, we use one logic; if it cuts top/bottom, another.
        // The simplest robust way for "FILL_CENTER" is to find the matching scale).
        float scale = Math.min(scaleX, scaleY);

        // 4. Calculate the actual size of the crop on the full image
        // We multiply the overlay size by the scale to see how big it is on the real image
        float actualCropWidth = overlayWidth * scale;
        float actualCropHeight = overlayHeight * scale;

        // 5. Calculate the starting coordinates (Top-Left corner of the crop)
        // We assume the overlay is centered, so we center the crop on the full image too.
        float cropX = (imageWidth - actualCropWidth) / 2;
        float cropY = (imageHeight - actualCropHeight) / 2;

        // 6. Safety Checks (Ensure we don't crop outside the image)
        if (cropX < 0) cropX = 0;
        if (cropY < 0) cropY = 0;
        if (cropX + actualCropWidth > imageWidth) actualCropWidth = imageWidth - cropX;
        if (cropY + actualCropHeight > imageHeight) actualCropHeight = imageHeight - cropY;

        // 7. Create the crop
        return Bitmap.createBitmap(
                fullImage,
                (int) cropX,
                (int) cropY,
                (int) actualCropWidth,
                (int) actualCropHeight
        );
    }

    // --- Permission Handling Boilerplate ---
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private java.io.File saveBitmapToFile(Bitmap bitmap) {
        try {
            // Create a temporary file in the cache directory
            java.io.File file = new java.io.File(getCacheDir(), "cropped_leaf_" + System.currentTimeMillis() + ".jpg");
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);

            // Compress the bitmap to JPEG format
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);

            out.flush();
            out.close();
            return file;
        } catch (java.io.IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
