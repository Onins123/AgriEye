package com.example.agrieye_main;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

public class DetectActivity extends AppCompatActivity {

    private static final String TAG = "DetectActivity";
    private String imagePath;
    private boolean isPalayDetected = false;
    private boolean isDetectionFailed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);

        ImageView ivDetectImage = findViewById(R.id.ivDetectImage);
        Button btnAction = findViewById(R.id.btnAction);
        Button btnRetake = findViewById(R.id.btnRetake);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        View viewOverlay = findViewById(R.id.viewOverlay);
        TextView tvInfoMessage = findViewById(R.id.tvInfoMessage);
        LinearLayout confidenceLayout = findViewById(R.id.confidenceLayout);
        TextView tvConfidenceValue = findViewById(R.id.tvConfidenceValue);

        imagePath = getIntent().getStringExtra("image_path");

        if (imagePath != null) {
            Log.d(TAG, "Loading image from: " + imagePath);
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (myBitmap != null) {
                    ivDetectImage.setImageBitmap(myBitmap);
                } else {
                    Log.e(TAG, "Failed to decode bitmap from path");
                }
            } else {
                Log.e(TAG, "Image file does not exist at path: " + imagePath);
            }
        }

        btnRetake.setOnClickListener(v -> {
            deleteCurrentPhoto();
            Intent intent = new Intent(DetectActivity.this, CameraActivity.class);
            startActivity(intent);
            finish();
        });

        btnAction.setOnClickListener(v -> {
            if (isDetectionFailed) {
                deleteCurrentPhoto();
                Intent intent = new Intent(DetectActivity.this, CameraActivity.class);
                startActivity(intent);
                finish();

            } else if (!isPalayDetected) {
                progressBar.setVisibility(View.VISIBLE);
                viewOverlay.setVisibility(View.VISIBLE);
                btnAction.setText("Detecting");
                btnAction.setEnabled(false);

                new Handler().postDelayed(() -> {
                    boolean detectionSuccess = true; 

                    if (detectionSuccess) {
                        isPalayDetected = true;
                        tvInfoMessage.setText("Palay Detected!");
                        tvInfoMessage.setVisibility(View.VISIBLE);
                        confidenceLayout.setVisibility(View.VISIBLE);
                        tvConfidenceValue.setText("98%"); 
                        btnAction.setText("Analyze");
                        btnRetake.setVisibility(View.VISIBLE);
                    } else {
                        isDetectionFailed = true;
                        tvInfoMessage.setText("No Palay Detected!");
                        tvInfoMessage.setVisibility(View.VISIBLE);
                        btnAction.setText("Retake");
                    }

                    progressBar.setVisibility(View.GONE);
                    viewOverlay.setVisibility(View.GONE);
                    btnAction.setEnabled(true);
                }, 3000);

            } else {
                progressBar.setVisibility(View.VISIBLE);
                viewOverlay.setVisibility(View.VISIBLE);
                btnAction.setText("Analyzing");
                btnAction.setEnabled(false);
                btnRetake.setEnabled(false);

                new Handler().postDelayed(() -> {
                    Intent intent = new Intent(DetectActivity.this, AnalysisResultActivity.class);
                    intent.putExtra("image_path", imagePath);
                    
                    intent.putExtra("disease_name", "Bacterial Leaf Blight");
                    intent.putExtra("diseased_percentage", 15.0);

                    startActivity(intent);

                    progressBar.setVisibility(View.GONE);
                    viewOverlay.setVisibility(View.GONE);
                    btnAction.setText("Analyze");
                    btnAction.setEnabled(true);
                    btnRetake.setEnabled(true);
                }, 3000);
            }
        });
    }

    private void deleteCurrentPhoto() {
        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                imgFile.delete();
            }
        }
    }
}
