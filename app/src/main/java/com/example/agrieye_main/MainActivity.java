package com.example.agrieye_main;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private ActivityResultLauncher<String> galleryLauncher; //declares the gallery launcher

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnTakePhoto = findViewById(R.id.btnTakePhoto);
        Button btnImportImage = findViewById(R.id.btnImportImage);

        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null){
                File savedFile = copyUriToCache(uri);
                if (savedFile != null){
                    Intent intent = new Intent(MainActivity.this, ResultActivity.class);
                    intent.putExtra("image_path", savedFile.getAbsolutePath());
                    startActivity(intent);
                }else{
                    Toast.makeText(MainActivity.this, "Failed to save image", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        );

        // Navigate to CameraActivity
        btnTakePhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                startActivity(intent);
            }
        });

        // Placeholder for Import Image (You can implement gallery picker later)
        btnImportImage.setOnClickListener(v -> {
            galleryLauncher.launch("image/*");
        });
    }

    private File copyUriToCache(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            File file = new File(getCacheDir(), "imported_leaf_" + System.currentTimeMillis() + ".jpg");
            OutputStream outputStream = new FileOutputStream(file);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }

            outputStream.close();
            inputStream.close();
            return file;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}