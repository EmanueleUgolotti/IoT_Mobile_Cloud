package com.manu.myapp.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.manu.myapp.AppState;
import com.manu.myapp.R;
import com.manu.myapp.ResultDetailActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Schermata Home: mostra lo stato del server (in ascolto/porta) e
 * l'ultimo risultato di classificazione ricevuto, aggiornati in tempo
 * reale osservando AppState. Toccando il riquadro del risultato si apre
 * il dettaglio con immagine e grafico delle probabilita' per classe.
 */
public class HomeFragment extends Fragment {

    private TextView textServerStatus;
    private LinearLayout lastResultCard;
    private TextView textLastResultFileName;
    private TextView textLastResultLabel;
    private TextView textLastResultConfidence;
    private ProgressBar progressConfidence;
    private TextView textLastResultSource;
    private TextView textTapHint;
    private View serverStatusDot;
    private TextView textServerSubtitle;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        textServerStatus = view.findViewById(R.id.textServerStatus);
        serverStatusDot = view.findViewById(R.id.serverStatusDot);
        textServerSubtitle = view.findViewById(R.id.textServerSubtitle);
        lastResultCard = view.findViewById(R.id.lastResultCard);
        textLastResultFileName = view.findViewById(R.id.textLastResultFileName);
        textLastResultLabel = view.findViewById(R.id.textLastResultLabel);
        textLastResultConfidence = view.findViewById(R.id.textLastResultConfidence);
        progressConfidence = view.findViewById(R.id.progressConfidence);
        textLastResultSource = view.findViewById(R.id.textLastResultSource);
        textTapHint = view.findViewById(R.id.textTapHint);

        AppState appState = AppState.getInstance();

        appState.serverRunning.observe(getViewLifecycleOwner(), running -> updateServerStatus());
        appState.serverPort.observe(getViewLifecycleOwner(), port -> updateServerStatus());

        appState.lastResult.observe(getViewLifecycleOwner(), result -> {
            if (result == null) {
                return;
            }

            textLastResultFileName.setText(result.fileName);
            textLastResultFileName.setVisibility(View.VISIBLE);

            textLastResultLabel.setText(result.label);

            int confidencePercent = Math.round(result.confidence * 100);
            textLastResultConfidence.setText(
                    getString(R.string.home_confidence_format, confidencePercent));
            textLastResultConfidence.setVisibility(View.VISIBLE);

            progressConfidence.setProgress(confidencePercent);
            progressConfidence.setVisibility(View.VISIBLE);

            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(result.timestampMillis));
            textLastResultSource.setText(
                    getString(R.string.home_source_format, result.source, time));
            textLastResultSource.setVisibility(View.VISIBLE);

            textTapHint.setVisibility(View.VISIBLE);

            lastResultCard.setOnClickListener(v -> {
                Intent intent = new Intent(getActivity(), ResultDetailActivity.class);
                startActivity(intent);
            });
        });
    }

    private void updateServerStatus() {
        AppState appState = AppState.getInstance();

        Boolean running = appState.serverRunning.getValue();
        Integer port = appState.serverPort.getValue();

        if (Boolean.TRUE.equals(running) && port != null) {

            // Server attivo
            textServerStatus.setText(
                    getString(R.string.home_server_status_running, port)
            );

            textServerSubtitle.setText(getString(R.string.home_server_ready_subtitle));

            serverStatusDot.setBackgroundResource(R.drawable.bg_status_dot);
            serverStatusDot.getBackground().setTint(
                    getResources().getColor(R.color.status_success)
            );

        } else {

            // Server spento
            textServerStatus.setText(
                    getString(R.string.home_server_status_stopped)
            );

            textServerSubtitle.setText(getString(R.string.home_server_stopped_subtitle));

            serverStatusDot.setBackgroundResource(R.drawable.bg_status_dot);
            serverStatusDot.getBackground().setTint(
                    getResources().getColor(R.color.status_error)
            );
        }
    }
}