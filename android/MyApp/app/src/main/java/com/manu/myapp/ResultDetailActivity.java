package com.manu.myapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mostra il dettaglio di un risultato di classificazione: l'immagine
 * analizzata, un grafico a barre con la probabilita' di ciascuna classe,
 * e permette di aggiungere/modificare una nota libera (salvata in
 * cronologia, ricercabile dalla schermata Cronologia).
 *
 * Tre modi di essere aperta:
 * - Dalla Home (tocco sull'ultimo risultato): nessun extra nell'Intent,
 *   legge il dato direttamente da AppState.lastResult
 * - Dalla Cronologia o dal Test manuale (tocco su un risultato passato):
 *   legge i dati dagli extra dell'Intent
 *
 * In entrambi i casi, se e' disponibile un id valido (>= 0), il campo
 * nota e' modificabile e salvabile; altrimenti (id non disponibile) la
 * nota resta nascosta.
 */
public class ResultDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "extra_id";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_LABEL = "extra_label";
    public static final String EXTRA_CONFIDENCE = "extra_confidence";
    public static final String EXTRA_PROBABILITIES = "extra_probabilities";
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_NOTE = "extra_note";

    private long resultId = -1;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        String note;
        String imagePathForShare = null; // null se non disponibile (caso aperto dalla Home)

        if (getIntent().hasExtra(EXTRA_LABEL)) {
            // Aperta dalla Cronologia o dal Test manuale: dati passati via Intent
            resultId = getIntent().getLongExtra(EXTRA_ID, -1);
            fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
            label = getIntent().getStringExtra(EXTRA_LABEL);
            confidence = getIntent().getFloatExtra(EXTRA_CONFIDENCE, 0f);
            probabilities = getIntent().getFloatArrayExtra(EXTRA_PROBABILITIES);
            String imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
            image = imagePath != null ? BitmapFactory.decodeFile(imagePath) : null;
            note = getIntent().getStringExtra(EXTRA_NOTE);
            imagePathForShare = imagePath;
        } else {
            // Aperta dalla Home: dati dall'ultimo risultato in memoria
            AppState.LastResult result = AppState.getInstance().lastResult.getValue();
            if (result == null) {
                finish();
                return;
            }
            resultId = result.id;
            fileName = result.fileName;
            label = result.label;
            confidence = result.confidence;
            probabilities = result.probabilities;
            image = result.image;
            note = ""; // la nota va cercata nel DB se serve, per ora vuota
        }

        TextView textFileName = findViewById(R.id.textDetailFileName);
        textFileName.setText(fileName);

        ImageView imageDetail = findViewById(R.id.imageDetail);
        if (image != null) {
            imageDetail.setImageBitmap(image);
        }

        Button buttonShare = findViewById(R.id.buttonShareDetail);
        if (imagePathForShare != null) {
            String pathToShare = imagePathForShare;
            buttonShare.setOnClickListener(v -> ShareUtils.shareImage(this, pathToShare));
        } else {
            // Aperta dalla Home: non abbiamo un percorso file diretto
            // (solo il Bitmap gia' in memoria) - condivisione non
            // disponibile in questo caso specifico.
            buttonShare.setEnabled(false);
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

        setupNoteSection(note);
    }

    private void setupNoteSection(String initialNote) {
        EditText editNote = findViewById(R.id.editDetailNote);
        Button buttonSaveNote = findViewById(R.id.buttonSaveNote);
        TextView textNoteSaved = findViewById(R.id.textNoteSaved);

        if (resultId < 0) {
            // Nessun id disponibile (caso raro): disabilita la nota
            editNote.setEnabled(false);
            buttonSaveNote.setEnabled(false);
            return;
        }

        editNote.setText(initialNote != null ? initialNote : "");

        buttonSaveNote.setOnClickListener(v -> {
            String noteText = editNote.getText().toString();
            long id = resultId;

            executor.execute(() -> {
                HistoryDbHelper db = new HistoryDbHelper(this);
                db.updateNote(id, noteText);
            });

            hideKeyboard(v);
            textNoteSaved.setVisibility(android.view.View.VISIBLE);
        });
    }

    private void hideKeyboard(android.view.View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        view.clearFocus();
    }
}