package com.manu.myapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * Mostra il dettaglio di un risultato di classificazione: l'immagine
 * analizzata e un grafico a barre con la probabilita' di ciascuna classe.
 *
 * Due modi di essere aperta:
 * - Dalla Home (tocco sull'ultimo risultato): nessun extra nell'Intent,
 *   legge il dato direttamente da AppState.lastResult
 * - Dalla Cronologia (tocco su una riga passata): legge i dati dagli
 *   extra dell'Intent (immagine caricata da file, non tenuta in memoria)
 */
public class ResultDetailActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_LABEL = "extra_label";
    public static final String EXTRA_CONFIDENCE = "extra_confidence";
    public static final String EXTRA_PROBABILITIES = "extra_probabilities";
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result_detail);

        Toolbar toolbar = findViewById(R.id.toolbarDetail);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.detail_title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        String fileName;
        String label;
        float confidence;
        float[] probabilities;
        Bitmap image;

        if (getIntent().hasExtra(EXTRA_LABEL)) {
            // Aperta dalla Cronologia: dati passati via Intent
            fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
            label = getIntent().getStringExtra(EXTRA_LABEL);
            confidence = getIntent().getFloatExtra(EXTRA_CONFIDENCE, 0f);
            probabilities = getIntent().getFloatArrayExtra(EXTRA_PROBABILITIES);
            String imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
            image = imagePath != null ? BitmapFactory.decodeFile(imagePath) : null;
        } else {
            // Aperta dalla Home: dati dall'ultimo risultato in memoria
            AppState.LastResult result = AppState.getInstance().lastResult.getValue();
            if (result == null) {
                finish();
                return;
            }
            fileName = result.fileName;
            label = result.label;
            confidence = result.confidence;
            probabilities = result.probabilities;
            image = result.image;
        }

        TextView textFileName = findViewById(R.id.textDetailFileName);
        textFileName.setText(fileName);

        ImageView imageDetail = findViewById(R.id.imageDetail);
        if (image != null) {
            imageDetail.setImageBitmap(image);
        }

        if (probabilities != null) {
            LinearLayout container = findViewById(R.id.containerProbabilities);
            LayoutInflater inflater = LayoutInflater.from(this);

            for (int i = 0; i < probabilities.length; i++) {
                android.view.View row = inflater.inflate(R.layout.item_probability_bar, container, false);

                TextView textClassName = row.findViewById(R.id.textClassName);
                TextView textClassPercent = row.findViewById(R.id.textClassPercent);
                ProgressBar progressClass = row.findViewById(R.id.progressClass);

                String className = i < Classifier.CLASS_NAMES.length ? Classifier.CLASS_NAMES[i] : "?";
                int percent = Math.round(probabilities[i] * 100);

                textClassName.setText(className);
                textClassPercent.setText(getString(R.string.home_confidence_format, percent));
                progressClass.setProgress(percent);

                container.addView(row);
            }
        }
    }
}