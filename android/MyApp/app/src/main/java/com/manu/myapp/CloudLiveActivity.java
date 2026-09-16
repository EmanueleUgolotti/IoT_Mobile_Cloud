package com.manu.myapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.manu.myapp.ui.CloudLiveAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finestra "live" sul Cloud: mostra le classificazioni e i tentativi
 * falliti gia' presenti sul backend, senza scaricare o salvare nulla
 * sul telefono - una fotografia di quello che c'e' sul Cloud in questo
 * momento. Diversa dalla sincronizzazione nella Cronologia (quella
 * scarica davvero le immagini e le salva in locale); questa e' solo
 * lettura al volo.
 *
 * Primo passo verso l'idea di usare l'app come "telecomando" del
 * Cloud - le azioni da remoto (es. lanciare un attacco direttamente
 * sul Cloud, senza scaricare l'immagine) sono un passo successivo,
 * non ancora fatto qui.
 */
public class CloudLiveActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView textStatus;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_live);

        MaterialToolbar toolbar = findViewById(R.id.toolbarCloudLive);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recyclerCloudLive);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        textStatus = findViewById(R.id.textCloudLiveStatus);

        Button buttonRefresh = findViewById(R.id.buttonCloudLiveRefresh);
        buttonRefresh.setOnClickListener(v -> loadFromCloud());

        loadFromCloud();
    }

    private void loadFromCloud() {
        String cloudApiUrl = SettingsManager.getCloudApiUrl(this);
        showStatus(getString(R.string.cloud_live_loading));

        executor.execute(() -> {
            List<CloudLiveItem> items = new ArrayList<>();
            String errorMessage = null;

            try {
                JSONArray results = CloudApiClient.fetchRecentResults(cloudApiUrl, 20);
                for (int i = 0; i < results.length(); i++) {
                    items.add(resultToItem(results.getJSONObject(i)));
                }
            } catch (Exception e) {
                errorMessage = e.getMessage();
            }

            try {
                JSONArray failures = CloudApiClient.fetchRecentFailures(cloudApiUrl, 20);
                for (int i = 0; i < failures.length(); i++) {
                    items.add(failureToItem(failures.getJSONObject(i)));
                }
            } catch (Exception e) {
                if (errorMessage == null) {
                    errorMessage = e.getMessage();
                }
            }

            // Piu' recenti prima - le stringhe data ISO8601 si ordinano
            // correttamente anche come semplice confronto testuale.
            Collections.sort(items, (a, b) -> b.receivedAt.compareTo(a.receivedAt));

            String finalErrorMessage = errorMessage;
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                recyclerView.setAdapter(new CloudLiveAdapter(items));

                if (items.isEmpty() && finalErrorMessage != null) {
                    showStatus(getString(R.string.cloud_live_error, finalErrorMessage));
                } else if (items.isEmpty()) {
                    showStatus(getString(R.string.cloud_live_empty));
                } else {
                    hideStatus();
                }
            });
        });
    }

    private CloudLiveItem resultToItem(JSONObject result) throws Exception {
        String requestId = result.optString("request_id", "");
        String title = result.isNull("image_ref") ? result.optString("capture_id", requestId)
                : result.getString("image_ref");
        String predictedLabel = result.optString("predicted_label", "?");

        double confidence = 0.0;
        if (!result.isNull("probabilities")) {
            JSONObject probs = result.getJSONObject("probabilities");
            if (probs.has(predictedLabel)) {
                confidence = probs.getDouble(predictedLabel);
            }
        }

        String subtitle = getString(R.string.cloud_live_result_subtitle,
                predictedLabel, confidence * 100);
        String receivedAt = result.optString("received_at", "");

        return new CloudLiveItem(CloudLiveItem.TYPE_RESULT, requestId, title, subtitle, receivedAt);
    }

    private CloudLiveItem failureToItem(JSONObject failure) throws Exception {
        String requestId = failure.optString("request_id", "");
        int nodeId = failure.optInt("node_id", 0);
        int seqNum = failure.optInt("seq_num", 0);
        String reason = failure.optString("failure_reason", "?");
        String receivedAt = failure.optString("received_at", "");

        String title = getString(R.string.cloud_live_failure_title, nodeId, seqNum);
        String subtitle = getString(R.string.cloud_live_failure_subtitle, reason);

        return new CloudLiveItem(CloudLiveItem.TYPE_FAILURE, requestId, title, subtitle, receivedAt);
    }

    private void showStatus(String message) {
        textStatus.setText(message);
        textStatus.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        textStatus.setVisibility(View.GONE);
    }
}