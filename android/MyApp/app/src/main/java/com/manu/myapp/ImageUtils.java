package com.manu.myapp;

import android.graphics.Bitmap;

/**
 * Utility per limitare le dimensioni di un'immagine appena ricevuta,
 * prima di classificarla, salvarla o mostrarla. Alcune immagini (es.
 * foto originali ad alta risoluzione arrivate via /mobile/ingest) possono
 * essere molto piu' grandi di quanto serva su un telefono, causando
 * OutOfMemoryError quando vengono elaborate o mostrate a piena
 * risoluzione. Va applicata una sola volta, appena l'immagine arriva -
 * da quel punto in poi ogni componente (Classifier, ImageStorage,
 * visualizzazione) lavora gia' su una versione di dimensioni sicure.
 */
public class ImageUtils {

    /** Dimensione massima (lato piu' lungo) per immagini gestite dall'app. */
    private static final int MAX_DIMENSION = 1024;

    /**
     * Se l'immagine supera MAX_DIMENSION su un lato, la ridimensiona
     * mantenendo le proporzioni. Se e' gia' entro il limite, la
     * restituisce invariata (nessun lavoro inutile).
     */
    public static Bitmap capMaxDimension(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int maxSide = Math.max(width, height);
        if (maxSide <= MAX_DIMENSION) {
            return bitmap;
        }

        double scale = (double) MAX_DIMENSION / maxSide;
        int newWidth = Math.max(1, (int) (width * scale));
        int newHeight = Math.max(1, (int) (height * scale));

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }
}