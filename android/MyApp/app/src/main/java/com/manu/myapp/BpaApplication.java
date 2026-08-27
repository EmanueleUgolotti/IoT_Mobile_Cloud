package com.manu.myapp;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Applica le impostazioni di tema e lingua il prima possibile
 * all'avvio dell'app, prima che qualunque Activity venga creata -
 * cosi' l'interfaccia e' coerente fin dal primo frame, non solo dopo
 * aver aperto la schermata Impostazioni.
 */
public class BpaApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme();
        applyLanguage();
    }

    private void applyTheme() {
        String theme = SettingsManager.getTheme(this);
        switch (theme) {
            case SettingsManager.THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case SettingsManager.THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private void applyLanguage() {
        String language = SettingsManager.getLanguage(this);
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
    }
}