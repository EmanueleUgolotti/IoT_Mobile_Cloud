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
 * Schermata Test manuale: scegli/scatta una foto e classificala subito,
 * senza aspettare il gateway. Per ora solo placeholder statico - la
 * logica di selezione immagine + classificazione arriva nel task dedicato.
 */
public class ManualTestFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manual_test, container, false);
    }
}