package com.manu.myapp;

/**
 * Rappresenta un attacco adversarial eseguito e salvato in cronologia.
 * A differenza di HistoryItem (una classificazione singola), qui ci
 * sono DUE immagini (originale e avversaria) e DUE set di probabilita'.
 */
public class AdversarialHistoryItem {
    public final long id;
    public final String attackName; // "boundary" | "simba"
    public final String predictionOriginal;
    public final float[] probabilitiesOriginal;
    public final String imagePathOriginal;
    public final String predictionAdversarial;
    public final float[] probabilitiesAdversarial;
    public final String imagePathAdversarial;
    public final boolean success;
    public final long timestampMillis;

    public AdversarialHistoryItem(long id, String attackName,
                                  String predictionOriginal, float[] probabilitiesOriginal, String imagePathOriginal,
                                  String predictionAdversarial, float[] probabilitiesAdversarial, String imagePathAdversarial,
                                  boolean success, long timestampMillis) {
        this.id = id;
        this.attackName = attackName;
        this.predictionOriginal = predictionOriginal;
        this.probabilitiesOriginal = probabilitiesOriginal;
        this.imagePathOriginal = imagePathOriginal;
        this.predictionAdversarial = predictionAdversarial;
        this.probabilitiesAdversarial = probabilitiesAdversarial;
        this.imagePathAdversarial = imagePathAdversarial;
        this.success = success;
        this.timestampMillis = timestampMillis;
    }
}