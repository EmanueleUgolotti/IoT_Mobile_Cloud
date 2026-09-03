package com.manu.myapp;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;

/**
 * Schermata Impostazioni: porta del server (effettiva al prossimo
 * riavvio dell'app), tema chiaro/scuro/sistema (applicato subito),
 * lingua italiano/inglese (applicata subito - le traduzioni inglesi
 * complete arrivano in un task dedicato successivo).
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText editServerPort;
    private RadioGroup radioGroupTheme;
    private RadioGroup radioGroupLanguage;
    private EditText editRobustnessUrl;
    private TextView textSettingsSaved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbarSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.action_settings);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        editServerPort = findViewById(R.id.editServerPort);
        radioGroupTheme = findViewById(R.id.radioGroupTheme);
        radioGroupLanguage = findViewById(R.id.radioGroupLanguage);
        editRobustnessUrl = findViewById(R.id.editRobustnessUrl);
        textSettingsSaved = findViewById(R.id.textSettingsSaved);
        Button buttonSave = findViewById(R.id.buttonSaveSettings);

        loadCurrentSettings();

        buttonSave.setOnClickListener(v -> saveSettings());
    }

    private void loadCurrentSettings() {
        editServerPort.setText(String.valueOf(SettingsManager.getPort(this)));

        String theme = SettingsManager.getTheme(this);
        if (SettingsManager.THEME_LIGHT.equals(theme)) {
            ((RadioButton) findViewById(R.id.radioThemeLight)).setChecked(true);
        } else if (SettingsManager.THEME_DARK.equals(theme)) {
            ((RadioButton) findViewById(R.id.radioThemeDark)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.radioThemeSystem)).setChecked(true);
        }

        String language = SettingsManager.getLanguage(this);
        if (SettingsManager.LANGUAGE_EN.equals(language)) {
            ((RadioButton) findViewById(R.id.radioLanguageEn)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.radioLanguageIt)).setChecked(true);
        }

        editRobustnessUrl.setText(SettingsManager.getRobustnessUrl(this));
    }

    private void saveSettings() {
        // Porta server
        try {
            int port = Integer.parseInt(editServerPort.getText().toString().trim());
            if (port > 0 && port <= 65535) {
                SettingsManager.setPort(this, port);
            }
        } catch (NumberFormatException ignored) {
            // porta non valida, si mantiene quella precedente
        }

        // Tema
        int themeId = radioGroupTheme.getCheckedRadioButtonId();
        String theme;
        if (themeId == R.id.radioThemeLight) {
            theme = SettingsManager.THEME_LIGHT;
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (themeId == R.id.radioThemeDark) {
            theme = SettingsManager.THEME_DARK;
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            theme = SettingsManager.THEME_SYSTEM;
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
        SettingsManager.setTheme(this, theme);

        // Lingua
        int languageId = radioGroupLanguage.getCheckedRadioButtonId();
        String language = languageId == R.id.radioLanguageEn
                ? SettingsManager.LANGUAGE_EN
                : SettingsManager.LANGUAGE_IT;
        SettingsManager.setLanguage(this, language);
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));

        // Indirizzo backend attacchi adversarial
        String robustnessUrl = editRobustnessUrl.getText().toString().trim();
        if (!robustnessUrl.isEmpty()) {
            SettingsManager.setRobustnessUrl(this, robustnessUrl);
        }

        textSettingsSaved.setVisibility(android.view.View.VISIBLE);
    }
}