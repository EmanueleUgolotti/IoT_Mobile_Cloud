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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.manu.myapp.Classifier;
import com.manu.myapp.CloudApiClient;
import com.manu.myapp.HistoryDbHelper;
import com.manu.myapp.HistoryItem;
import com.manu.myapp.ImageStorage;
import com.manu.myapp.R;
import com.manu.myapp.SettingsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
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
 * Il pulsante "Aggiorna dal Cloud" recupera le classificazioni gia'
 * fatte lato backend Cloud (GET /cloud/results) e le aggiunge qui,
 * fonte "Cloud" - non c'e' una schermata separata per i risultati
 * Cloud: una volta scaricati, sono voci di Cronologia come le altre,
 * cosi' riusano automaticamente ricerca, eliminazione, dettaglio, ecc.
 * senza bisogno di codice apposito.
 */
public class HistoryFragment extends Fragment {

    private static final String SOURCE_CLOUD = "Cloud";

    private RecyclerView recyclerHistory;
    private EditText editSearch;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String currentQuery = "";

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
        buttonSyncCloud.setOnClickListener(v -> syncFromCloud());

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
        // Ricarica ogni volta che si torna su questa schermata, cosi'
        // eventuali nuove classificazioni arrivate nel frattempo compaiono
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
            mainHandler.post(this::loadHistory); // ricarica la lista aggiornata
        });
    }

    // --- Sincronizzazione con il Cloud ---

    private void syncFromCloud() {
        if (getContext() == null) {
            return;
        }

        String cloudApiUrl = SettingsManager.getCloudApiUrl(requireContext());

        executor.execute(() -> {
            int syncedCount = 0;
            int errorCount = 0;

            try {
                JSONArray results = CloudApiClient.fetchRecentResults(cloudApiUrl, 20);
                HistoryDbHelper db = new HistoryDbHelper(requireContext());

                for (int i = 0; i < results.length(); i++) {
                    JSONObject result = results.getJSONObject(i);

                    // Per ora sincronizzio solo i risultati in modalita'
                    // 'reference' (hanno image_ref/dataset_id, quindi
                    // l'immagine e' scaricabile da /dataset/image). I
                    // risultati 'captured' non hanno ancora un endpoint
                    // di download dedicato lato backend - saltati per ora.
                    if (result.isNull("image_ref") || result.isNull("dataset_id")) {
                        continue;
                    }

                    String imageRef = result.getString("image_ref");
                    String datasetId = result.getString("dataset_id");

                    if (db.existsWithFileNameAndSource(imageRef, SOURCE_CLOUD)) {
                        continue; // gia' sincronizzato in precedenza
                    }

                    try {
                        byte[] imageBytes = CloudApiClient.downloadDatasetImage(
                                cloudApiUrl, datasetId, imageRef);
                        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                        if (bitmap == null) {
                            errorCount++;
                            continue;
                        }

                        String predictedLabel = result.getString("predicted_label");
                        float[] probabilities = jsonToOrderedArray(result.getJSONObject("probabilities"));

                        // Tra le probabilita' per classe, prendo quella
                        // della classe predetta come "confidenza" - stesso
                        // significato che ha gia' altrove nell'app.
                        float confidence = 0f;
                        for (int c = 0; c < Classifier.CLASS_NAMES.length; c++) {
                            if (Classifier.CLASS_NAMES[c].equals(predictedLabel)) {
                                confidence = probabilities[c];
                                break;
                            }
                        }

                        String imagePath = ImageStorage.save(requireContext(), bitmap, imageRef);

                        // Uso il momento della sincronizzazione come
                        // timestamp (non l'orario originale della
                        // classificazione sul server) - evita di dover
                        // analizzare il formato data ISO del backend,
                        // semplificazione accettabile per questo scopo.
                        long timestamp = System.currentTimeMillis();

                        db.insert(imageRef, predictedLabel, confidence, probabilities,
                                SOURCE_CLOUD, timestamp, imagePath);
                        syncedCount++;

                    } catch (Exception e) {
                        errorCount++;
                    }
                }

            } catch (Exception e) {
                errorCount++;
            }

            int finalSyncedCount = syncedCount;
            int finalErrorCount = errorCount;
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                showSyncResult(finalSyncedCount, finalErrorCount);
                loadHistory();
            });
        });
    }

    private void showSyncResult(int syncedCount, int errorCount) {
        if (getContext() == null) {
            return;
        }

        String message;
        if (syncedCount == 0 && errorCount == 0) {
            message = getString(R.string.history_sync_cloud_up_to_date);
        } else if (errorCount == 0) {
            message = getString(R.string.history_sync_cloud_success, syncedCount);
        } else {
            message = getString(R.string.history_sync_cloud_error);
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Converte un JSONObject {classe: probabilita'} in un array ordinato
     * secondo Classifier.CLASS_NAMES, cosi' l'ordine e' sempre coerente
     * con quello usato ovunque nell'app (stesso approccio gia' usato in
     * AdversarialTestFragment per lo stesso identico bisogno).
     */
    private float[] jsonToOrderedArray(JSONObject probs) throws Exception {
        float[] result = new float[Classifier.CLASS_NAMES.length];
        for (int i = 0; i < Classifier.CLASS_NAMES.length; i++) {
            String className = Classifier.CLASS_NAMES[i];
            result[i] = probs.has(className) ? (float) probs.getDouble(className) : 0f;
        }
        return result;
    }
}