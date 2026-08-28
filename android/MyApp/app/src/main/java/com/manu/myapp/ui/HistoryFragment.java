package com.manu.myapp.ui;

import android.app.AlertDialog;
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
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.manu.myapp.HistoryDbHelper;
import com.manu.myapp.HistoryItem;
import com.manu.myapp.R;

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
 */
public class HistoryFragment extends Fragment {

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
}