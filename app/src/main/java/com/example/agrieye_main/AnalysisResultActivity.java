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
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.util.Locale;

public class AnalysisResultActivity extends AppCompatActivity {

    private TextView tvTypeValue, tvCauseValue, tvLevelValue, tvSesValue, tvSymptomsValue, tvManagementTipsValue;
    private ViewGroup rootLayout;

    private View expandableAbout, expandableSymptoms, expandableManagement;
    private CardView cardAbout, cardSymptoms, cardManagement;
    private ImageView ivLogo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Allow layout to draw behind system bars so inset listener fires correctly
        getWindow().setDecorFitsSystemWindows(false);

        setContentView(R.layout.activity_analysis_result);

        // 1. Bind views
        rootLayout    = findViewById(R.id.rootLayout);
        ivLogo        = findViewById(R.id.ivLogo2);

        ImageView resultImageView = findViewById(R.id.imageView_ResultOfAnalysis);

        tvTypeValue   = findViewById(R.id.tv_diseaseType_value);
        tvLevelValue  = findViewById(R.id.tv_severityLevel_value);
        tvSesValue    = findViewById(R.id.tv_severityClassification_value);

        cardAbout      = findViewById(R.id.cardAbout);
        cardSymptoms   = findViewById(R.id.cardSymptoms);
        cardManagement = findViewById(R.id.cardManagement);

        expandableAbout      = findViewById(R.id.expandableAbout);
        expandableSymptoms   = findViewById(R.id.expandableSymptoms);
        expandableManagement = findViewById(R.id.expandableManagement);

        tvCauseValue          = findViewById(R.id.tv_commonCause_value);
        tvSymptomsValue       = findViewById(R.id.tv_symptoms_value);
        tvManagementTipsValue = findViewById(R.id.tvManagementTipsValue);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 2. Sections open by default
        expandableAbout.setVisibility(View.VISIBLE);
        expandableSymptoms.setVisibility(View.VISIBLE);
        expandableManagement.setVisibility(View.VISIBLE);

        // 3. Toggle listeners (Diagnosis has no listener — always open)
        cardAbout.setOnClickListener(v -> toggleSection(expandableAbout));
        cardSymptoms.setOnClickListener(v -> toggleSection(expandableSymptoms));
        cardManagement.setOnClickListener(v -> toggleSection(expandableManagement));

        // 4. Push logo above system navbar using WindowInsetsCompat
        //    This works reliably across gesture nav and 3-button nav on all API levels
        ViewCompat.setOnApplyWindowInsetsListener(ivLogo, (view, insets) -> {
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            params.bottomMargin = bottomInset + 24;
            view.setLayoutParams(params);
            return insets;
        });

        // 5. Load captured image
        String imagePath = getIntent().getStringExtra("image_path");
        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (bmp != null) resultImageView.setImageBitmap(bmp);
            }
        }

        // 6. Fill in disease data
        startAnalysis();
    }

    private void toggleSection(View section) {
        TransitionManager.beginDelayedTransition(rootLayout, new AutoTransition());
        section.setVisibility(
                section.getVisibility() == View.GONE ? View.VISIBLE : View.GONE
        );
    }

    private void startAnalysis() {
        String diseaseName        = getIntent().getStringExtra("disease_name");
        double diseasedPercentage = getIntent().getDoubleExtra("diseased_percentage", 0.0);

        String commonCause = "";
        String symptoms    = "";
        String management  = "";

        if (diseaseName != null) {
            switch (diseaseName) {
                case "Bacterial Leaf Blight":
                    commonCause = "Xanthomonas oryzae pv. oryzae";
                    symptoms    = "• Water-soaked to yellowish stripes on leaf blades.\n• Milky ooze on leaves during morning.";
                    management  = "• Use resistant varieties.\n• Ensure balanced nitrogen.\n• Maintain field sanitation.";
                    break;
                case "Leaf Blast":
                    commonCause = "Magnaporthe oryzae";
                    symptoms    = "• Diamond-shaped lesions with white/gray centers.\n• Brown to reddish borders on leaves.";
                    management  = "• Avoid high seeding density.\n• Manage water levels consistently.\n• Apply systemic fungicides if needed.";
                    break;
                case "Narrow Brown Spot":
                    commonCause = "Sphaerulina oryzina";
                    symptoms    = "• Short, narrow, brown longitudinal lesions.\n• Typically occurs in late growth stages.";
                    management  = "• Apply potassium fertilizer.\n• Use resistant cultivars.";
                    break;
                default:
                    commonCause = "Unknown Pathogen";
                    symptoms    = "General leaf spotting observed.";
                    management  = "Consult a local agriculturist.";
                    break;
            }
        }

        String sesScale;
        String description;
        if (diseasedPercentage <= 5)       { sesScale = "3"; description = "Low"; }
        else if (diseasedPercentage <= 25) { sesScale = "5"; description = "Moderate"; }
        else                               { sesScale = "9"; description = "Severe"; }

        if (tvTypeValue != null)           tvTypeValue.setText(diseaseName);
        if (tvCauseValue != null)          tvCauseValue.setText(commonCause);
        if (tvSymptomsValue != null)       tvSymptomsValue.setText(symptoms);
        if (tvManagementTipsValue != null) tvManagementTipsValue.setText(management);
        if (tvSesValue != null)            tvSesValue.setText("SES " + sesScale);
        if (tvLevelValue != null) {
            tvLevelValue.setText(String.format(Locale.getDefault(),
                    "Severity Level: %.0f%% (%s)", diseasedPercentage, description));
        }
    }
}