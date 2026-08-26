package com.manu.myapp.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.manu.myapp.R;

/**
 * Schermata Cronologia: lista delle classificazioni passate.
 * Per ora solo placeholder statico - il salvataggio/visualizzazione
 * reale arriva nel task dedicato alla cronologia.
 */
public class HistoryFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }
}