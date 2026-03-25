package com.example.agrieye_main;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    // ── Gallery launcher must be a field, declared here (NOT inside onCreate) ──
    private ActivityResultLauncher<String> galleryLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnTakePhoto   = findViewById(R.id.btnTakePhoto);
        Button btnImportImage = findViewById(R.id.btnImportImage);

        // Register the gallery picker — must be done in onCreate before any click
        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        File savedFile = copyUriToCache(uri);
                        if (savedFile != null) {
                            Intent intent = new Intent(MainActivity.this, DetectActivity.class);
                            intent.putExtra("image_path", savedFile.getAbsolutePath());
                            intent.putExtra("is_from_camera", false);
                            startActivity(intent);
                        } else {
                            Toast.makeText(MainActivity.this, "Failed to save image", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        // Navigate to CameraActivity
        btnTakePhoto.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            startActivity(intent);
        });

        // Open gallery picker
        btnImportImage.setOnClickListener(v -> galleryLauncher.launch("image/*"));

        // Back button → show exit dialog
        getOnBackPressedDispatcher().addCallback(
                this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        Intent intent = new Intent(MainActivity.this, ExitActivity.class);
                        startActivity(intent);
                    }
                }
        );
    }

    /** Copies a Uri (from gallery) into the app's cache dir as a real File. */
    private File copyUriToCache(Uri uri) {
        try {
            InputStream  inputStream  = getContentResolver().openInputStream(uri);
            File         file         = new File(getCacheDir(), "imported_leaf_" + System.currentTimeMillis() + ".jpg");
            OutputStream outputStream = new FileOutputStream(file);

            byte[] buffer = new byte[4096];
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