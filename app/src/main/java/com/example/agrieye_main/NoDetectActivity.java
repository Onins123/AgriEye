package com.example.agrieye_main;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

public class NoDetectActivity extends AppCompatActivity {

    private String imagePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_no_detect);

        ImageView ivResultImage = findViewById(R.id.ivResultImage);
        Button btnRetry = findViewById(R.id.btnRetry);

        imagePath = getIntent().getStringExtra("image_path");

        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                ivResultImage.setImageBitmap(myBitmap);
            }
        }

        btnRetry.setOnClickListener(v -> {
            deleteCurrentPhoto();
            Intent intent = new Intent(NoDetectActivity.this, CameraActivity.class);
            startActivity(intent);
            finish();
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
