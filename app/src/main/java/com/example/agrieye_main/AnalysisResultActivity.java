package com.example.agrieye_main;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import java.io.File;
import java.util.Locale;

public class AnalysisResultActivity extends AppCompatActivity {

    // Global UI references
    private TextView tvTypeValue, tvCauseValue, tvLevelValue, tvSesValue, tvSymptomsValue, tvManagementTipsValue;
    private ViewGroup rootLayout;

    // Expandable Containers (The inner cards)
    private View expandableAbout, expandableSymptoms, expandableManagement;

    // Clickable Headers (The outer cards)
    private CardView cardAbout, cardSymptoms, cardManagement;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analysis_result);

        // 1. Initialize Views
        rootLayout = findViewById(R.id.rootLayout);
        ImageView resultImageView = findViewById(R.id.imageView_ResultOfAnalysis);

        // Diagnosis section (Always visible)
        tvTypeValue = findViewById(R.id.tv_diseaseType_value);
        tvLevelValue = findViewById(R.id.tv_severityLevel_value);
        tvSesValue = findViewById(R.id.tv_severityClassification_value);

        // Accordion Outer Cards (Clickable)
        cardAbout = findViewById(R.id.cardAbout);
        cardSymptoms = findViewById(R.id.cardSymptoms);
        cardManagement = findViewById(R.id.cardManagement);

        // Accordion Inner Cards (Expandable)
        expandableAbout = findViewById(R.id.expandableAbout);
        expandableSymptoms = findViewById(R.id.expandableSymptoms);
        expandableManagement = findViewById(R.id.expandableManagement);

        // Content TextViews inside the expandable cards
        tvCauseValue = findViewById(R.id.tv_commonCause_value);
        tvSymptomsValue = findViewById(R.id.tv_symptoms_value);
        tvManagementTipsValue = findViewById(R.id.tvManagementTipsValue);

        // 2. Set Up Click Listeners for the Accordion
        cardAbout.setOnClickListener(v -> toggleAccordion(expandableAbout));
        cardSymptoms.setOnClickListener(v -> toggleAccordion(expandableSymptoms));
        cardManagement.setOnClickListener(v -> toggleAccordion(expandableManagement));

        // 3. Load Image
        String imagePath = getIntent().getStringExtra("image_path");
        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (myBitmap != null) {
                    resultImageView.setImageBitmap(myBitmap);
                }
            }
        }

        // 4. Run Analysis Logic to fill text
        startAnalysis();
    }

    private void toggleAccordion(View viewToToggle) {
        // Smooth transition animation
        TransitionManager.beginDelayedTransition(rootLayout, new AutoTransition());

        if (viewToToggle.getVisibility() == View.GONE) {
            viewToToggle.setVisibility(View.VISIBLE);
        } else {
            viewToToggle.setVisibility(View.GONE);
        }
    }

    private void startAnalysis() {
        String diseaseName = getIntent().getStringExtra("disease_name");
        double diseasedPercentage = getIntent().getDoubleExtra("diseased_percentage", 0.0);

        String commonCause = "";
        String symptoms = "";
        String management = "";

        // Logic for Data
        if (diseaseName != null) {
            switch (diseaseName) {
                case "Bacterial Leaf Blight":
                    commonCause = "Xanthomonas oryzae pv. oryzae";
                    symptoms = "• Water-soaked to yellowish stripes on leaf blades.\n• Milky ooze on leaves during morning.";
                    management = "• Use resistant varieties.\n• Ensure balanced nitrogen.\n• Maintain field sanitation.";
                    break;
                case "Leaf Blast":
                    commonCause = "Magnaporthe oryzae";
                    symptoms = "• Diamond-shaped lesions with white/gray centers.\n• Brown to reddish borders on leaves.";
                    management = "• Avoid high seeding density.\n• Manage water levels consistently.\n• Apply systemic fungicides if needed.";
                    break;
                case "Narrow Brown Spot":
                    commonCause = "Sphaerulina oryzina";
                    symptoms = "• Short, narrow, brown longitudinal lesions.\n• Typically occurs in late growth stages.";
                    management = "• Apply potassium fertilizer.\n• Use resistant cultivars.";
                    break;
                default:
                    commonCause = "Unknown Pathogen";
                    symptoms = "General leaf spotting observed.";
                    management = "Consult a local agriculturist.";
                    break;
            }
        }

        // Determine SES Scale
        String sesScale;
        String description;
        if (diseasedPercentage <= 5) { sesScale = "3"; description = "Low"; }
        else if (diseasedPercentage <= 25) { sesScale = "5"; description = "Moderate"; }
        else { sesScale = "9"; description = "Severe"; }

        // Update UI
        if (tvTypeValue != null) tvTypeValue.setText(diseaseName);
        if (tvCauseValue != null) tvCauseValue.setText(commonCause);
        if (tvSymptomsValue != null) tvSymptomsValue.setText(symptoms);
        if (tvManagementTipsValue != null) tvManagementTipsValue.setText(management);
        if (tvSesValue != null) tvSesValue.setText("SES " + sesScale);
        if (tvLevelValue != null) {
            tvLevelValue.setText(String.format(Locale.getDefault(), "Severity Level: %.0f%% (%s)", diseasedPercentage, description));
        }
    }
}