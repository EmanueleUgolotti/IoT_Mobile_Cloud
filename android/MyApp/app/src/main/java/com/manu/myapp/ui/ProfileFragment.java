package com.manu.myapp.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.manu.myapp.ProfileManager;
import com.manu.myapp.R;

/**
 * Schermata Profilo: nome utente locale, salvato sul telefono tramite
 * ProfileManager (SharedPreferences). Profilo semplice, senza
 * autenticazione - il nome viene associato alle classificazioni fatte
 * con "Test manuale" (vedi ManualTestFragment), cosi' la cronologia
 * mostra chi ha fatto quale foto quando piu' persone usano lo stesso
 * dispositivo.
 */
public class ProfileFragment extends Fragment {

    private EditText editProfileName;
    private TextView textProfileSaved;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        editProfileName = view.findViewById(R.id.editProfileName);
        textProfileSaved = view.findViewById(R.id.textProfileSaved);
        Button buttonSave = view.findViewById(R.id.buttonSaveProfile);

        editProfileName.setText(ProfileManager.getName(requireContext()));

        buttonSave.setOnClickListener(v -> saveProfileName());
    }

    private void saveProfileName() {
        String name = editProfileName.getText().toString().trim();
        ProfileManager.setName(requireContext(), name);
        textProfileSaved.setVisibility(View.VISIBLE);
    }
}