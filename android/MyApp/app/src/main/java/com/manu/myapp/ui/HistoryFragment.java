package com.manu.myapp.ui;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.manu.myapp.Classifier;
import com.manu.myapp.CloudApiClient;
import com.manu.myapp.CloudUploadHelper;
import com.manu.myapp.HistoryDbHelper;
import com.manu.myapp.HistoryItem;
import com.manu.myapp.ImageStorage;
import com.manu.myapp.R;
import com.manu.myapp.SettingsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Schermata Cronologia: lista delle classificazioni passate, lette dal
 * database SQLite locale. Il caricamento avviene su un thread separato
 * per non bloccare l'interfaccia. Tenendo premuto su una voce si puo'
 * eliminarla (record + file immagine), con conferma. La casella di
 * ricerca filtra per nome file o per contenuto della nota.
 *
 * Il pulsante "Aggiorna dal Cloud" fa la sincronizzazione in ENTRAMBE
 * le direzioni, mostrando un'unica barra di avanzamento che copre
 * entrambe le fasi in sequenza:
 * 1. Manda al Cloud le voci create localmente sul telefono e non
 *    ancora presenti li' (vedi CloudUploadHelper).
 * 2. Recupera le classificazioni gia' fatte lato backend Cloud
 *    (GET /cloud/results) e le aggiunge qui, fonte "Cloud".
 */
public class HistoryFragment extends Fragment {

    private static final String SOURCE_CLOUD = "Cloud";

    private RecyclerView recyclerHistory;
    private EditText editSearch;
    private ProgressBar progressSync;
    private TextView textSyncPercent;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String currentQuery = "";

    // Tiene l'ultima lista caricata, per poterla mostrare SUBITO al
    // prossimo onResume (anche se non freschissima) invece di partire
    // da una lista vuota in attesa dei dati.
    private List<HistoryItem> cachedItems = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerHistory = view.findViewById(R.id.recyclerHistory);
        recyclerHistory.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerHistory.setAdapter(new HistoryAdapter(cachedItems, this::confirmDelete));

        progressSync = view.findViewById(R.id.progressSync);
        textSyncPercent = view.findViewById(R.id.textSyncPercent);

