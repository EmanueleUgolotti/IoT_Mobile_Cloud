package com.manu.myapp;

/**
 * Rappresenta una classificazione salvata nella cronologia.
 */
public class HistoryItem {
    public final long id;
    public final String fileName;
    public final String label;
    public final float confidence;
    public final float[] probabilities;
    public final String source;
    public final long timestampMillis;
    public final String imagePath;

    public HistoryItem(long id, String fileName, String label, float confidence,
                       float[] probabilities, String source, long timestampMillis, String imagePath) {
        this.id = id;
        this.fileName = fileName;
        this.label = label;
        this.confidence = confidence;
        this.probabilities = probabilities;
        this.source = source;
        this.timestampMillis = timestampMillis;
        this.imagePath = imagePath;
    }
}