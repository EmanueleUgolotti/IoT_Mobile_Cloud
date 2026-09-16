package com.manu.myapp;

/**
 * Una riga della finestra "live" sul Cloud - puo' essere una
 * classificazione riuscita o un tentativo di scatto fallito, le due
 * cose che il backend espone su /cloud/results e /cloud/failures.
 * Non viene mai salvato sul telefono, vive solo mentre la schermata e'
 * aperta.
 */
public class CloudLiveItem {

    public static final int TYPE_RESULT = 0;
    public static final int TYPE_FAILURE = 1;

    public final int type;
    public final String requestId;
    public final String title;      // es. il nome del file, o "node1-000005"
    public final String subtitle;   // es. "gun (99.9%)" oppure "Scatto fallito: timeout"
    public final String receivedAt; // stringa ISO8601, usata anche per l'ordinamento

    public CloudLiveItem(int type, String requestId, String title, String subtitle, String receivedAt) {
        this.type = type;
        this.requestId = requestId;
        this.title = title;
        this.subtitle = subtitle;
        this.receivedAt = receivedAt;
    }
}