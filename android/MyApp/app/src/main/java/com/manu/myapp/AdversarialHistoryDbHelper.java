package com.manu.myapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Gestisce la persistenza locale della cronologia degli attacchi
 * adversarial. Database separato da HistoryDbHelper (classificazioni
 * normali) perche' lo schema e' diverso: due immagini e due predizioni
 * per ogni voce, invece di una.
 */
public class AdversarialHistoryDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "adversarial_history.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE = "adversarial_history";
    private static final String COL_ID = "id";
    private static final String COL_ATTACK_NAME = "attack_name";
    private static final String COL_PRED_ORIGINAL = "prediction_original";
    private static final String COL_PROBS_ORIGINAL = "probabilities_original";
    private static final String COL_IMAGE_PATH_ORIGINAL = "image_path_original";
    private static final String COL_PRED_ADVERSARIAL = "prediction_adversarial";
    private static final String COL_PROBS_ADVERSARIAL = "probabilities_adversarial";
    private static final String COL_IMAGE_PATH_ADVERSARIAL = "image_path_adversarial";
    private static final String COL_SUCCESS = "success";
    private static final String COL_TIMESTAMP = "timestamp_millis";

    public AdversarialHistoryDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_ATTACK_NAME + " TEXT, " +
                COL_PRED_ORIGINAL + " TEXT, " +
                COL_PROBS_ORIGINAL + " TEXT, " +
                COL_IMAGE_PATH_ORIGINAL + " TEXT, " +
                COL_PRED_ADVERSARIAL + " TEXT, " +
                COL_PROBS_ADVERSARIAL + " TEXT, " +
                COL_IMAGE_PATH_ADVERSARIAL + " TEXT, " +
                COL_SUCCESS + " INTEGER, " +
                COL_TIMESTAMP + " INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public long insert(String attackName,
                       String predictionOriginal, float[] probabilitiesOriginal, String imagePathOriginal,
                       String predictionAdversarial, float[] probabilitiesAdversarial, String imagePathAdversarial,
                       boolean success, long timestampMillis) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(COL_ATTACK_NAME, attackName);
        values.put(COL_PRED_ORIGINAL, predictionOriginal);
        values.put(COL_PROBS_ORIGINAL, floatArrayToCsv(probabilitiesOriginal));
        values.put(COL_IMAGE_PATH_ORIGINAL, imagePathOriginal);
        values.put(COL_PRED_ADVERSARIAL, predictionAdversarial);
        values.put(COL_PROBS_ADVERSARIAL, floatArrayToCsv(probabilitiesAdversarial));
        values.put(COL_IMAGE_PATH_ADVERSARIAL, imagePathAdversarial);
        values.put(COL_SUCCESS, success ? 1 : 0);
        values.put(COL_TIMESTAMP, timestampMillis);

        return db.insert(TABLE, null, values);
    }

    public void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public List<AdversarialHistoryItem> getAll() {
        List<AdversarialHistoryItem> items = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.query(TABLE, null, null, null, null, null,
                COL_TIMESTAMP + " DESC");

        while (cursor.moveToNext()) {
            items.add(new AdversarialHistoryItem(
                    cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_ATTACK_NAME)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_PRED_ORIGINAL)),
                    csvToFloatArray(cursor.getString(cursor.getColumnIndexOrThrow(COL_PROBS_ORIGINAL))),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_IMAGE_PATH_ORIGINAL)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_PRED_ADVERSARIAL)),
                    csvToFloatArray(cursor.getString(cursor.getColumnIndexOrThrow(COL_PROBS_ADVERSARIAL))),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_IMAGE_PATH_ADVERSARIAL)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(COL_SUCCESS)) == 1,
                    cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
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