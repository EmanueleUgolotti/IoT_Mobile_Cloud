package com.manu.myapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import androidx.activity.EdgeToEdge;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.navigation.fragment.NavHostFragment;

import com.manu.myapp.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;

import org.json.JSONObject;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BPA_IngestServer";
    private static final int SERVER_PORT = 8080;
    private static final String MODEL_FILE = "bpa_vgg16.tflite";

    private AppBarConfiguration appBarConfiguration;
    private IngestServer server;
    private Classifier classifier;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        setSupportActionBar(binding.toolbar);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_content_main);

        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();

            appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        }

        binding.fab.setOnClickListener(
                view -> Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAnchorView(R.id.fab)
                        .setAction("Action", null).show()
        );

        // --- Caricamento del classificatore ---
        try {
            classifier = new Classifier(getAssets(), MODEL_FILE);
            Log.i(TAG, "Modello caricato correttamente: " + MODEL_FILE);
        } catch (IOException e) {
            Log.e(TAG, "Errore caricamento modello", e);
        }

        // --- Avvio del server di ricezione acquisizioni ---
        server = new IngestServer(SERVER_PORT, this::handleIngest);

        try {
            server.start();
            Log.i(TAG, "Server avviato sulla porta " + SERVER_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Errore avvio server", e);
        }
    }

    /**
     * Gestisce un'acquisizione ricevuta dal gateway.
     * mode "captured": l'immagine e' gia' nel payload (base64), classifichiamo subito.
     * mode "reference": richiede di recuperare l'immagine da uno storage condiviso -
     *   non ancora implementato (serve un endpoint dedicato lato backend). TODO.
     */
    private void handleIngest(JSONObject payload) {
        Log.i(TAG, "Ricevuta acquisizione: " + payload.toString());

        String mode = payload.optString("mode", "");

        if ("captured".equals(mode)) {
            classifyCaptured(payload);
        } else if ("reference".equals(mode)) {
            Log.w(TAG, "Modalita' 'reference' non ancora gestita lato mobile " +
                    "(richiede un endpoint per recuperare l'immagine dal dataset condiviso).");
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

            Classifier.Result result = classifier.classify(bitmap);
            Log.i(TAG, "Classificazione: " + result.predictedLabel +
                    " (confidenza " + result.confidence + ")");

        } catch (Exception e) {
            Log.e(TAG, "Errore durante la classificazione", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) {
            server.stop();
        }
        if (classifier != null) {
            classifier.close();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_content_main);
        boolean handled = false;
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            handled = NavigationUI.navigateUp(navController, appBarConfiguration);
        }
        return handled || super.onSupportNavigateUp();
    }
}