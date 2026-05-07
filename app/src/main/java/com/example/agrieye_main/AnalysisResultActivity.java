package com.example.agrieye_main;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.Intent;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
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

        com.google.android.material.button.MaterialButton btnHome = findViewById(R.id.btnHome);
        btnHome.setOnClickListener(v -> {
            Intent intent = new Intent(AnalysisResultActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

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
                            "• Routine Scouting: Continue weekly field monitoring to detect early signs of pests or pathogens.\n\n" +
                                    "• Balanced Fertilization: Use the Leaf Color Chart (LCC) to apply the correct nitrogen levels and avoid over-fertilizing.\n\n" +
                                    "• Clean Culture: Keep bunds and canals free of weeds that may host disease vectors.\n\n" +
                                    "• Water Management: Practice Alternate Wetting and Drying (AWD) to strengthen roots and reduce fungal risks.\n\n" +
                                    "• Conserve Natural Enemies: Avoid unnecessary chemical sprays to protect beneficial insects like spiders and dragonflies.\n\n" +
                                    "• Field Sanitation: Remove any crop debris from previous seasons to prevent dormant pathogen buildup.";
                    sesScale    = "0";
                    description = "Healthy";
                    break;

                case "bacterial leaf blight":
                    commonCause = "Caused by Xanthomonas oryzae pv. oryzae. It causes wilting of seedlings and yellowing and drying of leaves.";
                    symptoms    =
                            "• On older plants, lesions usually develop as water-soaked to yellow-orange stripes on leaf blades or leaf tips or on mechanically injured parts of leaves. Lesions have a wavy margin and progress toward the leaf base.\n\n" +
                                    "• On young lesions, bacterial ooze resembling a milky dew drop can be observed early in the morning. The bacterial ooze later on dries up and becomes small yellowish beads underneath the leaf.\n\n" +
                                    "• Old lesions turn yellow to grayish white with black dots due to the growth of various saprophytic fungi.\n\n" +
                                    "• On severely infected leaves, lesions may extend to the leaf sheath.";
                    SeverityInfo blb = getSeverityInfo(diseasedPercentage, new SeverityTier[]{
                            new SeverityTier(5,   "1", "Very Slight",
                                    "• Ensure balanced nitrogen fertilization — avoid excessive application.\n\n" +
                                            "• Maintain proper field drainage to reduce moisture buildup.\n\n" +
                                            "• Remove weeds and volunteer seedlings that may serve as inoculum sources."),
                            new SeverityTier(12,  "3", "Low",
                                    "• Reduce nitrogen application to prevent susceptibility to BLB.\n\n" +
                                            "• Improve field drainage.\n\n" +
                                            "• Remove infected plant debris, weed hosts, and crop stubble from the field.\n\n" +
                                            "• Allow fallow fields to dry to suppress bacterial survival."),
                            new SeverityTier(25,  "5", "Moderate",
                                    "• Immediately balance nitrogen levels and ensure field drainage.\n\n" +
                                            "• Plow under right after harvest the stubble or volunteer seedlings to reduce inoculum.\n\n" +
                                            "• Isolate heavily affected areas if possible.\n\n" +
                                            "• Monitor neighboring hills closely for spread.\n\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(50,  "7", "High",
                                    "• Strictly control nitrogen fertilization.\n\n" +
                                            "• Ensure thorough drainage of standing water.\n\n" +
                                            "• Remove and destroy all infected plant material.\n\n" +
                                            "• Consider fallow drying to suppress bacterial survival in soil and plant residues.\n\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(100, "9", "Severe",
                                    "• Immediately stop nitrogen application.\n\n" +
                                            "• Drain fields thoroughly.\n\n" +
                                            "• Remove all infected material.\n\n" +
                                            "• Plan and choose resistant varieties for the next cropping season.\n\n" +
                                            "• Consult your local agriculture office for further intervention.")
                    });
                    management  = blb.management;
                    sesScale    = blb.sesScale;
                    description = blb.description;
                    break;

                case "bacterial leaf streak":
                    commonCause = "Caused by Xanthomonas oryzae pv. oryzicola. Infected plants show browning and drying of leaves. Under severe conditions, this could lead to reduced grain weight due to loss of photosynthetic area.";
                    symptoms    =
                            "• Symptoms initially appear as small, water-soaked, linear lesions between leaf veins. These streaks are initially dark green and later become light brown to yellowish gray.\n\n" +
                                    "• Entire leaves may become brown and die when the disease is very severe.\n\n" +
                                    "• Under humid conditions, yellow droplets of bacterial ooze, which contain masses of bacterial cells, may be observed on the surface of leaves.";
                    SeverityInfo bls = getSeverityInfo(diseasedPercentage, new SeverityTier[]{
                            new SeverityTier(0.99, "1", "Very Slight",
                                    "• Maintain balanced nitrogen application.\n\n" +
                                            "• Monitor and observe if lesions spread to surrounding plants."),
                            new SeverityTier(5,    "3", "Low",
                                    "• Improve field drainage.\n\n" +
                                            "• Remove infected stubble and weed hosts from the field.\n\n" +
                                            "• Continue balanced nitrogen fertilization.\n\n" +
                                            "• Monitor closely during high-temperature and high-humidity conditions."),
                            new SeverityTier(10,   "5", "Moderate",
                                    "• Plant resistant varieties in the next season.\n\n" +
                                            "• Apply hot-water seed treatment for seed lots.\n\n" +
                                            "• Ensure proper drainage and reduce nitrogen to avoid worsening.\n\n" +
                                            "• Remove weeds, stubble, and volunteer seedlings.\n\n" +
                                            "• Dry fallow fields to suppress bacterial survival."),
                            new SeverityTier(50,   "7", "High",
                                    "• In severe cases, apply copper-based bactericides at the heading stage to help reduce severity.\n\n" +
                                            "• Strictly maintain field sanitation — remove all infected material.\n\n" +
                                            "• Ensure thorough drainage.\n\n" +
                                            "• Stop excessive nitrogen application.\n\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(100,  "9", "Severe",
                                    "• Apply copper-based bactericides immediately if not yet done.\n\n" +
                                            "• Remove and destroy all infected plant material.\n\n" +
                                            "• Drain the field thoroughly.\n\n" +
                                            "• Consult your local agriculture office.\n\n" +
                                            "• Plan for resistant variety selection in the next cropping season.")
                    });
                    management  = bls.management;
                    sesScale    = bls.sesScale;
                    description = bls.description;
                    break;

                case "leaf blast":
                    commonCause = "Caused by the fungus Magnaporthe oryzae. It can affect all above ground parts of a rice plant: leaf, collar, node, neck, parts of panicle, and sometimes leaf sheath.";
                    symptoms    =
                            "• Initial symptoms appear as white to gray-green lesions or spots, with dark green borders.\n\n" +
                                    "• Older lesions on the leaves are elliptical or spindle-shaped and whitish to gray centers with red to brownish or necrotic border.\n\n" +
                                    "• Some resemble diamond shape, wide in the center and pointed toward either ends.\n\n" +
                                    "• Lesions can enlarge and coalesce, growing together, to kill the entire leaves.\n\n";
                    SeverityInfo lb = getSeverityInfo(diseasedPercentage, new SeverityTier[]{
                            new SeverityTier(0.99, "1", "Very Slight",
                                    "• Adjust planting time next cropping — sow early after the onset of the rainy season to avoid peak blast conditions.\n\n" +
                                            "• Split nitrogen fertilizer applications to avoid excessive nitrogen buildup.\n\n" +
                                            "• Maintain field flooding if possible."),
                            new SeverityTier(5,    "3", "Low",
                                    "• Reduce nitrogen application.\n\n" +
                                            "• Maintain field flooding.\n\n" +
                                            "• In silicon-deficient soils, apply calcium silicate fertilizer to strengthen cell walls and reduce susceptibility to blast."),
                            new SeverityTier(10,   "5", "Moderate",
                                    "• Strictly split nitrogen applications and avoid over-fertilization.\n\n" +
                                            "• Apply calcium silicate in silicon-deficient soils.\n\n" +
                                            "• Maintain flooding as much as possible.\n\n" +
                                            "• Monitor daily for further spread.\n\n" +
                                            "• Consult your local agriculture office for recommended fungicide options."),
                            new SeverityTier(50,   "7", "High",
                                    "• Stop nitrogen application immediately.\n\n" +
                                            "• Maintain field flooding.\n\n" +
                                            "• Apply recommended fungicides as advised by your local agriculture office.\n\n" +
                                            "• Adjust planting dates in future seasons to avoid high-risk blast periods.\n\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(100,  "9", "Severe",
                                    "• Remove the infected rice plants.\n\n" +
                                            "• Consult your local agriculture office urgently.\n\n" +
                                            "• Plan for resistant variety selection and improved cultural practices for the next season.")
                    });
                    management  = lb.management;
                    sesScale    = lb.sesScale;
                    description = lb.description;
                    break;

                case "narrow brown spot":
                    commonCause = "Caused by the fungus Sphaerulina oryzina and can infect leaves, sheaths, and panicles. It leads to premature death of leaves and leaf sheaths, premature ripening of grains, and in severe cases, lodging of plants.";
                    symptoms    =
                            "• Typical lesions on leaves and upper leaf sheath are light to dark brown, linear, and progress parallel to the vein.\n\n" +
                                    "• Lesions on the leaves of highly susceptible varieties may enlarge and connect together, forming brown linear necrotic regions.\n\n" +
                                    "• Lesions are usually 2−10 mm long and 1−1.5 mm wide.";
                    SeverityInfo nbs = getSeverityInfo(diseasedPercentage, new SeverityTier[]{
                            new SeverityTier(0.99, "1", "Very Slight",
                                    "• Test soil for potassium deficiency and apply balanced fertilization as needed.\n\n" +
                                            "• Remove weeds and alternate hosts from the field.\n\n" +
                                            "• Continue monitoring under temperatures of 25–28°C, which favor NBS development."),
                            new SeverityTier(5,    "3", "Low",
                                    "• Apply potassium fertilizer to correct soil nutrient deficiency.\n\n" +
                                            "• Maintain field sanitation by removing weeds and alternate hosts.\n\n" +
                                            "• Plant resistant varieties in the next cropping season."),
                            new SeverityTier(25,   "5", "Moderate",
                                    "• Apply balanced fertilization with focus on correcting potassium deficiency.\n\n" +
                                            "• Strictly remove weeds and alternate hosts.\n" +
                                            "• If the disease poses significant threat, apply propiconazole during booting to heading stages.\n\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(50,   "7", "High",
                                    "• Apply propiconazole fungicide immediately during booting to heading stage.\n\n" +
                                            "• Correct soil potassium deficiency with appropriate fertilizers.\n\n" +
                                            "• Remove and destroy all infected plant material.\n\n" +
                                            "• Maintain strict field sanitation.\n\n" +
                                            "• Consult your local agriculture office for further intervention."),
                            new SeverityTier(100,  "9", "Severe",
                                    "• Remove all infected plant material.\n\n" +
                                            "• Consult your local agriculture office.\n\n" +
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