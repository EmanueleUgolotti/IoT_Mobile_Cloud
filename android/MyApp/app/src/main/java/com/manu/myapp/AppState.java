package com.manu.myapp;

import android.graphics.Bitmap;

import androidx.lifecycle.MutableLiveData;

/**
 * Stato condiviso dell'app, osservabile dalle varie schermate (Fragment)
 * tramite LiveData. MainActivity aggiorna questi valori quando il server
 * cambia stato o arriva un nuovo risultato di classificazione; le
 * schermate (es. Home) si limitano ad osservarli e aggiornare l'interfaccia.
 *
 * Singleton semplice: sufficiente per un'app di questa dimensione, senza
 * bisogno di un vero framework di dependency injection.
 */
public class AppState {

    private static AppState instance;

    public static AppState getInstance() {
        if (instance == null) {
            instance = new AppState();
        }
        return instance;
    }

    private AppState() {
    }

    /** true se il server e' in ascolto, false altrimenti. */
    public final MutableLiveData<Boolean> serverRunning = new MutableLiveData<>(false);

    /** Porta su cui il server e' in ascolto (per mostrarla in Home). */
    public final MutableLiveData<Integer> serverPort = new MutableLiveData<>();

    /** Ultimo risultato di classificazione ricevuto (null se nessuno ancora). */
    public final MutableLiveData<LastResult> lastResult = new MutableLiveData<>();

    /**
     * Rappresenta un risultato di classificazione mostrabile in UI:
     * immagine, nome/identificativo del file, classe predetta, tutte
     * le probabilita' per classe, fonte, momento di ricezione.
     */
    public static class LastResult {
        public final Bitmap image;
        public final String fileName;
        public final String label;
        public final float confidence;
        public final float[] probabilities; // stesso ordine di Classifier.CLASS_NAMES
        public final String source; // "Mobile" (via gateway) o "Foto locale" (test manuale)
        public final long timestampMillis;

        public LastResult(Bitmap image, String fileName, String label, float confidence,
                          float[] probabilities, String source, long timestampMillis) {
            this.image = image;
            this.fileName = fileName;
            this.label = label;
            this.confidence = confidence;
            this.probabilities = probabilities;
            this.source = source;
            this.timestampMillis = timestampMillis;
        }
    }
}