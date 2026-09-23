package com.manu.myapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Condivide una singola immagine (gia' salvata nello storage privato
 * dell'app da ImageStorage) con altre app, senza copiarla in uno
 * spazio pubblico permanente (Galleria) -
 * Usa FileProvider, il meccanismo standard Android per condividere
 * temporaneamente un file privato in modo sicuro: genera un Uri
 * "content://" valido solo per l'app che lo riceve, invece di rendere
 * il file leggibile a chiunque.
 */
public class ShareUtils {

    /**
     * Apre il selettore di condivisione di Android (WhatsApp, email,
     * ecc.) per il file immagine indicato.
     *
     * @param context     il Context dell'Activity/Fragment chiamante
     * @param imagePath   percorso assoluto del file, come salvato da
     *                    ImageStorage.save() e letto da HistoryItem
     */
    public static void shareImage(Context context, String imagePath) {
        if (imagePath == null) {
            return;
        }

        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            return;
        }

        Uri contentUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                imageFile
        );

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/jpeg");
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        context.startActivity(Intent.createChooser(shareIntent, null));
    }
}