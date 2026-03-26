package com.example.agrieye_main;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Dialog;
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

    private ActivityResultLauncher<String> galleryLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- STEP 1: Show Disclaimer First ---
        showDisclaimerDialog();

        // --- STEP 2: Initialize UI Components ---
        Button btnTakePhoto = findViewById(R.id.btnTakePhoto);
        Button btnImportImage = findViewById(R.id.btnImportImage);

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

        btnTakePhoto.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            startActivity(intent);
        });

        btnImportImage.setOnClickListener(v -> galleryLauncher.launch("image/*"));

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

    private void showDisclaimerDialog() {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.activity_disclaimer);
        dialog.setCancelable(false);

        Button btnAgree = dialog.findViewById(R.id.btn_agree);

        // This is the key! We find the root LinearLayout from activity_main.xml
        final View mainContent = findViewById(R.id.main_content_root);

        btnAgree.setOnClickListener(v -> {
            dialog.dismiss();

            // If the main menu was hidden (GONE), we turn it back on (VISIBLE)
            if (mainContent != null) {
                mainContent.setVisibility(View.VISIBLE);
            } else {
                // If you still see white, this Toast will tell us if the ID is missing
                Toast.makeText(MainActivity.this, "Main layout not found!", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.show();
    }

    private File copyUriToCache(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            File file = new File(getCacheDir(), "imported_leaf_" + System.currentTimeMillis() + ".jpg");
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