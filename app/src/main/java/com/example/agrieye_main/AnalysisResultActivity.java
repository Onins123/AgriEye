package com.example.agrieye_main;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.util.Locale;

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
        // 1. Retrieve data from Intent
        String diseaseName = getIntent().getStringExtra("disease_name");
        double diseasedPercentage = getIntent().getDoubleExtra("diseased_percentage", 0.0);

        // 2. Logic to determine common cause based on disease type
        String commonCause;
        if (diseaseName != null) {
            switch (diseaseName) {
                case "Bacterial Leaf Blight":
                    commonCause = "Xanthomonas oryzae pv. oryzae";
                    break;
                case "Bacterial Leaf Streak":
                    commonCause = "Xanthomonas oryzae pv. Oryzicola";
                    break;
                case "Narrow Brown Spot":
                    commonCause = "Sphaerulina oryzina";
                    break;
                case "Leaf Blast":
                    commonCause = "Magnaporthe oryzae";
                    break;
                default:
                    commonCause = "Unknown Pathogen";
                    break;
            }
        } else {
            diseaseName = "Unknown Disease";
            commonCause = "N/A";
        }

        // 3. Map percentage to SES Scale and Severity Level description
        String infectionDescription;
        String sesScale;

        if (diseasedPercentage <= 0) {
            sesScale = "0";
            infectionDescription = "no infection";
        } else if (diseasedPercentage < 1) {
            sesScale = "1";
            infectionDescription = "trace";
        } else if (diseasedPercentage <= 5) {
            sesScale = "3";
            infectionDescription = "low";
        } else if (diseasedPercentage <= 25) {
            sesScale = "5";
            infectionDescription = "moderate";
        } else if (diseasedPercentage <= 50) {
            sesScale = "7";
            infectionDescription = "high";
        } else {
            sesScale = "9";
            infectionDescription = "severe";
        }

        // 4. Update UI text
        if (tvTypeValue != null) tvTypeValue.setText(diseaseName);
        if (tvCauseValue != null) tvCauseValue.setText(commonCause);
        
        // Format: Severity level: 15% (moderate)
        if (tvLevelValue != null) {
            tvLevelValue.setText(String.format(Locale.getDefault(), "%.0f%% (%s)", diseasedPercentage, infectionDescription));
        }
        
        // Format: Severity Classification (SES): 5
        if (tvSesValue != null) {
            tvSesValue.setText(sesScale);
        }
    }
}
