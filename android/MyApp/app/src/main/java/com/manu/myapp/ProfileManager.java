package com.manu.myapp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Accesso condiviso al nome del profilo utente locale (nessuna
 * autenticazione, solo un nome salvato sul telefono). Usato sia dalla
 * schermata Profilo (per salvare/mostrare il nome) sia dal Test Manuale
 * (per associare il nome alle classificazioni fatte).
 */
public class ProfileManager {

    private static final String PREFS_NAME = "profile_prefs";
    private static final String KEY_PROFILE_NAME = "profile_name";

    public static String getName(Context context) {
        return getPrefs(context).getString(KEY_PROFILE_NAME, "");
    }

    public static void setName(Context context, String name) {
        getPrefs(context).edit().putString(KEY_PROFILE_NAME, name).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, 0);
    }
}