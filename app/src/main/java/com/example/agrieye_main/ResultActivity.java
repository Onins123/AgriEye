package com.example.agrieye_main;

import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import java.io.File;

public class ResultActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        ImageView ivResultImage = findViewById(R.id.ivResultImage);

        // 1. Get the file path from the Intent
        String imagePath = getIntent().getStringExtra("image_path");

        if (imagePath != null) {
            // 2. Load the image from the file
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                ivResultImage.setImageBitmap(myBitmap);
            }
        }
    }
}
