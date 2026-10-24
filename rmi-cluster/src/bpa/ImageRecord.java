package bpa;

import java.io.Serializable;

public class ImageRecord implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String id;
    public final byte[] data;
    public final String label;      // esito classificazione
    public final double confidence;
    public final int processedBy;   // nodo che ha classificato
    public final long lamport;      // timestamp logico di creazione

    public ImageRecord(String id, byte[] data, String label, double confidence, int processedBy, long lamport) {
        this.id = id; this.data = data; this.label = label;
        this.confidence = confidence; this.processedBy = processedBy; this.lamport = lamport;
    }

    @Override public String toString() {
        return id + " -> " + label + String.format(" (%.2f)", confidence)
                + " by node" + processedBy + " @L" + lamport;
    }
}
