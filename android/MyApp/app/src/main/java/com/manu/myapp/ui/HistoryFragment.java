package com.manu.myapp.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.manu.myapp.HistoryDbHelper;
import com.manu.myapp.HistoryItem;
import com.manu.myapp.R;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Schermata Cronologia: lista delle classificazioni passate, lette dal
 * database SQLite locale. Il caricamento avviene su un thread separato
 * per non bloccare l'interfaccia.
 */
public class HistoryFragment extends Fragment {

    private RecyclerView recyclerHistory;
    private TextView textEmpty;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

        loadHistory();
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

        executor.execute(() -> {
            HistoryDbHelper db = new HistoryDbHelper(requireContext());
            List<HistoryItem> items = db.getAll();

            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                if (getView() == null) {
                    return; // fragment non piu' visibile
                }
                recyclerHistory.setAdapter(new HistoryAdapter(items));
            });
        });
    }
}