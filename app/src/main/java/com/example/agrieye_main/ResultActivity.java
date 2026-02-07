package com.example.agrieye_main;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;

public class ResultActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        // 1. Initialize UI elements
        ImageView ivResultImage = findViewById(R.id.ivResultImage);
        Button btnRetake = findViewById(R.id.btnRetake);
        Button btnAnalyze = findViewById(R.id.btnAnalyze);
        TextView tvConfidenceValue = findViewById(R.id.tvConfidenceValue);

        // 2. Load the image from the file (Your existing logic)
        String imagePath = getIntent().getStringExtra("image_path");
        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                ivResultImage.setImageBitmap(myBitmap);
            }
        }

        // 3. Retake Button: Goes back to the Camera screen
        btnRetake.setOnClickListener(v -> {
            Intent intent = new Intent(ResultActivity.this, CameraActivity.class);
            startActivity(intent);
            finish(); // Closes result screen to save memory
        });

        // 4. Analyze Button: Triggers the AgriEye model analysis
        btnAnalyze.setOnClickListener(v -> {
            // Placeholder: This is where you will call your disease detection model
            tvConfidenceValue.setText("98%");
        });
    }
}