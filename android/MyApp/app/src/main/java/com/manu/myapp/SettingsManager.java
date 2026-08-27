package com.manu.myapp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Accesso condiviso alle impostazioni dell'app: porta del server, tema
 * (chiaro/scuro/sistema), lingua. Salvate tramite SharedPreferences.
 */
public class SettingsManager {

    private static final String PREFS_NAME = "settings_prefs";
    private static final String KEY_PORT = "server_port";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_LANGUAGE = "language_code";

    public static final int DEFAULT_PORT = 8080;

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";

    public static final String LANGUAGE_IT = "it";
    public static final String LANGUAGE_EN = "en";

    public static int getPort(Context context) {
        return getPrefs(context).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void setPort(Context context, int port) {
        getPrefs(context).edit().putInt(KEY_PORT, port).apply();
    }

    public static String getTheme(Context context) {
        return getPrefs(context).getString(KEY_THEME, THEME_SYSTEM);
    }

    public static void setTheme(Context context, String theme) {
        getPrefs(context).edit().putString(KEY_THEME, theme).apply();
    }

    public static String getLanguage(Context context) {
        return getPrefs(context).getString(KEY_LANGUAGE, LANGUAGE_IT);
    }

    public static void setLanguage(Context context, String languageCode) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, 0);
    }
}