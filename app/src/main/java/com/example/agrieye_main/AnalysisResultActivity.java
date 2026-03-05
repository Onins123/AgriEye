package com.example.agrieye_main;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.util.Locale;

public class AnalysisResultActivity extends AppCompatActivity {

    private TextView tvTypeValue, tvCauseValue, tvLevelValue, tvSesValue, tvManagementTipsValue;
    private LinearLayout layoutManagementTips;
    private Button btnManageDisease;
    private ViewGroup rootLayout;
    private String currentManagementTips = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analysis_result);

        rootLayout = findViewById(R.id.rootLayout);
        ImageView resultImageView = findViewById(R.id.imageView_ResultOfAnalysis);
        tvTypeValue = findViewById(R.id.tv_diseaseType_value);
        tvCauseValue = findViewById(R.id.tv_commonCause_value);
        tvLevelValue = findViewById(R.id.tv_severityLevel_value);
        tvSesValue = findViewById(R.id.tv_severityClassification_value);
        
        layoutManagementTips = findViewById(R.id.layoutManagementTips);
        tvManagementTipsValue = findViewById(R.id.tvManagementTipsValue);
        btnManageDisease = findViewById(R.id.btnManageDisease);

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

        btnManageDisease.setOnClickListener(v -> toggleManagementTips());
    }

    private void toggleManagementTips() {
        // Smooth transition for the expanding card
        TransitionManager.beginDelayedTransition(rootLayout, new AutoTransition());

        if (layoutManagementTips.getVisibility() == View.GONE) {
            layoutManagementTips.setVisibility(View.VISIBLE);
            btnManageDisease.setText("Management Tips");
        } else {
            layoutManagementTips.setVisibility(View.GONE);
            btnManageDisease.setText(R.string.btn_manage_disease);
        }
    }

    private void startAnalysis() {
        String diseaseName = getIntent().getStringExtra("disease_name");
        double diseasedPercentage = getIntent().getDoubleExtra("diseased_percentage", 0.0);

        String commonCause;
        if (diseaseName != null) {
            switch (diseaseName) {
                case "Bacterial Leaf Blight":
                    commonCause = "Xanthomonas oryzae pv. oryzae";
                    currentManagementTips = "• Use resistant varieties.\n• Ensure balanced nitrogen fertilization.\n• Maintain field sanitation.\n• Apply copper-based fungicides if necessary.";
                    break;
                case "Bacterial Leaf Streak":
                    commonCause = "Xanthomonas oryzae pv. Oryzicola";
                    currentManagementTips = "• Use certified disease-free seeds.\n• Avoid excessive nitrogen application.\n• Keep fields drained to reduce humidity.";
                    break;
                case "Narrow Brown Spot":
                    commonCause = "Sphaerulina oryzina";
                    currentManagementTips = "• Apply potassium fertilizer.\n• Use resistant cultivars.\n• Consider foliar fungicides during peak infection periods.";
                    break;
                case "Leaf Blast":
                    commonCause = "Magnaporthe oryzae";
                    currentManagementTips = "• Avoid high seeding density.\n• Manage water levels consistently.\n• Use blast-resistant varieties.\n• Apply systemic fungicides early.";
                    break;
                default:
                    commonCause = "Unknown Pathogen";
                    currentManagementTips = "No specific management tips available.";
                    break;
            }
        } else {
            diseaseName = "Unknown Disease";
            commonCause = "N/A";
            currentManagementTips = "Management tips could not be generated.";
        }

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

        if (tvTypeValue != null) tvTypeValue.setText(diseaseName);
        if (tvCauseValue != null) tvCauseValue.setText(commonCause);
        if (tvManagementTipsValue != null) tvManagementTipsValue.setText(currentManagementTips);
        
        if (tvLevelValue != null) {
            tvLevelValue.setText(String.format(Locale.getDefault(), "%.0f%% (%s)", diseasedPercentage, infectionDescription));
        }
        
        if (tvSesValue != null) {
            tvSesValue.setText(sesScale);
        }
    }
}
