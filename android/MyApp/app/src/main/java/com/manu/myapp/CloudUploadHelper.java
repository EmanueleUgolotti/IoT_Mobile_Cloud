package com.manu.myapp;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Manda al Cloud tutte le voci della Cronologia create localmente e
 * non ancora sincronizzate (vedi HistoryDbHelper.getUnsyncedItems) -
 * direzione opposta rispetto alla sincronizzazione "Aggiorna dal
 * Cloud" gia' esistente (che scarica, non manda). Le voci gia' vecchie
 * o gia' sincronizzate non vengono toccate.
 *
 * Va chiamato da un thread separato dal principale (fa I/O di rete e
 * di file) - stesso schema gia' usato altrove nell'app.
 */
public class CloudUploadHelper {

    private static final String TAG = "BPA_CloudUpload";

    /** Chiamata dopo ogni voce processata, per aggiornare una barra di progresso. */
    public interface ProgressCallback {
        void onProgress(int processed, int total);
    }

    /**
     * Manda la lista di voci gia' passata (chi chiama decide quali, cosi'
     * puo' conoscerne il numero in anticipo per calcolare un progresso
     * complessivo insieme ad altre fasi, es. lo scaricamento dal Cloud).
     *
     * @return il numero di voci mandate con successo
     */
    public static int uploadItems(Context context, List<HistoryItem> pending, String cloudApiUrl,
                                  ProgressCallback callback) {
        HistoryDbHelper db = new HistoryDbHelper(context);
        int total = pending.size();
        int processed = 0;
        int successCount = 0;

        Log.i(TAG, "Sincronizzazione verso il Cloud: " + total + " voci da mandare a " + cloudApiUrl);

        for (HistoryItem item : pending) {
            processed++;

            if (item.imagePath == null) {
                Log.w(TAG, "Voce id=" + item.id + " (" + item.fileName + ") saltata: nessun percorso immagine.");
            } else {
                File imageFile = new File(item.imagePath);
                if (!imageFile.exists()) {
                    Log.w(TAG, "Voce id=" + item.id + " (" + item.fileName + ") saltata: file non trovato su "
                            + item.imagePath);
                } else {
                    try {
                        byte[] imageBytes = readFile(imageFile);
                        boolean sent = CloudApiClient.uploadCapturedPhoto(cloudApiUrl, item.fileName, imageBytes);
                        if (sent) {
                            db.markSyncedToCloud(item.id);
                            successCount++;
                            Log.i(TAG, "Voce id=" + item.id + " (" + item.fileName + ") inviata con successo.");
                        } else {
                            Log.w(TAG, "Voce id=" + item.id + " (" + item.fileName + ") rifiutata dal backend "
                                    + "(risposta HTTP non 2xx) - resta da sincronizzare.");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Voce id=" + item.id + " (" + item.fileName + ") fallita: " + e, e);
                    }
                }
            }

            if (callback != null) {
                callback.onProgress(processed, total);
            }
        }

        Log.i(TAG, "Sincronizzazione verso il Cloud completata: " + successCount + "/" + total
                + " inviate con successo.");

        return successCount;
    }

    /**
     * Variante semplice, senza barra di progresso - interroga da sola il
     * database per sapere cosa mandare. Utile per chi non ha bisogno di
     * conoscere in anticipo il numero di voci.
     */
    public static int uploadPendingItems(Context context, String cloudApiUrl) {
        HistoryDbHelper db = new HistoryDbHelper(context);
        return uploadItems(context, db.getUnsyncedItems(), cloudApiUrl, null);
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }
}