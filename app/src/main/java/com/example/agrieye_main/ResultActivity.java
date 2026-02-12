package com.example.agrieye_main;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

public class ResultActivity extends AppCompatActivity {

    private String imagePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        // 1. Initialize UI elements
        ImageView ivResultImage = findViewById(R.id.ivResultImage);
        Button btnRetake = findViewById(R.id.btnRetake);
        Button btnAnalyze = findViewById(R.id.btnAnalyze);
        TextView tvConfidenceValue = findViewById(R.id.tvConfidenceValue);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        View viewOverlay = findViewById(R.id.viewOverlay);

        // 2. Load the image from the file (Your existing logic)
        imagePath = getIntent().getStringExtra("image_path");

        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                ivResultImage.setImageBitmap(myBitmap);
            }
        }

        // 3. Retake Button: Goes back to the Camera screen
        btnRetake.setOnClickListener(v -> {

            deleteCurrentPhoto(); //delete the current photo to save memory

            Intent intent = new Intent(ResultActivity.this, CameraActivity.class);
            startActivity(intent);
            finish(); // Closes result screen to save memory
        });

        // 4. Analyze Button: Triggers the AgriEye model analysis
        btnAnalyze.setOnClickListener(v -> {
            // Placeholder: This is where you will call your disease detection model later
            // We DO NOT delete the photo here because the model needs it
            progressBar.setVisibility(View.VISIBLE);
            viewOverlay.setVisibility(View.VISIBLE);
            btnAnalyze.setText("Analyzing");
            btnAnalyze.setEnabled(false);
            btnRetake.setEnabled(false);

            new Handler().postDelayed(() -> {
                progressBar.setVisibility(View.GONE);
                viewOverlay.setVisibility(View.GONE);
                btnAnalyze.setText("Analyze");
                btnAnalyze.setEnabled(true);
                btnRetake.setEnabled(true);
                Toast.makeText(ResultActivity.this, "Analysis Complete!", Toast.LENGTH_SHORT).show();
                tvConfidenceValue.setText("98%");
            }, 3000); // 3-second delay
        });

        //when the back button is pressed it is considered as a retake button press and the photo is deleted
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                deleteCurrentPhoto();

                setEnabled(false);//disasble the callback so that when calling the dispatcher it doesnt go into infinite loop
                getOnBackPressedDispatcher().onBackPressed();
                finish();
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
