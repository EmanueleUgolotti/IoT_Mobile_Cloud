package com.manu.myapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Salva immagini (Bitmap) come file JPEG nello storage interno dell'app,
 * in una cartella condivisa da tutte le fonti di acquisizione (gateway,
 * test manuale). Centralizza la logica gia' usata da MainActivity per
 * le acquisizioni dal gateway, riusata anche da ManualTestFragment.
 */
public class ImageStorage {

    private static final String TAG = "BPA_ImageStorage";
    private static final String FOLDER_NAME = "history_images";

    /**
     * Salva il bitmap su file, restituendo il percorso assoluto, o null
     * se il salvataggio fallisce.
     */
    public static String save(Context context, Bitmap bitmap, String baseName) {
        File dir = new File(context.getFilesDir(), FOLDER_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String safeFileName = baseName + "_" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
        File imageFile = new File(dir, safeFileName);

        try (FileOutputStream out = new FileOutputStream(imageFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (IOException e) {
            Log.e(TAG, "Errore salvataggio immagine su storage interno", e);
            return null;
        }

        return imageFile.getAbsolutePath();
    }
}