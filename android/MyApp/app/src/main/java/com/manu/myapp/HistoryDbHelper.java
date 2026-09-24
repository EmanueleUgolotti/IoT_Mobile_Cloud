package com.manu.myapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Gestisce la persistenza locale della cronologia delle classificazioni.
 * Usa SQLiteOpenHelper nativo di Android (nessuna libreria esterna come
 * Room), per non aggiungere altre dipendenze Gradle dopo i problemi di
 * build gia' affrontati con TensorFlow Lite.
 *
 * Le immagini NON sono salvate nel database (inefficiente per BLOB
 * grandi) - vengono salvate come file separati nello storage interno
 * dell'app, e qui teniamo solo il percorso.
 */
public class HistoryDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "history.db";
    private static final int DB_VERSION = 3; // v3: aggiunta colonna "synced_to_cloud"

    private static final String TABLE = "history";
    private static final String COL_ID = "id";
    private static final String COL_FILE_NAME = "file_name";
    private static final String COL_LABEL = "label";
    private static final String COL_CONFIDENCE = "confidence";
    private static final String COL_PROBABILITIES = "probabilities"; // CSV, es. "0.01,0.02,0.9,..."
    private static final String COL_SOURCE = "source";
    private static final String COL_TIMESTAMP = "timestamp_millis";
    private static final String COL_IMAGE_PATH = "image_path";
    private static final String COL_NOTE = "note";
    // 0 = creata localmente, non ancora mandata al Cloud; 1 = gia'
    // presente sul Cloud (o perche' arrivata da li', o perche' gia'
    // sincronizzata in precedenza) - usata dalla sincronizzazione
    // telefono->Cloud per sapere quali voci mandare.
    private static final String COL_SYNCED_TO_CLOUD = "synced_to_cloud";

    public HistoryDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_FILE_NAME + " TEXT, " +
                COL_LABEL + " TEXT, " +
                COL_CONFIDENCE + " REAL, " +
                COL_PROBABILITIES + " TEXT, " +
                COL_SOURCE + " TEXT, " +
                COL_TIMESTAMP + " INTEGER, " +
                COL_IMAGE_PATH + " TEXT, " +
                COL_NOTE + " TEXT, " +
                COL_SYNCED_TO_CLOUD + " INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Aggiunge la colonna nota senza perdere i dati gia' salvati
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_NOTE + " TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_SYNCED_TO_CLOUD + " INTEGER DEFAULT 0");
        }
    }

    /**
     * Elimina una classificazione dalla cronologia, dato il suo id.
     * Non elimina il file immagine associato - va fatto separatamente
     * dal chiamante (il percorso e' disponibile in HistoryItem.imagePath).
     */
    public void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Aggiorna la nota libera associata a una classificazione.
     */
    public void updateNote(long id, String note) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_NOTE, note);
        db.update(TABLE, values, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Verifica se esiste gia' una voce con questo nome file e questa
     * fonte - usato per evitare di duplicare le classificazioni Cloud
     * gia' sincronizzate in precedenza, se l'utente sincronizza piu'
     * volte.
     */
    public boolean existsWithFileNameAndSource(String fileName, String source) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE, new String[]{COL_ID},
                COL_FILE_NAME + " = ? AND " + COL_SOURCE + " = ?",
                new String[]{fileName, source}, null, null, null);
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    /**
     * Inserisce una nuova classificazione nella cronologia, come
     * "non ancora sincronizzata verso il Cloud" (comportamento di
     * default corretto per qualunque voce creata solo localmente -
     * scattata sul telefono, o classificata con "Test manuale").
     * Va chiamato fuori dal thread principale (es. dal thread del server
     * NanoHTTPD, che non e' il thread UI).
     *
     * @return l'id della riga appena inserita, utile per poter poi
     *         aggiungere una nota o riferirsi a questa voce specifica.
     */
    public long insert(String fileName, String label, float confidence,
                       float[] probabilities, String source, long timestampMillis, String imagePath) {
        return insert(fileName, label, confidence, probabilities, source, timestampMillis, imagePath, false);
    }

    /**
     * Variante di insert() che permette di specificare esplicitamente
     * se la voce e' gia' presente sul Cloud (es. appena scaricata da
     * li') - in quel caso non ha senso marcarla come "da sincronizzare",
     * altrimenti la sincronizzazione verso il Cloud proverebbe a
     * rimandare indietro qualcosa che viene gia' da li'.
     */
    public long insert(String fileName, String label, float confidence,
                       float[] probabilities, String source, long timestampMillis, String imagePath,
                       boolean alreadyOnCloud) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(COL_FILE_NAME, fileName);
        values.put(COL_LABEL, label);
        values.put(COL_CONFIDENCE, confidence);
        values.put(COL_PROBABILITIES, floatArrayToCsv(probabilities));
        values.put(COL_SOURCE, source);
        values.put(COL_TIMESTAMP, timestampMillis);
        values.put(COL_IMAGE_PATH, imagePath);
        values.put(COL_NOTE, "");
        values.put(COL_SYNCED_TO_CLOUD, alreadyOnCloud ? 1 : 0);

        return db.insert(TABLE, null, values);
    }

    /**
     * Restituisce le voci create localmente e non ancora mandate al
     * Cloud - usato dalla sincronizzazione telefono->Cloud per sapere
     * cosa mandare. Le voci gia' arrivate dal Cloud, o gia' mandate in
     * precedenza, non compaiono qui.
     */
    public List<HistoryItem> getUnsyncedItems() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE, null, COL_SYNCED_TO_CLOUD + " = 0", null, null, null,
                COL_TIMESTAMP + " ASC");
        return cursorToList(cursor);
    }

    /**
     * Segna una voce come ormai presente sul Cloud, dopo un invio
     * riuscito - non verra' piu' proposta dalla prossima sincronizzazione.
     */
    public void markSyncedToCloud(long id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_SYNCED_TO_CLOUD, 1);
        db.update(TABLE, values, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Restituisce tutte le classificazioni salvate, piu' recenti prima.
     * Va chiamato fuori dal thread principale.
     */
    public List<HistoryItem> getAll() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE, null, null, null, null, null,
                COL_TIMESTAMP + " DESC");
        return cursorToList(cursor);
    }

    /**
     * Cerca le classificazioni il cui nome file o nota contengono il
     * testo cercato (case-insensitive). Se la query e' vuota, equivale
     * a getAll(). Va chiamato fuori dal thread principale.
     */
    public List<HistoryItem> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return getAll();
        }

        SQLiteDatabase db = getReadableDatabase();
        String likePattern = "%" + query.trim() + "%";

        Cursor cursor = db.query(TABLE, null,
                COL_FILE_NAME + " LIKE ? OR " + COL_NOTE + " LIKE ?",
                new String[]{likePattern, likePattern},
                null, null,
                COL_TIMESTAMP + " DESC");

        return cursorToList(cursor);
    }

    private List<HistoryItem> cursorToList(Cursor cursor) {
        List<HistoryItem> items = new ArrayList<>();

        while (cursor.moveToNext()) {
            String note = cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTE));
            items.add(new HistoryItem(
                    cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_NAME)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_LABEL)),
                    cursor.getFloat(cursor.getColumnIndexOrThrow(COL_CONFIDENCE)),
                    csvToFloatArray(cursor.getString(cursor.getColumnIndexOrThrow(COL_PROBABILITIES))),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_IMAGE_PATH)),
                    note != null ? note : ""
            ));
        }
        cursor.close();

        return items;
    }

    private String floatArrayToCsv(float[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private float[] csvToFloatArray(String csv) {
        if (csv == null || csv.isEmpty()) {
            return new float[0];
        }
        String[] parts = csv.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i]);
        }
        return values;
    }
}