package com.example.agrieye_main;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

public class AnalysisResultActivity extends AppCompatActivity {

    private TextView tvTypeValue, tvCauseValue, tvLevelValue, tvSesValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analysis_result);

        ImageView resultImageView = findViewById(R.id.imageView_ResultOfAnalysis);
        tvTypeValue = findViewById(R.id.tv_diseaseType_value);
        tvCauseValue = findViewById(R.id.tv_commonCause_value);
        tvLevelValue = findViewById(R.id.tv_severityLevel_value);
        tvSesValue = findViewById(R.id.tv_severityClassification_value);

        String imagePath = getIntent().getStringExtra("image_path");

        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (myBitmap != null) {
                    resultImageView.setImageBitmap(myBitmap);
                }
                startAnalysis();
            }
        }
    }

    private void startAnalysis() {
        if (tvTypeValue != null) {
            tvTypeValue.setText(getString(R.string.disease_type_blight));
        }
        if (tvCauseValue != null) {
            tvCauseValue.setText(getString(R.string.common_cause_humidity));
        }
        if (tvLevelValue != null) {
            tvLevelValue.setText(getString(R.string.severity_moderate));
        }
        if (tvSesValue != null) {
            tvSesValue.setText(getString(R.string.ses_scale_5));
        }
    }
}