        editSearch = view.findViewById(R.id.editSearch);
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentQuery = s.toString();
                loadHistory();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO) {
                hideKeyboard(v);
                return true;
            }
            return false;
        });

        Button buttonSyncCloud = view.findViewById(R.id.buttonSyncCloud);
        buttonSyncCloud.setOnClickListener(v -> syncWithCloud());

        loadHistory();
    }

    private void hideKeyboard(View view) {
        if (getContext() == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        view.clearFocus();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        if (getContext() == null) {
            return;
        }

        String queryAtRequestTime = currentQuery;

        executor.execute(() -> {
            HistoryDbHelper db = new HistoryDbHelper(requireContext());
            List<HistoryItem> items = db.search(queryAtRequestTime);

            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                if (getView() == null) {
                    return; // fragment non piu' visibile
                }
                cachedItems = items;
                recyclerHistory.setAdapter(new HistoryAdapter(items, this::confirmDelete));
            });
        });
    }

    private void confirmDelete(HistoryItem item) {
        if (getContext() == null) {
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.history_delete_title)
                .setMessage(getString(R.string.history_delete_message, item.fileName))
                .setPositiveButton(R.string.history_delete_confirm, (dialog, which) -> deleteItem(item))
                .setNegativeButton(R.string.history_delete_cancel, null)
                .show();
    }

    private void deleteItem(HistoryItem item) {
        executor.execute(() -> {
            HistoryDbHelper db = new HistoryDbHelper(requireContext());
            db.delete(item.id);

            if (item.imagePath != null) {
                File imageFile = new File(item.imagePath);
                if (imageFile.exists()) {
                    imageFile.delete();
                }
            }

            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(this::loadHistory);
        });
    }

    // --- Sincronizzazione con il Cloud (entrambe le direzioni, con barra di avanzamento) ---

    private void syncWithCloud() {
        if (getContext() == null) {
            return;
        }

        String cloudApiUrl = SettingsManager.getCloudApiUrl(requireContext());

        executor.execute(() -> {
            HistoryDbHelper db = new HistoryDbHelper(requireContext());

            // Conosciamo in anticipo quante voci ci sono da mandare, cosi'
            // possiamo calcolare una percentuale complessiva insieme alla
            // fase di scaricamento sotto (che conosceremo tra poco).
            List<HistoryItem> pendingUpload = db.getUnsyncedItems();
            int uploadTotal = pendingUpload.size();

            JSONArray results;
            int downloadTotal;
            try {
                results = CloudApiClient.fetchRecentResults(cloudApiUrl, 20);
                downloadTotal = results.length();
            } catch (Exception e) {
                results = new JSONArray();
                downloadTotal = 0;
            }

            int overallTotal = uploadTotal + downloadTotal;

            if (overallTotal > 0) {
                showProgressBar();
            }

            // Contatore condiviso tra le due fasi (girano una dopo l'altra
            // sullo stesso thread, nessun bisogno di sincronizzazione).
            int[] processedSoFar = {0};
            int finalOverallTotal = overallTotal;

            // 1. Invio verso il Cloud
            int uploadedCount = CloudUploadHelper.uploadItems(requireContext(), pendingUpload, cloudApiUrl,
                    (processed, total) -> {
                        processedSoFar[0]++;
                        updateProgress(processedSoFar[0], finalOverallTotal);
                    });

            // 2. Scaricamento dal Cloud (comportamento gia' esistente)
            int syncedCount = 0;
            int errorCount = 0;

            try {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject result = results.getJSONObject(i);

                    if (result.isNull("image_ref") || result.isNull("dataset_id")) {
                        processedSoFar[0]++;
                        updateProgress(processedSoFar[0], finalOverallTotal);
                        continue;
                    }

                    String imageRef = result.getString("image_ref");
                    String datasetId = result.getString("dataset_id");

                    if (db.existsWithFileNameAndSource(imageRef, SOURCE_CLOUD)) {
                        processedSoFar[0]++;
                        updateProgress(processedSoFar[0], finalOverallTotal);
                        continue;
                    }

                    try {
                        byte[] imageBytes = CloudApiClient.downloadDatasetImage(
                                cloudApiUrl, datasetId, imageRef);
                        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                        if (bitmap == null) {
                            errorCount++;
                            processedSoFar[0]++;
                            updateProgress(processedSoFar[0], finalOverallTotal);
                            continue;
                        }

                        String predictedLabel = result.getString("predicted_label");
                        float[] probabilities = jsonToOrderedArray(result.getJSONObject("probabilities"));

                        float confidence = 0f;
                        for (int c = 0; c < Classifier.CLASS_NAMES.length; c++) {
                            if (Classifier.CLASS_NAMES[c].equals(predictedLabel)) {
                                confidence = probabilities[c];
                                break;
                            }
                        }

                        String imagePath = ImageStorage.save(requireContext(), bitmap, imageRef);
                        long timestamp = System.currentTimeMillis();

                        db.insert(imageRef, predictedLabel, confidence, probabilities,
                                SOURCE_CLOUD, timestamp, imagePath, true);
                        syncedCount++;

                    } catch (Exception e) {
                        errorCount++;
                    }

                    processedSoFar[0]++;
                    updateProgress(processedSoFar[0], finalOverallTotal);
                }

            } catch (Exception e) {
                errorCount++;
            }

            int finalUploadedCount = uploadedCount;
            int finalSyncedCount = syncedCount;
            int finalErrorCount = errorCount;
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                hideProgressBar();
                showSyncResult(finalUploadedCount, finalSyncedCount, finalErrorCount);
                loadHistory();
            });
        });
    }

    private void showProgressBar() {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            if (getView() == null) {
                return;
            }
            progressSync.setProgress(0);
            progressSync.setVisibility(View.VISIBLE);
            textSyncPercent.setText("0%");
            textSyncPercent.setVisibility(View.VISIBLE);
        });
    }

    private void updateProgress(int processed, int total) {
        int percent = total > 0 ? (processed * 100 / total) : 100;
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            if (getView() == null) {
                return;
            }
            progressSync.setProgress(percent);
            textSyncPercent.setText(percent + "%");
        });
    }

    private void hideProgressBar() {
        if (getView() == null) {
            return;
        }
        progressSync.setVisibility(View.GONE);
        textSyncPercent.setVisibility(View.GONE);
    }

    private void showSyncResult(int uploadedCount, int syncedCount, int errorCount) {
        if (getContext() == null) {
            return;
        }

        String message;
        if (uploadedCount == 0 && syncedCount == 0 && errorCount == 0) {
            message = getString(R.string.history_sync_cloud_up_to_date);
        } else if (errorCount == 0) {
            message = getString(R.string.history_sync_cloud_success_full, uploadedCount, syncedCount);
        } else {
            message = getString(R.string.history_sync_cloud_error);
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    private float[] jsonToOrderedArray(JSONObject probs) throws Exception {
        float[] result = new float[Classifier.CLASS_NAMES.length];
        for (int i = 0; i < Classifier.CLASS_NAMES.length; i++) {
            String className = Classifier.CLASS_NAMES[i];
            result[i] = probs.has(className) ? (float) probs.getDouble(className) : 0f;
        }
        return result;
    }
}