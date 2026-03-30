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

        // Initialize both buttons from the Dialog content
        Button btnAgree = dialog.findViewById(R.id.btn_agree);
        Button btnDisagree = dialog.findViewById(R.id.btn_disagree); // NEW BUTTON

        // Find the root layout of activity_main
        final View mainContent = findViewById(R.id.main_content_root);

        // --- AGREE LOGIC ---
        btnAgree.setOnClickListener(v -> {
            dialog.dismiss();
            if (mainContent != null) {
                mainContent.setVisibility(View.VISIBLE);
            }
        });

        // --- DON'T AGREE LOGIC (EXIT APP) ---
        if (btnDisagree != null) {
            btnDisagree.setOnClickListener(v -> {
                dialog.dismiss();
                // Closes all activities and exits the app process
                finishAffinity();
                System.exit(0);
            });
        }

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