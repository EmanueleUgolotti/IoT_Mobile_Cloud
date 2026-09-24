package com.manu.myapp;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Activity principale: ospita la Bottom Navigation (Home, Cronologia,
 * Test manuale, Profilo) e mantiene in esecuzione, per tutta la vita
 * dell'app, il server di ricezione acquisizioni e il classificatore.
 *
 * Pubblica lo stato del server e i risultati di classificazione tramite
 * AppState, cosi' le schermate (es. Home) possono mostrarli in tempo reale.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BPA_IngestServer";
    private static final String MODEL_FILE = "bpa_vgg16.tflite";

    private IngestServer server;
    private Classifier classifier;
    private NavController navController;
    private HistoryDbHelper historyDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);

        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
            BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
            bottomNav.setItemActiveIndicatorEnabled(false);
            NavigationUI.setupWithNavController(bottomNav, navController);
            NavigationUI.setupActionBarWithNavController(this, navController);
        }

        // Caricamento del classificatore 
        try {
            classifier = new Classifier(getAssets(), MODEL_FILE);
            AppState.getInstance().classifier = classifier;
            Log.i(TAG, "Modello caricato correttamente: " + MODEL_FILE);
        } catch (IOException e) {
            Log.e(TAG, "Errore caricamento modello", e);
        }

        historyDb = new HistoryDbHelper(this);

        // --- Avvio del server di ricezione acquisizioni ---
        int serverPort = SettingsManager.getPort(this);
        server = new IngestServer(serverPort, this::handleIngest);

        try {
            server.start();
            Log.i(TAG, "Server avviato sulla porta " + serverPort);
            AppState.getInstance().serverRunning.postValue(true); //verde
            AppState.getInstance().serverPort.postValue(serverPort);
        } catch (Exception e) {
            Log.e(TAG, "Errore avvio server", e);
            AppState.getInstance().serverRunning.postValue(false); //rosso
        }
    }

    /**
     * Gestisce un'acquisizione ricevuta dal gateway.
     * mode "captured": l'immagine e' gia' nel payload (base64), classifichiamo subito.
     * mode "reference": il payload contiene solo un riferimento (nome file +
     *   dataset) - l'immagine va scaricata dal backend Cloud prima di poter
     *   classificare (vedi classifyReference).
     */
    private void handleIngest(JSONObject payload) {
        Log.i(TAG, "Ricevuta acquisizione: " + payload.toString());

        String mode = payload.optString("mode", "");

        if ("captured".equals(mode)) {
            classifyCaptured(payload);
        } else if ("reference".equals(mode)) {
            classifyReference(payload);
        }
    }

    private void classifyCaptured(JSONObject payload) {
        if (classifier == null) {
            Log.e(TAG, "Classificatore non disponibile, impossibile classificare.");
            return;
        }

        try {
            String imageDataBase64 = payload.getString("image_data");
            byte[] imageBytes = Base64.decode(imageDataBase64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            if (bitmap == null) {
                Log.e(TAG, "Impossibile decodificare l'immagine ricevuta.");
                return;
            }

            // Limita subito le dimensioni: alcune immagini sorgente (es. foto
            // originali ad alta risoluzione) possono altrimenti causare
            // OutOfMemoryError in fasi successive (classificazione, salvataggio,
            // visualizzazione)
            bitmap = ImageUtils.capMaxDimension(bitmap);

            Classifier.Result result = classifier.classify(bitmap);
            Log.i(TAG, "Classificazione: " + result.predictedLabel +
                    " (confidenza " + result.confidence + ")");

            String fileName = payload.optString("capture_id", "sconosciuto");
            long timestamp = System.currentTimeMillis();
            String imagePath = ImageStorage.save(this, bitmap, fileName);

            // Salvataggio persistente in cronologia (fuori dal thread UI,
            // siamo gia' nel thread del server NanoHTTPD)
            long id = historyDb.insert(fileName, result.predictedLabel, result.confidence,
                    result.probabilities, "Mobile", timestamp, imagePath);

            AppState.LastResult lastResult = new AppState.LastResult(
                    id,
                    bitmap,
                    fileName,
                    result.predictedLabel,
                    result.confidence,
                    result.probabilities,
                    "Mobile",
                    timestamp
            );
            AppState.getInstance().lastResult.postValue(lastResult);

        } catch (Exception e) {
            Log.e(TAG, "Errore durante la classificazione", e);
        }
    }

    /**
     * Gestisce la modalita' 'reference': il messaggio contiene solo il
     * nome del file e il dataset, non i byte dell'immagine - vanno
     * scaricati dal backend Cloud (endpoint /dataset/image) prima di
     * poter classificare. Stessa logica di classifyCaptured da quel
     * punto in poi.
     */
    private void classifyReference(JSONObject payload) {
        if (classifier == null) {
            Log.e(TAG, "Classificatore non disponibile, impossibile classificare.");
            return;
        }

        try {
            String imageRef = payload.getString("image_ref");
            String datasetId = payload.getString("dataset_id");
            String cloudApiUrl = SettingsManager.getCloudApiUrl(this);

            byte[] imageBytes = CloudApiClient.downloadDatasetImage(cloudApiUrl, datasetId, imageRef);
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            if (bitmap == null) {
                Log.e(TAG, "Impossibile decodificare l'immagine scaricata: " + imageRef);
                return;
            }

            bitmap = ImageUtils.capMaxDimension(bitmap);

            Classifier.Result result = classifier.classify(bitmap);
            Log.i(TAG, "Classificazione (reference '" + imageRef + "'): " + result.predictedLabel +
                    " (confidenza " + result.confidence + ")");

            long timestamp = System.currentTimeMillis();
            String imagePath = ImageStorage.save(this, bitmap, imageRef);

            long id = historyDb.insert(imageRef, result.predictedLabel, result.confidence,
                    result.probabilities, "Mobile", timestamp, imagePath);

            AppState.LastResult lastResult = new AppState.LastResult(
                    id,
                    bitmap,
                    imageRef,
                    result.predictedLabel,
                    result.confidence,
                    result.probabilities,
                    "Mobile",
                    timestamp
            );
            AppState.getInstance().lastResult.postValue(lastResult);

        } catch (Exception e) {
            Log.e(TAG, "Errore durante la gestione della modalita' reference", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) {
            server.stop();
            AppState.getInstance().serverRunning.postValue(false);
        }
        if (classifier != null) {
            classifier.close();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        return (navController != null && navController.navigateUp()) || super.onSupportNavigateUp();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}