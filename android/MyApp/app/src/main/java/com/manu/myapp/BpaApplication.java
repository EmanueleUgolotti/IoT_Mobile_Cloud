package com.manu.myapp;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Applica le impostazioni di tema e lingua il prima possibile
 * all'avvio dell'app, prima che qualunque Activity venga creata -
 * cosi' l'interfaccia e' coerente fin dal primo frame, non solo dopo
 * aver aperto la schermata Impostazioni. Crea anche il canale di
 * notifica usato da AdversarialAttackService.
 */
public class BpaApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme();
        applyLanguage();
        createNotificationChannel();
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    AdversarialAttackService.CHANNEL_ID,
                    getString(R.string.adv_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.adv_notification_channel_description));

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}