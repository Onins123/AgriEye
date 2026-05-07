package com.example.agrieye_main;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.io.File;
import java.util.Locale;

public class AnalysisResultActivity extends AppCompatActivity {

    private TextView tvTypeValue, tvCauseValue, tvLevelValue, tvSesValue,
            tvSymptomsValue, tvManagementTipsValue;
    private ViewGroup rootLayout;

    private View expandableAbout, expandableSymptoms, expandableManagement;
    private CardView cardAbout, cardSymptoms, cardManagement;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_analysis_result);

        // 1. Bind views
        rootLayout = findViewById(R.id.rootLayout);

        ImageView resultImageView = findViewById(R.id.imageView_ResultOfAnalysis);

        tvTypeValue  = findViewById(R.id.tv_diseaseType_value);
        tvLevelValue = findViewById(R.id.tv_severityLevel_value);
        tvSesValue   = findViewById(R.id.tv_severityClassification_value);

        cardAbout      = findViewById(R.id.cardAbout);
        cardSymptoms   = findViewById(R.id.cardSymptoms);
        cardManagement = findViewById(R.id.cardManagement);

        expandableAbout      = findViewById(R.id.expandableAbout);
        expandableSymptoms   = findViewById(R.id.expandableSymptoms);
        expandableManagement = findViewById(R.id.expandableManagement);

        tvCauseValue          = findViewById(R.id.tv_commonCause_value);
        tvSymptomsValue       = findViewById(R.id.tv_symptoms_value);
        tvManagementTipsValue = findViewById(R.id.tvManagementTipsValue);

        // 2. Back button
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // 3. Sections open by default
        expandableAbout.setVisibility(View.VISIBLE);
        expandableSymptoms.setVisibility(View.VISIBLE);
        expandableManagement.setVisibility(View.VISIBLE);

        // 4. Toggle listeners
        cardAbout.setOnClickListener(v -> toggleSection(expandableAbout));
        cardSymptoms.setOnClickListener(v -> toggleSection(expandableSymptoms));
        cardManagement.setOnClickListener(v -> toggleSection(expandableManagement));

        // 5. Bottom sheet setup
        NestedScrollView bottomSheet = findViewById(R.id.bottomSheet);
        BottomSheetBehavior<NestedScrollView> sheetBehavior =
                BottomSheetBehavior.from(bottomSheet);
        sheetBehavior.setPeekHeight(250);
        sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        sheetBehavior.setHideable(false);

        TextView tvSwipeHint = findViewById(R.id.tvSwipeHint);
        sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View sheet, int newState) {
                if (tvSwipeHint == null) return;
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    tvSwipeHint.setText("Swipe down to see the image");
                } else {
                    tvSwipeHint.setText("Swipe up to see analysis");
                }
            }

            @Override
            public void onSlide(@NonNull View sheet, float slideOffset) {}
        });

        // 6. Load captured image
        String imagePath = getIntent().getStringExtra("image_path");
        if (imagePath != null) {
            File imgFile = new File(imagePath);
            if (imgFile.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (bmp != null) resultImageView.setImageBitmap(bmp);
            }
        }

        // 7. Fill in disease data
        startAnalysis();
    }

    // ── Toggle expandable sections ────────────────────────────────────────────

    private void toggleSection(View section) {
        TransitionManager.beginDelayedTransition(rootLayout, new AutoTransition());
        section.setVisibility(
                section.getVisibility() == View.GONE ? View.VISIBLE : View.GONE
        );
    }

    // ── Disease analysis ──────────────────────────────────────────────────────

    private void startAnalysis() {
        String diseaseName        = getIntent().getStringExtra("disease_name");
        double diseasedPercentage = getIntent().getDoubleExtra("diseased_percentage", 0.0);
        float  averageConfidence  = getIntent().getFloatExtra("average_confidence", 0f);

        String commonCause = "";
        String symptoms    = "";
        String management  = "";
        String sesScale    = "9";
        String description = "Severe";

        if (diseaseName != null) {
            switch (diseaseName.trim().toLowerCase()) {

                case "healthy":
                case "healthy leaf":
                    commonCause = "N/A";
                    symptoms    = "No disease symptoms detected.";
                    management  =
                            "• Routine Scouting: Continue weekly field monitoring to detect early signs of pests or pathogens.\n" +
                                    "• Balanced Fertilization: Use the Leaf Color Chart (LCC) to apply the correct nitrogen levels and avoid over-fertilizing.\n" +
                                    "• Clean Culture: Keep bunds and canals free of weeds that may host disease vectors.\n" +
                                    "• Water Management: Practice Alternate Wetting and Drying (AWD) to strengthen roots and reduce fungal risks.\n" +
                                    "• Conserve Natural Enemies: Avoid unnecessary chemical sprays to protect beneficial insects like spiders and dragonflies.\n" +
                                    "• Field Sanitation: Remove any crop debris from previous seasons to prevent dormant pathogen buildup.";
                    sesScale    = "0";
                    description = "Healthy";
                    break;

                case "bacterial leaf blight":
                    commonCause = "Xanthomonas oryzae pv. oryzae";
                    symptoms    =
                            "• Water-soaked to yellowish stripes on leaf blades.\n" +
                                    "• Milky ooze on leaves during morning.";
                    SeverityInfo blb = getSeverityInfo(diseasedPercentage, new SeverityTier[]{
                            new SeverityTier(5,   "1", "Very Slight",
                                    "• Ensure balanced nitrogen fertilization — avoid excessive application.\n" +
                                            "• Maintain proper field drainage to reduce moisture buildup.\n" +
                                            "• Remove weeds and volunteer seedlings that may serve as inoculum sources."),
                            new SeverityTier(12,  "3", "Low",
                                    "• Reduce nitrogen application to prevent susceptibility to BLB.\n" +
                                            "• Improve field drainage.\n" +
                                            "• Remove infected plant debris, weed hosts, and crop stubble from the field.\n" +
                                            "• Allow fallow fields to dry to suppress bacterial survival."),
                            new SeverityTier(25,  "5", "Moderate",
                                    "• Immediately balance nitrogen levels and ensure field drainage.\n" +
                                            "• Plow under right after harvest the stubble or volunteer seedlings to reduce inoculum.\n" +
                                            "• Isolate heavily affected areas if possible.\n" +
                                            "• Monitor neighboring hills closely for spread.\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(50,  "7", "High",
                                    "• Strictly control nitrogen fertilization.\n" +
                                            "• Ensure thorough drainage of standing water.\n" +
                                            "• Remove and destroy all infected plant material.\n" +
                                            "• Consider fallow drying to suppress bacterial survival in soil and plant residues.\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(100, "9", "Severe",
                                    "• Immediately stop nitrogen application.\n" +
                                            "• Drain fields thoroughly.\n" +
                                            "• Remove all infected material.\n" +
                                            "• Plan and choose resistant varieties for the next cropping season.\n" +
                                            "• Consult your local agriculture office for further intervention.")
                    });
                    management  = blb.management;
                    sesScale    = blb.sesScale;
                    description = blb.description;
                    break;

                case "bacterial leaf streak":
                    commonCause = "Xanthomonas oryzae pv. oryzicola";
                    symptoms    =
                            "• Water-soaked, dark green streaks on leaves.\n" +
                                    "• Translucent stripes between leaf veins.";
                    SeverityInfo bls = getSeverityInfo(diseasedPercentage, new SeverityTier[]{
                            new SeverityTier(0.99, "1", "Very Slight",
                                    "• Maintain balanced nitrogen application.\n" +
                                            "• Monitor and observe if lesions spread to surrounding plants."),
                            new SeverityTier(5,    "3", "Low",
                                    "• Improve field drainage.\n" +
                                            "• Remove infected stubble and weed hosts from the field.\n" +
                                            "• Continue balanced nitrogen fertilization.\n" +
                                            "• Monitor closely during high-temperature and high-humidity conditions."),
                            new SeverityTier(10,   "5", "Moderate",
                                    "• Plant resistant varieties in the next season.\n" +
                                            "• Apply hot-water seed treatment for seed lots.\n" +
                                            "• Ensure proper drainage and reduce nitrogen to avoid worsening.\n" +
                                            "• Remove weeds, stubble, and volunteer seedlings.\n" +
                                            "• Dry fallow fields to suppress bacterial survival."),
                            new SeverityTier(50,   "7", "High",
                                    "• In severe cases, apply copper-based bactericides at the heading stage to help reduce severity.\n" +
                                            "• Strictly maintain field sanitation — remove all infected material.\n" +
                                            "• Ensure thorough drainage.\n" +
                                            "• Stop excessive nitrogen application.\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(100,  "9", "Severe",
                                    "• Apply copper-based bactericides immediately if not yet done.\n" +
                                            "• Remove and destroy all infected plant material.\n" +
                                            "• Drain the field thoroughly.\n" +
                                            "• Consult your local agriculture office.\n" +
                                            "• Plan for resistant variety selection in the next cropping season.")
                    });
                    management  = bls.management;
                    sesScale    = bls.sesScale;
                    description = bls.description;
                    break;

                case "leaf blast":
                    commonCause = "Magnaporthe oryzae";
                    symptoms    =
                            "• Diamond-shaped lesions with white/gray centers.\n" +
                                    "• Brown to reddish borders on leaves.";
                    SeverityInfo lb = getSeverityInfo(diseasedPercentage, new SeverityTier[]{
                            new SeverityTier(0.99, "1", "Very Slight",
                                    "• Adjust planting time next cropping — sow early after the onset of the rainy season to avoid peak blast conditions.\n" +
                                            "• Split nitrogen fertilizer applications to avoid excessive nitrogen buildup.\n" +
                                            "• Maintain field flooding if possible."),
                            new SeverityTier(5,    "3", "Low",
                                    "• Reduce nitrogen application.\n" +
                                            "• Maintain field flooding.\n" +
                                            "• In silicon-deficient soils, apply calcium silicate fertilizer to strengthen cell walls and reduce susceptibility to blast."),
                            new SeverityTier(10,   "5", "Moderate",
                                    "• Strictly split nitrogen applications and avoid over-fertilization.\n" +
                                            "• Apply calcium silicate in silicon-deficient soils.\n" +
                                            "• Maintain flooding as much as possible.\n" +
                                            "• Monitor daily for further spread.\n" +
                                            "• Consult your local agriculture office for recommended fungicide options."),
                            new SeverityTier(50,   "7", "High",
                                    "• Stop nitrogen application immediately.\n" +
                                            "• Maintain field flooding.\n" +
                                            "• Apply recommended fungicides as advised by your local agriculture office.\n" +
                                            "• Adjust planting dates in future seasons to avoid high-risk blast periods.\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(100,  "9", "Severe",
                                    "• Remove the infected rice plants.\n" +
                                            "• Consult your local agriculture office urgently.\n" +
                                            "• Plan for resistant variety selection and improved cultural practices for the next season.")
                    });
                    management  = lb.management;
                    sesScale    = lb.sesScale;
                    description = lb.description;
                    break;

                case "narrow brown spot":
                    commonCause = "Sphaerulina oryzina";
                    symptoms    =
                            "• Short, narrow, brown longitudinal lesions.\n" +
                                    "• Typically occurs in late growth stages.";
                    SeverityInfo nbs = getSeverityInfo(diseasedPercentage, new SeverityTier[]{
                            new SeverityTier(0.99, "1", "Very Slight",
                                    "• Test soil for potassium deficiency and apply balanced fertilization as needed.\n" +
                                            "• Remove weeds and alternate hosts from the field.\n" +
                                            "• Continue monitoring under temperatures of 25–28°C, which favor NBS development."),
                            new SeverityTier(5,    "3", "Low",
                                    "• Apply potassium fertilizer to correct soil nutrient deficiency.\n" +
                                            "• Maintain field sanitation by removing weeds and alternate hosts.\n" +
                                            "• Plant resistant varieties in the next cropping season."),
                            new SeverityTier(25,   "5", "Moderate",
                                    "• Apply balanced fertilization with focus on correcting potassium deficiency.\n" +
                                            "• Strictly remove weeds and alternate hosts.\n" +
                                            "• If the disease poses significant threat, apply propiconazole during booting to heading stages.\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(50,   "7", "High",
                                    "• Apply propiconazole fungicide immediately during booting to heading stage.\n" +
                                            "• Correct soil potassium deficiency with appropriate fertilizers.\n" +
                                            "• Remove and destroy all infected plant material.\n" +
                                            "• Maintain strict field sanitation.\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(100,  "9", "Severe",
                                    "• Remove all infected plant material.\n" +
                                            "• Consult your local agriculture office.\n" +
                                            "• Plant resistant varieties in the next season.")
                    });
                    management  = nbs.management;
                    sesScale    = nbs.sesScale;
                    description = nbs.description;
                    break;

                default:
                    commonCause = "Unknown Pathogen";
                    symptoms    = "General leaf spotting observed.";
                    management  = "Consult a local agriculturist.";
                    break;
            }
        }

        if (tvTypeValue != null)           tvTypeValue.setText(diseaseName);
        if (tvCauseValue != null)          tvCauseValue.setText(commonCause);
        if (tvSymptomsValue != null)       tvSymptomsValue.setText(symptoms);
        if (tvManagementTipsValue != null) tvManagementTipsValue.setText(management);
        if (tvSesValue != null)            tvSesValue.setText("SES " + sesScale);
        if (tvLevelValue != null) {
            String normalized = diseaseName != null ? diseaseName.trim().toLowerCase() : "";
            if (normalized.equals("healthy") || normalized.equals("healthy leaf")) {
                tvLevelValue.setText(String.format(Locale.getDefault(),
                        "No infection detected · Confidence: %.0f%%",
                        averageConfidence * 100f));
            } else {
                tvLevelValue.setText(String.format(Locale.getDefault(),
                        "Severity Level: %.0f%% (%s) · Confidence: %.0f%%",
                        diseasedPercentage, description, averageConfidence * 100f));
            }
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    private static class SeverityTier {
        final double maxPercent;
        final String sesScale;
        final String description;
        final String management;

        SeverityTier(double maxPercent, String sesScale, String description, String management) {
            this.maxPercent  = maxPercent;
            this.sesScale    = sesScale;
            this.description = description;
            this.management  = management;
        }
    }

    private static class SeverityInfo {
        final String sesScale;
        final String description;
        final String management;

        SeverityInfo(String sesScale, String description, String management) {
            this.sesScale    = sesScale;
            this.description = description;
            this.management  = management;
        }
    }

    // ── Threshold resolver ────────────────────────────────────────────────────

    private SeverityInfo getSeverityInfo(double percentage, SeverityTier[] tiers) {
        for (SeverityTier tier : tiers) {
            if (percentage <= tier.maxPercent) {
                return new SeverityInfo(tier.sesScale, tier.description, tier.management);
            }
        }
        SeverityTier last = tiers[tiers.length - 1];
        return new SeverityInfo(last.sesScale, last.description, last.management);
    }
}