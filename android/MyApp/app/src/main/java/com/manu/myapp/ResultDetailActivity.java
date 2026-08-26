package com.manu.myapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * Mostra il dettaglio dell'ultimo risultato di classificazione:
 * l'immagine analizzata e un grafico a barre con la probabilita'
 * di ciascuna delle classi previste dal modello.
 *
 * Legge il risultato direttamente da AppState (non tramite Intent,
 * per evitare di dover serializzare il Bitmap tra Activity).
 */
public class ResultDetailActivity extends AppCompatActivity {

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

        AppState.LastResult result = AppState.getInstance().lastResult.getValue();
        if (result == null) {
            finish(); // niente da mostrare, non dovrebbe succedere se aperto dal click
            return;
        }

        TextView textFileName = findViewById(R.id.textDetailFileName);
        textFileName.setText(result.fileName);

        ImageView imageDetail = findViewById(R.id.imageDetail);
        if (result.image != null) {
            imageDetail.setImageBitmap(result.image);
        }

        LinearLayout container = findViewById(R.id.containerProbabilities);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < result.probabilities.length; i++) {
            android.view.View row = inflater.inflate(R.layout.item_probability_bar, container, false);

            TextView textClassName = row.findViewById(R.id.textClassName);
            TextView textClassPercent = row.findViewById(R.id.textClassPercent);
            ProgressBar progressClass = row.findViewById(R.id.progressClass);

            String className = i < Classifier.CLASS_NAMES.length ? Classifier.CLASS_NAMES[i] : "?";
            int percent = Math.round(result.probabilities[i] * 100);

            textClassName.setText(className);
            textClassPercent.setText(getString(R.string.home_confidence_format, percent));
            progressClass.setProgress(percent);

            container.addView(row);
        }
    }
}